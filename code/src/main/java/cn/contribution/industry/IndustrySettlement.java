package cn.contribution.industry;

import cn.contribution.ContributionMod;
import cn.contribution.account.AccountService;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;

import net.minecraft.server.MinecraftServer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class IndustrySettlement {
    private static final BigDecimal LONG_KEEP = new BigDecimal("0.99");
    private static final BigDecimal SHORT_KEEP = new BigDecimal("0.8");
    private final DatabaseService database;
    private final ServerConfig config;
    private boolean running;
    private long lastRequested = Long.MIN_VALUE;
    private int nextRetryTick;
    private long nextMissingCloseNotice;

    public IndustrySettlement(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.config = config;
    }

    public void requestRecovery() {
        nextRetryTick = 0;
        lastRequested = Long.MIN_VALUE;
    }

    public void tick(MinecraftServer server, StatisticsService statistics) {
        long today = RuleManager.day(server);
        if (!RuleManager.historyReady()
                || today <= 0
                || !RuleManager.settlementWindow(server)
                || running
                || server.getTickCount() < nextRetryTick
                || lastRequested == today - 1
                || !statistics.isDrainedThrough(today - 1)) {
            return;
        }
        long latestComplete = today - 1;
        running = true;
        settleNext(latestComplete)
                .whenComplete(
                        (completed, error) ->
                                server.execute(
                                        () -> {
                                            running = false;
                                            if (error == null && completed != null) {
                                                lastRequested = completed;
                                            } else {
                                                nextRetryTick = server.getTickCount() + 600;
                                            }
                                            if (error != null) {
                                                ContributionMod.LOGGER.warn(
                                                        "Industry settlement is pending: {}",
                                                        error.toString());
                                            }
                                        }));
    }

    /** One bounded, asynchronous recovery step, shared by normal ticks and recovery checks. */
    CompletableFuture<Long> settleNext(long latestComplete) {
        return database.transaction(connection -> acquireNext(connection, latestComplete))
                .thenCompose(
                        acquired -> {
                            if (acquired == null) return CompletableFuture.completedFuture(null);
                            if (acquired.completed)
                                return CompletableFuture.completedFuture(acquired.day);
                            return database.transaction(
                                    connection -> {
                                        settle(connection, acquired.day, acquired.runId);
                                        return acquired.day;
                                    });
                        });
    }

    private Acquisition acquireNext(Connection connection, long latestComplete)
            throws SQLException {
        String taskName = "industry_daily_settlement";
        long day = RuleManager.firstDay(connection);
        try (PreparedStatement latest =
                connection.prepareStatement(
                        "SELECT MAX(game_day) FROM scheduled_task_run WHERE task_name = ? AND"
                                + " status = 'SUCCEEDED'")) {
            latest.setString(1, taskName);
            try (ResultSet rows = latest.executeQuery()) {
                rows.next();
                long previous = rows.getLong(1);
                if (!rows.wasNull()) {
                    day = previous + 1;
                }
            }
        }
        if (day > latestComplete) {
            return new Acquisition(latestComplete, null, true);
        }
        if (!allStatisticsServersClosed(connection, day)) {
            return null;
        }
        UUID proposedId = UUID.randomUUID();
        int inserted;
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT IGNORE INTO scheduled_task_run (task_name, game_day, run_id,"
                            + " status, attempt_count, started_at, updated_at, lease_until) VALUES"
                            + " (?, ?, ?, 'RUNNING', 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6),"
                            + " CURRENT_TIMESTAMP(6) + INTERVAL '5' MINUTE)")) {
            insert.setString(1, taskName);
            insert.setLong(2, day);
            insert.setBytes(3, AccountService.uuidBytes(proposedId));
            inserted = insert.executeUpdate();
        }
        if (inserted == 1) {
            return new Acquisition(day, proposedId, false);
        }
        try (PreparedStatement state =
                connection.prepareStatement(
                        "SELECT status, run_id, lease_until <= CURRENT_TIMESTAMP(6) FROM"
                                + " scheduled_task_run WHERE task_name = ? AND game_day = ? FOR"
                                + " UPDATE")) {
            state.setString(1, taskName);
            state.setLong(2, day);
            try (ResultSet rows = state.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Scheduled settlement task disappeared");
                }
                String status = rows.getString(1);
                if ("SUCCEEDED".equals(status)) {
                    return new Acquisition(day, null, true);
                }
                if ("RUNNING".equals(status) && !rows.getBoolean(3)) {
                    return null;
                }
                UUID existingId = AccountService.bytesUuid(rows.getBytes(2));
                try (PreparedStatement retry =
                        connection.prepareStatement(
                                "UPDATE scheduled_task_run SET status = 'RUNNING', attempt_count ="
                                    + " attempt_count + 1, started_at = CURRENT_TIMESTAMP(6),"
                                    + " updated_at = CURRENT_TIMESTAMP(6), lease_until ="
                                    + " CURRENT_TIMESTAMP(6) + INTERVAL '5' MINUTE, completed_at ="
                                    + " NULL, failure_code = NULL WHERE task_name = ? AND game_day"
                                    + " = ?")) {
                    retry.setString(1, taskName);
                    retry.setLong(2, day);
                    retry.executeUpdate();
                }
                return new Acquisition(day, existingId, false);
            }
        }
    }

    private boolean allStatisticsServersClosed(Connection connection, long day)
            throws SQLException {
        byte[] expectedHash = RuleManager.dayHash(connection, day, StatisticsService.ruleHash());
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT config_hash FROM statistics_day_close WHERE server_id = ? AND"
                                + " game_day = ?")) {
            for (String serverId : config.statisticsServers) {
                statement.setString(1, serverId);
                statement.setLong(2, day);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        long now = System.nanoTime();
                        if (now >= nextMissingCloseNotice) {
                            nextMissingCloseNotice =
                                    now + java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
                            ContributionMod.LOGGER.warn(
                                    "INDUSTRY_WAITING_FOR_CLOSE day={} missingServer={}"
                                            + " configuredServers={}",
                                    day,
                                    serverId,
                                    java.util.Arrays.toString(config.statisticsServers));
                        }
                        return false;
                    }
                    if (!MessageDigest.isEqual(expectedHash, rows.getBytes(1))) {
                        throw new SQLException(
                                "Statistics configuration differs on server "
                                        + serverId
                                        + " for game day "
                                        + day);
                    }
                }
            }
        }
        return true;
    }

    private void settle(Connection connection, long day, UUID runId) throws SQLException {
        String taskName = "industry_daily_settlement";
        try (PreparedStatement state =
                connection.prepareStatement(
                        "SELECT status, run_id FROM scheduled_task_run "
                                + "WHERE task_name = ? AND game_day = ? FOR UPDATE")) {
            state.setString(1, taskName);
            state.setLong(2, day);
            try (ResultSet rows = state.executeQuery()) {
                if (!rows.next()
                        || !"RUNNING".equals(rows.getString(1))
                        || !runId.equals(AccountService.bytesUuid(rows.getBytes(2)))) {
                    throw new SQLException("Scheduled settlement lease was not held");
                }
            }
        }
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            settleIndustry(connection, day, industry);
        }
        try (PreparedStatement done =
                connection.prepareStatement(
                        "UPDATE scheduled_task_run SET status = 'SUCCEEDED', updated_at ="
                                + " CURRENT_TIMESTAMP(6), completed_at = CURRENT_TIMESTAMP(6),"
                                + " lease_until = NULL, failure_code = NULL WHERE task_name = ? AND"
                                + " game_day = ?")) {
            done.setString(1, taskName);
            done.setLong(2, day);
            done.executeUpdate();
        }
    }

    void settleIndustry(Connection connection, long day, BuiltInIndustry industry)
            throws SQLException {
        String id = "contribution:" + industry.path();
        byte[] hash = RuleManager.dayHash(connection, day, StatisticsService.ruleHash());
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT IGNORE INTO industry_state (industry_id, last_settled_game_day,"
                            + " total_development, long_ema, short_ema, prosperity, config_version,"
                            + " config_hash, updated_at) VALUES (?, NULL, 0, NULL, NULL, 0, 1, ?,"
                            + " CURRENT_TIMESTAMP(6))")) {
            insert.setString(1, id);
            insert.setBytes(2, hash);
            insert.executeUpdate();
        }
        long total;
        Long last;
        BigDecimal oldLong;
        BigDecimal oldShort;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT last_settled_game_day, total_development, long_ema, short_ema "
                                + "FROM industry_state WHERE industry_id = ? FOR UPDATE")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                long value = rows.getLong(1);
                last = rows.wasNull() ? null : value;
                total = rows.getLong(2);
                oldLong = rows.getBigDecimal(3);
                oldShort = rows.getBigDecimal(4);
            }
        }
        if (last != null && last >= day) {
            return;
        }
        long daily = 0;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT development FROM industry_day_accumulator WHERE game_day = ? AND"
                                + " industry_id = ?")) {
            query.setLong(1, day);
            query.setString(2, id);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    daily = rows.getLong(1);
                }
            }
        }
        BigDecimal x = BigDecimal.valueOf(daily);
        BigDecimal nextLong = null;
        BigDecimal nextShort = null;
        BigDecimal prosperity = BigDecimal.ZERO;
        // The deployment day may be partial. Keep its totals but initialize EMA from two subsequent
        // full days.
        long firstDay = RuleManager.firstDay(connection);
        boolean hasEpoch;
        try (var query =
                        connection.prepareStatement(
                                "SELECT EXISTS(SELECT 1 FROM contribution_rule_epoch)");
                var rows = query.executeQuery()) {
            rows.next();
            hasEpoch = rows.getBoolean(1);
        }
        if (last != null && (!hasEpoch || day > firstDay + 1)) {
            if (oldLong == null) {
                long previous = previousDaily(connection, id, last);
                nextLong =
                        x.add(BigDecimal.valueOf(previous))
                                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
                nextShort = nextLong;
            } else {
                nextLong =
                        oldLong.multiply(LONG_KEEP)
                                .add(x.multiply(new BigDecimal("0.01")))
                                .setScale(8, RoundingMode.HALF_UP);
                nextShort =
                        oldShort.multiply(SHORT_KEEP)
                                .add(x.multiply(new BigDecimal("0.2")))
                                .setScale(8, RoundingMode.HALF_UP);
            }
            prosperity = IndustryProsperity.calculate(daily, nextLong, nextShort);
        }
        long nextTotal = StatisticMath.add(total, daily);
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE industry_state SET last_settled_game_day = ?, total_development ="
                            + " ?, long_ema = ?, short_ema = ?, prosperity = ?, config_version = 1,"
                            + " config_hash = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE"
                            + " industry_id = ?")) {
            update.setLong(1, day);
            update.setLong(2, nextTotal);
            update.setBigDecimal(3, nextLong);
            update.setBigDecimal(4, nextShort);
            update.setBigDecimal(5, prosperity);
            update.setBytes(6, hash);
            update.setString(7, id);
            update.executeUpdate();
        }
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO industry_daily (game_day, industry_id, daily_development,"
                            + " total_development, long_ema, short_ema, prosperity, config_version,"
                            + " config_hash, prosperity_version, settled_at) VALUES (?, ?, ?, ?, ?,"
                            + " ?, ?, 1, ?, 2, CURRENT_TIMESTAMP(6))")) {
            insert.setLong(1, day);
            insert.setString(2, id);
            insert.setLong(3, daily);
            insert.setLong(4, nextTotal);
            insert.setBigDecimal(5, nextLong);
            insert.setBigDecimal(6, nextShort);
            insert.setBigDecimal(7, prosperity);
            insert.setBytes(8, hash);
            insert.executeUpdate();
        }
    }

    private static long previousDaily(Connection connection, String industry, long day)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT daily_development FROM industry_daily WHERE game_day = ? AND"
                                + " industry_id = ?")) {
            query.setLong(1, day);
            query.setString(2, industry);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private record Acquisition(long day, UUID runId, boolean completed) {}
}
