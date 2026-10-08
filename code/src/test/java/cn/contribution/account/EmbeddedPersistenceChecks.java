package cn.contribution.account;

import cn.contribution.api.AccountTarget;
import cn.contribution.api.BalanceChangeRequest;
import cn.contribution.api.BalanceChangeType;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;

import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A newly created world database survives a complete service restart. */
public final class EmbeddedPersistenceChecks {
    public static void main(String[] args) throws Exception {
        Path path =
                Files.createTempDirectory(Path.of("build"), "embedded-restart-")
                        .resolve("contribution");
        ServerConfig config = new ServerConfig();
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var request =
                new BalanceChangeRequest(
                        requestId,
                        AccountTarget.byUuid(player),
                        37,
                        BalanceChangeType.EXTERNAL,
                        Identifier.parse("contribution:restart_test"),
                        "重启测试",
                        "");
        try (var database = new DatabaseService(config, path)) {
            require(database.start().join() == DatabaseState.AVAILABLE, "first startup");
            var accounts = new AccountService(database, config.serverId);
            accounts.registerPlayer(player, "RestartTester").join();
            require(accounts.changeBalance(request).join().successful(), "first credit");
        }
        require(Files.exists(Path.of(path + ".mv.db")), "database file exists");
        try (var database = new DatabaseService(config, path)) {
            require(database.start().join() == DatabaseState.AVAILABLE, "restart");
            var accounts = new AccountService(database, config.serverId);
            require(
                    accounts.account(AccountTarget.byUuid(player)).join().orElseThrow().balance()
                            == 37,
                    "balance persisted");
            require(accounts.changeBalance(request).join().replayed(), "idempotency persisted");
            require(
                    accounts.historyPage(AccountTarget.byUuid(player), null, 8, 90)
                                    .join()
                                    .orElseThrow()
                                    .rows()
                                    .size()
                            == 1,
                    "ledger persisted");
        }
        System.out.println(
                "EMBEDDED_PERSISTENCE_PASS: first-run creation, restart, balance, idempotency and"
                        + " ledger");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
