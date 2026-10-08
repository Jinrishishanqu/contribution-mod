package cn.contribution.shop;

import cn.contribution.account.AccountService;
import cn.contribution.account.EconomyLedger;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.reward.DeliveryService;
import cn.contribution.reward.RewardConfigGuard;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Shared catalog operations and purchases; all database work runs on the bounded worker pool. */
public final class ShopService {
    private final DatabaseService database;
    private final ServerConfig config;

    public ShopService(DatabaseService database, ServerConfig config) {
        this.database = database;
        this.config = config;
    }

    public CompletableFuture<List<ShopOffer>> offers(boolean includeUnlisted) {
        return database.transaction(connection -> ShopCatalog.list(connection, includeUnlisted));
    }

    public CompletableFuture<ShopOffer> offer(long id) {
        return database.transaction(
                connection -> ShopCatalog.find(connection, Long.toString(id), false));
    }

    public CompletableFuture<ShopCatalog.Result> create(ShopCatalog.Change change) {
        if (DeliveryService.findItem(change.itemId()) == null)
            return CompletableFuture.completedFuture(new ShopCatalog.Result(false, "物品不存在", 0));
        return database.transaction(connection -> ShopCatalog.create(connection, change));
    }

    public CompletableFuture<ShopCatalog.Result> modify(
            long id, ShopCatalog.Change change, Long expectedRevision) {
        if (change.itemId() != null && DeliveryService.findItem(change.itemId()) == null)
            return CompletableFuture.completedFuture(new ShopCatalog.Result(false, "物品不存在", id));
        return database.transaction(
                connection -> ShopCatalog.modify(connection, id, change, expectedRevision));
    }

    public CompletableFuture<String> buy(UUID player, String offerId, int quantity, UUID orderId) {
        return buy(player, offerId, quantity, orderId, null);
    }

    public CompletableFuture<String> buy(
            UUID player, String offerId, int quantity, UUID orderId, Long expectedRevision) {
        if (quantity < 1 || quantity > 64 || orderId == null)
            return CompletableFuture.completedFuture("购买数量无效");
        return database.transaction(
                connection -> {
                    RewardConfigGuard.require(connection, config);
                    ShopOffer offer = ShopCatalog.find(connection, offerId, true);
                    try (PreparedStatement replay =
                            connection.prepareStatement(
                                    "SELECT player_uuid, offer_id, quantity FROM shop_order WHERE"
                                            + " order_id = ?")) {
                        replay.setBytes(1, AccountService.uuidBytes(orderId));
                        try (var row = replay.executeQuery()) {
                            if (row.next()) {
                                ShopOffer original =
                                        ShopCatalog.find(connection, row.getString(2), false);
                                boolean sameProduct =
                                        row.getString(2).equals(offerId)
                                                || (offer != null
                                                        && original != null
                                                        && offer.id() == original.id());
                                return java.util.Arrays.equals(
                                                        row.getBytes(1),
                                                        AccountService.uuidBytes(player))
                                                && sameProduct
                                                && quantity == row.getInt(3)
                                        ? "订单已提交，可领取物品"
                                        : "订单 ID 已用于其他请求";
                            }
                        }
                    }
                    if (offer == null || !offer.listed()) return "商品不存在或已经下架";
                    if (expectedRevision != null && expectedRevision != offer.revision())
                        return "商品信息已更新，请刷新商店后购买";
                    if (DeliveryService.findItem(offer.itemId()) == null) return "商品物品无效，请联系管理员";
                    long total = (long) offer.price() * quantity;
                    long count = (long) offer.itemCount() * quantity;
                    if (total > Integer.MAX_VALUE || count > 2304) return "购买数量超过上限";
                    EconomyLedger.Result paid =
                            EconomyLedger.change(
                                    connection,
                                    player,
                                    -(int) total,
                                    false,
                                    "SHOP_BUY",
                                    "contribution:shop",
                                    "商店购买",
                                    config.serverId,
                                    orderId);
                    if (!paid.success()) return paid.message();
                    UUID delivery = UUID.randomUUID();
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO reward_delivery (delivery_id, player_uuid,"
                                            + " item_id, item_count, source, item_spec, status,"
                                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING',"
                                            + " CURRENT_TIMESTAMP(6))")) {
                        insert.setBytes(1, AccountService.uuidBytes(delivery));
                        insert.setBytes(2, AccountService.uuidBytes(player));
                        insert.setString(3, offer.itemId());
                        insert.setInt(4, (int) count);
                        insert.setString(5, "shop:" + offer.id());
                        insert.setString(6, offer.itemSpec());
                        insert.executeUpdate();
                    }
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO shop_order (order_id, player_uuid, offer_id,"
                                        + " quantity, total_price, delivery_id, created_at) VALUES"
                                        + " (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))")) {
                        insert.setBytes(1, AccountService.uuidBytes(orderId));
                        insert.setBytes(2, AccountService.uuidBytes(player));
                        insert.setString(3, Long.toString(offer.id()));
                        insert.setInt(4, quantity);
                        insert.setInt(5, (int) total);
                        insert.setBytes(6, AccountService.uuidBytes(delivery));
                        insert.executeUpdate();
                    }
                    return "购买成功，商品 #"
                            + offer.id()
                            + " · "
                            + offer.name()
                            + "，扣除 "
                            + total
                            + " 贡献值；物品已进入领取队列";
                });
    }
}
