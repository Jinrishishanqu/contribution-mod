package cn.contribution.gamecurrency;

import static cn.contribution.account.AccountService.uuidBytes;

import cn.contribution.account.AccountIdentityService;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The isolated game-currency (GC) wallet. GC is a low-value currency reserved for the speculation
 * gameplay: it is funded only by destroying contribution points, so holding GC can never displace
 * the contribution economy. Balances live in thousandths (milli-GC) to keep the adjustable exchange
 * rate exact.
 */
public final class GameCurrencyService {
    public static final String SOURCE_STOCK = "game-currency:stock";
    public static final String SOURCE_EXCHANGE = "game-currency:exchange";

    private static final int LEDGER_PAGE = 10;

    private final DatabaseService database;
    private final String serverId;
    private final long exchangeRateMilli;

    public GameCurrencyService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.serverId = config.serverId;
        this.exchangeRateMilli = config.gameCurrency.exchangeRateMilli;
    }

    /** Milli-GC granted per destroyed contribution point; 950 means 1 CP : 0.95 GC. */
    public long exchangeRateMilli() {
        return exchangeRateMilli;
    }

    /** One GC value as up to three decimals with trailing zeros removed: 95000 → 95, 950 → 0.95. */
    public static String format(long milli) {
        if (milli == 0) return "0";
        boolean negative = milli < 0;
        long value = Math.abs(milli);
        long whole = value / 1000;
        long fraction = value % 1000;
        String text = Long.toString(whole);
        if (fraction != 0) {
            String digits = String.format(Locale.ROOT, "%03d", fraction);
            int end = digits.length();
            while (end > 0 && digits.charAt(end - 1) == '0') end--;
            text = text + "." + digits.substring(0, end);
        }
        return negative ? "-" + text : text;
    }

    public CompletableFuture<Void> registerPlayer(UUID player, String name) {
        if (player == null || name == null || AccountIdentityService.isBotName(name))
            return CompletableFuture.completedFuture(null);
        return database.transaction(
                connection -> {
                    ensureAccount(connection, player, name, name.toLowerCase(Locale.ROOT));
                    return null;
                });
    }

    /** Wallet balance plus a short recent ledger; amounts include the sign of the movement. */
    public CompletableFuture<Wallet> wallet(UUID player) {
        return database.transaction(
                connection -> {
                    long balance = 0;
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT balance_milli FROM game_currency_account WHERE"
                                            + " player_uuid = ?")) {
                        query.setBytes(1, uuidBytes(player));
                        try (ResultSet rows = query.executeQuery()) {
                            if (rows.next()) balance = rows.getLong(1);
                        }
                    }
                    List<Entry> entries = new ArrayList<>();
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT record_no, type, source, reason, amount_milli,"
                                            + " balance_after_milli FROM game_currency_transaction"
                                            + " WHERE player_uuid = ? ORDER BY record_no DESC LIMIT"
                                            + " ?")) {
                        query.setBytes(1, uuidBytes(player));
                        query.setInt(2, LEDGER_PAGE);
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next())
                                entries.add(
                                        new Entry(
                                                rows.getLong(1),
                                                rows.getString(2),
                                                rows.getString(3),
                                                rows.getString(4),
                                                rows.getLong(5),
                                                rows.getLong(6)));
                        }
                    }
                    return new Wallet(balance, List.copyOf(entries));
                });
    }

    /**
     * Destroys contribution points and credits GC in one transaction. The consumed CP never returns
     * to any account: the exchange is a permanent sink, not a conversion.
     */
    public CompletableFuture<ExchangeResult> exchange(UUID player, int cpAmount, UUID requestId) {
        if (player == null || requestId == null || cpAmount < 1)
            return CompletableFuture.completedFuture(fail("兑换数量无效；至少使用 1 点贡献值"));
        byte[] hash = hash(player + "|exchange|" + cpAmount);
        return database.transaction(
                        connection -> exchangeLocked(connection, player, cpAmount, requestId, hash))
                .exceptionallyCompose(
                        error ->
                                database.transaction(
                                                connection -> {
                                                    ExchangeResult replay =
                                                            replay(connection, requestId, hash);
                                                    return replay == null
                                                            ? fail("数据暂时不可用；请保留请求 ID 并重试")
                                                            : replay;
                                                })
                                        .exceptionally(ignored -> fail("数据暂时不可用；请保留请求 ID 并重试")));
    }

    private ExchangeResult exchangeLocked(
            Connection connection, UUID player, int cpAmount, UUID requestId, byte[] hash)
            throws SQLException {
        ExchangeResult existing = replay(connection, requestId, hash);
        if (existing != null) return existing;
        if (contributionRequestExists(connection, requestId)) return fail("请求 ID 已用于其他账户操作");
        String name;
        String normalized;
        int balance;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT player_name, player_name_normalized, balance FROM"
                                + " contribution_account WHERE player_uuid = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return fail("未找到玩家账户，请重新进入服务器");
                name = rows.getString(1);
                normalized = rows.getString(2);
                balance = rows.getInt(3);
            }
        }
        if (cpAmount > balance) return fail("贡献值不足；本次兑换未发起");
        long milli = cpAmount * exchangeRateMilli;
        if (milli < 1) return fail("当前兑换比例无法产生游戏币");
        int nextCp = balance - cpAmount;
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE contribution_account SET balance = ?, updated_at ="
                                + " CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, nextCp);
            update.setBytes(2, uuidBytes(player));
            update.executeUpdate();
        }
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO contribution_transaction (transaction_id, idempotency_id,"
                                + " request_hash, player_uuid, player_name, amount, income_delta,"
                                + " balance_before, balance_after, type, source, reason, operator,"
                                + " server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?,"
                                + " 'EXCHANGE', 'contribution:game_currency', '兑换游戏币',"
                                + " 'game-currency', ?, CURRENT_TIMESTAMP(6), NULL)")) {
            insert.setBytes(1, uuidBytes(UUID.randomUUID()));
            insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash);
            insert.setBytes(4, uuidBytes(player));
            insert.setString(5, name);
            insert.setInt(6, -cpAmount);
            insert.setInt(7, balance);
            insert.setInt(8, nextCp);
            insert.setString(9, serverId);
            insert.executeUpdate();
        }
        ensureAccount(connection, player, name, normalized);
        long before = lockWallet(connection, player).balanceMilli();
        long after = before + milli;
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE game_currency_account SET balance_milli = ?, player_name = ?,"
                                + " updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setLong(1, after);
            update.setString(2, name);
            update.setBytes(3, uuidBytes(player));
            update.executeUpdate();
        }
        insertTransaction(
                connection,
                requestId,
                hash,
                player,
                name,
                milli,
                before,
                after,
                "EXCHANGE",
                SOURCE_EXCHANGE,
                "兑换游戏币",
                "game-currency",
                serverId);
        return new ExchangeResult(true, "兑换成功", cpAmount, milli, after);
    }

    private static ExchangeResult replay(Connection connection, UUID requestId, byte[] hash)
            throws SQLException {
        long milli = 0;
        long after = 0;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT request_hash, amount_milli, balance_after_milli FROM"
                                + " game_currency_transaction WHERE idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                if (!MessageDigest.isEqual(hash, rows.getBytes(1))) return fail("请求 ID 已用于另一笔操作");
                milli = rows.getLong(2);
                after = rows.getLong(3);
            }
        }
        int cpSpent = 0;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT amount FROM (SELECT * FROM contribution_transaction UNION ALL"
                                + " SELECT * FROM contribution_transaction_archive) transactions"
                                + " WHERE idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) cpSpent = -rows.getInt(1);
            }
        }
        return new ExchangeResult(true, "该兑换此前已完成，未重复销毁贡献值", cpSpent, milli, after);
    }

    /** Detects ids already spent on a plain contribution movement, which never touch GC. */
    private static boolean contributionRequestExists(Connection connection, UUID requestId)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT 1 FROM (SELECT * FROM contribution_transaction UNION ALL SELECT *"
                                + " FROM contribution_transaction_archive) transactions WHERE"
                                + " idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public static void ensureAccount(
            Connection connection, UUID player, String name, String normalized)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT IGNORE INTO game_currency_account (player_uuid, player_name,"
                            + " player_name_normalized, balance_milli, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
            insert.setBytes(1, uuidBytes(player));
            insert.setString(2, name);
            insert.setString(3, normalized);
            insert.executeUpdate();
        }
    }

    /** Locks and returns the GC row, or null when the player has no wallet yet. */
    public static WalletRow lockWallet(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT player_name, balance_milli FROM game_currency_account WHERE"
                                + " player_uuid = ? FOR UPDATE")) {
            query.setBytes(1, uuidBytes(player));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new WalletRow(rows.getString(1), rows.getLong(2)) : null;
            }
        }
    }

    public static void insertTransaction(
            Connection connection,
            UUID requestId,
            byte[] hash,
            UUID player,
            String playerName,
            long amountMilli,
            long beforeMilli,
            long afterMilli,
            String type,
            String source,
            String reason,
            String operator,
            String serverId)
            throws SQLException {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO game_currency_transaction (transaction_id, idempotency_id,"
                            + " request_hash, player_uuid, player_name, amount_milli,"
                            + " balance_before_milli, balance_after_milli, type, source, reason,"
                            + " operator, server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, ?,"
                            + " ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), NULL)")) {
            insert.setBytes(1, uuidBytes(UUID.randomUUID()));
            insert.setBytes(2, uuidBytes(requestId));
            insert.setBytes(3, hash);
            insert.setBytes(4, uuidBytes(player));
            insert.setString(5, playerName);
            insert.setLong(6, amountMilli);
            insert.setLong(7, beforeMilli);
            insert.setLong(8, afterMilli);
            insert.setString(9, type);
            insert.setString(10, source);
            insert.setString(11, reason);
            insert.setString(12, operator);
            insert.setString(13, serverId);
            insert.executeUpdate();
        }
    }

    /** True when this id already exists in the GC ledger, regardless of the original operation. */
    public static boolean currencyRequestExists(Connection connection, UUID requestId)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT 1 FROM game_currency_transaction WHERE idempotency_id = ?")) {
            query.setBytes(1, uuidBytes(requestId));
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    public static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ExchangeResult fail(String message) {
        return new ExchangeResult(false, message, 0, 0, 0);
    }

    public record WalletRow(String playerName, long balanceMilli) {}

    public record Entry(
            long recordNo,
            String type,
            String source,
            String reason,
            long amountMilli,
            long balanceAfterMilli) {}

    public record Wallet(long balanceMilli, List<Entry> entries) {}

    public record ExchangeResult(
            boolean success, String message, int cpSpent, long milliReceived, long balanceMilli) {}
}
