package cn.contribution.stock;

import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.BuiltInIndustry;
import cn.contribution.industry.IndustryProsperity;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/** Economic boundaries and transactional persistence in a fresh isolated world database. */
public final class StockModelChecks {
    public static void main(String[] args) throws Exception {
        StockCatalogChecks.verify();
        verifyUpgrade();
        check(
                Math.abs(StockPricing.dailyReturn(.5, .5, 1) - (.05 * Math.tanh(1) + .04)) < 1e-12,
                "five percent trend and four percent noise");
        for (long stable : new long[] {0, 1, 100, 1000000}) {
            var baseline = BigDecimal.valueOf(stable);
            check(
                    IndustryProsperity.calculate(stable, baseline, baseline).signum() == 0,
                    "stable construction is neutral at every scale");
        }
        check(
                IndustryProsperity.calculate(200, new BigDecimal("101"), new BigDecimal("120"))
                                .signum()
                        > 0,
                "construction growth is positive");
        check(
                IndustryProsperity.calculate(0, new BigDecimal("99"), new BigDecimal("80")).signum()
                        < 0,
                "construction decline is negative");
        check(
                StockPricing.ordinaryPrice(100, 100, 0, .5, 3) == 112,
                "ordinary days allow increases beyond six percent");
        check(
                StockPricing.ordinaryPrice(100, 100, 0, .5, -3) == 88,
                "ordinary days allow decreases beyond six percent");
        check(
                StockPricing.ordinaryPrice(100, 100, 0, .5, -100) == 1,
                "extreme noise cannot create a nonpositive price");
        check(StockPricing.ordinaryPrice(1000, 100, 1, .5, 100) == 1000, "listing cap");
        check(
                StockPricing.dailyReturn(.5, 0, 0) == StockPricing.dailyReturn(.5, .5, 0),
                "scale floor");
        check(
                StockPricing.dailyReturn(.5, 100, 0) == StockPricing.dailyReturn(.5, 1.5, 0),
                "scale ceiling");
        check(
                StockPricing.dailyReturn(.5, 1, 0) > 0 && StockPricing.dailyReturn(-.5, 1, 0) < 0,
                "prosperity determines the trend sign");
        check(
                StockPricing.swanPrice(100, 100, false, 1) == 60
                        && StockPricing.swanPrice(100, 100, false, 2) == 75
                        && StockPricing.swanPrice(102, 100, false) == 77,
                "legacy swans preserved and new swans rounded");
        var industries =
                new EnumMap<BuiltInIndustry, StockSettlement.IndustryValue>(BuiltInIndustry.class);
        for (var industry : BuiltInIndustry.values())
            industries.put(industry, new StockSettlement.IndustryValue(0, BigDecimal.ZERO));
        check(StockSettlement.decliningBottom(industries) == null, "neutral ties have no loser");
        var first = BuiltInIndustry.values()[0];
        var second = BuiltInIndustry.values()[1];
        industries.put(first, new StockSettlement.IndustryValue(0, new BigDecimal("-0.5")));
        check(StockSettlement.decliningBottom(industries) == first, "unique declining loser");
        industries.put(second, new StockSettlement.IndustryValue(0, new BigDecimal("-0.50")));
        check(
                StockSettlement.decliningBottom(industries) == null,
                "negative numeric ties have no loser");
        industries.put(first, new StockSettlement.IndustryValue(0, BigDecimal.ONE));
        industries.put(second, new StockSettlement.IndustryValue(0, BigDecimal.ZERO));
        Path path =
                Files.createTempDirectory(Path.of("build"), "stock-signal-")
                        .resolve("contribution");
        var config = new ServerConfig();
        try (var db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "signal migration");
            db.transaction(
                            c -> {
                                var scale = StockSignal.advance(c, 10, industries);
                                check(
                                        scale.get(first).compareTo(new BigDecimal("0.5")) == 0,
                                        "today uses the previous scale, not today's updated"
                                                + " magnitude");
                                return null;
                            })
                    .join();
            try {
                db.transaction(
                                c -> {
                                    StockSignal.advance(c, 11, industries);
                                    throw new java.sql.SQLException("deliberate rollback");
                                })
                        .join();
                throw new AssertionError("rollback accepted");
            } catch (java.util.concurrent.CompletionException expected) {
            }
        }
        try (var db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "signal restart");
            db.transaction(
                            c -> {
                                var scale = StockSignal.advance(c, 11, industries);
                                check(
                                        scale.get(first).compareTo(new BigDecimal("0.525")) == 0,
                                        "EMA persisted through restart, rolled-back update did not"
                                                + " survive");
                                return null;
                            })
                    .join();
        }
        System.out.println(
                "STOCK_MODEL_PASS: neutral prosperity, trend, unbounded daily return, scale limits,"
                        + " beta, previous-day scale, rollback, restart, ties, legacy swans");
    }

    private static void verifyUpgrade() throws Exception {
        Path path =
                Files.createTempDirectory(Path.of("build"), "stock-upgrade-")
                        .resolve("contribution");
        String url =
                "jdbc:h2:file:"
                        + path.toAbsolutePath().toString().replace('\\', '/')
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target("21")
                .load()
                .migrate();
        byte[] hash = new byte[32];
        java.util.UUID eventId = java.util.UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
            for (var industry : BuiltInIndustry.values()) {
                String id = "contribution:" + industry.path();
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO industry_daily (game_day, industry_id,"
                                    + " daily_development, total_development, long_ema, short_ema,"
                                    + " prosperity, config_version, config_hash, settled_at) VALUES"
                                    + " (5, ?, 0, 123, 0, 0, -1, 1, ?, CURRENT_TIMESTAMP(6))")) {
                    insert.setString(1, id);
                    insert.setBytes(2, hash);
                    insert.executeUpdate();
                }
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO industry_state (industry_id, last_settled_game_day,"
                                    + " total_development, long_ema, short_ema, prosperity,"
                                    + " config_version, config_hash, updated_at) VALUES (?, 5, 123,"
                                    + " 0, 0, -1, 1, ?, CURRENT_TIMESTAMP(6))")) {
                    insert.setString(1, id);
                    insert.setBytes(2, hash);
                    insert.executeUpdate();
                }
            }
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO stock_swan_event (event_id, player_uuid, player_name,"
                                + " started_clock, deadline_clock) VALUES (?, ?, 'LegacyHuman', ?,"
                                + " ?)")) {
                insert.setBytes(1, cn.contribution.account.AccountService.uuidBytes(eventId));
                insert.setBytes(
                        2,
                        cn.contribution.account.AccountService.uuidBytes(
                                java.util.UUID.randomUUID()));
                insert.setLong(3, 5 * 24000 + 2000);
                insert.setLong(4, 5 * 24000 + 4000);
                insert.executeUpdate();
            }
            for (int i = 0; i < 3; i++) {
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO stock_swan_candidate (event_id, industry_id,"
                                        + " baseline_development) VALUES (?, ?, 0)")) {
                    insert.setBytes(1, cn.contribution.account.AccountService.uuidBytes(eventId));
                    insert.setString(2, "contribution:" + BuiltInIndustry.values()[i].path());
                    insert.executeUpdate();
                }
            }
            try (var update =
                    connection.prepareStatement(
                            "UPDATE stock_swan_schedule SET active_event_id = ? WHERE singleton_id"
                                    + " = 1")) {
                update.setBytes(1, cn.contribution.account.AccountService.uuidBytes(eventId));
                update.executeUpdate();
            }
        }
        try (var db = new DatabaseService(new ServerConfig(), path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "upgrade from v21");
            db.transaction(
                            c -> {
                                check(
                                        StockSettlement.loadIndustries(c, 5).values().stream()
                                                .allMatch(
                                                        value -> value.prosperity().signum() == 0),
                                        "old stock snapshots translated to neutral without"
                                                + " overwriting history");
                                try (var query =
                                                c.prepareStatement(
                                                        "SELECT prosperity, total_development FROM"
                                                                + " industry_state");
                                        var rows = query.executeQuery()) {
                                    while (rows.next())
                                        check(
                                                rows.getBigDecimal(1).signum() == 0
                                                        && rows.getLong(2) == 123,
                                                "only current derived prosperity corrected");
                                }
                                try (var query =
                                                c.prepareStatement(
                                                        "SELECT prosperity FROM industry_daily");
                                        var rows = query.executeQuery()) {
                                    while (rows.next())
                                        check(
                                                rows.getBigDecimal(1)
                                                                .compareTo(BigDecimal.ONE.negate())
                                                        == 0,
                                                "historical prosperity retained");
                                }
                                StockSettlement.initialize(c, 5);
                                StockSwanService.advance(
                                        c,
                                        5 * 24000 + 4100,
                                        java.util.List.of(),
                                        new java.util.Random(7));
                                var effects = StockSwanService.pendingEffects(c, 6);
                                check(
                                        effects.size() == 3
                                                && effects.stream()
                                                        .allMatch(
                                                                effect ->
                                                                        effect.ruleVersion() == 1
                                                                                && !effect.good()),
                                        "old active event completes with three bad industries and"
                                                + " its original rule version");
                                return null;
                            })
                    .join();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
