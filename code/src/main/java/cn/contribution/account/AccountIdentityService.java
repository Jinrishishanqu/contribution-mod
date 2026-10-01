package cn.contribution.account;

import cn.contribution.database.DatabaseService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicit, transactional identity repair. Never merges two nonempty accounts. */
public final class AccountIdentityService {
    private static final String[] OWNED_TABLES = {
            "contribution_transaction", "contribution_transaction_archive", "player_activity_stats",
            "player_industry_stats", "player_distance_remainder", "stock_position", "stock_trade", "stock_refund",
            "stock_batch_request", "player_development_reward", "checkin_daily", "checkin_player",
            "checkin_event_claim", "reward_delivery", "shop_order"
    };
    private final DatabaseService database;

    public AccountIdentityService(DatabaseService database) { this.database = database; }

    public CompletableFuture<String> create(UUID uuid, String name) {
        if (uuid == null || name == null || !name.matches("[A-Za-z0-9_]{3,16}"))
            return CompletableFuture.completedFuture("UUID 或玩家名称无效");
        return database.transaction(connection -> {
            Account old = account(connection, uuid, true);
            if (old != null) return "账户已存在：" + old.name + "（" + uuid + "）";
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT player_uuid FROM contribution_account WHERE player_name_normalized = ? FOR UPDATE")) {
                check.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rows = check.executeQuery()) {
                    if (rows.next()) return "该名称已属于 UUID " + AccountService.bytesUuid(rows.getBytes(1)) + "；请先迁移账户";
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO contribution_account (player_uuid, player_name, player_name_normalized, balance, total_income, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
                insert.setBytes(1, AccountService.uuidBytes(uuid)); insert.setString(2, name);
                insert.setString(3, name.toLowerCase(Locale.ROOT)); insert.executeUpdate();
            }
            return "已创建空账户：" + name + "（" + uuid + "）";
        });
    }

    public CompletableFuture<String> migrate(UUID source, UUID target) {
        if (source == null || target == null || source.equals(target))
            return CompletableFuture.completedFuture("源和目标 UUID 必须不同");
        return database.transaction(connection -> migrateLocked(connection, source, target));
    }

    private static String migrateLocked(Connection connection, UUID source, UUID target) throws SQLException {
        // Lock in a consistent order across concurrent administrative operations.
        UUID first = source.compareTo(target) < 0 ? source : target;
        account(connection, first, true);
        account(connection, first.equals(source) ? target : source, true);
        Account old = account(connection, source, false);
        Account next = account(connection, target, false);
        if (old == null) return "源 UUID 没有账户；未做任何修改";
        if (next != null && (next.balance != 0 || next.income != 0)) return "目标账户已有余额或历史收入，禁止覆盖";
        for (String table : OWNED_TABLES) if (hasRows(connection, table, target))
            return "目标 UUID 在 " + table + " 已有数据，禁止覆盖";
        String name = next == null ? old.name : next.name;
        if (next != null) try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM contribution_account WHERE player_uuid = ?")) {
            delete.setBytes(1, AccountService.uuidBytes(target)); delete.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE contribution_account SET player_uuid = ?, player_name = ?, player_name_normalized = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setBytes(1, AccountService.uuidBytes(target)); update.setString(2, name);
            update.setString(3, name.toLowerCase(Locale.ROOT)); update.setBytes(4, AccountService.uuidBytes(source));
            update.executeUpdate();
        }
        for (String table : OWNED_TABLES) try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET player_uuid = ? WHERE player_uuid = ?")) {
            update.setBytes(1, AccountService.uuidBytes(target)); update.setBytes(2, AccountService.uuidBytes(source));
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE player_activity_stats SET player_name = ?, player_name_normalized = ? WHERE player_uuid = ?")) {
            update.setString(1, name); update.setString(2, name.toLowerCase(Locale.ROOT));
            update.setBytes(3, AccountService.uuidBytes(target)); update.executeUpdate();
        }
        try (PreparedStatement audit = connection.prepareStatement(
                "INSERT INTO account_uuid_migration (source_uuid, target_uuid, source_name, target_name, migrated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
            audit.setBytes(1, AccountService.uuidBytes(source)); audit.setBytes(2, AccountService.uuidBytes(target));
            audit.setString(3, old.name); audit.setString(4, name); audit.executeUpdate();
        }
        return "已迁移 " + old.name + "（" + source + "）→ " + name + "（" + target + "）；余额、流水、统计、持仓和返还一并迁移";
    }

    private static boolean hasRows(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE player_uuid = ? LIMIT 1")) {
            query.setBytes(1, AccountService.uuidBytes(id));
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private static Account account(Connection connection, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_name, balance, total_income FROM contribution_account WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setBytes(1, AccountService.uuidBytes(id));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Account(rows.getString(1), rows.getInt(2), rows.getInt(3)) : null;
            }
        }
    }
    private record Account(String name, int balance, int income) { }
}
