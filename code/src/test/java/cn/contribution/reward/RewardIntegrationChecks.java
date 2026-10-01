package cn.contribution.reward;

import cn.contribution.account.AccountService;
import cn.contribution.api.AccountTarget;
import cn.contribution.api.BalanceChangeRequest;
import cn.contribution.api.BalanceChangeType;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.DevelopmentRewardService;
import cn.contribution.shop.ShopService;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

/** Embedded transactional checks for development, daily/event check-ins and shop orders. */
public final class RewardIntegrationChecks {
    public static void main(String[] args) throws Exception {
        checkClockAccounting();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        boolean mysql = args.length > 0 && args[0].equals("mysql");
        String schema = "contribution_reward_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ServerConfig config = new ServerConfig();
        config.database.mode = mysql ? "mysql" : "embedded";
        config.database.jdbcUrl = "jdbc:mysql://127.0.0.1:23306/" + schema + "?serverTimezone=UTC";
        config.database.username = "root";
        Path path = mysql ? Path.of("unused-reward-mysql") : Files.createTempDirectory(Path.of("build"), "reward-").resolve("contribution");
        try (DatabaseService db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration");
            RewardConfigGuard.initialize(db, config).join();
            AccountService accounts = new AccountService(db, "reward-test");
            UUID player = UUID.randomUUID();
            accounts.registerPlayer(player, "RewardTester").join();
            accounts.changeBalance(new BalanceChangeRequest(UUID.randomUUID(), AccountTarget.byUuid(player), 100,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:test"), "initial", "")).join();
            db.transaction(connection -> {
                try (var insert = connection.prepareStatement(
                        "INSERT INTO player_industry_stats (player_uuid, industry_id, development, updated_at) "
                                + "VALUES (?, 'contribution:construction_landscaping', 10000, CURRENT_TIMESTAMP(6))")) {
                    insert.setBytes(1, AccountService.uuidBytes(player)); insert.executeUpdate();
                }
                return null;
            }).join();
            DevelopmentRewardService development = new DevelopmentRewardService(db, config);
            development.settlePlayer(player).join();
            development.settlePlayer(player).join();
            check(balance(accounts, player) == 101, "development credited once");
            check(count(db, player, "DEVELOP") == 1, "development ledger");
            db.transaction(connection -> {
                try (var update = connection.prepareStatement(
                        "UPDATE player_industry_stats SET development = 30000 WHERE player_uuid = ?")) {
                    update.setBytes(1, AccountService.uuidBytes(player)); update.executeUpdate();
                }
                return null;
            }).join();
            development.settlePlayer(player).join();
            check(balance(accounts, player) == 103, "incremental development delta");

            ShopService shop = new ShopService(db, config);
            UUID order = UUID.randomUUID();
            check(shop.buy(player, "bread", 1, order).join().startsWith("购买成功"), "shop purchase");
            check(shop.buy(player, "bread", 1, order).join().contains("已提交"), "shop retry");
            check(shop.buy(player, "torch", 1, order).join().contains("已用于其他请求"), "changed retry rejected");
            check(balance(accounts, player) == 83, "shop charged once");
            check(count(db, player, "SHOP_BUY") == 1, "shop ledger");
            db.transaction(connection -> {
                try (var lease = connection.prepareStatement(
                        "UPDATE reward_delivery SET status = 'CLAIMING', lease_until = TIMESTAMPADD(MINUTE, 2, CURRENT_TIMESTAMP(6)) WHERE player_uuid = ?")) {
                    lease.setBytes(1, AccountService.uuidBytes(player));
                    check(lease.executeUpdate() == 1, "delivery lease syntax");
                }
                try (var reset = connection.prepareStatement(
                        "UPDATE reward_delivery SET status = 'PENDING', lease_until = NULL WHERE player_uuid = ?")) {
                    reset.setBytes(1, AccountService.uuidBytes(player)); reset.executeUpdate();
                }
                return null;
            }).join();
            ServerConfig divergent = new ServerConfig();
            divergent.rewards.shopOffers[0].price++;
            try {
                new ShopService(db, divergent).buy(player, "bread", 1, UUID.randomUUID()).join();
                throw new AssertionError("divergent shop config accepted");
            } catch (java.util.concurrent.CompletionException expected) {
                check(balance(accounts, player) == 83, "config mismatch changed no balance");
            }

            EventCheckinService events = new EventCheckinService(db, config);
            LocalDate today = LocalDate.now(eventsZone(config));
            var event = new EventCheckinService.Event("autumn", "秋日活动", today, today.plusDays(2),
                    15, "minecraft:torch", 2, null, null);
            check(events.create(event).join().startsWith("已创建"), "event creation");
            check(events.claim(player, "autumn").join().startsWith("活动签到成功"), "event claim");
            check(events.claim(player, "autumn").join().contains("已经签到"), "event duplicate");
            check(balance(accounts, player) == 98, "event credited once");

            CheckinService daily = new CheckinService(db, config);
            db.transaction(connection -> { daily.process(connection, new CheckinService.Key(player, today), 600); return null; }).join();
            db.transaction(connection -> { daily.process(connection, new CheckinService.Key(player, today), 60); return null; }).join();
            check(balance(accounts, player) == 108, "daily credited once");
            check(count(db, player, "CHECK_IN") == 1, "daily ledger");
            check(accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().totalIncome() == 128,
                    "shop debit excludes income, all grants included");
            System.out.println("REWARDS_PASS: development, shop, event, daily, replay and income");
        } finally {
            if (mysql) try (var admin = java.sql.DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:23306/mysql?serverTimezone=UTC", "root", "");
                         var remove = admin.createStatement()) {
                remove.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private static java.time.ZoneId eventsZone(ServerConfig config) { return java.time.ZoneId.of(config.rewards.timeZone); }
    private static void checkClockAccounting() {
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var clock = new OnlineTimeAccumulator(zone);
        UUID player = UUID.randomUUID();
        long start = LocalDate.of(2026, 10, 1).atTime(23, 59, 58, 500_000_000)
                .atZone(zone).toInstant().toEpochMilli();
        clock.joined(player, start);
        check(clock.sample(java.util.List.of(player), start + 950).isEmpty(), "fractional second retained");
        var first = clock.sample(java.util.List.of(player), start + 3100);
        check(first.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 1))) == 1,
                "before-midnight seconds");
        check(first.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 2))) == 2,
                "after-midnight seconds");
        check(clock.sample(java.util.List.of(player), start + 3999).isEmpty(), "later fractional second retained");
        var second = clock.left(player, start + 4100);
        check(second.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 2))) == 1,
                "departure accounts final whole second");
    }
    private static int balance(AccountService accounts, UUID player) {
        return accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().balance();
    }
    private static int count(DatabaseService db, UUID player, String type) {
        return db.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM contribution_transaction WHERE player_uuid = ? AND type = ?")) {
                query.setBytes(1, AccountService.uuidBytes(player)); query.setString(2, type);
                try (var row = query.executeQuery()) { row.next(); return row.getInt(1); }
            }
        }).join();
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
