package cn.contribution.reward;

import cn.contribution.account.AccountService;
import cn.contribution.account.EconomyLedger;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real-clock playtime is aggregated in memory, then written off-thread once a minute. */
public final class CheckinService {
    private final DatabaseService database;
    private final ServerConfig config;
    private final ZoneId zone;
    private final OnlineTimeAccumulator onlineTime;
    private final Map<Key, Integer> pending = new HashMap<>();
    private long lastFlushMs;
    private long lastSampleMs;
    private CompletableFuture<java.util.List<UUID>> inFlight;

    public CheckinService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.config = config;
        this.zone = ZoneId.of(config.rewards.timeZone);
        this.onlineTime = new OnlineTimeAccumulator(zone);
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        long now = System.currentTimeMillis();
        // Join/leave callbacks retain exact cursors; reconcile membership at flush cadence.
        // A clock rollback must still reach the accumulator's existing reset protection.
        if (lastSampleMs != 0 && now >= lastSampleMs && now - lastSampleMs < 60_000) return;
        lastSampleMs = now;
        onlineTime
                .sample(
                        server.getPlayerList().getPlayers().stream()
                                .filter(
                                        player ->
                                                !cn.contribution.account.AccountIdentityService
                                                        .isBotName(player.getGameProfile().name()))
                                .map(ServerPlayer::getUUID)
                                .toList(),
                        now)
                .forEach(
                        (key, seconds) ->
                                pending.merge(
                                        new Key(key.player(), key.day()), seconds, Integer::sum));
        if (lastFlushMs == 0) lastFlushMs = now;
        if (now - lastFlushMs >= 60_000 && inFlight == null) flush(server);
    }

    public void joined(UUID player) {
        onlineTime.joined(player, System.currentTimeMillis());
    }

    public void left(UUID player) {
        onlineTime
                .left(player, System.currentTimeMillis())
                .forEach(
                        (key, seconds) ->
                                pending.merge(
                                        new Key(key.player(), key.day()), seconds, Integer::sum));
    }

    public void flush(MinecraftServer server) {
        if (pending.isEmpty() || inFlight != null) return;
        Map<Key, Integer> batch = new HashMap<>(pending);
        pending.clear();
        inFlight =
                database.transaction(
                        connection -> {
                            RewardConfigGuard.require(connection, config);
                            java.util.List<UUID> awarded = new java.util.ArrayList<>();
                            for (Map.Entry<Key, Integer> entry : batch.entrySet())
                                if (process(connection, entry.getKey(), entry.getValue()))
                                    awarded.add(entry.getKey().player());
                            return awarded;
                        });
        inFlight.whenComplete(
                (awarded, error) ->
                        server.execute(
                                () -> {
                                    if (error != null)
                                        batch.forEach(
                                                (key, seconds) ->
                                                        pending.merge(key, seconds, Integer::sum));
                                    else
                                        for (UUID id : awarded) {
                                            ServerPlayer player =
                                                    server.getPlayerList().getPlayer(id);
                                            if (player != null)
                                                player.sendSystemMessage(
                                                        net.minecraft.network.chat.Component
                                                                .literal("今日在线签到成功，奖励已发放"));
                                        }
                                    inFlight = null;
                                    lastFlushMs = System.currentTimeMillis();
                                }));
    }

    public void shutdown(MinecraftServer server) {
        onlineTime
                .sample(
                        server.getPlayerList().getPlayers().stream()
                                .filter(
                                        player ->
                                                !cn.contribution.account.AccountIdentityService
                                                        .isBotName(player.getGameProfile().name()))
                                .map(ServerPlayer::getUUID)
                                .toList(),
                        System.currentTimeMillis())
                .forEach(
                        (key, seconds) ->
                                pending.merge(
                                        new Key(key.player(), key.day()), seconds, Integer::sum));
        if (inFlight != null) inFlight.join();
        if (pending.isEmpty()) return;
        Map<Key, Integer> batch = new HashMap<>(pending);
        pending.clear();
        database.transaction(
                        connection -> {
                            RewardConfigGuard.require(connection, config);
                            for (Map.Entry<Key, Integer> entry : batch.entrySet())
                                process(connection, entry.getKey(), entry.getValue());
                            return null;
                        })
                .join();
    }

    boolean process(Connection connection, Key key, int increment) throws SQLException {
        byte[] uuid = AccountService.uuidBytes(key.player());
        try (PreparedStatement create =
                connection.prepareStatement(
                        "INSERT IGNORE INTO checkin_daily (player_uuid, calendar_day,"
                                + " online_seconds) VALUES (?, ?, 0)")) {
            create.setBytes(1, uuid);
            create.setDate(2, Date.valueOf(key.day()));
            create.executeUpdate();
        }
        int seconds;
        boolean claimed;
        try (PreparedStatement lock =
                connection.prepareStatement(
                        "SELECT online_seconds, claimed_at FROM checkin_daily WHERE player_uuid = ?"
                                + " AND calendar_day = ? FOR UPDATE")) {
            lock.setBytes(1, uuid);
            lock.setDate(2, Date.valueOf(key.day()));
            try (ResultSet row = lock.executeQuery()) {
                row.next();
                seconds = row.getInt(1);
                claimed = row.getTimestamp(2) != null;
            }
        }
        int updated = (int) Math.min(86_400L, (long) seconds + increment);
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE checkin_daily SET online_seconds = ? WHERE player_uuid = ? AND"
                                + " calendar_day = ?")) {
            update.setInt(1, updated);
            update.setBytes(2, uuid);
            update.setDate(3, Date.valueOf(key.day()));
            update.executeUpdate();
        }
        if (claimed || updated < config.rewards.dailyRequiredSeconds) return false;
        try (PreparedStatement create =
                connection.prepareStatement(
                        "INSERT IGNORE INTO checkin_player (player_uuid, cycle_day, updated_at)"
                                + " VALUES (?, 0, CURRENT_TIMESTAMP(6))")) {
            create.setBytes(1, uuid);
            create.executeUpdate();
        }
        LocalDate previous;
        int cycle;
        try (PreparedStatement lock =
                connection.prepareStatement(
                        "SELECT last_claim_day, cycle_day FROM checkin_player WHERE player_uuid = ?"
                                + " FOR UPDATE")) {
            lock.setBytes(1, uuid);
            try (ResultSet row = lock.executeQuery()) {
                row.next();
                Date date = row.getDate(1);
                previous = date == null ? null : date.toLocalDate();
                cycle = row.getInt(2);
            }
        }
        if (previous != null && !previous.isBefore(key.day())) return false;
        int nextCycle =
                previous != null && previous.plusDays(1).equals(key.day())
                        ? cycle % config.rewards.dailyCycleRewards.length + 1
                        : 1;
        int reward = config.rewards.dailyCycleRewards[nextCycle - 1];
        if (reward > 0) {
            EconomyLedger.Result paid =
                    EconomyLedger.change(
                            connection,
                            key.player(),
                            reward,
                            true,
                            "CHECK_IN",
                            "contribution:daily_checkin",
                            "每日签到第 " + nextCycle + " 天",
                            config.serverId,
                            UUID.randomUUID());
            if (!paid.success()) return false;
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE checkin_player SET last_claim_day = ?, cycle_day = ?, updated_at ="
                                + " CURRENT_TIMESTAMP(6) WHERE player_uuid = ?")) {
            update.setDate(1, Date.valueOf(key.day()));
            update.setInt(2, nextCycle);
            update.setBytes(3, uuid);
            update.executeUpdate();
        }
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE checkin_daily SET claimed_at = CURRENT_TIMESTAMP(6) WHERE"
                                + " player_uuid = ? AND calendar_day = ?")) {
            update.setBytes(1, uuid);
            update.setDate(2, Date.valueOf(key.day()));
            update.executeUpdate();
        }
        return true;
    }

    public CompletableFuture<String> status(UUID player) {
        LocalDate today = LocalDate.now(zone);
        return database.transaction(
                connection -> {
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT online_seconds, claimed_at FROM checkin_daily WHERE"
                                            + " player_uuid = ? AND calendar_day = ?")) {
                        query.setBytes(1, AccountService.uuidBytes(player));
                        query.setDate(2, Date.valueOf(today));
                        try (ResultSet row = query.executeQuery()) {
                            if (!row.next())
                                return "今日尚未累计在线；满 "
                                        + config.rewards.dailyRequiredSeconds / 60
                                        + " 分钟自动签到";
                            return "今日在线 "
                                    + row.getInt(1)
                                    + "/"
                                    + config.rewards.dailyRequiredSeconds
                                    + " 秒，"
                                    + (row.getTimestamp(2) == null ? "尚未签到" : "已签到");
                        }
                    }
                });
    }

    public ZoneId zone() {
        return zone;
    }

    record Key(UUID player, LocalDate day) {}
}
