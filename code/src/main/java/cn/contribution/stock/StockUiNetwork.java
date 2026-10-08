package cn.contribution.stock;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.ui.StockDialogs;

import com.google.gson.Gson;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Detects the optional client receiver; vanilla clients keep native dialogs. */
public final class StockUiNetwork {
    private static final Gson JSON = new Gson();
    private static final AtomicLong CLOCK_REVISION = new AtomicLong();

    static long nextClockRevision() {
        return CLOCK_REVISION.incrementAndGet();
    }

    private static final Set<UUID> FORCE_VANILLA = ConcurrentHashMap.newKeySet();

    private StockUiNetwork() {}

    public static void openDefault(CommandSourceStack source) {
        if (source.getPlayer() != null) FORCE_VANILLA.remove(source.getPlayer().getUUID());
        market(source);
    }

    public static void openVanilla(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("请在游戏内打开股票市场"));
            return;
        }
        FORCE_VANILLA.add(player.getUUID());
        StockDialogs.market(source, 0, "name", "all");
    }

    public static void forget(UUID player) {
        FORCE_VANILLA.remove(player);
    }

    private static boolean clientUi(ServerPlayer player) {
        return !FORCE_VANILLA.contains(player.getUUID())
                && ServerPlayNetworking.canSend(player, StockSnapshotPayload.TYPE);
    }

    public static void market(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("请在游戏内打开股票市场"));
            return;
        }
        if (!clientUi(player)) {
            StockDialogs.market(source, 0, "name", "all");
            return;
        }
        var service = ContributionRuntime.stocks();
        if (service == null) {
            source.sendFailure(Component.literal("股票市场尚未启动"));
            return;
        }
        long revision = nextClockRevision();
        service.dashboard(player.getUUID())
                .whenComplete(
                        (dashboard, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (player.hasDisconnected()) return;
                                                    if (error != null)
                                                        source.sendFailure(
                                                                Component.literal("股票数据暂时不可用"));
                                                    else
                                                        ServerPlayNetworking.send(
                                                                player,
                                                                new StockSnapshotPayload(
                                                                        JSON.toJson(
                                                                                new Snapshot(
                                                                                        "market",
                                                                                        dashboard,
                                                                                        null,
                                                                                        dashboard
                                                                                                        .market()
                                                                                                        .clockFresh()
                                                                                                ? dashboard
                                                                                                        .market()
                                                                                                        .clockDay()
                                                                                                : -1,
                                                                                        dashboard
                                                                                                        .market()
                                                                                                        .clockFresh()
                                                                                                ? dashboard
                                                                                                        .market()
                                                                                                        .clockTime()
                                                                                                : -1,
                                                                                        dashboard
                                                                                                .news(),
                                                                                        revision))));
                                                }));
    }

    public static void portfolio(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("请在游戏内查看持仓"));
            return;
        }
        if (!clientUi(player)) {
            StockDialogs.portfolio(source);
            return;
        }
        var service = ContributionRuntime.stocks();
        if (service == null) {
            source.sendFailure(Component.literal("股票市场尚未启动"));
            return;
        }
        long revision = nextClockRevision();
        service.dashboard(player.getUUID())
                .whenComplete(
                        (dashboard, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (player.hasDisconnected()) return;
                                                    if (error != null)
                                                        source.sendFailure(
                                                                Component.literal("个人持仓暂时不可用"));
                                                    else
                                                        ServerPlayNetworking.send(
                                                                player,
                                                                new StockSnapshotPayload(
                                                                        JSON.toJson(
                                                                                new Snapshot(
                                                                                        "profile",
                                                                                        dashboard,
                                                                                        null,
                                                                                        dashboard
                                                                                                        .market()
                                                                                                        .clockFresh()
                                                                                                ? dashboard
                                                                                                        .market()
                                                                                                        .clockDay()
                                                                                                : -1,
                                                                                        dashboard
                                                                                                        .market()
                                                                                                        .clockFresh()
                                                                                                ? dashboard
                                                                                                        .market()
                                                                                                        .clockTime()
                                                                                                : -1,
                                                                                        dashboard
                                                                                                .news(),
                                                                                        revision))));
                                                }));
    }

    public static void detail(CommandSourceStack source, String symbol, int days) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("请在游戏内查看股票"));
            return;
        }
        if (!clientUi(player)) {
            StockDialogs.detail(source, symbol, days);
            return;
        }
        var service = ContributionRuntime.stocks();
        if (service == null) {
            source.sendFailure(Component.literal("股票市场尚未启动"));
            return;
        }
        service.detail(player.getUUID(), symbol, days)
                .whenComplete(
                        (detail, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (player.hasDisconnected()) return;
                                                    if (error != null || detail == null)
                                                        source.sendFailure(
                                                                Component.literal("股票详情暂时不可用"));
                                                    else
                                                        ServerPlayNetworking.send(
                                                                player,
                                                                new StockSnapshotPayload(
                                                                        JSON.toJson(
                                                                                new Snapshot(
                                                                                        "detail",
                                                                                        null,
                                                                                        detail, -1,
                                                                                        -1,
                                                                                        null))));
                                                }));
    }

    public static void clock(
            ServerPlayer player, long day, int time, java.util.List<StockView.News> news) {
        clock(player, day, time, news, nextClockRevision());
    }

    static void clock(
            ServerPlayer player,
            long day,
            int time,
            java.util.List<StockView.News> news,
            long revision) {
        if (clientUi(player))
            ServerPlayNetworking.send(player, clockPayload(day, time, news, revision));
    }

    static void broadcastClock(
            java.util.List<ServerPlayer> players,
            long day,
            int time,
            java.util.List<StockView.News> news,
            long revision) {
        StockSnapshotPayload payload = null;
        for (ServerPlayer player : players) {
            if (!clientUi(player)) continue;
            if (payload == null) payload = clockPayload(day, time, news, revision);
            ServerPlayNetworking.send(player, payload);
        }
    }

    static StockSnapshotPayload clockPayload(
            long day, int time, java.util.List<StockView.News> news, long revision) {
        return new StockSnapshotPayload(
                JSON.toJson(new Snapshot("clock", null, null, day, time, news, revision)));
    }

    public record Snapshot(
            String view,
            StockView.Dashboard dashboard,
            StockView.Detail detail,
            long day,
            int time,
            java.util.List<StockView.News> news,
            long clockRevision) {
        public Snapshot(
                String view,
                StockView.Dashboard dashboard,
                StockView.Detail detail,
                long day,
                int time,
                java.util.List<StockView.News> news) {
            this(view, dashboard, detail, day, time, news, 0);
        }
    }
}
