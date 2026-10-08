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
        String schema =
                "contribution_reward_test_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ServerConfig config = new ServerConfig();
        config.database.mode = mysql ? "mysql" : "embedded";
        config.database.jdbcUrl = "jdbc:mysql://127.0.0.1:23306/" + schema + "?serverTimezone=UTC";
        config.database.username = "root";
        Path path =
                mysql
                        ? Path.of("unused-reward-mysql")
                        : Files.createTempDirectory(Path.of("build"), "reward-")
                                .resolve("contribution");
        try (DatabaseService db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration");
            RewardConfigGuard.initialize(db, config).join();
            checkWeaponSkinCatalog(db, config);
            AccountService accounts = new AccountService(db, "reward-test");
            UUID player = UUID.randomUUID();
            accounts.registerPlayer(player, "RewardTester").join();
            accounts.changeBalance(
                            new BalanceChangeRequest(
                                    UUID.randomUUID(),
                                    AccountTarget.byUuid(player),
                                    100,
                                    BalanceChangeType.EXTERNAL,
                                    Identifier.parse("contribution:test"),
                                    "initial",
                                    ""))
                    .join();
            db.transaction(
                            connection -> {
                                try (var insert =
                                        connection.prepareStatement(
                                                "INSERT INTO player_industry_stats (player_uuid,"
                                                    + " industry_id, development, updated_at)"
                                                    + " VALUES (?,"
                                                    + " 'contribution:construction_landscaping',"
                                                    + " 10000, CURRENT_TIMESTAMP(6))")) {
                                    insert.setBytes(1, AccountService.uuidBytes(player));
                                    insert.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            DevelopmentRewardService development = new DevelopmentRewardService(db, config);
            development.settlePlayer(player).join();
            development.settlePlayer(player).join();
            check(balance(accounts, player) == 140, "development credited once at 1/250");
            check(count(db, player, "DEVELOP") == 1, "development ledger");
            db.transaction(
                            connection -> {
                                try (var update =
                                        connection.prepareStatement(
                                                "UPDATE player_industry_stats SET development ="
                                                        + " 30000 WHERE player_uuid = ?")) {
                                    update.setBytes(1, AccountService.uuidBytes(player));
                                    update.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            development.settlePlayer(player).join();
            check(balance(accounts, player) == 220, "incremental development delta at 1/250");

            ShopService shop = new ShopService(db, config);
            UUID order = UUID.randomUUID();
            check(shop.buy(player, "bread", 1, order).join().startsWith("购买成功"), "shop purchase");
            check(shop.buy(player, "bread", 1, order).join().contains("已提交"), "shop retry");
            check(
                    shop.buy(player, "torch", 1, order).join().contains("已用于其他请求"),
                    "changed retry rejected");
            check(balance(accounts, player) == 200, "shop charged once");
            check(count(db, player, "SHOP_BUY") == 1, "shop ledger");
            db.transaction(
                            connection -> {
                                try (var lease =
                                        connection.prepareStatement(
                                                "UPDATE reward_delivery SET status = 'CLAIMING',"
                                                        + " lease_until = TIMESTAMPADD(MINUTE, 2,"
                                                        + " CURRENT_TIMESTAMP(6)) WHERE player_uuid"
                                                        + " = ?")) {
                                    lease.setBytes(1, AccountService.uuidBytes(player));
                                    check(lease.executeUpdate() == 1, "delivery lease syntax");
                                }
                                try (var reset =
                                        connection.prepareStatement(
                                                "UPDATE reward_delivery SET status = 'PENDING',"
                                                        + " lease_until = NULL WHERE player_uuid ="
                                                        + " ?")) {
                                    reset.setBytes(1, AccountService.uuidBytes(player));
                                    reset.executeUpdate();
                                }
                                return null;
                            })
                    .join();
            ServerConfig divergent = new ServerConfig();
            divergent.rewards.dailyCycleRewards[0]++;
            try {
                new ShopService(db, divergent).buy(player, "bread", 1, UUID.randomUUID()).join();
                throw new AssertionError("divergent shop config accepted");
            } catch (java.util.concurrent.CompletionException expected) {
                check(balance(accounts, player) == 200, "config mismatch changed no balance");
            }

            EventCheckinService events = new EventCheckinService(db, config);
            LocalDate today = LocalDate.now(eventsZone(config));
            var event =
                    new EventCheckinService.Event(
                            "autumn",
                            "秋日活动",
                            today,
                            today.plusDays(2),
                            15,
                            "minecraft:torch",
                            2,
                            null,
                            null);
            check(events.create(event).join().startsWith("已创建"), "event creation");
            check(events.claim(player, "autumn").join().startsWith("活动签到成功"), "event claim");
            check(events.claim(player, "autumn").join().contains("已经签到"), "event duplicate");
            check(balance(accounts, player) == 215, "event credited once");

            CheckinService daily = new CheckinService(db, config);
            db.transaction(
                            connection -> {
                                daily.process(
                                        connection, new CheckinService.Key(player, today), 600);
                                return null;
                            })
                    .join();
            db.transaction(
                            connection -> {
                                daily.process(
                                        connection, new CheckinService.Key(player, today), 60);
                                return null;
                            })
                    .join();
            check(balance(accounts, player) == 225, "daily credited once");
            check(count(db, player, "CHECK_IN") == 1, "daily ledger");
            check(
                    accounts.account(AccountTarget.byUuid(player))
                                    .join()
                                    .orElseThrow()
                                    .totalIncome()
                            == 245,
                    "shop debit excludes income, all grants included");
            checkCatalog(db, config, accounts);
            System.out.println("REWARDS_PASS: development, shop, event, daily, replay and income");
        } finally {
            if (mysql)
                try (var admin =
                                java.sql.DriverManager.getConnection(
                                        "jdbc:mysql://127.0.0.1:23306/mysql?serverTimezone=UTC",
                                        "root",
                                        "");
                        var remove = admin.createStatement()) {
                    remove.execute("DROP DATABASE IF EXISTS `" + schema + "`");
                }
        }
    }

    private static java.time.ZoneId eventsZone(ServerConfig config) {
        return java.time.ZoneId.of(config.rewards.timeZone);
    }

    private static void checkWeaponSkinCatalog(DatabaseService db, ServerConfig config) {
        db.transaction(
                        connection -> {
                            var before = cn.contribution.shop.ShopCatalog.list(connection, true);
                            for (var starter : cn.contribution.items.WeaponSkins.starters()) {
                                var offer =
                                        cn.contribution.shop.ShopCatalog.find(
                                                connection, "weapon_skin_" + starter.type(), false);
                                check(
                                        offer != null
                                                && offer.itemId().equals("minecraft:firework_star")
                                                && offer.name().equals(starter.name())
                                                && offer.itemSpec().equals(starter.itemSpec())
                                                && offer.itemCount() == 1
                                                && offer.price() == 100,
                                        "built-in starter " + starter.type());
                            }
                            cn.contribution.shop.ShopCatalog.seedWeaponSkins(connection);
                            check(
                                    before.equals(
                                            cn.contribution.shop.ShopCatalog.list(
                                                    connection, true)),
                                    "starter seeding idempotent");
                            var sword =
                                    cn.contribution.shop.ShopCatalog.find(
                                            connection, "weapon_skin_sword", false);
                            cn.contribution.shop.ShopCatalog.modify(
                                    connection,
                                    sword.id(),
                                    new cn.contribution.shop.ShopCatalog.Change(
                                            null, null, null, 123, "管理员描述", false, null),
                                    null);
                            var edited =
                                    cn.contribution.shop.ShopCatalog.find(
                                            connection, "weapon_skin_sword", false);
                            cn.contribution.shop.ShopCatalog.seedWeaponSkins(connection);
                            check(
                                    edited.equals(
                                            cn.contribution.shop.ShopCatalog.find(
                                                    connection, "weapon_skin_sword", false)),
                                    "restart preserves price, description and delisting");
                            return null;
                        })
                .join();
    }

    private static void checkCatalog(
            DatabaseService db, ServerConfig config, AccountService accounts) {
        var shop = new ShopService(db, config);
        UUID player = UUID.randomUUID();
        accounts.registerPlayer(player, "CatalogTester").join();
        accounts.changeBalance(
                        new BalanceChangeRequest(
                                UUID.randomUUID(),
                                AccountTarget.byUuid(player),
                                1000,
                                BalanceChangeType.EXTERNAL,
                                Identifier.parse("contribution:test"),
                                "catalog-test",
                                ""))
                .join();
        var created =
                shop.create(
                                new cn.contribution.shop.ShopCatalog.Change(
                                        "测试商品", "minecraft:diamond", 2, 30, "双列目录描述", true, -7))
                        .join();
        check(created.success() && created.id() > 3, "automatic stable numeric product ID");
        var first = shop.offer(created.id()).join();
        check(
                first.description().equals("双列目录描述") && first.sortOrder() == -7,
                "metadata persisted");
        check(
                shop.offers(false).join().getFirst().id() == created.id(),
                "configured product order");
        ServerConfig other = new ServerConfig();
        other.rewards.shopOffers[0].price = 999;
        RewardConfigGuard.initialize(db, other).join();
        check(
                new ShopService(db, other).offer(first.id()).join().equals(first),
                "restart never overwrites catalog");
        var patch =
                new cn.contribution.shop.ShopCatalog.Change(
                        "新名称", "minecraft:apple", 3, 40, "新描述", true, 2);
        check(
                shop.modify(first.id(), patch, first.revision()).join().success(),
                "edit all product fields");
        check(
                !shop.modify(first.id(), patch, first.revision()).join().success(),
                "stale editor rejected");
        check(
                shop.buy(player, "" + first.id(), 1, UUID.randomUUID(), first.revision())
                        .join()
                        .contains("信息已更新"),
                "stale GUI price rejected");
        check(balance(accounts, player) == 1000, "stale price does not debit account");
        var current = shop.offer(first.id()).join();
        UUID order = UUID.randomUUID();
        check(
                shop.buy(player, "" + first.id(), 2, order, current.revision())
                        .join()
                        .startsWith("购买成功"),
                "numeric ID buy");
        check(balance(accounts, player) == 920, "updated authoritative price");
        check(
                shop.modify(
                                first.id(),
                                new cn.contribution.shop.ShopCatalog.Change(
                                        null, null, null, null, null, false, null),
                                null)
                        .join()
                        .success(),
                "take off preserves product");
        check(
                shop.buy(player, "" + first.id(), 2, order).join().contains("已提交"),
                "retry after delisting remains idempotent");
        check(
                shop.buy(player, "" + first.id(), 1, UUID.randomUUID()).join().contains("已经下架"),
                "delisted buy rejected");
        check(
                shop.offers(false).join().stream().noneMatch(value -> value.id() == first.id()),
                "public catalog hides delisted item");
        check(
                shop.offers(true).join().stream().anyMatch(value -> value.id() == first.id()),
                "admin catalog retains delisted item");
        check(
                !shop.create(
                                new cn.contribution.shop.ShopCatalog.Change(
                                        "坏物品", "minecraft:not_an_item", 1, 1, "", true, 0))
                        .join()
                        .success(),
                "unknown registry item rejected");
        check(
                !shop.modify(
                                first.id(),
                                new cn.contribution.shop.ShopCatalog.Change(
                                        null, null, 65, null, null, null, null),
                                null)
                        .join()
                        .success(),
                "quantity bounds enforced");
        check(
                count(db, player, "SHOP_BUY") == 1,
                "replay and invalid orders create no extra ledger");
        check(
                accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().totalIncome()
                        == 1000,
                "shop debit never reduces historical income");
        System.out.println(
                "CATALOG_PASS: seeding, IDs, metadata, revision conflicts, shared pricing,"
                        + " delisting and replay");
    }

    private static void checkClockAccounting() {
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var clock = new OnlineTimeAccumulator(zone);
        UUID player = UUID.randomUUID();
        long start =
                LocalDate.of(2026, 10, 1)
                        .atTime(23, 59, 58, 500_000_000)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli();
        clock.joined(player, start);
        check(
                clock.sample(java.util.List.of(player), start + 950).isEmpty(),
                "fractional second retained");
        var first = clock.sample(java.util.List.of(player), start + 3100);
        check(
                first.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 1))) == 1,
                "before-midnight seconds");
        check(
                first.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 2))) == 2,
                "after-midnight seconds");
        check(
                clock.sample(java.util.List.of(player), start + 3999).isEmpty(),
                "later fractional second retained");
        var second = clock.left(player, start + 4100);
        check(
                second.get(new OnlineTimeAccumulator.Key(player, LocalDate.of(2026, 10, 2))) == 1,
                "departure accounts final whole second");
        var frequent = new OnlineTimeAccumulator(zone);
        var batched = new OnlineTimeAccumulator(zone);
        frequent.joined(player, start);
        batched.joined(player, start);
        var expected = new java.util.HashMap<OnlineTimeAccumulator.Key, Integer>();
        for (int i = 1; i <= 120; i++)
            frequent.sample(java.util.List.of(player), start + i * 1000L)
                    .forEach((key, seconds) -> expected.merge(key, seconds, Integer::sum));
        check(
                batched.sample(java.util.List.of(player), start + 120_000).equals(expected),
                "minute sampling preserves cross-midnight accounting");
        batched.sample(java.util.List.of(player), start + 119_000);
        check(
                batched.sample(java.util.List.of(player), start + 120_000).values().stream()
                                .mapToInt(Integer::intValue)
                                .sum()
                        == 1,
                "clock rollback resets cursor safely");
    }

    private static int balance(AccountService accounts, UUID player) {
        return accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().balance();
    }

    private static int count(DatabaseService db, UUID player, String type) {
        return db.transaction(
                        connection -> {
                            try (var query =
                                    connection.prepareStatement(
                                            "SELECT COUNT(*) FROM contribution_transaction WHERE"
                                                    + " player_uuid = ? AND type = ?")) {
                                query.setBytes(1, AccountService.uuidBytes(player));
                                query.setString(2, type);
                                try (var row = query.executeQuery()) {
                                    row.next();
                                    return row.getInt(1);
                                }
                            }
                        })
                .join();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
