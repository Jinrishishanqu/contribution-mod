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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Isolated embedded or loopback-MySQL stock integration; never touches a player's world. */
public final class StockIntegrationChecks {
    public static void main(String[] args) throws Exception {
        boolean mysql = args.length > 0 && args[0].equals("mysql");
        String temporarySchema =
                "contribution_stock_test_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ServerConfig config = new ServerConfig();
        config.database.mode = mysql ? "mysql" : "embedded";
        config.database.jdbcUrl =
                "jdbc:mysql://127.0.0.1:23306/" + temporarySchema + "?serverTimezone=UTC";
        config.database.username = "root";
        Path path =
                mysql
                        ? Path.of("unused-stock-mysql")
                        : Files.createTempDirectory(Path.of("build"), "embedded-stock-")
                                .resolve("contribution");
        try (DatabaseService db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration");
            StockCoverageChecks.verify(db);
            StockService stocks = new StockService(db, config);
            AccountService accounts = new AccountService(db, "stock-test");
            UUID player = UUID.randomUUID();
            accounts.registerPlayer(player, "StockTester").join();
            accounts.changeBalance(
                            new BalanceChangeRequest(
                                    UUID.randomUUID(),
                                    AccountTarget.byUuid(player),
                                    100000,
                                    BalanceChangeType.EXTERNAL,
                                    Identifier.parse("contribution:test"),
                                    "测试资金",
                                    ""))
                    .join();
            db.transaction(
                            connection -> {
                                StockSettlement.initialize(connection, 2);
                                seedIndustryDay(connection, 1);
                                seedIndustryDay(connection, 2);
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE industry_daily SET prosperity = 100 WHERE"
                                                        + " game_day = 2")) {
                                    update.executeUpdate();
                                }
                                check(
                                        StockSettlement.loadIndustries(connection, 1)
                                                .values()
                                                .stream()
                                                .allMatch(
                                                        value -> value.prosperity().signum() == 0),
                                        "historical stocks do not read latest industry state");
                                try {
                                    StockSettlement.loadIndustries(connection, 0);
                                    throw new AssertionError("missing history accepted");
                                } catch (java.sql.SQLException expected) {
                                }
                                StockSettlement.run(connection, 2);
                                return null;
                            })
                    .join();
            var market = stocks.market(player).join();
            check(market.listings().size() == 20, "initial 20 listings");
            check(
                    market.listings().stream().map(StockView.Listing::industry).distinct().count()
                            == 9,
                    "all industries");
            check(
                    market.listings().stream()
                            .allMatch(
                                    stock ->
                                            stock.initialPrice() >= 100
                                                    && stock.initialPrice() <= 400),
                    "random initial prices stay within 100-400");
            check(
                    StockSettlement.retirementThreshold(100, 1000) == 250,
                    "historical high raises retirement threshold");
            check(
                    StockSettlement.retirementThreshold(100, 120) == 50,
                    "initial price sets minimum retirement threshold");
            check(
                    StockPricing.retirementThreshold(101, 151) == 50,
                    "odd prices use the same integer threshold in every UI");
            check(StockPricing.priceCap(400) == 4000, "price cap is ten times listing price");
            List<int[]> steepLine = new ArrayList<>();
            StockLineRaster.trace(0, 0, 3, 40, (x, y) -> steepLine.add(new int[] {x, y}));
            check(
                    steepLine.size() >= 41
                            && steepLine.getFirst()[1] == 0
                            && steepLine.getLast()[1] == 40,
                    "steep price curve reaches every vertical pixel");
            for (int i = 1; i < steepLine.size(); i++) {
                int[] last = steepLine.get(i - 1), next = steepLine.get(i);
                check(
                        Math.abs(next[0] - last[0]) <= 1 && Math.abs(next[1] - last[1]) <= 1,
                        "stock curve has no disconnected raster steps");
            }
            List<int[]> thinLine = new ArrayList<>();
            StockLineRaster.traceConnected(0, 0, 3, 40, (x, y) -> thinLine.add(new int[] {x, y}));
            check(
                    thinLine.getFirst()[0] == 0
                            && thinLine.getFirst()[1] == 0
                            && thinLine.getLast()[0] == 3
                            && thinLine.getLast()[1] == 40,
                    "thin curve keeps both endpoints");
            for (int i = 1; i < thinLine.size(); i++) {
                int[] last = thinLine.get(i - 1), next = thinLine.get(i);
                check(
                        Math.abs(next[0] - last[0]) + Math.abs(next[1] - last[1]) == 1,
                        "thin curve connects through one-pixel orthogonal steps");
            }
            check(
                    StockChart.lastMovement(
                                    List.of(
                                            new StockView.PricePoint(1, 100),
                                            new StockView.PricePoint(2, 105),
                                            new StockView.PricePoint(3, 105)))
                            == 1,
                    "flat day retains last upward color");
            check(
                    StockChart.lastMovement(
                                    List.of(
                                            new StockView.PricePoint(1, 105),
                                            new StockView.PricePoint(2, 100),
                                            new StockView.PricePoint(3, 100)))
                            == -1,
                    "flat day retains last downward color");
            check(
                    StockChart.lastMovement(
                                    List.of(
                                            new StockView.PricePoint(1, 100),
                                            new StockView.PricePoint(2, 100)))
                            == 0,
                    "never-changing price remains neutral");
            check(
                    StockLineRaster.dayX(1, 1, 30, 10, 60) == 10
                            && StockLineRaster.dayX(15, 1, 30, 10, 60) > 10
                            && StockLineRaster.dayX(30, 1, 30, 10, 60) == 69,
                    "market price chart spans a fixed thirty-game-day axis");
            StockChart.ExtremaSampler sampler = new StockChart.ExtremaSampler(1000, 512);
            for (int day = 1; day <= 1000; day++)
                sampler.accept(
                        new StockView.PricePoint(day, day == 517 ? 1 : day == 732 ? 900 : 100));
            var sampled = sampler.finish();
            check(
                    sampled.size() <= 512
                            && sampled.getFirst().day() == 1
                            && sampled.getLast().day() == 1000
                            && sampled.stream()
                                    .anyMatch(point -> point.day() == 517 && point.price() == 1)
                            && sampled.stream()
                                    .anyMatch(point -> point.day() == 732 && point.price() == 900),
                    "all-history chart is bounded and preserves endpoints and extrema");
            UUID batchId = UUID.randomUUID();
            check(
                    stocks.openBatch(player, batchId, "1,2", 3, true).join(),
                    "reserve batch identity");
            check(stocks.openBatch(player, batchId, "1,2", 3, true).join(), "retry same batch");
            check(
                    !stocks.openBatch(player, batchId, "1,3", 3, true).join(),
                    "reject changed batch list");
            String symbol = Long.toString(market.listings().getFirst().id());
            db.transaction(
                            connection -> {
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_market_state SET game_day = 2,"
                                                        + " day_time = 5000, observed_day = 2,"
                                                        + " observed_time = 5000, observed_at ="
                                                        + " CURRENT_TIMESTAMP(6), updated_at ="
                                                        + " CURRENT_TIMESTAMP(6) WHERE singleton_id"
                                                        + " = 1")) {
                                    update.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            check(
                    stocks.market(player).join().clockDay() == 2,
                    "market displays observed overworld day");
            db.transaction(
                            connection -> {
                                StockMarketClock.publish(
                                        connection, config.serverId, 3 * 24_000L + 5000);
                                return null;
                            })
                    .join();
            check(
                    stocks.market(player).join().clockDay() == 3,
                    "independent heartbeat updates visible clock while settlement is behind");
            boolean foreignClockRejected = false;
            try {
                db.transaction(
                                connection -> {
                                    StockMarketClock.publish(
                                            connection, "foreign-main-server", 4 * 24_000L);
                                    return null;
                                })
                        .join();
            } catch (java.util.concurrent.CompletionException expected) {
                foreignClockRejected = true;
            }
            check(foreignClockRejected, "another main server cannot overwrite clock ownership");
            check(
                    stocks.market(player).join().clockDay() == 3,
                    "rejected heartbeat keeps authoritative clock");
            check(
                    stocks.trade(player, symbol, 1, true, UUID.randomUUID())
                            .join()
                            .message()
                            .contains("核算尚未完成"),
                    "unsettled market day is distinguished from a missing clock");
            db.transaction(
                            connection -> {
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_market_state SET observed_day = 2"
                                                        + " WHERE singleton_id = 1")) {
                                    update.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            UUID buyId = UUID.randomUUID();
            var buy = stocks.trade(player, symbol, 10, true, buyId).join();
            check(buy.success(), "buy: " + buy.message());
            check(stocks.trade(player, symbol, 10, true, buyId).join().replay(), "idempotent buy");
            check(
                    !stocks.trade(player, symbol, 10, true, UUID.randomUUID()).join().success(),
                    "one buy per day");
            check(
                    !stocks.trade(player, symbol, 1, false, UUID.randomUUID()).join().success(),
                    "no same-day resale");
            check(
                    !stocks.trade(player, symbol, 10000, false, UUID.randomUUID()).join().success(),
                    "holding bound");
            db.transaction(
                            connection -> {
                                try (var crash =
                                        connection.prepareStatement(
                                                "UPDATE stock_listing SET price = initial_price /"
                                                        + " 4, high_price = initial_price * 4,"
                                                        + " wave_base = 1 WHERE stock_id = ?")) {
                                    crash.setLong(1, Long.parseLong(symbol));
                                    crash.executeUpdate();
                                }
                                StockSettlement.run(connection, 3);
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_market_state SET game_day = 3,"
                                                        + " day_time = 5000, observed_day = 3,"
                                                        + " observed_time = 5000, observed_at ="
                                                        + " CURRENT_TIMESTAMP(6), updated_at ="
                                                        + " CURRENT_TIMESTAMP(6) WHERE singleton_id"
                                                        + " = 1")) {
                                    update.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            db.transaction(
                            connection -> {
                                try (var query =
                                        connection.prepareStatement(
                                                "SELECT COUNT(*) FROM stock_notice WHERE"
                                                    + " player_uuid = ? AND stock_id = ? AND kind ="
                                                    + " 'RISK'")) {
                                    query.setBytes(1, AccountService.uuidBytes(player));
                                    query.setLong(2, Long.parseLong(symbol));
                                    try (var rows = query.executeQuery()) {
                                        rows.next();
                                        check(rows.getInt(1) == 1, "risk notice queued");
                                    }
                                }
                                return null;
                            })
                    .join();
            check(
                    !stocks.trade(player, symbol, 5000, true, UUID.randomUUID()).join().success(),
                    "insufficient balance is rejected before writing a trade");
            UUID sellId = UUID.randomUUID();
            var sell = stocks.trade(player, symbol, 5, false, sellId).join();
            check(sell.success(), "sell: " + sell.message());
            check(
                    stocks.trade(player, symbol, 5, false, sellId).join().replay(),
                    "idempotent sell");
            check(
                    accounts.account(AccountTarget.byUuid(player))
                                    .join()
                                    .orElseThrow()
                                    .totalIncome()
                            == 100000,
                    "sale does not count as historical income");
            var detail = stocks.detail(player, symbol, 7).join();
            check(detail.prices().size() == 2, "daily history");
            check(
                    detail.position().quantity() == 5 && detail.position().costBasis() > 0,
                    "remaining position cost basis");
            var dashboard = stocks.dashboard(player).join();
            check(
                    dashboard.portfolio().positions().containsKey(Long.parseLong(symbol)),
                    "portfolio position");
            db.transaction(
                            connection -> {
                                try (var query =
                                        connection.prepareStatement(
                                                "SELECT type, income_delta FROM"
                                                    + " contribution_transaction WHERE player_uuid"
                                                    + " = ? AND source = 'contribution:stock' ORDER"
                                                    + " BY record_no")) {
                                    query.setBytes(1, AccountService.uuidBytes(player));
                                    try (var rows = query.executeQuery()) {
                                        check(
                                                rows.next()
                                                        && "SPEND".equals(rows.getString(1))
                                                        && rows.getInt(2) == 0,
                                                "buy ledger type");
                                        check(
                                                rows.next()
                                                        && "STOCK".equals(rows.getString(1))
                                                        && rows.getInt(2) == 0,
                                                "sell ledger type");
                                    }
                                }
                                return null;
                            })
                    .join();
            check(StockChart.draw(detail.prices()).size() == 11, "visible line chart");
            db.transaction(
                            connection -> {
                                try (var stale =
                                        connection.prepareStatement(
                                                "UPDATE stock_market_state SET observed_at ="
                                                    + " '2020-01-01 00:00:00' WHERE singleton_id ="
                                                    + " 1")) {
                                    stale.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            check(
                    !stocks.trade(player, symbol, 1, true, UUID.randomUUID()).join().success(),
                    "stale main server closes market");
            var accountBeforeRetirement =
                    accounts.account(AccountTarget.byUuid(player)).join().orElseThrow();
            db.transaction(
                            connection -> {
                                try (var retire =
                                        connection.prepareStatement(
                                                "UPDATE stock_listing SET status = 'RETIRING',"
                                                    + " retirement_day = 3 WHERE stock_id = ?")) {
                                    retire.setLong(1, Long.parseLong(symbol));
                                    retire.executeUpdate();
                                }
                                StockSettlement.closeRetirements(connection, 3);
                                return null;
                            })
                    .join();
            long expectedRefund = (long) sell.price() * 5;
            long actualRefund =
                    db.transaction(
                                    connection -> {
                                        try (var query =
                                                connection.prepareStatement(
                                                        "SELECT amount FROM stock_refund WHERE"
                                                                + " player_uuid = ? AND stock_id ="
                                                                + " ?")) {
                                            query.setBytes(1, AccountService.uuidBytes(player));
                                            query.setLong(2, Long.parseLong(symbol));
                                            try (var row = query.executeQuery()) {
                                                row.next();
                                                return row.getLong(1);
                                            }
                                        }
                                    })
                            .join();
            check(actualRefund == expectedRefund, "full retirement refund");
            db.transaction(
                            connection -> {
                                try (var query =
                                        connection.prepareStatement(
                                                "SELECT COUNT(*) FROM stock_notice WHERE"
                                                    + " player_uuid = ? AND stock_id = ? AND kind ="
                                                    + " 'REFUND'")) {
                                    query.setBytes(1, AccountService.uuidBytes(player));
                                    query.setLong(2, Long.parseLong(symbol));
                                    try (var rows = query.executeQuery()) {
                                        rows.next();
                                        check(rows.getInt(1) == 1, "refund notice queued");
                                    }
                                }
                                return null;
                            })
                    .join();
            var accountAfterRetirement =
                    accounts.account(AccountTarget.byUuid(player)).join().orElseThrow();
            check(
                    accountAfterRetirement.balance()
                            == accountBeforeRetirement.balance() + expectedRefund,
                    "retirement automatically credited at close");
            long expectedProfit =
                    5L * sell.price()
                            - sell.fee()
                            + expectedRefund
                            - (10L * buy.price() + buy.fee());
            check(
                    stocks.dashboard(player).join().portfolio().realizedProfit() == expectedProfit,
                    "portfolio realized profit includes sale fees and retirement refund");
            int incomeBefore = accountBeforeRetirement.totalIncome();
            String claim = stocks.claim(player, UUID.randomUUID()).join();
            check(claim.contains("暂无待领取"), "refund was already credited: " + claim);
            check(
                    accounts.account(AccountTarget.byUuid(player))
                                    .join()
                                    .orElseThrow()
                                    .totalIncome()
                            == incomeBefore,
                    "refund does not count as income");
            long flatStock = market.listings().getLast().id();
            db.transaction(
                            connection -> {
                                try (var remove =
                                        connection.prepareStatement(
                                                "DELETE FROM stock_daily_price WHERE stock_id ="
                                                        + " ?")) {
                                    remove.setLong(1, flatStock);
                                    remove.executeUpdate();
                                }
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_listing SET price = 100, high_price ="
                                                    + " 120, low_price = 90 WHERE stock_id = ?")) {
                                    update.setLong(1, flatStock);
                                    update.executeUpdate();
                                }
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_market_state SET game_day = 40,"
                                                        + " day_time = 5000, observed_day = 40,"
                                                        + " observed_time = 5000, observed_at ="
                                                        + " CURRENT_TIMESTAMP(6), updated_at ="
                                                        + " CURRENT_TIMESTAMP(6) WHERE singleton_id"
                                                        + " = 1")) {
                                    update.executeUpdate();
                                }
                                try (var insert =
                                        connection.prepareStatement(
                                                "INSERT INTO stock_daily_price(stock_id, game_day,"
                                                        + " price, status) VALUES (?, ?, ?,"
                                                        + " 'ACTIVE')")) {
                                    for (int day = 1; day <= 40; day++) {
                                        insert.setLong(1, flatStock);
                                        insert.setInt(2, day);
                                        insert.setInt(3, day == 1 ? 90 : 100);
                                        insert.addBatch();
                                    }
                                    insert.executeBatch();
                                }
                                return null;
                            })
                    .join();
            var flatDashboard = stocks.dashboard(player).join();
            check(
                    flatDashboard.curves().get(flatStock).size() == 30
                            && StockChart.lastMovement(flatDashboard.curves().get(flatStock)) == 0
                            && flatDashboard.lastDirections().get(flatStock) == 1,
                    "a thirty-day flat chart retains the older upward direction");
            check(
                    stocks.detail(player, Long.toString(flatStock), 7).join().lastDirection() == 1,
                    "a seven-day detail retains the older upward direction");
            var allDetail = stocks.detail(player, Long.toString(flatStock), -1).join();
            check(
                    allDetail.requestedDays() == -1
                            && allDetail.prices().size() == 40
                            && allDetail.prices().getFirst().day() == 1
                            && allDetail.prices().getLast().day() == 40,
                    "all-history detail includes the full listing lifetime");
            check(
                    StockPricing.swanPrice(100, 100, true) == 140
                            && StockPricing.swanPrice(100, 100, false) == 75
                            && StockPricing.swanPrice(1000, 100, true) == 1000,
                    "swan correction is +40/-25 percent and obeys the listing cap");
            db.transaction(
                            connection -> {
                                var online =
                                        List.of(
                                                new StockSwanService.Player(player, "StockTester"),
                                                new StockSwanService.Player(
                                                        UUID.randomUUID(), "SecondTester"),
                                                new StockSwanService.Player(
                                                        UUID.randomUUID(), "bot_Excluded"));
                                var random = new java.util.Random(23);
                                long start = 41L * 24_000 + 2_000;
                                StockSwanService.advance(
                                        connection, 40L * 24_000 + 2_000, online, random);
                                StockSwanService.advance(
                                        connection,
                                        start,
                                        List.of(online.get(0), online.get(2)),
                                        random);
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT COUNT(*) FROM stock_swan_event");
                                        var rows = query.executeQuery()) {
                                    rows.next();
                                    check(
                                            rows.getInt(1) == 0,
                                            "one human plus a bot cannot start an event");
                                }
                                StockSwanService.advance(connection, start, online, random);
                                String chosen;
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT c.industry_id FROM"
                                                            + " stock_swan_candidate c WHERE EXISTS"
                                                            + " (SELECT 1 FROM stock_listing s"
                                                            + " WHERE s.industry_id = c.industry_id"
                                                            + " AND s.status <> 'DELISTED') ORDER"
                                                            + " BY c.industry_id LIMIT 1");
                                        var rows = query.executeQuery()) {
                                    rows.next();
                                    chosen = rows.getString(1);
                                }
                                verifyCombinedWinner(connection, player, chosen, start);
                                StockSwanService.advance(connection, start + 4_099, online, random);
                                check(
                                        StockSwanService.pendingEffects(connection, 42).isEmpty(),
                                        "swan does not decide before the window closes");
                                StockSwanService.advance(connection, start + 4_100, online, random);
                                var good = StockSwanService.pendingEffects(connection, 42);
                                check(
                                        good.size() == 1
                                                && good.getFirst().good()
                                                && ("contribution:"
                                                                + good.getFirst().industry().path())
                                                        .equals(chosen),
                                        "combined player gain wins over a larger single-player"
                                                + " gain");
                                check(
                                        StockSwanService.news(connection, start + 4_100).size()
                                                == 1,
                                        "good swan news starts after decision");
                                java.util.Map<Long, Integer> before = new java.util.HashMap<>();
                                java.util.Map<Long, Integer> initials = new java.util.HashMap<>();
                                try (var query =
                                        connection.prepareStatement(
                                                "SELECT stock_id, price, initial_price FROM"
                                                        + " stock_listing WHERE industry_id = ? AND"
                                                        + " status <> 'DELISTED'")) {
                                    query.setString(1, chosen);
                                    try (var rows = query.executeQuery()) {
                                        while (rows.next()) {
                                            before.put(rows.getLong(1), rows.getInt(2));
                                            initials.put(rows.getLong(1), rows.getInt(3));
                                        }
                                    }
                                }
                                check(!before.isEmpty(), "chosen industry has a listed stock");
                                seedIndustryDay(connection, 41);
                                StockSettlement.run(connection, 42);
                                for (var stock : before.entrySet()) {
                                    try (var query =
                                            connection.prepareStatement(
                                                    "SELECT price FROM stock_listing WHERE stock_id"
                                                            + " = ?")) {
                                        query.setLong(1, stock.getKey());
                                        try (var rows = query.executeQuery()) {
                                            rows.next();
                                            check(
                                                    rows.getInt(1)
                                                            == StockPricing.swanPrice(
                                                                    stock.getValue(),
                                                                    initials.get(stock.getKey()),
                                                                    true),
                                                    "good swan replaces the ordinary settlement"
                                                            + " price");
                                        }
                                    }
                                }
                                check(
                                        StockSwanService.pendingEffects(connection, 42).isEmpty(),
                                        "settlement consumes event once");
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_swan_schedule SET next_start_day = 43"
                                                        + " WHERE singleton_id = 1")) {
                                    update.executeUpdate();
                                }
                                long badStart = 43L * 24_000 + 2_000;
                                // Ensure every eligible industry has multiple listings, to detect
                                // accidental industry-wide effects.
                                try (var clone =
                                        connection.prepareStatement(
                                                "INSERT INTO stock_listing (item_id, item_name,"
                                                    + " industry_id, listed_day, initial_price,"
                                                    + " price, high_price, low_price, wave_base,"
                                                    + " ou_noise, status, last_price_day) SELECT"
                                                    + " item_id, item_name, industry_id,"
                                                    + " listed_day, initial_price, price,"
                                                    + " high_price, low_price, 0, 0, status,"
                                                    + " last_price_day FROM stock_listing WHERE"
                                                    + " status <> 'DELISTED'")) {
                                    clone.executeUpdate();
                                }
                                StockSwanService.advance(connection, badStart, online, random);
                                StockSwanService.advance(
                                        connection, badStart + 4_100, online, random);
                                var bad = StockSwanService.pendingEffects(connection, 44);
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT next_start_day FROM"
                                                                + " stock_swan_schedule WHERE"
                                                                + " singleton_id = 1");
                                        var rows = query.executeQuery()) {
                                    rows.next();
                                    check(
                                            rows.getLong(1) >= 49 && rows.getLong(1) <= 103,
                                            "new swan schedule waits six to sixty days");
                                }
                                check(
                                        bad.size() >= 2
                                                && bad.size() <= 4
                                                && bad.stream()
                                                        .noneMatch(StockSwanService.Effect::good)
                                                && bad.stream()
                                                                .map(
                                                                        StockSwanService.Effect
                                                                                ::industry)
                                                                .distinct()
                                                                .count()
                                                        == 2,
                                        "no contribution affects exactly two industries and two to"
                                                + " four stocks");
                                check(
                                        bad.stream()
                                                        .allMatch(
                                                                effect ->
                                                                        effect.targetStockId()
                                                                                != null)
                                                && bad.stream()
                                                                .map(
                                                                        StockSwanService.Effect
                                                                                ::targetStockId)
                                                                .distinct()
                                                                .count()
                                                        == bad.size(),
                                        "all bad targets are distinct and persisted");
                                check(
                                        bad.equals(StockSwanService.pendingEffects(connection, 44)),
                                        "retry never rerolls bad targets");
                                java.util.Map<Long, Integer> badPrices = new java.util.HashMap<>();
                                java.util.Map<Long, Integer> badInitials =
                                        new java.util.HashMap<>();
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE stock_listing SET wave_base = 0, ou_noise ="
                                                        + " 0")) {
                                    update.executeUpdate();
                                }
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT stock_id, price, initial_price FROM"
                                                                + " stock_listing WHERE status <>"
                                                                + " 'DELISTED'");
                                        var rows = query.executeQuery()) {
                                    while (rows.next()) {
                                        badPrices.put(rows.getLong(1), rows.getInt(2));
                                        badInitials.put(rows.getLong(1), rows.getInt(3));
                                    }
                                }
                                seedIndustryDay(connection, 43);
                                StockSettlement.run(
                                        connection,
                                        44,
                                        new java.util.Random(77) {
                                            @Override
                                            public double nextGaussian() {
                                                return 0;
                                            }
                                        });
                                var targets =
                                        bad.stream()
                                                .map(StockSwanService.Effect::targetStockId)
                                                .collect(java.util.stream.Collectors.toSet());
                                for (var stock : badPrices.entrySet()) {
                                    try (var query =
                                            connection.prepareStatement(
                                                    "SELECT price FROM stock_listing WHERE stock_id"
                                                            + " = ?")) {
                                        query.setLong(1, stock.getKey());
                                        try (var rows = query.executeQuery()) {
                                            rows.next();
                                            int expected =
                                                    targets.contains(stock.getKey())
                                                            ? StockPricing.swanPrice(
                                                                    stock.getValue(),
                                                                    badInitials.get(stock.getKey()),
                                                                    false)
                                                            : stock.getValue();
                                            check(
                                                    rows.getInt(1) == expected,
                                                    "only selected bad stocks drop twenty-five"
                                                            + " percent");
                                        }
                                    }
                                }
                                check(
                                        StockSwanService.pendingEffects(connection, 44).isEmpty(),
                                        "bad effects consumed once");
                                check(
                                        StockSwanService.news(connection, badStart + 4_100).size()
                                                == 3,
                                        "news includes the good industry and two bad industries");
                                return null;
                            })
                    .join();
            System.out.println(
                    (mysql ? "MYSQL" : "EMBEDDED")
                            + "_STOCK_PASS: listing, coverage, buy/sell, fees, no same-day resale,"
                            + " idempotency, chart, stale clock, refund, swans");
        } finally {
            if (mysql) {
                // Only the random schema created by this invocation is removed; no shared test or
                // player data.
                try (var admin =
                                java.sql.DriverManager.getConnection(
                                        "jdbc:mysql://127.0.0.1:23306/mysql?serverTimezone=UTC",
                                        "root",
                                        "");
                        var remove = admin.createStatement()) {
                    remove.execute("DROP DATABASE IF EXISTS `" + temporarySchema + "`");
                }
            }
        }
    }

    private static void verifyCombinedWinner(
            java.sql.Connection connection, UUID player, String winner, long start)
            throws java.sql.SQLException {
        UUID other;
        try (var query =
                        connection.prepareStatement(
                                "SELECT player_uuid, second_player_uuid, player_name,"
                                        + " second_player_name, deadline_clock, rule_version FROM"
                                        + " stock_swan_event");
                var rows = query.executeQuery()) {
            check(rows.next(), "event starts with two humans");
            UUID first = AccountService.bytesUuid(rows.getBytes(1));
            UUID second = AccountService.bytesUuid(rows.getBytes(2));
            other = first.equals(player) ? second : first;
            check(
                    !first.equals(second)
                            && !rows.getString(3).startsWith("bot_")
                            && !rows.getString(4).startsWith("bot_"),
                    "distinct humans selected");
            check(
                    rows.getLong(5) == start + 4000 && rows.getInt(6) == 2,
                    "four-hour window and new version persisted");
        }
        String rival;
        try (var query =
                        connection.prepareStatement(
                                "SELECT industry_id FROM stock_swan_candidate ORDER BY"
                                        + " industry_id");
                var rows = query.executeQuery()) {
            int count = 0;
            rival = null;
            while (rows.next()) {
                count++;
                if (!rows.getString(1).equals(winner)) rival = rows.getString(1);
            }
            check(count == 4 && rival != null, "four distinct candidate industries");
        }
        addConstruction(connection, player, winner, 3);
        addConstruction(connection, other, winner, 3);
        addConstruction(connection, player, rival, 5);
    }

    private static void addConstruction(
            java.sql.Connection connection, UUID player, String industry, long amount)
            throws java.sql.SQLException {
        try (var insert =
                connection.prepareStatement(
                        "INSERT INTO player_industry_stats (player_uuid, industry_id, development,"
                                + " updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))")) {
            insert.setBytes(1, AccountService.uuidBytes(player));
            insert.setString(2, industry);
            insert.setLong(3, amount);
            insert.executeUpdate();
        }
    }

    private static void seedIndustryDay(java.sql.Connection connection, long day)
            throws java.sql.SQLException {
        try (var insert =
                connection.prepareStatement(
                        "INSERT IGNORE INTO industry_daily (game_day, industry_id,"
                            + " daily_development, total_development, prosperity, config_version,"
                            + " config_hash, settled_at) VALUES (?, ?, 0, 0, 0, 1, ?,"
                            + " CURRENT_TIMESTAMP(6))")) {
            for (var industry : cn.contribution.industry.BuiltInIndustry.values()) {
                insert.setLong(1, day);
                insert.setString(2, "contribution:" + industry.path());
                insert.setBytes(3, new cn.contribution.industry.RuleSnapshot().hash());
                insert.executeUpdate();
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
