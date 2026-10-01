package cn.contribution.account;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** One locked account update and its immutable ledger row in the caller's transaction. */
public final class EconomyLedger {
    private EconomyLedger() { }

    public static Result change(Connection connection, UUID player, int amount, boolean income,
                                String type, String source, String reason, String serverId, UUID id) throws SQLException {
        if (amount == 0) return new Result(false, "变动数量为零");
        try (PreparedStatement replay = connection.prepareStatement(
                "SELECT player_uuid, amount, type FROM contribution_transaction WHERE idempotency_id = ?")) {
            replay.setBytes(1, AccountService.uuidBytes(id));
            try (ResultSet row = replay.executeQuery()) {
                if (row.next()) return new Result(java.util.Arrays.equals(row.getBytes(1), AccountService.uuidBytes(player))
                        && row.getInt(2) == amount && type.equals(row.getString(3)), "重复请求");
            }
        }
        String name;
        int before, total;
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT player_name, balance, total_income FROM contribution_account WHERE player_uuid = ? FOR UPDATE")) {
            lock.setBytes(1, AccountService.uuidBytes(player));
            try (ResultSet row = lock.executeQuery()) {
                if (!row.next()) return new Result(false, "账户不存在");
                name = row.getString(1); before = row.getInt(2); total = row.getInt(3);
            }
        }
        long after = (long) before + amount;
        long nextIncome = (long) total + (income ? amount : 0);
        if (after < 0) return new Result(false, "余额不足");
        if (after > Integer.MAX_VALUE || nextIncome > Integer.MAX_VALUE) return new Result(false, "余额或历史收入已达上限");
        if (nextIncome < 0) return new Result(false, "历史收入不足");
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE contribution_account SET balance = ?, total_income = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, (int) after); update.setInt(2, (int) nextIncome);
            update.setBytes(3, AccountService.uuidBytes(player)); update.executeUpdate();
        }
        byte[] requestHash;
        try {
            requestHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + player + ":" + amount + ":" + type).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO contribution_transaction (transaction_id, idempotency_id, request_hash, player_uuid, player_name, amount, income_delta, balance_before, balance_after, type, source, reason, operator, server_id, created_at, note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), NULL)")) {
            insert.setBytes(1, AccountService.uuidBytes(UUID.randomUUID()));
            insert.setBytes(2, AccountService.uuidBytes(id)); insert.setBytes(3, requestHash);
            insert.setBytes(4, AccountService.uuidBytes(player)); insert.setString(5, name);
            insert.setInt(6, amount); insert.setInt(7, income ? amount : 0);
            insert.setInt(8, before); insert.setInt(9, (int) after);
            insert.setString(10, type); insert.setString(11, source);
            insert.setString(12, reason); insert.setString(13, source);
            insert.setString(14, serverId); insert.executeUpdate();
        }
        return new Result(true, "成功");
    }

    public record Result(boolean success, String message) { }
}
