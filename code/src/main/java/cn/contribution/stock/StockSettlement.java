package cn.contribution.stock;

import cn.contribution.industry.BuiltInIndustry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import static cn.contribution.account.AccountService.uuidBytes;
import static cn.contribution.account.AccountService.bytesUuid;

/** One main-server-only, database-serialized market transition at the 08:00 accounting boundary. */
final class StockSettlement {
    private static final double[] RANK_WEIGHT = {.28, .21, .14, .07, 0, -.07, -.14, -.21, -.28};

    private StockSettlement() { }

    static boolean industryReady(Connection connection, long completedDay) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT status FROM scheduled_task_run WHERE task_name = 'industry_daily_settlement' AND game_day = ?")) {
            query.setLong(1, completedDay);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && "SUCCEEDED".equals(rows.getString(1));
            }
        }
    }

    static void run(Connection connection, long day) throws SQLException {
        Map<BuiltInIndustry, IndustryValue> industries = loadIndustries(connection);
        List<BuiltInIndustry> ranking = new ArrayList<>(List.of(BuiltInIndustry.values()));
        ranking.sort(Comparator.comparing((BuiltInIndustry i) -> industries.get(i).prosperity()).reversed()
                .thenComparing(BuiltInIndustry::path));
        Map<BuiltInIndustry, Double> weights = new EnumMap<>(BuiltInIndustry.class);
        for (int i = 0; i < ranking.size(); i++) weights.put(ranking.get(i), RANK_WEIGHT[i]);
        updateBottomStreak(connection, day, ranking.getLast());

        List<Row> rows = loadListings(connection);
        Random random = new Random();
        for (Row row : rows) {
            if (row.status.equals("DELISTED")) continue;
            if (row.lastPriceDay >= day) continue;
            int price;
            double noise = row.noise;
            double base = row.base;
            noise = .65 * noise + .8 * random.nextGaussian();
            double weight = weights.get(row.industry);
            double multiplier = noise > 0 ? 1 + weight : 1 - weight;
            double proposed = row.price + .1 * noise * multiplier * base;
            long cap = 128L * row.initial;
            long lower = Math.max(1L, Math.round(.3 * row.price));
            long upper = Math.min(cap, 3L * row.price);
            price = (int) Math.max(lower, Math.min(upper, Math.round(proposed)));
            base = .9 * base + .1 * (.1 * price);
            String status = row.status;
            Long retirement = row.retirementDay;
            if (status.equals("ACTIVE") && day - row.listedDay >= 3
                    && (price <= .3 * row.initial || price <= .15 * row.high)) {
                status = "RETIRING";
                retirement = day;
            }
            updateListing(connection, row, day, price, base, noise, status, retirement);
            dailyPrice(connection, row.id, day, price, status);
        }
        triggerBottomRetirement(connection, day, ranking.getLast());
        trimExcess(connection, day);
        fillVacancies(connection, day, industries, random);
    }

    /** Existing 30-stock worlds converge to the 20-stock limit after today's trading window. */
    private static void trimExcess(Connection connection, long day) throws SQLException {
        List<Row> active = loadListings(connection).stream().filter(row -> row.status.equals("ACTIVE"))
                .sorted(Comparator.comparingDouble(row -> row.price / (double) row.initial)).toList();
        int surplus = Math.max(0, active.size() - 20);
        for (int i = 0; i < surplus; i++) {
            Row row = active.get(i);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stock_listing SET status = 'RETIRING', retirement_day = ? WHERE stock_id = ?")) {
                update.setLong(1, day); update.setLong(2, row.id); update.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stock_daily_price SET status = 'RETIRING' WHERE stock_id = ? AND game_day = ?")) {
                update.setLong(1, row.id); update.setLong(2, day); update.executeUpdate();
            }
        }
    }

    private static Map<BuiltInIndustry, IndustryValue> loadIndustries(Connection connection) throws SQLException {
        Map<BuiltInIndustry, IndustryValue> result = new EnumMap<>(BuiltInIndustry.class);
        for (BuiltInIndustry industry : BuiltInIndustry.values()) result.put(industry, new IndustryValue(0, BigDecimal.ZERO));
        try (PreparedStatement query = connection.prepareStatement("SELECT industry_id, total_development, prosperity FROM industry_state");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                if (rows.getString(1).equals("contribution:" + industry.path()))
                    result.put(industry, new IndustryValue(rows.getLong(2), rows.getBigDecimal(3)));
            }
        }
        return result;
    }

    private static void updateBottomStreak(Connection connection, long day, BuiltInIndustry bottom) throws SQLException {
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            String id = "contribution:" + industry.path();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT IGNORE INTO stock_industry_streak (industry_id, bottom_streak, last_bottom_retirement_day) VALUES (?, 0, -100)")) {
                insert.setString(1, id); insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE stock_industry_streak SET bottom_streak = CASE WHEN ? THEN bottom_streak + 1 ELSE 0 END WHERE industry_id = ?")) {
                update.setBoolean(1, industry == bottom); update.setString(2, id); update.executeUpdate();
            }
        }
    }

    private static void triggerBottomRetirement(Connection connection, long day, BuiltInIndustry bottom) throws SQLException {
        String industryId = "contribution:" + bottom.path();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT bottom_streak, last_bottom_retirement_day FROM stock_industry_streak WHERE industry_id = ?")) {
            query.setString(1, industryId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(1) < 7 || day - rows.getLong(2) < 7) return;
            }
        }
        Row candidate = null;
        for (Row row : loadListings(connection)) {
            if (row.status.equals("ACTIVE") && row.industry == bottom && day - row.listedDay >= 3
                    && (candidate == null || (long) row.price * candidate.initial < (long) candidate.price * row.initial))
                candidate = row;
        }
        if (candidate == null) return;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_listing SET status = 'RETIRING', retirement_day = ? WHERE stock_id = ?")) {
            update.setLong(1, day); update.setLong(2, candidate.id); update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_daily_price SET status = 'RETIRING' WHERE stock_id = ? AND game_day = ?")) {
            update.setLong(1, candidate.id); update.setLong(2, day); update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_industry_streak SET last_bottom_retirement_day = ? WHERE industry_id = ?")) {
            update.setLong(1, day); update.setString(2, industryId); update.executeUpdate();
        }
    }

    static void closeRetirements(Connection connection, long day) throws SQLException {
        for (Row row : loadListings(connection)) {
            if (row.status.equals("RETIRING") && row.retirementDay != null && row.retirementDay <= day)
                delist(connection, row, day);
        }
    }

    private static void delist(Connection connection, Row row, long day) throws SQLException {
        try (PreparedStatement positions = connection.prepareStatement(
                "SELECT player_uuid, quantity FROM stock_position WHERE stock_id = ?")) {
            positions.setLong(1, row.id);
            try (ResultSet holders = positions.executeQuery()) {
                while (holders.next()) {
                    byte[] playerBytes = holders.getBytes(1);
                    long amount = Math.max(0, Math.round(row.price * holders.getLong(2) * .5));
                    int balance = 0;
                    String name = null;
                    try (PreparedStatement account = connection.prepareStatement(
                            "SELECT player_name, balance FROM contribution_account WHERE player_uuid = ? FOR UPDATE")) {
                        account.setBytes(1, playerBytes);
                        try (ResultSet result = account.executeQuery()) {
                            if (result.next()) { name = result.getString(1); balance = result.getInt(2); }
                        }
                    }
                    if (name == null) throw new SQLException("Retiring shareholder has no account");
                    int paid = (int) Math.min(amount, Integer.MAX_VALUE - (long) balance);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO stock_refund (player_uuid, stock_id, amount, claimed) VALUES (?, ?, ?, ?)")) {
                        insert.setBytes(1, playerBytes); insert.setLong(2, row.id);
                        insert.setLong(3, amount); insert.setInt(4, paid);
                        insert.executeUpdate();
                    }
                    if (paid > 0) {
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE contribution_account SET balance = balance + ?, updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
                            update.setInt(1, paid); update.setBytes(2, playerBytes); update.executeUpdate();
                        }
                        UUID request = UUID.nameUUIDFromBytes(("stock-retirement|" + row.id + "|" + bytesUuid(playerBytes))
                                .getBytes(StandardCharsets.UTF_8));
                        byte[] requestHash;
                        try { requestHash = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)); }
                        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO contribution_transaction (transaction_id, idempotency_id, request_hash, player_uuid, "
                                        + "player_name, amount, income_delta, balance_before, balance_after, type, source, reason, "
                                        + "operator, server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 'REFUND', "
                                        + "'contribution:stock', '股票退市自动返还', 'stock-market', 'stock-main', CURRENT_TIMESTAMP(6), NULL)")) {
                            insert.setBytes(1, uuidBytes(UUID.randomUUID())); insert.setBytes(2, uuidBytes(request));
                            insert.setBytes(3, requestHash); insert.setBytes(4, playerBytes); insert.setString(5, name);
                            insert.setInt(6, paid); insert.setInt(7, balance); insert.setInt(8, balance + paid);
                            insert.executeUpdate();
                        }
                    }
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM stock_position WHERE stock_id = ?")) {
            delete.setLong(1, row.id); delete.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_listing SET status = 'DELISTED', delisted_day = ? WHERE stock_id = ?")) {
            update.setLong(1, day); update.setLong(2, row.id); update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_daily_price SET status = 'DELISTED' WHERE stock_id = ? AND game_day = ?")) {
            update.setLong(1, row.id); update.setLong(2, row.retirementDay); update.executeUpdate();
        }
    }

    private static void fillVacancies(Connection connection, long day, Map<BuiltInIndustry, IndustryValue> values,
                                      Random random) throws SQLException {
        List<Row> active = loadListings(connection).stream().filter(row -> !row.status.equals("DELISTED")).toList();
        if (active.size() >= 20) return;
        Set<String> listed = new HashSet<>();
        Set<BuiltInIndustry> covered = new HashSet<>();
        for (Row row : active) { listed.add(row.itemId); covered.add(row.industry); }
        Set<String> recent = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT item_id FROM stock_listing WHERE status = 'DELISTED' ORDER BY delisted_day DESC, stock_id DESC LIMIT 5");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) recent.add(rows.getString(1));
        }
        List<StockCatalog.Candidate> pool = new ArrayList<>(StockCatalog.all());
        java.util.Collections.shuffle(pool, random);
        while (listed.size() < 20 && !pool.isEmpty()) {
            StockCatalog.Candidate choice = null;
            for (StockCatalog.Candidate candidate : pool) {
                if (!listed.contains(candidate.itemId()) && !recent.contains(candidate.itemId())
                        && !covered.contains(candidate.industry())) { choice = candidate; break; }
            }
            if (choice == null) for (StockCatalog.Candidate candidate : pool) {
                if (!listed.contains(candidate.itemId()) && !recent.contains(candidate.itemId())) { choice = candidate; break; }
            }
            if (choice == null) break;
            BuiltInIndustry selectedIndustry = choice.industry();
            int count = (int) StockCatalog.all().stream().filter(item -> item.industry() == selectedIndustry).count();
            double proposed = values.get(choice.industry()).total() / (double) count * (.5 + random.nextDouble());
            int price = (int) Math.max(100, Math.min(10000, Math.round(proposed)));
            long id;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO stock_listing (item_id, item_name, industry_id, listed_day, initial_price, price, high_price, low_price, "
                            + "wave_base, ou_noise, status, retirement_day, last_price_day, delisted_day) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', NULL, ?, NULL)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, choice.itemId()); insert.setString(2, choice.name());
                insert.setString(3, "contribution:" + choice.industry().path()); insert.setLong(4, day);
                insert.setInt(5, price); insert.setInt(6, price); insert.setInt(7, price); insert.setInt(8, price);
                insert.setBigDecimal(9, BigDecimal.valueOf(price).movePointLeft(1)); insert.setLong(10, day);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
            }
            dailyPrice(connection, id, day, price, "ACTIVE");
            listed.add(choice.itemId()); covered.add(choice.industry()); pool.remove(choice);
            if (listed.size() >= 20) break;
        }
    }

    private static void updateListing(Connection connection, Row row, long day, int price, double base, double noise,
                                      String status, Long retirement) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE stock_listing SET price = ?, high_price = ?, low_price = ?, wave_base = ?, ou_noise = ?, "
                        + "status = ?, retirement_day = ?, last_price_day = ? WHERE stock_id = ?")) {
            update.setInt(1, price); update.setInt(2, Math.max(price, row.high));
            update.setInt(3, Math.min(price, row.low));
            update.setBigDecimal(4, BigDecimal.valueOf(base).setScale(8, RoundingMode.HALF_UP));
            update.setBigDecimal(5, BigDecimal.valueOf(noise).setScale(8, RoundingMode.HALF_UP));
            update.setString(6, status);
            if (retirement == null) update.setNull(7, java.sql.Types.BIGINT); else update.setLong(7, retirement);
            update.setLong(8, day); update.setLong(9, row.id); update.executeUpdate();
        }
    }

    private static void dailyPrice(Connection connection, long id, long day, int price, String status) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO stock_daily_price (stock_id, game_day, price, status) VALUES (?, ?, ?, ?)")) {
            insert.setLong(1, id); insert.setLong(2, day); insert.setInt(3, price);
            insert.setString(4, status); insert.executeUpdate();
        }
    }

    private static List<Row> loadListings(Connection connection) throws SQLException {
        List<Row> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT stock_id, item_id, industry_id, listed_day, initial_price, price, high_price, low_price, "
                        + "wave_base, ou_noise, status, retirement_day, last_price_day FROM stock_listing "
                        + "WHERE status <> 'DELISTED'");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                BuiltInIndustry industry = null;
                for (BuiltInIndustry value : BuiltInIndustry.values())
                    if (rows.getString(3).equals("contribution:" + value.path())) industry = value;
                if (industry == null) throw new SQLException("Unknown stock industry " + rows.getString(3));
                long retire = rows.getLong(12);
                boolean noRetirement = rows.wasNull();
                result.add(new Row(rows.getLong(1), rows.getString(2), industry, rows.getLong(4),
                        rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getInt(8),
                        rows.getBigDecimal(9).doubleValue(), rows.getBigDecimal(10).doubleValue(),
                        rows.getString(11), noRetirement ? null : retire, rows.getLong(13)));
            }
        }
        return result;
    }

    private record IndustryValue(long total, BigDecimal prosperity) { }
    private record Row(long id, String itemId, BuiltInIndustry industry, long listedDay, int initial,
                       int price, int high, int low, double base, double noise, String status,
                       Long retirementDay, long lastPriceDay) { }
}
