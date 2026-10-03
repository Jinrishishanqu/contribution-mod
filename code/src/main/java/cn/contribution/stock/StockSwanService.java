package cn.contribution.stock;

import cn.contribution.account.AccountService;
import cn.contribution.industry.BuiltInIndustry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Rare, database-serialized market events. Called only from the main server's async market tick. */
final class StockSwanService {
    private static final long WINDOW_TICKS = 2_000;
    private static final long FLUSH_GRACE_TICKS = 100;
    private static final long NEWS_TICKS = 3 * 24_000L;

    record Player(UUID id, String name) { }
    record Effect(UUID eventId, BuiltInIndustry industry, boolean good, Long targetStockId) { }

    private StockSwanService() { }

    static void advance(Connection connection, long clock, List<Player> online, Random random) throws SQLException {
        long day = accountingDay(clock);
        int time = (int) Math.floorMod(clock, 24_000);
        long nextDay;
        UUID active;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT next_start_day, active_event_id FROM stock_swan_schedule WHERE singleton_id = 1 FOR UPDATE");
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing stock swan schedule");
            nextDay = rows.getLong(1);
            byte[] bytes = rows.getBytes(2);
            active = bytes == null ? null : AccountService.bytesUuid(bytes);
        }
        if (active != null) {
            complete(connection, active, clock, random);
            return;
        }
        if (nextDay < 0) {
            setSchedule(connection, day + 1, null);
            return;
        }
        if (day < nextDay) return;
        if (time >= 4_000) {
            setSchedule(connection, day + 1, null);
            return;
        }
        if (time < 2_000 || online.isEmpty()) return;
        Player player = online.get(random.nextInt(online.size()));
        List<BuiltInIndustry> choices = new ArrayList<>(List.of(BuiltInIndustry.values()));
        Collections.shuffle(choices, random);
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_swan_event (event_id, player_uuid, player_name, started_clock, deadline_clock) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setBytes(1, AccountService.uuidBytes(id));
            insert.setBytes(2, AccountService.uuidBytes(player.id()));
            insert.setString(3, player.name());
            insert.setLong(4, clock);
            insert.setLong(5, clock + WINDOW_TICKS);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_swan_candidate (event_id, industry_id, baseline_development) VALUES (?, ?, ?)")) {
            for (int index = 0; index < 3; index++) {
                String industryId = id(choices.get(index));
                insert.setBytes(1, AccountService.uuidBytes(id));
                insert.setString(2, industryId);
                insert.setLong(3, development(connection, player.id(), industryId));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        setSchedule(connection, nextDay, id);
    }

