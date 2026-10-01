package cn.contribution.shop;

import cn.contribution.account.AccountService;
import cn.contribution.account.EconomyLedger;
import cn.contribution.config.RewardConfig;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.reward.DeliveryService;
import cn.contribution.reward.RewardConfigGuard;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ShopService {
    private final DatabaseService database;
    private final ServerConfig config;

    public ShopService(DatabaseService database, ServerConfig config) {
        this.database = database; this.config = config;
    }

    public List<RewardConfig.ShopOffer> offers() { return List.of(config.rewards.shopOffers); }

    public CompletableFuture<String> buy(UUID player, String offerId, int quantity, UUID orderId) {
        if (quantity < 1 || quantity > 64 || orderId == null) return CompletableFuture.completedFuture("购买数量无效");
        RewardConfig.ShopOffer offer = offers().stream().filter(value -> value.id.equals(offerId)).findFirst().orElse(null);
        if (offer == null || DeliveryService.findItem(offer.itemId) == null) return CompletableFuture.completedFuture("商品不存在或物品无效");
        long total = (long) offer.price * quantity;
        long count = (long) offer.itemCount * quantity;
        if (total > Integer.MAX_VALUE || count > 2304) return CompletableFuture.completedFuture("购买数量超过上限");
        return database.transaction(connection -> {
            RewardConfigGuard.require(connection, config);
            try (PreparedStatement replay = connection.prepareStatement(
                    "SELECT player_uuid, offer_id, quantity, total_price FROM shop_order WHERE order_id = ?")) {
                replay.setBytes(1, AccountService.uuidBytes(orderId));
                try (var row = replay.executeQuery()) {
                    if (row.next()) return java.util.Arrays.equals(row.getBytes(1), AccountService.uuidBytes(player))
                            && offer.id.equals(row.getString(2)) && quantity == row.getInt(3) && total == row.getInt(4)
                            ? "订单已提交，可领取物品" : "订单 ID 已用于其他请求";
                }
            }
            EconomyLedger.Result paid = EconomyLedger.change(connection, player, -(int) total, false,
                    "SHOP_BUY", "contribution:shop", "购买 " + offer.name, config.serverId, orderId);
            if (!paid.success()) return paid.message();
            UUID delivery = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO reward_delivery (delivery_id, player_uuid, item_id, item_count, source, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(6))")) {
                insert.setBytes(1, AccountService.uuidBytes(delivery)); insert.setBytes(2, AccountService.uuidBytes(player));
                insert.setString(3, offer.itemId); insert.setInt(4, (int) count);
                insert.setString(5, "shop:" + offer.id); insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO shop_order (order_id, player_uuid, offer_id, quantity, total_price, delivery_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
                insert.setBytes(1, AccountService.uuidBytes(orderId)); insert.setBytes(2, AccountService.uuidBytes(player));
                insert.setString(3, offer.id); insert.setInt(4, quantity); insert.setInt(5, (int) total);
                insert.setBytes(6, AccountService.uuidBytes(delivery)); insert.executeUpdate();
            }
            return "购买成功，订单 " + orderId + " 已扣除 " + total + " 贡献值；物品已进入领取队列";
        });
    }
}
