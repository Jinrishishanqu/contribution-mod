package cn.contribution.stock;

import cn.contribution.account.AccountService;
import cn.contribution.api.AccountTarget;
import cn.contribution.api.BalanceChangeRequest;
import cn.contribution.api.BalanceChangeType;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Isolated embedded or loopback-MySQL stock integration; never touches a player's world. */
public final class StockIntegrationChecks {
    public static void main(String[] args) throws Exception {
        boolean mysql = args.length > 0 && args[0].equals("mysql");
        String temporarySchema = "contribution_stock_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ServerConfig config = new ServerConfig();
        config.database.mode = mysql ? "mysql" : "embedded";
        config.database.jdbcUrl = "jdbc:mysql://127.0.0.1:23306/" + temporarySchema + "?serverTimezone=UTC";
        config.database.username = "root";
        Path path = mysql ? Path.of("unused-stock-mysql")
                : Files.createTempDirectory(Path.of("build"), "embedded-stock-").resolve("contribution");
        try (DatabaseService db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration");
            StockService stocks = new StockService(db, config);
            AccountService accounts = new AccountService(db, "stock-test");
            UUID player = UUID.randomUUID();
            accounts.registerPlayer(player, "StockTester").join();
            accounts.changeBalance(new BalanceChangeRequest(UUID.randomUUID(), AccountTarget.byUuid(player), 100000,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:test"), "测试资金", "")).join();
            db.transaction(connection -> { StockSettlement.run(connection, 2); return null; }).join();
            var market = stocks.market(player).join();
            check(market.listings().size() == 20, "initial 20 listings");
            check(market.listings().stream().map(StockView.Listing::industry).distinct().count() == 9, "all industries");
            check(market.listings().stream().allMatch(stock -> stock.initialPrice() >= 100
                    && stock.initialPrice() <= 400), "random initial prices stay within 100-400");
            check(StockSettlement.retirementThreshold(100, 1000) == 50, "initial price drives retirement floor");
            check(StockSettlement.retirementThreshold(100, 120) == 30, "historical high drives retirement floor");
            UUID batchId = UUID.randomUUID();
            check(stocks.openBatch(player, batchId, "1,2", 3, true).join(), "reserve batch identity");
            check(stocks.openBatch(player, batchId, "1,2", 3, true).join(), "retry same batch");
            check(!stocks.openBatch(player, batchId, "1,3", 3, true).join(), "reject changed batch list");
            String symbol = Long.toString(market.listings().getFirst().id());
            db.transaction(connection -> {
                try (var update = connection.prepareStatement(
                        "UPDATE stock_market_state SET game_day = 2, day_time = 5000, updated_at = CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
                    update.executeUpdate();
                }
                return null;
            }).join();
            UUID buyId = UUID.randomUUID();
            var buy = stocks.trade(player, symbol, 10, true, buyId).join();
            check(buy.success(), "buy: " + buy.message());
            check(stocks.trade(player, symbol, 10, true, buyId).join().replay(), "idempotent buy");
            check(!stocks.trade(player, symbol, 10, true, UUID.randomUUID()).join().success(), "one buy per day");
            check(!stocks.trade(player, symbol, 1, false, UUID.randomUUID()).join().success(), "no same-day resale");
            check(!stocks.trade(player, symbol, 10000, false, UUID.randomUUID()).join().success(), "holding bound");
            db.transaction(connection -> {
                try (var crash = connection.prepareStatement(
                        "UPDATE stock_listing SET price = initial_price / 4, high_price = initial_price * 4, wave_base = 1 WHERE stock_id = ?")) {
                    crash.setLong(1, Long.parseLong(symbol)); crash.executeUpdate();
                }
                StockSettlement.run(connection, 3);
                try (var update = connection.prepareStatement(
                        "UPDATE stock_market_state SET game_day = 3, day_time = 5000, updated_at = CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
                    update.executeUpdate();
                }
                return null;
            }).join();
            db.transaction(connection -> {
                try (var query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM stock_notice WHERE player_uuid = ? AND stock_id = ? AND kind = 'RISK'")) {
                    query.setBytes(1, AccountService.uuidBytes(player)); query.setLong(2, Long.parseLong(symbol));
                    try (var rows = query.executeQuery()) { rows.next(); check(rows.getInt(1) == 1, "risk notice queued"); }
                }
                return null;
            }).join();
            check(!stocks.trade(player, symbol, 5000, true, UUID.randomUUID()).join().success(),
                    "insufficient balance is rejected before writing a trade");
            UUID sellId = UUID.randomUUID();
            var sell = stocks.trade(player, symbol, 5, false, sellId).join();
            check(sell.success(), "sell: " + sell.message());
            check(stocks.trade(player, symbol, 5, false, sellId).join().replay(), "idempotent sell");
            check(accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().totalIncome()
                    == 100000, "sale does not count as historical income");
            var detail = stocks.detail(player, symbol, 7).join();
            check(detail.prices().size() == 2, "daily history");
            check(detail.position().quantity() == 5 && detail.position().costBasis() > 0,
                    "remaining position cost basis");
            var dashboard = stocks.dashboard(player).join();
            check(dashboard.portfolio().positions().containsKey(Long.parseLong(symbol)), "portfolio position");
            db.transaction(connection -> {
                try (var query = connection.prepareStatement(
                        "SELECT type, income_delta FROM contribution_transaction WHERE player_uuid = ? AND source = 'contribution:stock' ORDER BY record_no")) {
                    query.setBytes(1, AccountService.uuidBytes(player));
                    try (var rows = query.executeQuery()) {
                        check(rows.next() && "SPEND".equals(rows.getString(1)) && rows.getInt(2) == 0, "buy ledger type");
                        check(rows.next() && "STOCK".equals(rows.getString(1)) && rows.getInt(2) == 0, "sell ledger type");
                    }
                }
                return null;
            }).join();
            check(StockChart.draw(detail.prices()).size() == 11, "visible line chart");
            db.transaction(connection -> {
                try (var stale = connection.prepareStatement(
                        "UPDATE stock_market_state SET updated_at = '2020-01-01 00:00:00' WHERE singleton_id = 1")) {
                    stale.executeUpdate();
                }
                return null;
            }).join();
            check(!stocks.trade(player, symbol, 1, true, UUID.randomUUID()).join().success(), "stale main server closes market");
            var accountBeforeRetirement = accounts.account(AccountTarget.byUuid(player)).join().orElseThrow();
            db.transaction(connection -> {
                try (var retire = connection.prepareStatement(
                        "UPDATE stock_listing SET status = 'RETIRING', retirement_day = 3 WHERE stock_id = ?")) {
                    retire.setLong(1, Long.parseLong(symbol)); retire.executeUpdate();
                }
                StockSettlement.closeRetirements(connection, 3);
                return null;
            }).join();
            long expectedRefund = Math.round(sell.price() * 5 * .5);
            long actualRefund = db.transaction(connection -> {
                try (var query = connection.prepareStatement(
                        "SELECT amount FROM stock_refund WHERE player_uuid = ? AND stock_id = ?")) {
                    query.setBytes(1, AccountService.uuidBytes(player)); query.setLong(2, Long.parseLong(symbol));
                    try (var row = query.executeQuery()) { row.next(); return row.getLong(1); }
                }
            }).join();
            check(actualRefund == expectedRefund, "50% retirement refund");
            db.transaction(connection -> {
                try (var query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM stock_notice WHERE player_uuid = ? AND stock_id = ? AND kind = 'REFUND'")) {
                    query.setBytes(1, AccountService.uuidBytes(player)); query.setLong(2, Long.parseLong(symbol));
                    try (var rows = query.executeQuery()) { rows.next(); check(rows.getInt(1) == 1, "refund notice queued"); }
                }
                return null;
            }).join();
            var accountAfterRetirement = accounts.account(AccountTarget.byUuid(player)).join().orElseThrow();
            check(accountAfterRetirement.balance() == accountBeforeRetirement.balance() + expectedRefund,
                    "retirement automatically credited at close");
            long expectedProfit = 5L * sell.price() - sell.fee() + expectedRefund
                    - (10L * buy.price() + buy.fee());
            check(stocks.dashboard(player).join().portfolio().realizedProfit() == expectedProfit,
                    "portfolio realized profit includes sale fees and retirement refund");
            int incomeBefore = accountBeforeRetirement.totalIncome();
            String claim = stocks.claim(player, UUID.randomUUID()).join();
            check(claim.contains("暂无待领取"), "refund was already credited: " + claim);
            check(accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().totalIncome() == incomeBefore,
                    "refund does not count as income");
            System.out.println((mysql ? "MYSQL" : "EMBEDDED")
                    + "_STOCK_PASS: listing, coverage, buy/sell, fees, no same-day resale, idempotency, chart, stale clock, refund");
        } finally {
            if (mysql) {
                // Only the random schema created by this invocation is removed; no shared test or player data.
                try (var admin = java.sql.DriverManager.getConnection(
                        "jdbc:mysql://127.0.0.1:23306/mysql?serverTimezone=UTC", "root", "");
                     var remove = admin.createStatement()) {
                    remove.execute("DROP DATABASE IF EXISTS `" + temporarySchema + "`");
                }
            }
        }
    }

    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
