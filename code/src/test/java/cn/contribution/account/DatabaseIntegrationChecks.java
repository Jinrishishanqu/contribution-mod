package cn.contribution.account;

import cn.contribution.api.*;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.*;
import net.minecraft.resources.Identifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Explicit opt-in integration suite. Uses an isolated schema on loopback, never a production database. */
public final class DatabaseIntegrationChecks {
    public static void main(String[] args) throws Exception {
        boolean embedded = args.length > 0 && args[0].equals("embedded");
        ServerConfig config = new ServerConfig();
        config.database.mode = embedded ? "embedded" : "mysql";
        config.database.jdbcUrl = "jdbc:mysql://127.0.0.1:23306/contribution_test_v2?serverTimezone=UTC";
        config.database.username = "root";
        Path databasePath = embedded ? Files.createTempDirectory(Path.of("build"), "embedded-account-").resolve("contribution") : Path.of("unused-mysql-path");
        try (DatabaseService db = new DatabaseService(config, databasePath)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration/startup");
            AccountService accounts = new AccountService(db, "integration");
            UUID player = UUID.randomUUID();
            String name = "T" + player.toString().replace("-", "").substring(0, 15);
            accounts.registerPlayer(player, name).join();
            AccountTarget target = AccountTarget.byUuid(player);
            var credit = request(target, 100, BalanceChangeType.EXTERNAL);
            check(accounts.changeBalance(credit).join().successful(), "credit");
            check(accounts.changeBalance(credit).join().replayed(), "duplicate replay");
            var conflict = new BalanceChangeRequest(credit.idempotencyId(), target, 101, credit.type(), credit.source(), credit.reason(), "");
            check(accounts.changeBalance(conflict).join().status() == BalanceChangeStatus.IDEMPOTENCY_CONFLICT, "conflicting ID");
            check(accounts.changeBalance(request(target, -101, BalanceChangeType.EXTERNAL)).join().status() == BalanceChangeStatus.INSUFFICIENT_BALANCE, "insufficient balance");
            check(accounts.historyPage(target, null, 100, 90).join().orElseThrow().rows().size() == 1, "rejections create no transaction");
            check(accounts.changeBalance(request(target, -20, BalanceChangeType.EXTERNAL)).join().successful(), "spend");
            check(accounts.changeBalance(request(target, 20, BalanceChangeType.REFUND)).join().successful(), "refund");
            check(accounts.account(AccountTarget.byName(name.toLowerCase(Locale.ROOT))).join().orElseThrow().totalIncome() == 100, "offline name and refund income");
            List<CompletableFuture<BalanceChangeResult>> parallel = new ArrayList<>();
            for (int i = 0; i < 30; i++) parallel.add(accounts.changeBalance(request(target, 1, BalanceChangeType.EXTERNAL)));
            for (var result : parallel) check(result.join().successful(), "concurrent credit");
            check(accounts.account(target).join().orElseThrow().balance() == 130, "no lost updates");
            var same = request(target, 7, BalanceChangeType.EXTERNAL);
            parallel.clear();
            for (int i = 0; i < 12; i++) parallel.add(accounts.changeBalance(same));
            for (var result : parallel) check(result.join().successful(), "concurrent duplicate");
            check(accounts.account(target).join().orElseThrow().balance() == 137, "one concurrent application");
            check(accounts.changeBalance(request(target, Integer.MAX_VALUE, BalanceChangeType.EXTERNAL)).join().status() == BalanceChangeStatus.BALANCE_OVERFLOW, "overflow");
            check(accounts.changeBalance(request(target, Integer.MIN_VALUE, BalanceChangeType.EXTERNAL)).join().status() == BalanceChangeStatus.INSUFFICIENT_BALANCE, "minimum int");
            db.transaction(connection -> {
                try (var update = connection.prepareStatement("UPDATE contribution_transaction SET created_at = '2020-01-05 12:00:00' WHERE idempotency_id = ?")) {
                    update.setBytes(1, AccountService.uuidBytes(credit.idempotencyId())); update.executeUpdate();
                }
                TransactionArchive.moveBatch(connection); return null;
            }).join();
            check(accounts.changeBalance(credit).join().replayed(), "archived idempotency replay");
            check(accounts.historyPage(target, null, 20, 365, HistoryFilter.parse("from=2020-01-01 to=2020-01-31",365)).join().orElseThrow().rows().size() == 1, "archive date query");
            check(accounts.historyPage(target, null, 20, 365, HistoryFilter.parse("to=2020-01-31",365)).join().orElseThrow().rows().size() == 1, "archive end-only query");
            check(accounts.account(target).join().orElseThrow().balance() == 137, "archive leaves balance unchanged");
            var excludedIncome = new BalanceChangeRequest(UUID.randomUUID(), target, 5,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:integration"), "不计收入", "", false);
            check(accounts.changeBalance(excludedIncome).join().successful(), "admin credit without income");
            check(accounts.account(target).join().orElseThrow().totalIncome() == 137, "credit income excluded");
            check(accounts.changeBalance(new BalanceChangeRequest(excludedIncome.idempotencyId(), target, 5,
                    excludedIncome.type(), excludedIncome.source(), excludedIncome.reason(), "", true)).join().status()
                    == BalanceChangeStatus.IDEMPOTENCY_CONFLICT, "income flag is part of idempotency");
            var reverseIncome = new BalanceChangeRequest(UUID.randomUUID(), target, -5,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:integration"), "冲销收入", "", true);
            check(accounts.changeBalance(reverseIncome).join().successful(), "admin debit with income reversal");
            check(accounts.account(target).join().orElseThrow().totalIncome() == 132, "debit reverses income");
            check(accounts.changeBalance(new BalanceChangeRequest(UUID.randomUUID(), target, -133,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:integration"), "收入不足", "", true)).join().status()
                    == BalanceChangeStatus.INSUFFICIENT_BALANCE, "income cannot become negative");
            System.out.println((embedded ? "EMBEDDED_" : "MYSQL_") + "DATABASE_INTEGRATION_PASS: migration, balance bounds, refunds, offline queries, concurrent writes, idempotency and archive");
        }
    }
    private static BalanceChangeRequest request(AccountTarget target, int amount, BalanceChangeType type) {
        return new BalanceChangeRequest(UUID.randomUUID(), target, amount, type, Identifier.parse("contribution:integration"), "集成测试", "");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
