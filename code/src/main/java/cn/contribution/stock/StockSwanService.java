package cn.contribution.stock;

import cn.contribution.account.AccountIdentityService;
import cn.contribution.account.AccountService;
import cn.contribution.industry.BuiltInIndustry;
import cn.contribution.industry.StatisticMath;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Rare, database-serialized market events. Called only from the main server's async market tick.
 */
final class StockSwanService {
    private static final long WINDOW_TICKS = 4_000;
    private static final long FLUSH_GRACE_TICKS = 100;
    private static final long NEWS_TICKS = 3 * 24_000L;

    record Player(UUID id, String name) {}

    record Effect(
            UUID eventId,
            BuiltInIndustry industry,
            boolean good,
            Long targetStockId,
            int ruleVersion) {}

    private StockSwanService() {}

    static void advance(Connection connection, long clock, List<Player> online, Random random)
            throws SQLException {
        long day = accountingDay(clock);
        int time = (int) Math.floorMod(clock, 24_000);
        long nextDay;
        UUID active;
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT next_start_day, active_event_id FROM stock_swan_schedule"
                                        + " WHERE singleton_id = 1 FOR UPDATE");
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
        if (time < 2_000) return;
        List<Player> eligible =
                new ArrayList<>(
                        online.stream()
                                .filter(player -> !AccountIdentityService.isBotName(player.name()))
                                .distinct()
                                .toList());
        if (eligible.size() < 2) return;
        Collections.shuffle(eligible, random);
        Player player = eligible.get(0);
        Player second = eligible.get(1);
        List<BuiltInIndustry> choices = new ArrayList<>(List.of(BuiltInIndustry.values()));
        Collections.shuffle(choices, random);
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO stock_swan_event (event_id, player_uuid, player_name,"
                                + " started_clock, deadline_clock, second_player_uuid,"
                                + " second_player_name, rule_version) VALUES (?, ?, ?, ?, ?, ?, ?,"
                                + " 2)")) {
            insert.setBytes(1, AccountService.uuidBytes(id));
            insert.setBytes(2, AccountService.uuidBytes(player.id()));
            insert.setString(3, player.name());
            insert.setLong(4, clock);
            insert.setLong(5, clock + WINDOW_TICKS);
            insert.setBytes(6, AccountService.uuidBytes(second.id()));
            insert.setString(7, second.name());
            insert.executeUpdate();
        }
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO stock_swan_candidate (event_id, industry_id,"
                            + " baseline_development, baseline_second_development) VALUES (?, ?, ?,"
                            + " ?)")) {
            for (int index = 0; index < 4; index++) {
                String industryId = id(choices.get(index));
                insert.setBytes(1, AccountService.uuidBytes(id));
                insert.setString(2, industryId);
                insert.setLong(3, development(connection, player.id(), industryId));
                insert.setLong(4, development(connection, second.id(), industryId));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        setSchedule(connection, nextDay, id);
    }

    private static void complete(Connection connection, UUID eventId, long clock, Random random)
            throws SQLException {
        UUID player;
        UUID second;
        int ruleVersion;
        long deadline;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT player_uuid, deadline_clock, second_player_uuid, rule_version FROM"
                                + " stock_swan_event WHERE event_id = ?")) {
            query.setBytes(1, AccountService.uuidBytes(eventId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing active stock swan event");
                player = AccountService.bytesUuid(rows.getBytes(1));
                deadline = rows.getLong(2);
                byte[] secondBytes = rows.getBytes(3);
                second = secondBytes == null ? null : AccountService.bytesUuid(secondBytes);
                ruleVersion = rows.getInt(4);
            }
        }
        if (clock < deadline + FLUSH_GRACE_TICKS) return;
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT industry_id, baseline_development, baseline_second_development FROM"
                            + " stock_swan_candidate WHERE event_id = ? ORDER BY industry_id")) {
            query.setBytes(1, AccountService.uuidBytes(eventId));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String industryId = rows.getString(1);
                    candidates.add(
                            new Candidate(
                                    industryId,
                                    StatisticMath.add(
                                            Math.max(
                                                    0,
                                                    development(connection, player, industryId)
                                                            - rows.getLong(2)),
                                            second == null
                                                    ? 0
                                                    : Math.max(
                                                            0,
                                                            development(
                                                                            connection,
                                                                            second,
                                                                            industryId)
                                                                    - rows.getLong(3)))));
                }
            }
        }
        if (candidates.size() != (ruleVersion < 2 ? 3 : 4))
            throw new SQLException("Invalid stock swan candidate count");
        Candidate winner = null;
        for (Candidate candidate : candidates)
            if (candidate.gain() > 0 && (winner == null || candidate.gain() > winner.gain()))
                winner = candidate;
        List<Candidate> affected = new ArrayList<>(candidates);
        if (winner == null && ruleVersion >= 2) {
            Collections.shuffle(affected, random);
            affected = affected.subList(0, 2);
        }
        long effectDay = accountingDay(clock) + 1;
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_swan_candidate SET result_kind = ?, effect_day = ?,"
                            + " news_until_clock = ?, target_stock_id = ?, second_target_stock_id ="
                            + " ?, target_selected = TRUE WHERE event_id = ? AND industry_id ="
                            + " ?")) {
            for (Candidate candidate : affected) {
                if (winner != null && !candidate.industryId().equals(winner.industryId())) continue;
                update.setString(1, winner == null ? "BAD" : "GOOD");
                update.setLong(2, effectDay);
                update.setLong(3, clock + NEWS_TICKS);
                List<Long> targets =
                        winner == null
                                ? chooseStocks(
                                        connection,
                                        candidate.industryId(),
                                        ruleVersion < 2 ? 1 : 1 + random.nextInt(2),
                                        random)
                                : List.of();
                if (targets.isEmpty()) update.setNull(4, java.sql.Types.BIGINT);
                else update.setLong(4, targets.get(0));
                if (targets.size() < 2) update.setNull(5, java.sql.Types.BIGINT);
                else update.setLong(5, targets.get(1));
                update.setBytes(6, AccountService.uuidBytes(eventId));
                update.setString(7, candidate.industryId());
                update.addBatch();
            }
            update.executeBatch();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_swan_event SET completed_clock = ? WHERE event_id = ?")) {
            update.setLong(1, clock);
            update.setBytes(2, AccountService.uuidBytes(eventId));
            update.executeUpdate();
        }
        setSchedule(
                connection,
                accountingDay(clock) + 6 + random.nextInt(ruleVersion < 2 ? 75 : 55),
                null);
    }

    static List<Effect> pendingEffects(Connection connection, long day) throws SQLException {
        // Upgrade outstanding 0.1.2 events once. Persist selection so retries never reroll targets.
        List<String[]> legacy = new ArrayList<>();
        try (var query =
                        connection.prepareStatement(
                                "SELECT event_id, industry_id FROM stock_swan_candidate WHERE"
                                        + " result_kind = 'BAD' AND target_selected = FALSE AND"
                                        + " applied_day IS NULL");
                var rows = query.executeQuery()) {
            while (rows.next())
                legacy.add(
                        new String[] {
                            AccountService.bytesUuid(rows.getBytes(1)).toString(), rows.getString(2)
                        });
        }
        for (var old : legacy) {
            Long target = chooseStock(connection, old[1], new Random());
            try (var update =
                    connection.prepareStatement(
                            "UPDATE stock_swan_candidate SET target_stock_id = ?, target_selected ="
                                + " TRUE WHERE event_id = ? AND industry_id = ? AND target_selected"
                                + " = FALSE")) {
                if (target == null) update.setNull(1, java.sql.Types.BIGINT);
                else update.setLong(1, target);
                update.setBytes(2, AccountService.uuidBytes(UUID.fromString(old[0])));
                update.setString(3, old[1]);
                update.executeUpdate();
            }
        }
        List<Effect> effects = new ArrayList<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT c.event_id, c.industry_id, c.result_kind, c.target_stock_id,"
                            + " c.second_target_stock_id, e.rule_version FROM stock_swan_candidate"
                            + " c JOIN stock_swan_event e ON e.event_id = c.event_id WHERE"
                            + " c.effect_day <= ? AND c.applied_day IS NULL ORDER BY c.effect_day,"
                            + " c.event_id, c.industry_id")) {
            query.setLong(1, day);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long target = rows.getLong(4);
                    Long targetId = rows.wasNull() ? null : target;
                    effects.add(
                            new Effect(
                                    AccountService.bytesUuid(rows.getBytes(1)),
                                    industry(rows.getString(2)),
                                    "GOOD".equals(rows.getString(3)),
                                    targetId,
                                    rows.getInt(6)));
                    long secondTarget = rows.getLong(5);
                    if (!rows.wasNull())
                        effects.add(
                                new Effect(
                                        AccountService.bytesUuid(rows.getBytes(1)),
                                        industry(rows.getString(2)),
                                        false,
                                        secondTarget,
                                        rows.getInt(6)));
                }
            }
        }
        return effects;
    }

    private static Long chooseStock(Connection connection, String industry, Random random)
            throws SQLException {
        List<Long> ids = chooseStocks(connection, industry, 1, random);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static List<Long> chooseStocks(
            Connection connection, String industry, int count, Random random) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (var query =
                connection.prepareStatement(
                        "SELECT stock_id FROM stock_listing WHERE industry_id = ? AND status <>"
                                + " 'DELISTED' ORDER BY stock_id")) {
            query.setString(1, industry);
            try (var rows = query.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        Collections.shuffle(ids, random);
        return List.copyOf(ids.subList(0, Math.min(count, ids.size())));
    }

    static void markApplied(Connection connection, List<Effect> effects, long day)
            throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_swan_candidate SET applied_day = ? WHERE event_id = ? AND"
                                + " industry_id = ? AND applied_day IS NULL")) {
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
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT e.player_name, c.industry_id, c.result_kind, c.news_until_clock,"
                                + " e.second_player_name FROM stock_swan_candidate c JOIN"
                                + " stock_swan_event e ON e.event_id = c.event_id WHERE"
                                + " c.news_until_clock > ? ORDER BY e.completed_clock DESC,"
                                + " c.industry_id LIMIT 12")) {
            query.setLong(1, clock);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String player = rows.getString(1);
                    String secondName = rows.getString(5);
                    if (secondName != null) player += "、" + secondName;
                    String name = industry(rows.getString(2)).displayName();
                    boolean good = "GOOD".equals(rows.getString(3));
                    String message =
                            good
                                    ? "由于 "
                                            + player
                                            + " 在 "
                                            + name
                                            + " 行业的杰出贡献，"
                                            + name
                                            + " 行业股市繁荣增长"
                                    : "由于 "
                                            + player
                                            + " 在 "
                                            + name
                                            + " 行业的无所作为，"
                                            + name
                                            + " 行业股市动荡不安";
                    result.add(new StockView.News(message, rows.getLong(4), good));
                }
            }
        }
        return List.copyOf(result);
    }

    private static long development(Connection connection, UUID player, String industryId)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT development FROM player_industry_stats WHERE player_uuid = ? AND"
                                + " industry_id = ?")) {
            query.setBytes(1, AccountService.uuidBytes(player));
            query.setString(2, industryId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private static void setSchedule(Connection connection, long day, UUID active)
            throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_swan_schedule SET next_start_day = ?, active_event_id = ?"
                                + " WHERE singleton_id = 1")) {
            update.setLong(1, day);
            if (active == null) update.setNull(2, java.sql.Types.BINARY);
            else update.setBytes(2, AccountService.uuidBytes(active));
            update.executeUpdate();
        }
    }

    private static long accountingDay(long clock) {
        return Math.max(0, Math.floorDiv(clock - 2_000, 24_000));
    }

    private static String id(BuiltInIndustry industry) {
        return "contribution:" + industry.path();
    }

    private static BuiltInIndustry industry(String id) throws SQLException {
        for (BuiltInIndustry industry : BuiltInIndustry.values())
            if (id.equals(id(industry))) return industry;
        throw new SQLException("Unknown stock swan industry: " + id);
    }

    private record Candidate(String industryId, long gain) {}
}
