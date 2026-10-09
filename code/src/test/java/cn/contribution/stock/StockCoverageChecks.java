package cn.contribution.stock;

import cn.contribution.database.DatabaseService;
import cn.contribution.industry.BuiltInIndustry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Random;

/** Destructive fixtures run only inside a savepoint in the isolated integration database. */
final class StockCoverageChecks {
    static void verify(DatabaseService database) {
        database.transaction(
                        c -> {
                            var savepoint = c.setSavepoint();
                            StockSettlement.closeRetirements(c, 1);
                            check(
                                    count(c, "SELECT COUNT(*) FROM stock_listing") == 0,
                                    "retirement close must not open the market before day three");
                            StockSettlement.initialize(c, 90);
                            String missing = "contribution:" + BuiltInIndustry.values()[0].path();
                            try (var update =
                                    c.prepareStatement(
                                            "UPDATE stock_listing SET status = 'DELISTED',"
                                                    + " delisted_day = 91 WHERE industry_id = ?")) {
                                update.setString(1, missing);
                                update.executeUpdate();
                            }
                            // Recent symbols stay excluded when the larger industry pool has
                            // alternatives.
                            for (var candidate :
                                    StockCatalog.forIndustry(BuiltInIndustry.values()[0]).stream()
                                            .limit(5)
                                            .toList())
                                insert(c, candidate, "DELISTED", 100, 90, 91L);
                            StockSettlement.initialize(c, 92);
                            assertMarket(c, 20, 9);
                            long listingCount = count(c, "SELECT COUNT(*) FROM stock_listing");
                            StockSettlement.initialize(c, 92);
                            check(
                                    listingCount == count(c, "SELECT COUNT(*) FROM stock_listing"),
                                    "coverage repair is idempotent");

                            // Fill the market with 20 active rows while removing one industry
                            // entirely.
                            try (var update =
                                    c.prepareStatement(
                                            "UPDATE stock_listing SET status = 'DELISTED',"
                                                    + " delisted_day = 93 WHERE industry_id = ?")) {
                                update.setString(1, missing);
                                update.executeUpdate();
                            }
                            var duplicate =
                                    StockCatalog.all().stream()
                                            .filter(
                                                    candidate ->
                                                            candidate.industry()
                                                                    != BuiltInIndustry.values()[0])
                                            .findFirst()
                                            .orElseThrow();
                            while (count(
                                            c,
                                            "SELECT COUNT(*) FROM stock_listing WHERE status ="
                                                    + " 'ACTIVE'")
                                    < 20) insert(c, duplicate, "ACTIVE", 100, 90, null);
                            StockSettlement.initialize(c, 94);
                            assertMarket(c, 20, 9);
                            check(
                                    count(
                                                    c,
                                                    "SELECT COUNT(*) FROM stock_listing WHERE"
                                                            + " status = 'RETIRING'")
                                            > 0,
                                    "full market repair preserves the outgoing trading window");
                            StockSettlement.closeRetirements(c, 94);
                            assertMarket(c, 20, 9);
                            check(
                                    count(
                                                    c,
                                                    "SELECT COUNT(*) FROM stock_listing WHERE"
                                                            + " status = 'RETIRING'")
                                            == 0,
                                    "closing retirement fills immediately");
                            c.rollback(savepoint);

                            savepoint = c.setSavepoint();
                            // Exhaust one industry's whole pool and trigger mass retirement.
                            var exhausted = BuiltInIndustry.ENERGY_CHEMICAL;
                            for (var candidate : StockCatalog.forIndustry(exhausted))
                                insert(c, candidate, "ACTIVE", 1, 0, null);
                            for (var industry : BuiltInIndustry.values())
                                if (industry != exhausted)
                                    insert(
                                            c,
                                            StockCatalog.forIndustry(industry).getFirst(),
                                            "ACTIVE",
                                            100,
                                            0,
                                            null);
                            for (var industry : BuiltInIndustry.values()) {
                                try (var insert =
                                        c.prepareStatement(
                                                "INSERT INTO industry_daily (game_day, industry_id,"
                                                    + " daily_development, total_development,"
                                                    + " long_ema, short_ema, prosperity,"
                                                    + " config_version, config_hash, settled_at,"
                                                    + " prosperity_version) VALUES (7, ?, 0, 0, 0,"
                                                    + " 0, 0, 1, ?, CURRENT_TIMESTAMP(6), 2)")) {
                                    insert.setString(1, "contribution:" + industry.path());
                                    insert.setBytes(2, new byte[32]);
                                    insert.executeUpdate();
                                }
                            }
                            StockSettlement.run(
                                    c,
                                    8,
                                    new Random(1) {
                                        @Override
                                        public double nextGaussian() {
                                            return 0;
                                        }
                                    });
                            assertMarket(c, 20, 9);
                            check(
                                    count(
                                                    c,
                                                    "SELECT COUNT(*) FROM stock_listing WHERE"
                                                            + " status = 'ACTIVE' AND industry_id ="
                                                            + " 'contribution:energy_chemical'")
                                            == 1,
                                    "last stock protected when all industry symbols are reserved");
                            check(
                                    count(
                                                    c,
                                                    "SELECT COUNT(DISTINCT item_id) FROM"
                                                            + " stock_listing WHERE status <>"
                                                            + " 'DELISTED'")
                                            == 20 + StockCatalog.forIndustry(exhausted).size() - 1,
                                    "no duplicate symbol during mass retirement");
                            StockSettlement.closeRetirements(c, 8);
                            assertMarket(c, 20, 9);
                            long total = count(c, "SELECT COUNT(*) FROM stock_listing");
                            StockSettlement.closeRetirements(c, 8);
                            check(
                                    total == count(c, "SELECT COUNT(*) FROM stock_listing"),
                                    "retirement close retry does not duplicate replacements");
                            c.rollback(savepoint);

                            savepoint = c.setSavepoint();
                            byte[] holder =
                                    cn.contribution.account.AccountService.uuidBytes(
                                            java.util.UUID.randomUUID());
                            try (var insert =
                                    c.prepareStatement(
                                            "INSERT INTO contribution_account (player_uuid,"
                                                + " player_name, player_name_normalized, balance,"
                                                + " total_income, created_at, updated_at) VALUES"
                                                + " (?, 'RefundCap', 'refundcap', 0, 0,"
                                                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
                                insert.setBytes(1, holder);
                                insert.executeUpdate();
                            }
                            insert(c, StockCatalog.all().getFirst(), "RETIRING", 101, 2, null);
                            long retiring = count(c, "SELECT MAX(stock_id) FROM stock_listing");
                            try (var update =
                                    c.prepareStatement(
                                            "UPDATE stock_listing SET retirement_day = 9 WHERE"
                                                    + " stock_id = ?")) {
                                update.setLong(1, retiring);
                                update.executeUpdate();
                            }
                            try (var insert =
                                    c.prepareStatement(
                                            "INSERT INTO stock_position (player_uuid, stock_id,"
                                                + " quantity, bought_day, bought_quantity) VALUES"
                                                + " (?, ?, 10000, 2, 0)")) {
                                insert.setBytes(1, holder);
                                insert.setLong(2, retiring);
                                insert.executeUpdate();
                            }
                            StockSettlement.closeRetirements(c, 9);
                            check(
                                    count(c, "SELECT amount FROM stock_refund") == 1_010_000_000L,
                                    "refund retains the entire price times quantity in milli-GC");
                            check(
                                    count(c, "SELECT claimed FROM stock_refund") == 1_010_000_000L,
                                    "the whole refund is credited to the GC wallet");
                            check(
                                    countFor(
                                                    c,
                                                    "SELECT balance_milli FROM"
                                                            + " game_currency_account WHERE"
                                                            + " player_uuid = ?",
                                                    holder)
                                            == 1_010_000_000L,
                                    "GC wallet holds the full credited refund");
                            check(
                                    count(c, "SELECT COUNT(*) FROM stock_position") == 0,
                                    "retirement clears refunded holdings");
                            StockSettlement.closeRetirements(c, 9);
                            check(
                                    count(c, "SELECT COUNT(*) FROM stock_refund") == 1,
                                    "retirement retry does not pay twice");
                            c.rollback(savepoint);
                            return null;
                        })
                .join();
        System.out.println(
                "STOCK_COVERAGE_PASS: recent exclusion, full market, idempotence,"
                        + " protected industry, mass retirement, immediate replacement");
    }

    private static void insert(
            Connection c,
            StockCatalog.Candidate candidate,
            String status,
            int price,
            long listedDay,
            Long delistedDay)
            throws SQLException {
        try (var insert =
                c.prepareStatement(
                        "INSERT INTO stock_listing (item_id, item_name, industry_id, listed_day,"
                            + " initial_price, price, high_price, low_price, wave_base, ou_noise,"
                            + " status, retirement_day, last_price_day, delisted_day) VALUES (?, ?,"
                            + " ?, ?, 100, ?, 100, ?, 0, 0, ?, NULL, ?, ?)")) {
            insert.setString(1, candidate.itemId());
            insert.setString(2, candidate.name());
            insert.setString(3, "contribution:" + candidate.industry().path());
            insert.setLong(4, listedDay);
            insert.setInt(5, price);
            insert.setInt(6, price);
            insert.setString(7, status);
            insert.setLong(8, listedDay);
            if (delistedDay == null) insert.setNull(9, java.sql.Types.BIGINT);
            else insert.setLong(9, delistedDay);
            insert.executeUpdate();
        }
    }

    private static void assertMarket(Connection c, int stocks, int industries) throws SQLException {
        check(
                count(c, "SELECT COUNT(*) FROM stock_listing WHERE status = 'ACTIVE'") == stocks,
                "expected active count " + stocks);
        check(
                count(
                                c,
                                "SELECT COUNT(DISTINCT industry_id) FROM stock_listing WHERE status"
                                        + " = 'ACTIVE'")
                        == industries,
                "every industry remains buyable");
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (var query = c.prepareStatement(sql);
                var rows = query.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long countFor(Connection c, String sql, byte[] parameter) throws SQLException {
        try (var query = c.prepareStatement(sql)) {
            query.setBytes(1, parameter);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
