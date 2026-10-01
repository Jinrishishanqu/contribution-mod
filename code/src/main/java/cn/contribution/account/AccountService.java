package cn.contribution.account;

import cn.contribution.ContributionMod;
import cn.contribution.api.AccountTarget;
import cn.contribution.api.BalanceChangeRequest;
import cn.contribution.api.BalanceChangeResult;
import cn.contribution.api.BalanceChangeStatus;
import cn.contribution.api.BalanceChangeType;
import cn.contribution.api.ContributionApi;
import cn.contribution.database.DatabaseService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AccountService extends ContributionApi {
    private final DatabaseService database;
    private final String serverId;

    public AccountService(DatabaseService database, String serverId) {
        this.database = database;
        this.serverId = serverId;
    }

    public CompletableFuture<Void> registerPlayer(UUID uuid, String name) {
        if (AccountIdentityService.isBotName(name)) return CompletableFuture.completedFuture(null);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO contribution_account "
                            + "(player_uuid, player_name, player_name_normalized, balance, total_income, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
                statement.setBytes(1, uuidBytes(uuid));
                statement.setString(2, name);
                statement.setString(3, name.toLowerCase(Locale.ROOT));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<AccountRecord>> account(AccountTarget target) {
        return database.transaction(connection -> findAccount(connection, target, false));
    }

    public CompletableFuture<Optional<HistoryPage>> historyPage(AccountTarget target, UUID before,
                                                                 int limit, int maxAgeDays) {
        return historyPage(target, before, limit, maxAgeDays, HistoryFilter.empty());
    }

    public CompletableFuture<Optional<HistoryPage>> historyPage(AccountTarget target, UUID before,
                                                                 int limit, int maxAgeDays, HistoryFilter filter) {
        return database.transaction(connection -> {
            Optional<AccountRecord> account = findAccount(connection, target, false);
            if (account.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(loadHistoryPage(connection, account.get().playerUuid(), before, limit, maxAgeDays, filter));
        });
    }

    public CompletableFuture<HistoryPage> allHistoryPage(UUID before, int limit, int maxAgeDays) {
        return allHistoryPage(before, limit, maxAgeDays, HistoryFilter.empty());
    }

    public CompletableFuture<HistoryPage> allHistoryPage(UUID before, int limit, int maxAgeDays, HistoryFilter filter) {
        return database.transaction(connection -> loadHistoryPage(connection, null, before, limit, maxAgeDays, filter));
    }

    private static HistoryPage loadHistoryPage(Connection connection, UUID playerUuid, UUID before,
                                                int limit, int maxAgeDays, HistoryFilter filter) throws SQLException {
        if (limit < 1 || limit > 100 || maxAgeDays < 1 || maxAgeDays > 365 || filter == null) {
            throw new IllegalArgumentException("Invalid history query bounds");
        }
        Instant cutoff = Instant.now().minusSeconds(maxAgeDays * 86_400L);
        if (filter.fromInclusive() != null && (maxAgeDays == 365 || filter.fromInclusive().isAfter(cutoff))) {
            cutoff = filter.fromInclusive();
        }
        Cursor cursor = null;
        if (before != null) {
            cursor = findCursor(connection, before);
            if (cursor == null || (playerUuid != null && !playerUuid.equals(cursor.playerUuid()))
                    || cursor.createdAt().isBefore(cutoff)
                    || (filter.untilExclusive() != null && !cursor.createdAt().isBefore(filter.untilExclusive()))
                    || (filter.type() != null && !filter.type().equals(cursor.type()))
                    || (filter.source() != null && !filter.source().equals(cursor.source()))
                    || (filter.serverId() != null && !filter.serverId().equals(cursor.serverId()))) {
                return HistoryPage.invalidCursor();
            }
        }
        String sql = "SELECT transaction_id, player_name, amount, balance_after, type, reason, created_at "
                + "FROM " + (maxAgeDays == 365 ? "(SELECT * FROM contribution_transaction UNION ALL SELECT * FROM contribution_transaction_archive) transactions" : "contribution_transaction") + " WHERE created_at >= ?"
                + (filter.untilExclusive() == null ? "" : " AND created_at < ?")
                + (playerUuid == null ? "" : " AND player_uuid = ?")
                + (filter.type() == null ? "" : " AND type = ?")
                + (filter.source() == null ? "" : " AND source = ?")
                + (filter.serverId() == null ? "" : " AND server_id = ?")
                + (cursor == null ? "" : " AND (created_at < ? OR (created_at = ? AND record_no < ?))")
                + " ORDER BY created_at DESC, record_no DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setObject(parameter++, LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC));
            if (filter.untilExclusive() != null) {
                statement.setObject(parameter++, LocalDateTime.ofInstant(filter.untilExclusive(), ZoneOffset.UTC));
            }
            if (playerUuid != null) {
                statement.setBytes(parameter++, uuidBytes(playerUuid));
            }
            if (filter.type() != null) {
                statement.setString(parameter++, filter.type());
            }
            if (filter.source() != null) {
                statement.setString(parameter++, filter.source());
            }
            if (filter.serverId() != null) {
                statement.setString(parameter++, filter.serverId());
            }
            if (cursor != null) {
                LocalDateTime time = LocalDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC);
                statement.setObject(parameter++, time);
                statement.setObject(parameter++, time);
                statement.setLong(parameter++, cursor.recordNo());
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                List<TransactionRecord> result = new ArrayList<>();
                while (rows.next()) {
                    if (result.size() == limit) {
                        return new HistoryPage(result, true, true);
                    }
                    result.add(new TransactionRecord(bytesUuid(rows.getBytes(1)), rows.getString(2), rows.getInt(3),
                            rows.getInt(4), rows.getString(5), rows.getString(6),
                            rows.getObject(7, LocalDateTime.class).toInstant(ZoneOffset.UTC)));
                }
                return new HistoryPage(result, false, true);
            }
        }
    }

    private static Cursor findCursor(Connection connection, UUID transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_no, player_uuid, created_at, type, source, server_id "
                        + "FROM (SELECT * FROM contribution_transaction UNION ALL SELECT * FROM contribution_transaction_archive) transactions WHERE transaction_id = ?")) {
            statement.setBytes(1, uuidBytes(transactionId));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new Cursor(rows.getLong(1), bytesUuid(rows.getBytes(2)),
                        rows.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rows.getString(4), rows.getString(5), rows.getString(6)) : null;
            }
        }
    }

    private record Cursor(long recordNo, UUID playerUuid, Instant createdAt,
                          String type, String source, String serverId) {
    }

    public CompletableFuture<AccountPage> allAccountsPage(UUID before, int limit) {
        return database.transaction(connection -> {
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("Invalid account page size");
            }
            String cursorName = null;
            if (before != null) {
                try (PreparedStatement cursor = connection.prepareStatement(
                        "SELECT player_name_normalized FROM contribution_account WHERE player_uuid = ?")) {
                    cursor.setBytes(1, uuidBytes(before));
                    try (ResultSet rows = cursor.executeQuery()) {
                        if (!rows.next()) {
                            return AccountPage.invalidCursor();
                        }
                        cursorName = rows.getString(1);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid, player_name, balance, total_income FROM contribution_account "
                            + (cursorName == null ? "" : "WHERE player_name_normalized > ? ")
                            + "ORDER BY player_name_normalized LIMIT ?")) {
                int parameter = 1;
                if (cursorName != null) {
                    statement.setString(parameter++, cursorName);
                }
                statement.setInt(parameter, limit + 1);
                try (ResultSet rows = statement.executeQuery()) {
                    List<AccountRecord> result = new ArrayList<>();
                    while (rows.next()) {
                        if (result.size() == limit) {
                            return new AccountPage(result, true, true);
                        }
                        result.add(new AccountRecord(bytesUuid(rows.getBytes(1)), rows.getString(2),
                                rows.getInt(3), rows.getInt(4)));
                    }
                    return new AccountPage(result, false, true);
                }
            }
        });
    }

    @Override
    public CompletableFuture<BalanceChangeResult> changeBalance(BalanceChangeRequest request) {
        return change(request, request == null || request.type() == null ? "EXTERNAL" : request.type().name(),
                request == null || request.source() == null ? "unknown" : request.source().toString());
    }

    public CompletableFuture<BalanceChangeResult> changeAdmin(BalanceChangeRequest request, String operator) {
        return change(request, "ADMIN", "command-" + operator);
    }

    private CompletableFuture<BalanceChangeResult> change(BalanceChangeRequest request, String storedType, String operator) {
        UUID id = request == null ? null : request.idempotencyId();
        BalanceChangeResult invalid = validate(request);
        if (invalid != null) {
            return CompletableFuture.completedFuture(invalid);
        }
        byte[] hash = requestHash(request, storedType);
        byte[] legacyHash = request.affectTotalIncome() == (request.amount() > 0 && request.type() != BalanceChangeType.REFUND)
                ? requestHash(request, storedType, false) : null;
        return database.transaction(connection -> apply(connection, request, storedType, operator, hash, legacyHash))
                .exceptionallyCompose(error -> {
                    ContributionMod.LOGGER.warn("Account operation needs idempotency recovery: id={} error={}",
                            id, error.getClass().getSimpleName());
                    return database.transaction(connection -> {
                        BalanceChangeResult replay = findReplay(connection, request.idempotencyId(), hash, legacyHash);
                        if (replay != null) {
                            return replay;
                        }
                        throw new SQLException("Account transaction failed", error);
                    }).exceptionally(ignored -> BalanceChangeResult.rejected(
                            BalanceChangeStatus.DATABASE_UNAVAILABLE, id, "数据服务暂时不可用，请稍后重试"));
                });
    }

    private BalanceChangeResult apply(Connection connection, BalanceChangeRequest request, String storedType,
                                      String operator, byte[] hash, byte[] legacyHash) throws SQLException {
        BalanceChangeResult replay = findReplay(connection, request.idempotencyId(), hash, legacyHash);
        if (replay != null) {
            return replay;
        }
        Optional<AccountRecord> found = findAccount(connection, request.target(), true);
        if (found.isEmpty()) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.ACCOUNT_NOT_FOUND,
                    request.idempotencyId(), "未找到该玩家账户");
        }
        AccountRecord account = found.get();
        replay = findReplay(connection, request.idempotencyId(), hash, legacyHash);
        if (replay != null) {
            return replay;
        }
        long next = (long) account.balance() + request.amount();
        if (next < 0) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INSUFFICIENT_BALANCE,
                    request.idempotencyId(), "余额不足");
        }
        int income = request.affectTotalIncome() ? request.amount() : 0;
        long nextIncome = (long) account.totalIncome() + income;
        if (nextIncome < 0) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INSUFFICIENT_BALANCE,
                    request.idempotencyId(), "历史总收入不足");
        }
        if (next > Integer.MAX_VALUE || nextIncome > Integer.MAX_VALUE) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.BALANCE_OVERFLOW,
                    request.idempotencyId(), "余额或历史总收入超过上限");
        }
        UUID transactionId = UUID.randomUUID();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE contribution_account SET balance = ?, total_income = total_income + ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, (int) next);
            update.setInt(2, income);
            update.setBytes(3, uuidBytes(account.playerUuid()));
            update.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO contribution_transaction (transaction_id, idempotency_id, request_hash, player_uuid, "
                        + "player_name, amount, income_delta, balance_before, balance_after, type, source, reason, "
                        + "operator, server_id, created_at, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?)")) {
            insert.setBytes(1, uuidBytes(transactionId));
            insert.setBytes(2, uuidBytes(request.idempotencyId()));
            insert.setBytes(3, hash);
            insert.setBytes(4, uuidBytes(account.playerUuid()));
            insert.setString(5, account.playerName());
            insert.setInt(6, request.amount());
            insert.setInt(7, income);
            insert.setInt(8, account.balance());
            insert.setInt(9, (int) next);
            insert.setString(10, storedType);
            insert.setString(11, request.source().toString());
            insert.setString(12, request.reason());
            insert.setString(13, operator);
            insert.setString(14, serverId);
            insert.setString(15, request.note());
            insert.executeUpdate();
        }
        return BalanceChangeResult.success(request.idempotencyId(), transactionId, account.balance(), (int) next, false);
    }

    private static BalanceChangeResult findReplay(Connection connection, UUID id, byte[] hash,
                                                  byte[] legacyHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_id, request_hash, balance_before, balance_after "
                        + "FROM (SELECT * FROM contribution_transaction UNION ALL SELECT * FROM contribution_transaction_archive) transactions WHERE idempotency_id = ?")) {
            statement.setBytes(1, uuidBytes(id));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                byte[] storedHash = rows.getBytes(2);
                if (!MessageDigest.isEqual(hash, storedHash)
                        && (legacyHash == null || !MessageDigest.isEqual(legacyHash, storedHash))) {
                    return BalanceChangeResult.rejected(BalanceChangeStatus.IDEMPOTENCY_CONFLICT,
                            id, "幂等 ID 已用于不同请求");
                }
                return BalanceChangeResult.success(id, bytesUuid(rows.getBytes(1)), rows.getInt(3), rows.getInt(4), true);
            }
        }
    }

    private static Optional<AccountRecord> findAccount(Connection connection, AccountTarget target, boolean lock)
            throws SQLException {
        String sql = "SELECT player_uuid, player_name, balance, total_income FROM contribution_account WHERE "
                + (target.playerUuid() != null ? "player_uuid = ?" : "player_name_normalized = ?")
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (target.playerUuid() != null) {
                statement.setBytes(1, uuidBytes(target.playerUuid()));
            } else {
                statement.setString(1, target.playerName().toLowerCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountRecord(bytesUuid(rows.getBytes(1)), rows.getString(2),
                        rows.getInt(3), rows.getInt(4)));
            }
        }
    }

    private static BalanceChangeResult validate(BalanceChangeRequest request) {
        UUID id = request == null ? null : request.idempotencyId();
        if (request == null || id == null || request.target() == null || request.type() == null) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INVALID_AMOUNT, id, "请求参数不完整");
        }
        if (request.amount() == 0 || (request.type() == BalanceChangeType.REFUND && request.amount() < 0)) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INVALID_AMOUNT, id, "数量或退款类型无效");
        }
        if (request.type() == BalanceChangeType.REFUND && request.affectTotalIncome()) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INVALID_AMOUNT, id, "退款不影响历史总收入");
        }
        if (request.source() == null || request.source().toString().length() > 128
                || !validText(request.reason(), false) || !validText(request.note(), true)) {
            return BalanceChangeResult.rejected(BalanceChangeStatus.INVALID_TEXT, id, "来源、原因或备注无效");
        }
        return null;
    }

    private static boolean validText(String text, boolean optional) {
        return text != null && (optional || !text.isBlank()) && text.codePointCount(0, text.length()) <= 64;
    }

    private static byte[] requestHash(BalanceChangeRequest request, String storedType) {
        return requestHash(request, storedType, true);
    }

    private static byte[] requestHash(BalanceChangeRequest request, String storedType, boolean includeIncomeFlag) {
        String normalized = request.target().playerUuid() != null
                ? request.target().playerUuid().toString()
                : request.target().playerName().toLowerCase(Locale.ROOT);
        StringBuilder value = new StringBuilder();
        appendHashField(value, storedType);
        appendHashField(value, normalized);
        appendHashField(value, Integer.toString(request.amount()));
        appendHashField(value, request.type().name());
        appendHashField(value, request.source().toString());
        appendHashField(value, request.reason());
        appendHashField(value, request.note());
        if (includeIncomeFlag) appendHashField(value, Boolean.toString(request.affectTotalIncome()));
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendHashField(StringBuilder value, String field) {
        value.append(field.length()).append(':').append(field);
    }

    public static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    public static UUID bytesUuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
