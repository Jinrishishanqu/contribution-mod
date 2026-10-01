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

/** Isolated H2 check for create/migrate, refusal to overwrite, and audit persistence. */
public final class AccountIdentityChecks {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        Path path = Files.createTempDirectory(Path.of("build"), "identity-").resolve("contribution");
        try (DatabaseService db = new DatabaseService(config, path)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "migration");
            AccountIdentityService identities = new AccountIdentityService(db);
            AccountService accounts = new AccountService(db, "test");
            UUID oldId = UUID.randomUUID(), newId = UUID.randomUUID();
            check(identities.create(oldId, "OldHolder").join().startsWith("已创建"), "create old");
            check(identities.create(newId, "NewHolder").join().startsWith("已创建"), "create target");
            check(identities.create(UUID.randomUUID(), "OldHolder").join().contains("已属于"), "name conflict");
            accounts.changeBalance(new BalanceChangeRequest(UUID.randomUUID(), AccountTarget.byUuid(oldId), 325,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:test"), "测试", "")).join();
            db.transaction(connection -> {
                try (var insert = connection.prepareStatement(
                        "INSERT INTO player_activity_stats (player_uuid, player_name, player_name_normalized, total_placed, total_mined, updated_at) "
                                + "VALUES (?, 'OldHolder', 'oldholder', 4, 2, CURRENT_TIMESTAMP(6))")) {
                    insert.setBytes(1, AccountService.uuidBytes(oldId)); insert.executeUpdate();
                }
                return null;
            }).join();
            check(identities.migrate(oldId, newId).join().startsWith("已迁移"), "migrate into empty target");
            check(accounts.account(AccountTarget.byUuid(oldId)).join().isEmpty(), "old UUID removed");
            var migrated = accounts.account(AccountTarget.byUuid(newId)).join().orElseThrow();
            check(migrated.balance() == 325 && migrated.totalIncome() == 325 && migrated.playerName().equals("NewHolder"),
                    "balance, income and target name preserved");
            check(accounts.historyPage(AccountTarget.byUuid(newId), null, 20, 90).join().orElseThrow().rows().size() == 1,
                    "transaction follows target UUID");
            db.transaction(connection -> {
                try (var query = connection.prepareStatement(
                        "SELECT player_name, total_placed FROM player_activity_stats WHERE player_uuid = ?")) {
                    query.setBytes(1, AccountService.uuidBytes(newId));
                    try (var rows = query.executeQuery()) {
                        check(rows.next() && rows.getString(1).equals("NewHolder") && rows.getLong(2) == 4,
                                "statistics follow target UUID");
                    }
                }
                try (var query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM account_uuid_migration WHERE source_uuid = ? AND target_uuid = ?")) {
                    query.setBytes(1, AccountService.uuidBytes(oldId)); query.setBytes(2, AccountService.uuidBytes(newId));
                    try (var rows = query.executeQuery()) { rows.next(); check(rows.getInt(1) == 1, "audit row"); }
                }
                return null;
            }).join();
            check(identities.migrate(oldId, newId).join().contains("没有账户"), "repeat cannot apply twice");
            UUID another = UUID.randomUUID();
            check(identities.create(another, "Another").join().startsWith("已创建"), "create another");
            check(identities.migrate(newId, another).join().startsWith("已迁移"), "pristine account allowed");
            UUID occupied = UUID.randomUUID();
            check(identities.create(occupied, "Occupied").join().startsWith("已创建"), "create occupied target");
            accounts.changeBalance(new BalanceChangeRequest(UUID.randomUUID(), AccountTarget.byUuid(occupied), 1,
                    BalanceChangeType.EXTERNAL, Identifier.parse("contribution:test"), "目标已有数据", "")).join();
            check(identities.migrate(another, occupied).join().contains("禁止覆盖"), "occupied account is rejected");
            check(accounts.account(AccountTarget.byUuid(another)).join().orElseThrow().balance() == 325,
                    "failed migration leaves source unchanged");
            System.out.println("IDENTITY_PASS: creation, UUID migration, history, stats, audit, replay protection");
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
