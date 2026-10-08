package cn.contribution.reward;

import cn.contribution.account.AccountService;
import cn.contribution.account.EconomyLedger;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Activity definitions and one claim per player and event, shared across servers. */
public final class EventCheckinService {
    private final DatabaseService database;
    private final ServerConfig config;
    private final ZoneId zone;

    public EventCheckinService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.config = config;
        this.zone = ZoneId.of(config.rewards.timeZone);
    }

    public CompletableFuture<String> create(Event event) {
        if (event == null
                || event.id == null
                || !event.id.matches("[a-z0-9_-]{1,64}")
                || event.title == null
                || event.title.isBlank()
                || event.title.codePointCount(0, event.title.length()) > 64
                || event.start == null
                || event.end == null
                || event.start.isAfter(event.end)
                || event.contribution < 0
                || event.itemCount < 0
                || event.itemCount > 2304
                || (event.itemCount > 0 && DeliveryService.findItem(event.itemId) == null)
                || (event.itemCount == 0 && event.itemId != null)
                || !EventRewardRegistry.known(event.rewardKey)
                || (event.rewardKey != null
                        && (event.rewardData == null || event.rewardData.length() > 512))
                || (event.contribution == 0 && event.itemCount == 0 && event.rewardKey == null))
            return CompletableFuture.completedFuture("活动参数或奖励无效");
        return database.transaction(
                connection -> {
                    RewardConfigGuard.require(connection, config);
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO checkin_event (event_id, title, starts_on,"
                                        + " ends_on, contribution_amount, item_id, item_count,"
                                        + " reward_key, reward_data, created_at) VALUES (?, ?, ?,"
                                        + " ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
                        insert.setString(1, event.id);
                        insert.setString(2, event.title);
                        insert.setDate(3, Date.valueOf(event.start));
                        insert.setDate(4, Date.valueOf(event.end));
                        insert.setInt(5, event.contribution);
                        insert.setString(6, event.itemId);
                        insert.setInt(7, event.itemCount);
                        insert.setString(8, event.rewardKey);
                        insert.setString(9, event.rewardData);
                        insert.executeUpdate();
                        return "已创建签到活动 " + event.id;
                    }
                });
    }

    public CompletableFuture<List<Event>> list() {
        return database.transaction(
                connection -> {
                    RewardConfigGuard.require(connection, config);
                    List<Event> events = new ArrayList<>();
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT event_id, title, starts_on, ends_on,"
                                        + " contribution_amount, item_id, item_count, reward_key,"
                                        + " reward_data FROM checkin_event WHERE ends_on >= ? ORDER"
                                        + " BY starts_on, event_id LIMIT 50")) {
                        query.setDate(1, Date.valueOf(LocalDate.now(zone)));
                        try (ResultSet row = query.executeQuery()) {
                            while (row.next()) events.add(read(row));
                        }
                    }
                    return List.copyOf(events);
                });
    }

    public CompletableFuture<String> claim(UUID player, String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,64}"))
            return CompletableFuture.completedFuture("活动 ID 无效");
        LocalDate today = LocalDate.now(zone);
        return database.transaction(
                connection -> {
                    RewardConfigGuard.require(connection, config);
                    Event event;
                    try (PreparedStatement query =
                            connection.prepareStatement(
                                    "SELECT event_id, title, starts_on, ends_on,"
                                        + " contribution_amount, item_id, item_count, reward_key,"
                                        + " reward_data FROM checkin_event WHERE event_id = ?")) {
                        query.setString(1, id);
                        try (ResultSet row = query.executeQuery()) {
                            if (!row.next()) return "活动不存在";
                            event = read(row);
                        }
                    }
                    if (today.isBefore(event.start) || today.isAfter(event.end)) return "不在活动签到期限内";
                    try (PreparedStatement reserve =
                            connection.prepareStatement(
                                    "INSERT IGNORE INTO checkin_event_claim (event_id, player_uuid,"
                                            + " claimed_on) VALUES (?, ?, ?)")) {
                        reserve.setString(1, id);
                        reserve.setBytes(2, AccountService.uuidBytes(player));
                        reserve.setDate(3, Date.valueOf(today));
                        if (reserve.executeUpdate() == 0) return "这项活动已经签到";
                    }
                    UUID claimId = UUID.randomUUID();
                    if (event.contribution > 0) {
                        EconomyLedger.Result result =
                                EconomyLedger.change(
                                        connection,
                                        player,
                                        event.contribution,
                                        true,
                                        "EVENT_CHECK_IN",
                                        "contribution:event/" + id,
                                        event.title,
                                        config.serverId,
                                        claimId);
                        if (!result.success()) return result.message();
                    }
                    if (event.itemCount > 0) {
                        try (PreparedStatement insert =
                                connection.prepareStatement(
                                        "INSERT INTO reward_delivery (delivery_id, player_uuid,"
                                            + " item_id, item_count, source, status, created_at)"
                                            + " VALUES (?, ?, ?, ?, ?, 'PENDING',"
                                            + " CURRENT_TIMESTAMP(6))")) {
                            insert.setBytes(1, AccountService.uuidBytes(UUID.randomUUID()));
                            insert.setBytes(2, AccountService.uuidBytes(player));
                            insert.setString(3, event.itemId);
                            insert.setInt(4, event.itemCount);
                            insert.setString(5, "event:" + id);
                            insert.executeUpdate();
                        }
                    }
                    EventRewardRegistry.apply(
                            event.rewardKey, connection, player, claimId, event.rewardData);
                    return "活动签到成功：" + event.title + "；奖励已发放或进入领取队列";
                });
    }

    private static Event read(ResultSet row) throws java.sql.SQLException {
        return new Event(
                row.getString(1),
                row.getString(2),
                row.getDate(3).toLocalDate(),
                row.getDate(4).toLocalDate(),
                row.getInt(5),
                row.getString(6),
                row.getInt(7),
                row.getString(8),
                row.getString(9));
    }

    public record Event(
            String id,
            String title,
            LocalDate start,
            LocalDate end,
            int contribution,
            String itemId,
            int itemCount,
            String rewardKey,
            String rewardData) {}
}
