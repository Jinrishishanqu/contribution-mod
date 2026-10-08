package cn.contribution.reward;

import cn.contribution.account.AccountService;
import cn.contribution.database.DatabaseService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent reward mailbox; claim explicitly or when the player joins. */
public final class DeliveryService {
    private final DatabaseService database;
    private final Set<UUID> busy = new HashSet<>();

    public DeliveryService(DatabaseService database) {
        this.database = database;
    }

    public void claim(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!busy.add(uuid)) return;
        database.transaction(
                        connection -> {
                            try (PreparedStatement query =
                                    connection.prepareStatement(
                                            "SELECT delivery_id, item_id, item_count, item_spec"
                                                + " FROM reward_delivery WHERE player_uuid = ? AND"
                                                + " (status = 'PENDING' OR (status = 'CLAIMING' AND"
                                                + " lease_until < CURRENT_TIMESTAMP(6))) ORDER BY"
                                                + " created_at LIMIT 1 FOR UPDATE")) {
                                query.setBytes(1, AccountService.uuidBytes(uuid));
                                try (ResultSet row = query.executeQuery()) {
                                    if (!row.next()) return null;
                                    Delivery delivery =
                                            new Delivery(
                                                    AccountService.bytesUuid(row.getBytes(1)),
                                                    row.getString(4) == null
                                                            ? row.getString(2)
                                                            : row.getString(4),
                                                    row.getInt(3));
                                    try (PreparedStatement reserve =
                                            connection.prepareStatement(
                                                    "UPDATE reward_delivery SET status ="
                                                        + " 'CLAIMING', lease_until ="
                                                        + " TIMESTAMPADD(MINUTE, 2,"
                                                        + " CURRENT_TIMESTAMP(6)) WHERE delivery_id"
                                                        + " = ?")) {
                                        reserve.setBytes(
                                                1, AccountService.uuidBytes(delivery.id()));
                                        reserve.executeUpdate();
                                    }
                                    return delivery;
                                }
                            }
                        })
                .whenComplete(
                        (delivery, error) ->
                                player.level()
                                        .getServer()
                                        .execute(
                                                () -> {
                                                    if (error != null
                                                            || delivery == null
                                                            || player.hasDisconnected()) {
                                                        busy.remove(uuid);
                                                        return;
                                                    }
                                                    ItemStack prototype;
                                                    try {
                                                        prototype =
                                                                cn.contribution.shop.ItemStackSpec
                                                                        .parse(
                                                                                player.level()
                                                                                        .getServer()
                                                                                        .registryAccess(),
                                                                                delivery.itemId());
                                                    } catch (Exception invalid) {
                                                        cn.contribution.ContributionMod.LOGGER.warn(
                                                                "Invalid pending item {}; leaving"
                                                                        + " it queued",
                                                                delivery.id(),
                                                                invalid);
                                                        busy.remove(uuid);
                                                        return;
                                                    }
                                                    int remaining = delivery.count();
                                                    while (remaining > 0) {
                                                        int size =
                                                                Math.min(
                                                                        remaining,
                                                                        prototype
                                                                                .getMaxStackSize());
                                                        ItemStack stack =
                                                                prototype.copyWithCount(size);
                                                        player.getInventory().add(stack);
                                                        if (!stack.isEmpty())
                                                            player.drop(
                                                                    stack,
                                                                    false,
                                                                    net.minecraft.util.Prediction
                                                                            .SERVER_ONLY);
                                                        remaining -= size;
                                                    }
                                                    database.transaction(
                                                                    connection -> {
                                                                        try (PreparedStatement
                                                                                mark =
                                                                                        connection
                                                                                                .prepareStatement(
                                                                                                        "UPDATE"
                                                                                                            + " reward_delivery"
                                                                                                            + " SET status"
                                                                                                            + " = 'DELIVERED',"
                                                                                                            + " lease_until"
                                                                                                            + " = NULL,"
                                                                                                            + " delivered_at"
                                                                                                            + " = CURRENT_TIMESTAMP(6)"
                                                                                                            + " WHERE"
                                                                                                            + " delivery_id"
                                                                                                            + " = ? AND"
                                                                                                            + " status"
                                                                                                            + " = 'CLAIMING'")) {
                                                                            mark.setBytes(
                                                                                    1,
                                                                                    AccountService
                                                                                            .uuidBytes(
                                                                                                    delivery
                                                                                                            .id()));
                                                                            mark.executeUpdate();
                                                                        }
                                                                        return null;
                                                                    })
                                                            .whenComplete(
                                                                    (done, failure) ->
                                                                            player.level()
                                                                                    .getServer()
                                                                                    .execute(
                                                                                            () -> {
                                                                                                busy
                                                                                                        .remove(
                                                                                                                uuid);
                                                                                                if (failure
                                                                                                                == null
                                                                                                        && !player
                                                                                                                .hasDisconnected())
                                                                                                    claim(
                                                                                                            player);
                                                                                            }));
                                                }));
    }

    public static Item findItem(String id) {
        Identifier target = Identifier.tryParse(id);
        if (target == null) return null;
        return BuiltInRegistries.ITEM.getOptional(target).orElse(null);
    }

    private record Delivery(UUID id, String itemId, int count) {}
}
