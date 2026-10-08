package cn.contribution.shop;

import cn.contribution.api.AccountTarget;
import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.ui.ShopDialogs;

import com.google.gson.Gson;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps a player's catalog and management navigation in the selected UI mode. */
public final class ShopUiNetwork {
    private static final Gson JSON = new Gson();
    private static final Set<UUID> VANILLA = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<UUID, Long> REQUESTS = new ConcurrentHashMap<>();

    private ShopUiNetwork() {}

    public record Snapshot(
            String view,
            int balance,
            List<ShopOffer> offers,
            boolean admin,
            ShopOffer editing,
            String message) {}

    public static boolean admin(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
    }

    public static boolean clientUi(ServerPlayer player) {
        return !VANILLA.contains(player.getUUID())
                && ServerPlayNetworking.canSend(player, ShopSnapshotPayload.TYPE);
    }

    public static void forget(UUID player) {
        VANILLA.remove(player);
        REQUESTS.remove(player);
    }

    public static void clear() {
        VANILLA.clear();
        REQUESTS.clear();
    }

    public static void openDefault(CommandSourceStack source) {
        if (source.getPlayer() != null) VANILLA.remove(source.getPlayer().getUUID());
        open(source);
    }

    public static void openVanilla(CommandSourceStack source) {
        if (source.getPlayer() != null) VANILLA.add(source.getPlayer().getUUID());
        open(source);
    }

    public static void open(CommandSourceStack source) {
        catalog(source, "catalog");
    }

    public static void refresh(CommandSourceStack source) {
        catalog(source, "catalog_refresh");
    }

    public static void back(CommandSourceStack source) {
        catalog(source, "catalog_return");
    }

    public static void feedback(CommandSourceStack source, String message) {
        if (source.getPlayer() != null
                && !source.getPlayer().hasDisconnected()
                && clientUi(source.getPlayer()))
            send(
                    source.getPlayer(),
                    new Snapshot("notice", -1, List.of(), admin(source), null, message));
    }

    private static void catalog(CommandSourceStack source, String view) {
        var player = source.getPlayer();
        if (!ready(source)) return;
        if (!clientUi(player)) {
            ShopDialogs.page(source, 0);
            return;
        }
        long request = request(player);
        ContributionRuntime.shop()
                .offers(false)
                .thenCombine(
                        ContributionRuntime.accounts()
                                .account(AccountTarget.byUuid(player.getUUID())),
                        (offers, account) ->
                                new Snapshot(
                                        view,
                                        account.map(value -> value.balance()).orElse(-1),
                                        offers,
                                        admin(source),
                                        null,
                                        ""))
                .whenComplete(
                        (snapshot, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (!current(player, request)) return;
                                                    if (error != null || snapshot.balance() < 0)
                                                        failure(source);
                                                    else send(player, snapshot);
                                                }));
    }

    public static void management(CommandSourceStack source) {
        management(source, "");
    }

    public static void management(CommandSourceStack source, String message) {
        if (!ready(source) || !admin(source)) return;
        if (!clientUi(source.getPlayer())) {
            ShopDialogs.management(source, 0, message);
            return;
        }
        var player = source.getPlayer();
        long request = request(player);
        ContributionRuntime.shop()
                .offers(true)
                .whenComplete(
                        (offers, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (!current(player, request)) return;
                                                    if (error != null) failure(source);
                                                    else
                                                        send(
                                                                player,
                                                                new Snapshot(
                                                                        "admin", -1, offers, true,
                                                                        null, message));
                                                }));
    }

    public static void edit(CommandSourceStack source, long id, String message) {
        if (!ready(source) || !admin(source)) return;
        var player = source.getPlayer();
        long request = request(player);
        ContributionRuntime.shop()
                .offer(id)
                .whenComplete(
                        (offer, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (!current(player, request)) return;
                                                    if (error != null) failure(source);
                                                    else if (offer == null)
                                                        source.sendFailure(
                                                                Component.literal("商品不存在"));
                                                    else if (!clientUi(player))
                                                        ShopDialogs.editor(source, offer, message);
                                                    else
                                                        send(
                                                                player,
                                                                new Snapshot(
                                                                        "edit", -1, List.of(), true,
                                                                        offer, message));
                                                }));
    }

    public static void create(CommandSourceStack source) {
        if (!ready(source) || !admin(source)) return;
        request(source.getPlayer());
        if (!clientUi(source.getPlayer())) {
            ShopDialogs.editor(source, null, "");
            return;
        }
        send(source.getPlayer(), new Snapshot("create", -1, List.of(), true, null, ""));
    }

    private static long request(ServerPlayer player) {
        return REQUESTS.merge(player.getUUID(), 1L, Long::sum);
    }

    private static boolean current(ServerPlayer player, long request) {
        return !player.hasDisconnected() && REQUESTS.getOrDefault(player.getUUID(), 0L) == request;
    }

    private static boolean ready(CommandSourceStack source) {
        if (source.getPlayer() == null) {
            source.sendFailure(Component.literal("请在游戏内打开商店"));
            return false;
        }
        if (ContributionRuntime.shop() == null || ContributionRuntime.accounts() == null) {
            failure(source);
            return false;
        }
        return true;
    }

    private static void failure(CommandSourceStack source) {
        source.sendFailure(Component.literal("商店数据暂时不可用，请稍后重试"));
    }

    private static void send(ServerPlayer player, Snapshot snapshot) {
        ServerPlayNetworking.send(player, new ShopSnapshotPayload(JSON.toJson(snapshot)));
    }
}