    private static void complete(Connection connection, UUID eventId, long clock, Random random) throws SQLException {
        UUID player;
        long deadline;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid, deadline_clock FROM stock_swan_event WHERE event_id = ?")) {
            query.setBytes(1, AccountService.uuidBytes(eventId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing active stock swan event");
                player = AccountService.bytesUuid(rows.getBytes(1));
                deadline = rows.getLong(2);
            }
        }
        if (clock < deadline + FLUSH_GRACE_TICKS) return;
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT industry_id, baseline_development FROM stock_swan_candidate "
                        + "WHERE event_id = ? ORDER BY industry_id")) {
            query.setBytes(1, AccountService.uuidBytes(eventId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String industryId = rows.getString(1);
                    candidates.add(new Candidate(industryId,
                            development(connection, player, industryId) - rows.getLong(2)));
                }
            }
        }
        if (candidates.size() != 3) throw new SQLException("Stock swan event must have three industries");
        Candidate winner = null;
        for (Candidate candidate : candidates)
            if (candidate.gain() > 0 && (winner == null || candidate.gain() > winner.gain())) winner = candidate;
        long effectDay = accountingDay(clock) + 1;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_swan_candidate SET result_kind = ?, effect_day = ?, news_until_clock = ?, "
                        + "target_stock_id = ?, target_selected = TRUE "
                        + "WHERE event_id = ? AND industry_id = ?")) {
            for (Candidate candidate : candidates) {
                if (winner != null && !candidate.industryId().equals(winner.industryId())) continue;
                update.setString(1, winner == null ? "BAD" : "GOOD");
                update.setLong(2, effectDay);
                update.setLong(3, clock + NEWS_TICKS);
                Long target = winner == null ? chooseStock(connection, candidate.industryId(), random) : null;
                if (target == null) update.setNull(4, java.sql.Types.BIGINT); else update.setLong(4, target);
                update.setBytes(5, AccountService.uuidBytes(eventId));
                update.setString(6, candidate.industryId());
                update.addBatch();
            }
            update.executeBatch();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_swan_event SET completed_clock = ? WHERE event_id = ?")) {
            update.setLong(1, clock);
            update.setBytes(2, AccountService.uuidBytes(eventId));
            update.executeUpdate();
        }
        setSchedule(connection, accountingDay(clock) + 6 + random.nextInt(75), null);
    }

    static List<Effect> pendingEffects(Connection connection, long day) throws SQLException {
        // Upgrade outstanding 0.1.2 events once. Persist selection so retries never reroll targets.
        List<String[]> legacy = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT event_id, industry_id FROM stock_swan_candidate "
                + "WHERE result_kind = 'BAD' AND target_selected = FALSE AND applied_day IS NULL");
             var rows = query.executeQuery()) {
            while (rows.next()) legacy.add(new String[] {AccountService.bytesUuid(rows.getBytes(1)).toString(), rows.getString(2)});
        }
        for (var old : legacy) {
            Long target = chooseStock(connection, old[1], new Random());
            try (var update = connection.prepareStatement("UPDATE stock_swan_candidate SET target_stock_id = ?, target_selected = TRUE "
                    + "WHERE event_id = ? AND industry_id = ? AND target_selected = FALSE")) {
                if (target == null) update.setNull(1, java.sql.Types.BIGINT); else update.setLong(1, target);
                update.setBytes(2, AccountService.uuidBytes(UUID.fromString(old[0]))); update.setString(3, old[1]); update.executeUpdate();
            }
        }
        List<Effect> effects = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT event_id, industry_id, result_kind, target_stock_id FROM stock_swan_candidate "
                        + "WHERE effect_day <= ? AND applied_day IS NULL ORDER BY effect_day, event_id, industry_id")) {
            query.setLong(1, day);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long target = rows.getLong(4);
                    Long targetId = rows.wasNull() ? null : target;
                    effects.add(new Effect(AccountService.bytesUuid(rows.getBytes(1)),
                            industry(rows.getString(2)), "GOOD".equals(rows.getString(3)), targetId));
                }
            }
        }
        return effects;
    }

    private static Long chooseStock(Connection connection, String industry, Random random) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT stock_id FROM stock_listing "
                + "WHERE industry_id = ? AND status <> 'DELISTED' ORDER BY stock_id")) {
            query.setString(1, industry);
            try (var rows = query.executeQuery()) { while (rows.next()) ids.add(rows.getLong(1)); }
        }
        return ids.isEmpty() ? null : ids.get(random.nextInt(ids.size()));
    }

    static void markApplied(Connection connection, List<Effect> effects, long day) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_swan_candidate SET applied_day = ? WHERE event_id = ? AND industry_id = ? "
                        + "AND applied_day IS NULL")) {
            for (Effect effect : effects) {
                update.setLong(1, day);
                update.setBytes(2, AccountService.uuidBytes(effect.eventId()));
                update.setString(3, id(effect.industry()));
                update.addBatch();
            }
            if (!effects.isEmpty()) update.executeBatch();
        }
    }

    static List<StockView.News> news(Connection connection, long clock) throws SQLException {
        List<StockView.News> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT e.player_name, c.industry_id, c.result_kind, c.news_until_clock "
                        + "FROM stock_swan_candidate c JOIN stock_swan_event e ON e.event_id = c.event_id "
                        + "WHERE c.news_until_clock > ? ORDER BY e.completed_clock DESC, c.industry_id LIMIT 12")) {
            query.setLong(1, clock);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String player = rows.getString(1);
                    String name = industry(rows.getString(2)).displayName();
                    boolean good = "GOOD".equals(rows.getString(3));
                    String message = good
                            ? "由于 " + player + " 在 " + name + " 行业的杰出贡献，" + name + " 行业股市繁荣增长"
                            : "由于 " + player + " 在 " + name + " 行业的无所作为，" + name + " 行业股市动荡不安";
                    result.add(new StockView.News(message, rows.getLong(4), good));
                }
            }
        }
        return List.copyOf(result);
    }

    private static long development(Connection connection, UUID player, String industryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT development FROM player_industry_stats WHERE player_uuid = ? AND industry_id = ?")) {
            query.setBytes(1, AccountService.uuidBytes(player));
            query.setString(2, industryId);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }

    private static void setSchedule(Connection connection, long day, UUID active) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_swan_schedule SET next_start_day = ?, active_event_id = ? WHERE singleton_id = 1")) {
            update.setLong(1, day);
            if (active == null) update.setNull(2, java.sql.Types.BINARY);
            else update.setBytes(2, AccountService.uuidBytes(active));
            update.executeUpdate();
        }
    }

    private static long accountingDay(long clock) {
        return Math.max(0, Math.floorDiv(clock - 2_000, 24_000));
    }

    private static String id(BuiltInIndustry industry) { return "contribution:" + industry.path(); }

    private static BuiltInIndustry industry(String id) throws SQLException {
        for (BuiltInIndustry industry : BuiltInIndustry.values()) if (id.equals(id(industry))) return industry;
        throw new SQLException("Unknown stock swan industry: " + id);
    }

    private record Candidate(String industryId, long gain) { }
}
