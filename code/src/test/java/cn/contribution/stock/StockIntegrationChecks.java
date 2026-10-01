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
            check(market.listings().size() == 30, "initial 30 listings");
            check(market.listings().stream().map(StockView.Listing::industry).distinct().count() == 9, "all industries");
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
                StockSettlement.run(connection, 3);
                try (var update = connection.prepareStatement(
                        "UPDATE stock_market_state SET game_day = 3, day_time = 5000, updated_at = CURRENT_TIMESTAMP(6) WHERE singleton_id = 1")) {
                    update.executeUpdate();
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
                    == 100000 + 5 * sell.price() - sell.fee(), "sale counts as income");
            var detail = stocks.detail(player, symbol, 7).join();
            check(detail.prices().size() == 2, "daily history");
            check(StockChart.draw(detail.prices()).size() == 11, "visible line chart");
            db.transaction(connection -> {
                try (var stale = connection.prepareStatement(
                        "UPDATE stock_market_state SET updated_at = '2020-01-01 00:00:00' WHERE singleton_id = 1")) {
                    stale.executeUpdate();
                }
                return null;
            }).join();
            check(!stocks.trade(player, symbol, 1, true, UUID.randomUUID()).join().success(), "stale main server closes market");
            db.transaction(connection -> {
                try (var retire = connection.prepareStatement(
                        "UPDATE stock_listing SET status = 'RETIRING', retirement_day = 3 WHERE stock_id = ?")) {
                    retire.setLong(1, Long.parseLong(symbol)); retire.executeUpdate();
                }
                StockSettlement.run(connection, 7);
                return null;
            }).join();
            int incomeBefore = accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().totalIncome();
            String claim = stocks.claim(player, UUID.randomUUID()).join();
            check(claim.startsWith("已领取"), "delisting refund: " + claim);
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
