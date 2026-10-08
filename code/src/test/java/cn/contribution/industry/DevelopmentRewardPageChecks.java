package cn.contribution.industry;

import cn.contribution.account.AccountService;
import cn.contribution.api.AccountTarget;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.reward.RewardConfigGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

/** Exercises the production paginated payout, including offline players and replay. */
public final class DevelopmentRewardPageChecks {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        Path path = Files.createTempDirectory(Path.of("build"), "reward-pages-").resolve("db");
        try (DatabaseService database = new DatabaseService(config, path)) {
            check(database.start().join() == DatabaseState.AVAILABLE, "database ready");
            RewardConfigGuard.initialize(database, config).join();
            AccountService accounts = new AccountService(database, config.serverId);
            var players = new ArrayList<UUID>();
            for (int i = 1; i <= 36; i++) {
                UUID player = new UUID(0, i);
                players.add(player);
                accounts.registerPlayer(player, "Offline" + i).join();
                int points = 250 * i;
                database.transaction(
                                connection -> {
                                    try (var insert =
                                            connection.prepareStatement(
                                                    "INSERT INTO player_industry_stats"
                                                        + " (player_uuid,industry_id,development,updated_at)"
                                                        + " VALUES"
                                                        + " (?,'contribution:construction_landscaping',?,CURRENT_TIMESTAMP(6))")) {
                                        insert.setBytes(1, AccountService.uuidBytes(player));
                                        insert.setLong(2, points);
                                        insert.executeUpdate();
                                    }
                                    return null;
                                })
                        .join();
            }
            DevelopmentRewardService rewards = new DevelopmentRewardService(database, config);
            var first =
                    database.transaction(connection -> rewards.processPage(connection, null))
                            .join();
            check(first.next() != null, "first page limited to 32");
            var last =
                    database.transaction(
                                    connection -> rewards.processPage(connection, first.next()))
                            .join();
            check(last.next() == null, "last page exhausted");
            database.transaction(connection -> rewards.processPage(connection, null)).join();
            for (int i = 0; i < players.size(); i++)
                check(
                        accounts.account(AccountTarget.byUuid(players.get(i)))
                                        .join()
                                        .orElseThrow()
                                        .balance()
                                == i + 1,
                        "offline payout and replay " + i);
            long ledgers =
                    database.transaction(
                                    connection -> {
                                        try (var query =
                                                        connection.prepareStatement(
                                                                "SELECT COUNT(*) FROM"
                                                                    + " contribution_transaction"
                                                                    + " WHERE type='DEVELOP'");
                                                var result = query.executeQuery()) {
                                            result.next();
                                            return result.getLong(1);
                                        }
                                    })
                            .join();
            check(ledgers == 36, "one ledger per offline player");
        }
        System.out.println(
                "REWARD_PAGES_PASS: 32-player bound, offline players, exact grants, replay");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
