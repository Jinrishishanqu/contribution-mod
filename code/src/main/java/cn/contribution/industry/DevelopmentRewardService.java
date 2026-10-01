package cn.contribution.industry;

import cn.contribution.account.AccountService;
import cn.contribution.account.EconomyLedger;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.reward.RewardConfigGuard;
import net.minecraft.server.MinecraftServer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Incremental, off-thread payout scan. Only the main server schedules it. */
public final class DevelopmentRewardService {
    private final DatabaseService database;
    private final ServerConfig config;
    private byte[] cursor;
    private long nextRunMs;
    private CompletableFuture<Page> pending;

    public DevelopmentRewardService(DatabaseService database, ServerConfig config) {
        this.database = database; this.config = config;
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0 || pending != null || System.currentTimeMillis() < nextRunMs) return;
        byte[] start = cursor;
        pending = database.transaction(connection -> processPage(connection, start));
        pending.whenComplete((value, error) -> server.execute(() -> {
            pending = null;
            if (error != null) {
                nextRunMs = System.currentTimeMillis() + 30_000;
                return;
            }
            Page page = value;
            cursor = page.next();
            if (cursor == null) nextRunMs = System.currentTimeMillis() + config.rewards.developmentIntervalSeconds * 1000L;
        }));
    }

    public CompletableFuture<Void> settlePlayer(UUID player) {
        return database.transaction(connection -> {
            RewardConfigGuard.require(connection, config);
            pay(connection, AccountService.uuidBytes(player)); return null;
        });
    }

    private Page processPage(Connection connection, byte[] after) throws SQLException {
        RewardConfigGuard.require(connection, config);
        List<byte[]> players = new ArrayList<>();
        try (PreparedStatement list = connection.prepareStatement(
                "SELECT DISTINCT player_uuid FROM player_industry_stats "
                        + (after == null ? "" : "WHERE player_uuid > ? ") + "ORDER BY player_uuid LIMIT 32")) {
            if (after != null) list.setBytes(1, after);
            try (ResultSet rows = list.executeQuery()) {
                while (rows.next()) players.add(rows.getBytes(1));
            }
        }
        for (byte[] player : players) pay(connection, player);
        return new Page(players.size() < 32 ? null : players.getLast());
    }

    private void pay(Connection connection, byte[] player) throws SQLException {
        UUID uuid = AccountService.bytesUuid(player);
        Map<String, Long> counts = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT industry_id, development FROM player_industry_stats WHERE player_uuid = ?")) {
            query.setBytes(1, player);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
            }
        }
        BigDecimal expectedValue = BigDecimal.ZERO;
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            long points = counts.getOrDefault("contribution:" + industry.path(), 0L);
            expectedValue = expectedValue.add(new BigDecimal(points)
                    .multiply(new BigDecimal(config.rewards.developmentWeights.get(industry.path()))));
        }
        long expected = expectedValue.setScale(0, RoundingMode.FLOOR).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
        try (PreparedStatement create = connection.prepareStatement(
                "INSERT IGNORE INTO player_development_reward (player_uuid, paid_contribution, updated_at) VALUES (?, 0, CURRENT_TIMESTAMP(6))")) {
            create.setBytes(1, player); create.executeUpdate();
        }
        long paid;
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT paid_contribution FROM player_development_reward WHERE player_uuid = ? FOR UPDATE")) {
            lock.setBytes(1, player);
            try (ResultSet row = lock.executeQuery()) { row.next(); paid = row.getLong(1); }
        }
        if (expected <= paid) return;
        int balance, income;
        try (PreparedStatement account = connection.prepareStatement(
                "SELECT balance, total_income FROM contribution_account WHERE player_uuid = ?")) {
            account.setBytes(1, player);
            try (ResultSet row = account.executeQuery()) {
                if (!row.next()) return;
                balance = row.getInt(1); income = row.getInt(2);
            }
        }
        long room = Math.min(Integer.MAX_VALUE - (long) balance, Integer.MAX_VALUE - (long) income);
        int grant = (int) Math.min(Math.min(expected - paid, room), Integer.MAX_VALUE);
        if (grant <= 0) return;
        EconomyLedger.Result result = EconomyLedger.change(connection, uuid, grant, true, "DEVELOP",
                "contribution:development", "服务器建设", config.serverId, UUID.randomUUID());
        if (!result.success()) return;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE player_development_reward SET paid_contribution = paid_contribution + ?, updated_at = CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setInt(1, grant); update.setBytes(2, player); update.executeUpdate();
        }
    }

    private record Page(byte[] next) { }
}
