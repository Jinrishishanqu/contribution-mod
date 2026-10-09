package cn.contribution.stock;

import static cn.contribution.account.AccountService.bytesUuid;

import cn.contribution.gamecurrency.GameCurrencyService;
import cn.contribution.industry.BuiltInIndustry;
import cn.contribution.industry.IndustryProsperity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** One main-server-only, database-serialized market transition at the 08:00 accounting boundary. */
final class StockSettlement {
    private StockSettlement() {}

    static boolean industryReady(Connection connection, long completedDay) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT status FROM scheduled_task_run WHERE task_name ="
                                + " 'industry_daily_settlement' AND game_day = ?")) {
            query.setLong(1, completedDay);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() && "SUCCEEDED".equals(rows.getString(1));
            }
        }
    }

    /** First installation has no prior industry day; list stocks without inventing history. */
    static void initialize(Connection connection, long day) throws SQLException {
        fillVacancies(connection, day, new Random());
        trimExcess(connection, day);
    }

    static void run(Connection connection, long day) throws SQLException {
        run(connection, day, new Random());
    }

    static void run(Connection connection, long day, Random random) throws SQLException {
        Map<BuiltInIndustry, IndustryValue> industries = loadIndustries(connection, day - 1);
        Map<BuiltInIndustry, BigDecimal> scales =
                StockSignal.advance(connection, day - 1, industries);
        BuiltInIndustry bottom = decliningBottom(industries);
        updateBottomStreak(connection, day, bottom);

        List<Row> rows = loadListings(connection);
        List<StockSwanService.Effect> swanEffects =
                StockSwanService.pendingEffects(connection, day);
        for (Row row : rows) {
            if (row.status.equals("DELISTED")) continue;
            if (row.lastPriceDay >= day) continue;
            int price;
            double noise = row.noise;
            double base = row.base;
            noise = random.nextGaussian();
            price =
                    StockPricing.ordinaryPrice(
                            row.price,
                            row.initial,
                            industries.get(row.industry).prosperity().doubleValue(),
                            scales.get(row.industry).doubleValue(),
                            noise);
            for (StockSwanService.Effect effect : swanEffects)
                if (effect.industry() == row.industry
                        && (effect.good()
                                || (effect.targetStockId() != null
                                        && effect.targetStockId() == row.id)))
                    price =
                            StockPricing.swanPrice(
                                    row.price, row.initial, effect.good(), effect.ruleVersion());
            base = .9 * base + .1 * (.1 * price);
            String status = row.status;
            Long retirement = row.retirementDay;
            if (status.equals("ACTIVE")
                    && day - row.listedDay >= 3
                    && price <= retirementThreshold(row.initial, Math.max(price, row.high))
                    && canRetire(connection, row.industry)) {
                status = "RETIRING";
                retirement = day;
            }
            updateListing(connection, row, day, price, base, noise, status, retirement);
            dailyPrice(connection, row.id, day, price, status);
            int threshold = retirementThreshold(row.initial, Math.max(price, row.high));
            if (price <= threshold * 1.25)
                noticeHolders(
                        connection,
                        row.id,
                        day,
                        "RISK",
                        "[股票] "
                                + row.itemId
                                + " 股价 "
                                + price
                                + "，接近退市阈值 "
                                + threshold
                                + "，请关注交易窗口");
            if (!row.status.equals("RETIRING") && status.equals("RETIRING"))
                noticeHolders(
                        connection,
                        row.id,
                        day,
                        "RETIRING",
                        "[股票] " + row.itemId + " 今日退市，14:00 前可按正常股价手动卖出");
        }
        if (bottom != null) triggerBottomRetirement(connection, day, bottom);
        trimExcess(connection, day);
        fillVacancies(connection, day, random);
        trimExcess(connection, day);
        StockSwanService.markApplied(connection, swanEffects, day);
    }

    /** Existing 30-stock worlds converge to the 20-stock limit after today's trading window. */
    private static void trimExcess(Connection connection, long day) throws SQLException {
        List<Row> active =
                loadListings(connection).stream()
                        .filter(row -> row.status.equals("ACTIVE"))
                        .sorted(Comparator.comparingDouble(row -> row.price / (double) row.initial))
                        .toList();
        int surplus = Math.max(0, active.size() - 20);
        Map<BuiltInIndustry, Integer> counts = new EnumMap<>(BuiltInIndustry.class);
        for (Row row : active) counts.merge(row.industry, 1, Integer::sum);
        for (Row row : active) {
            if (surplus == 0) break;
            if (counts.get(row.industry) <= 1) continue;
            counts.merge(row.industry, -1, Integer::sum);
            surplus--;
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE stock_listing SET status = 'RETIRING', retirement_day = ? WHERE"
                                    + " stock_id = ?")) {
                update.setLong(1, day);
                update.setLong(2, row.id);
                update.executeUpdate();
            }
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE stock_daily_price SET status = 'RETIRING' WHERE stock_id = ?"
                                    + " AND game_day = ?")) {
                update.setLong(1, row.id);
                update.setLong(2, day);
                update.executeUpdate();
            }
            noticeHolders(
                    connection,
                    row.id,
                    day,
                    "RETIRING",
                    "[股票] " + row.itemId + " 今日退市，14:00 前可按正常股价手动卖出");
        }
    }

    static Map<BuiltInIndustry, IndustryValue> loadIndustries(Connection connection, long day)
            throws SQLException {
        Map<BuiltInIndustry, IndustryValue> result = new EnumMap<>(BuiltInIndustry.class);
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT industry_id, total_development, prosperity, long_ema, short_ema,"
                                + " daily_development, prosperity_version FROM"
                                + " industry_daily WHERE game_day = ?")) {
            query.setLong(1, day);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next())
                    for (BuiltInIndustry industry : BuiltInIndustry.values()) {
                        if (rows.getString(1).equals("contribution:" + industry.path()))
                            result.put(
                                    industry,
                                    new IndustryValue(
                                            rows.getLong(2),
                                            rows.getInt(7) < 2 && rows.getBigDecimal(4) != null
                                                    ? IndustryProsperity.calculate(
                                                            rows.getLong(6),
                                                            rows.getBigDecimal(4),
                                                            rows.getBigDecimal(5))
                                                    : rows.getBigDecimal(3),
                                            rows.getBigDecimal(4) != null));
                    }
            }
        }
        if (result.size() != BuiltInIndustry.values().length)
            throw new SQLException(
                    "Incomplete historical industry snapshot for stock settlement: " + day);
        return result;
    }

    static BuiltInIndustry decliningBottom(Map<BuiltInIndustry, IndustryValue> industries) {
        BigDecimal lowest =
                industries.values().stream()
                        .map(IndustryValue::prosperity)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
        if (lowest.signum() >= 0) return null;
        List<BuiltInIndustry> bottom =
                industries.entrySet().stream()
                        .filter(entry -> entry.getValue().prosperity().compareTo(lowest) == 0)
                        .map(Map.Entry::getKey)
                        .toList();
        return bottom.size() == 1 ? bottom.getFirst() : null;
    }

    private static void updateBottomStreak(Connection connection, long day, BuiltInIndustry bottom)
            throws SQLException {
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            String id = "contribution:" + industry.path();
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT IGNORE INTO stock_industry_streak (industry_id, bottom_streak,"
                                    + " last_bottom_retirement_day) VALUES (?, 0, -100)")) {
                insert.setString(1, id);
                insert.executeUpdate();
            }
            try (PreparedStatement update =
                    connection.prepareStatement(
                            "UPDATE stock_industry_streak SET bottom_streak = CASE WHEN ? THEN"
                                    + " bottom_streak + 1 ELSE 0 END WHERE industry_id = ?")) {
                update.setBoolean(1, industry == bottom);
                update.setString(2, id);
                update.executeUpdate();
            }
        }
    }

    private static void triggerBottomRetirement(
            Connection connection, long day, BuiltInIndustry bottom) throws SQLException {
        String industryId = "contribution:" + bottom.path();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT bottom_streak, last_bottom_retirement_day FROM"
                                + " stock_industry_streak WHERE industry_id = ?")) {
            query.setString(1, industryId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(1) < 7 || day - rows.getLong(2) < 7) return;
            }
        }
        Row candidate = null;
        for (Row row : loadListings(connection)) {
            if (row.status.equals("ACTIVE")
                    && row.industry == bottom
                    && day - row.listedDay >= 3
                    && (candidate == null
                            || (long) row.price * candidate.initial
                                    < (long) candidate.price * row.initial)) candidate = row;
        }
        if (candidate == null || !canRetire(connection, bottom)) return;
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_listing SET status = 'RETIRING', retirement_day = ? WHERE"
                                + " stock_id = ?")) {
            update.setLong(1, day);
            update.setLong(2, candidate.id);
            update.executeUpdate();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_daily_price SET status = 'RETIRING' WHERE stock_id = ? AND"
                                + " game_day = ?")) {
            update.setLong(1, candidate.id);
            update.setLong(2, day);
            update.executeUpdate();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_industry_streak SET last_bottom_retirement_day = ? WHERE"
                                + " industry_id = ?")) {
            update.setLong(1, day);
            update.setString(2, industryId);
            update.executeUpdate();
        }
        noticeHolders(
                connection,
                candidate.id,
                day,
                "RETIRING",
                "[股票] " + candidate.itemId + " 今日退市，14:00 前可按正常股价手动卖出");
    }

    /** Keep the last buyable stock until a distinct replacement symbol is available. */
    private static boolean canRetire(Connection connection, BuiltInIndustry industry)
            throws SQLException {
        List<Row> rows = loadListings(connection);
        long count =
                rows.stream()
                        .filter(row -> row.status.equals("ACTIVE") && row.industry == industry)
                        .count();
        if (count > 1) return true;
        Set<String> reserved = new HashSet<>();
        for (Row row : rows) reserved.add(row.itemId);
        return StockCatalog.all().stream()
                .anyMatch(
                        candidate ->
                                candidate.industry() == industry
                                        && !reserved.contains(candidate.itemId()));
    }

    static void closeRetirements(Connection connection, long day) throws SQLException {
        if (day < 2) return;
        initialize(connection, day);
        for (Row row : loadListings(connection)) {
            if (row.status.equals("RETIRING")
                    && row.retirementDay != null
                    && row.retirementDay <= day) delist(connection, row, day);
        }
        fillVacancies(connection, day, new Random());
    }

    private static void delist(Connection connection, Row row, long day) throws SQLException {
        try (PreparedStatement positions =
                connection.prepareStatement(
                        "SELECT player_uuid, quantity FROM stock_position WHERE stock_id = ? AND"
                                + " quantity > 0")) {
            positions.setLong(1, row.id);
            try (ResultSet holders = positions.executeQuery()) {
                while (holders.next()) {
                    byte[] playerBytes = holders.getBytes(1);
                    UUID holder = bytesUuid(playerBytes);
                    long amount = Math.max(0, (long) row.price * holders.getLong(2) * 1000);
                    String name = null;
                    String normalized = null;
                    try (PreparedStatement account =
                            connection.prepareStatement(
                                    "SELECT player_name, player_name_normalized FROM"
                                            + " contribution_account WHERE player_uuid = ? FOR"
                                            + " UPDATE")) {
                        account.setBytes(1, playerBytes);
                        try (ResultSet result = account.executeQuery()) {
                            if (result.next()) {
                                name = result.getString(1);
                                normalized = result.getString(2);
                            }
                        }
                    }
                    if (name == null) throw new SQLException("Retiring shareholder has no account");
                    GameCurrencyService.ensureAccount(connection, holder, name, normalized);
                    GameCurrencyService.WalletRow wallet =
                            GameCurrencyService.lockWallet(connection, holder);
                    if (wallet == null)
                        throw new SQLException("Retiring shareholder has no wallet");
                    long before = wallet.balanceMilli();
                    long after = before + amount;
                    try (PreparedStatement update =
                            connection.prepareStatement(
                                    "UPDATE game_currency_account SET balance_milli = ?, updated_at"
                                            + " = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
                        update.setLong(1, after);
                        update.setBytes(2, playerBytes);
                        update.executeUpdate();
                    }
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO stock_refund (player_uuid, stock_id, amount,"
                                            + " claimed) VALUES (?, ?, ?, ?)")) {
                        insert.setBytes(1, playerBytes);
                        insert.setLong(2, row.id);
                        insert.setLong(3, amount);
                        insert.setLong(4, amount);
                        insert.executeUpdate();
                    }
                    UUID request =
                            UUID.nameUUIDFromBytes(
                                    ("stock-retirement|" + row.id + "|" + bytesUuid(playerBytes))
                                            .getBytes(StandardCharsets.UTF_8));
                    byte[] requestHash;
                    try {
                        requestHash =
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                request.toString()
                                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (NoSuchAlgorithmException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                    GameCurrencyService.insertTransaction(
                            connection,
                            request,
                            requestHash,
                            holder,
                            name,
                            amount,
                            before,
                            after,
                            "REFUND",
                            GameCurrencyService.SOURCE_STOCK,
                            "股票退市自动返还",
                            "stock-market",
                            "stock-main");
                    try (PreparedStatement notice =
                            connection.prepareStatement(
                                    "INSERT IGNORE INTO stock_notice (player_uuid, stock_id,"
                                        + " game_day, kind, message, delivered_at) VALUES (?, ?, ?,"
                                        + " 'REFUND', ?, NULL)")) {
                        notice.setBytes(1, playerBytes);
                        notice.setLong(2, row.id);
                        notice.setLong(3, day);
                        notice.setString(
                                4,
                                "[股票] "
                                        + row.itemId
                                        + " 已退市，按当日股价全额返还 "
                                        + GameCurrencyService.format(amount)
                                        + " 游戏币，已到账");
                        notice.executeUpdate();
                    }
                }
            }
        }
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM stock_position WHERE stock_id = ?")) {
            delete.setLong(1, row.id);
            delete.executeUpdate();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_listing SET status = 'DELISTED', delisted_day = ? WHERE"
                                + " stock_id = ?")) {
            update.setLong(1, day);
            update.setLong(2, row.id);
            update.executeUpdate();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_daily_price SET status = 'DELISTED' WHERE stock_id = ? AND"
                                + " game_day = ?")) {
            update.setLong(1, row.id);
            update.setLong(2, row.retirementDay);
            update.executeUpdate();
        }
    }

    private static void fillVacancies(Connection connection, long day, Random random)
            throws SQLException {
        List<Row> active =
                loadListings(connection).stream()
                        .filter(row -> !row.status.equals("DELISTED"))
                        .toList();
        int activeCount = (int) active.stream().filter(row -> row.status.equals("ACTIVE")).count();
        Set<String> listed = new HashSet<>();
        Set<BuiltInIndustry> covered = new HashSet<>();
        for (Row row : active) {
            listed.add(row.itemId);
            if (row.status.equals("ACTIVE")) covered.add(row.industry);
        }
        Set<String> recent = new HashSet<>();
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT item_id FROM stock_listing WHERE status = 'DELISTED' ORDER"
                                        + " BY delisted_day DESC, stock_id DESC LIMIT 5");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) recent.add(rows.getString(1));
        }
        while (activeCount < 20 || covered.size() < BuiltInIndustry.values().length) {
            StockCatalog.Candidate choice =
                    selectCandidate(listed, recent, covered, activeCount < 20, random);
            if (choice == null) break;
            int price = 100 + random.nextInt(301);
            long id;
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO stock_listing (item_id, item_name, industry_id,"
                                + " listed_day, initial_price, price, high_price, low_price,"
                                + " wave_base, ou_noise, status, retirement_day, last_price_day,"
                                + " delisted_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE',"
                                + " NULL, ?, NULL)",
                            PreparedStatement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, choice.itemId());
                insert.setString(2, choice.name());
                insert.setString(3, "contribution:" + choice.industry().path());
                insert.setLong(4, day);
                insert.setInt(5, price);
                insert.setInt(6, price);
                insert.setInt(7, price);
                insert.setInt(8, price);
                insert.setBigDecimal(9, BigDecimal.valueOf(price).movePointLeft(1));
                insert.setLong(10, day);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            dailyPrice(connection, id, day, price, "ACTIVE");
            listed.add(choice.itemId());
            covered.add(choice.industry());
            activeCount++;
        }
    }

    /** Choose an eligible industry uniformly, then a symbol uniformly within that industry. */
    static StockCatalog.Candidate selectCandidate(
            Set<String> listed,
            Set<String> recent,
            Set<BuiltInIndustry> covered,
            boolean needMore,
            Random random) {
        var ordinary = availableCandidates(listed, recent);
        var choices =
                new EnumMap<BuiltInIndustry, List<StockCatalog.Candidate>>(BuiltInIndustry.class);
        ordinary.forEach(
                (industry, candidates) -> {
                    if (!covered.contains(industry)) choices.put(industry, candidates);
                });
        if (choices.isEmpty())
            availableCandidates(listed, Set.of())
                    .forEach(
                            (industry, candidates) -> {
                                if (!covered.contains(industry)) choices.put(industry, candidates);
                            });
        if (choices.isEmpty() && needMore) choices.putAll(ordinary);
        if (choices.isEmpty()) return null;
        var industries = new ArrayList<>(choices.keySet());
        var candidates = choices.get(industries.get(random.nextInt(industries.size())));
        return candidates.get(random.nextInt(candidates.size()));
    }

    private static Map<BuiltInIndustry, List<StockCatalog.Candidate>> availableCandidates(
            Set<String> listed, Set<String> recent) {
        var result =
                new EnumMap<BuiltInIndustry, List<StockCatalog.Candidate>>(BuiltInIndustry.class);
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            var candidates =
                    StockCatalog.forIndustry(industry).stream()
                            .filter(
                                    candidate ->
                                            !listed.contains(candidate.itemId())
                                                    && !recent.contains(candidate.itemId()))
                            .toList();
            if (!candidates.isEmpty()) result.put(industry, candidates);
        }
        return result;
    }

    private static void updateListing(
            Connection connection,
            Row row,
            long day,
            int price,
            double base,
            double noise,
            String status,
            Long retirement)
            throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_listing SET price = ?, high_price = ?, low_price = ?,"
                                + " wave_base = ?, ou_noise = ?, status = ?, retirement_day = ?,"
                                + " last_price_day = ? WHERE stock_id = ?")) {
            update.setInt(1, price);
            update.setInt(2, Math.max(price, row.high));
            update.setInt(3, Math.min(price, row.low));
            update.setBigDecimal(4, BigDecimal.valueOf(base).setScale(8, RoundingMode.HALF_UP));
            update.setBigDecimal(5, BigDecimal.valueOf(noise).setScale(8, RoundingMode.HALF_UP));
            update.setString(6, status);
            if (retirement == null) update.setNull(7, java.sql.Types.BIGINT);
            else update.setLong(7, retirement);
            update.setLong(8, day);
            update.setLong(9, row.id);
            update.executeUpdate();
        }
    }

    static int retirementThreshold(int initial, int high) {
        return StockPricing.retirementThreshold(initial, high);
    }

    private static void noticeHolders(
            Connection connection, long stockId, long day, String kind, String message)
            throws SQLException {
        try (PreparedStatement notice =
                connection.prepareStatement(
                        "INSERT IGNORE INTO stock_notice (player_uuid, stock_id, game_day, kind,"
                            + " message, delivered_at) SELECT player_uuid, stock_id, ?, ?, ?, NULL"
                            + " FROM stock_position WHERE stock_id = ? AND quantity > 0")) {
            notice.setLong(1, day);
            notice.setString(2, kind);
            notice.setString(3, message);
            notice.setLong(4, stockId);
            notice.executeUpdate();
        }
    }

    private static void dailyPrice(
            Connection connection, long id, long day, int price, String status)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO stock_daily_price (stock_id, game_day, price, status) VALUES"
                                + " (?, ?, ?, ?)")) {
            insert.setLong(1, id);
            insert.setLong(2, day);
            insert.setInt(3, price);
            insert.setString(4, status);
            insert.executeUpdate();
        }
    }

    private static List<Row> loadListings(Connection connection) throws SQLException {
        List<Row> result = new ArrayList<>();
        try (PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT stock_id, item_id, industry_id, listed_day, initial_price,"
                                    + " price, high_price, low_price, wave_base, ou_noise, status,"
                                    + " retirement_day, last_price_day FROM stock_listing WHERE"
                                    + " status <> 'DELISTED'");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                BuiltInIndustry industry = null;
                for (BuiltInIndustry value : BuiltInIndustry.values())
                    if (rows.getString(3).equals("contribution:" + value.path())) industry = value;
                if (industry == null)
                    throw new SQLException("Unknown stock industry " + rows.getString(3));
                long retire = rows.getLong(12);
                boolean noRetirement = rows.wasNull();
                result.add(
                        new Row(
                                rows.getLong(1),
                                rows.getString(2),
                                industry,
                                rows.getLong(4),
                                rows.getInt(5),
                                rows.getInt(6),
                                rows.getInt(7),
                                rows.getInt(8),
                                rows.getBigDecimal(9).doubleValue(),
                                rows.getBigDecimal(10).doubleValue(),
                                rows.getString(11),
                                noRetirement ? null : retire,
                                rows.getLong(13)));
            }
        }
        return result;
    }

    record IndustryValue(long total, BigDecimal prosperity, boolean initialized) {
        IndustryValue(long total, BigDecimal prosperity) {
            this(total, prosperity, true);
        }
    }

    private record Row(
            long id,
            String itemId,
            BuiltInIndustry industry,
            long listedDay,
            int initial,
            int price,
            int high,
            int low,
            double base,
            double noise,
            String status,
            Long retirementDay,
            long lastPriceDay) {}
}
