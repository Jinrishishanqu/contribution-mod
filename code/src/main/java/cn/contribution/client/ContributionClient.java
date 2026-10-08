package cn.contribution.client;

import cn.contribution.shop.ShopSnapshotPayload;
import cn.contribution.shop.ShopUiNetwork;
import cn.contribution.stock.StockSnapshotPayload;
import cn.contribution.stock.StockUiNetwork;
import cn.contribution.ui.ContributionSnapshotPayload;
import cn.contribution.ui.ContributionUiNetwork;

import com.google.gson.Gson;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Optional client-only entrypoint. Dedicated servers never load this package. */
public final class ContributionClient implements ClientModInitializer {
    private static final Gson JSON = new Gson();

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                cn.contribution.items.WeaponSkinPayload.TYPE,
                (payload, context) ->
                        context.client().execute(() -> WeaponSkinScreen.receive(payload)));
        cn.contribution.ContributionMod.LOGGER.info(
                "CONTRIBUTION_CLIENT_READY version={}",
                cn.contribution.network.VersionCompatibility.local().modVersion());
        ClientPlayNetworking.registerGlobalReceiver(
                cn.contribution.network.VersionPayload.TYPE,
                (payload, context) -> {
                    var local = cn.contribution.network.VersionCompatibility.local();
                    context.client()
                            .execute(
                                    () -> {
                                        if (!cn.contribution.network.VersionCompatibility.matches(
                                                local, payload))
                                            context.client()
                                                    .gui
                                                    .chatListener()
                                                    .handleSystemMessage(
                                                            net.minecraft.network.chat.Component
                                                                    .literal(
                                                                            cn.contribution.network
                                                                                    .VersionCompatibility
                                                                                    .notice(
                                                                                            local,
                                                                                            payload))
                                                                    .withStyle(
                                                                            net.minecraft
                                                                                    .ChatFormatting
                                                                                    .YELLOW),
                                                            false);
                                    });
                });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    StockScreen.clear();
                    ClientDiagnostics.disconnected();
                    ShopScreen.clearPreviews();
                });
        ClientPlayNetworking.registerGlobalReceiver(
                StockSnapshotPayload.TYPE,
                (payload, context) -> {
                    StockUiNetwork.Snapshot snapshot;
                    try {
                        snapshot = JSON.fromJson(payload.json(), StockUiNetwork.Snapshot.class);
                    } catch (RuntimeException invalid) {
                        ClientDiagnostics.invalid("stock", invalid);
                        return;
                    }
                    if (snapshot == null) return;
                    context.client()
                            .execute(
                                    () -> {
                                        ClientDiagnostics.stock(snapshot);
                                        StockScreen.receive(snapshot);
                                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(
                ShopSnapshotPayload.TYPE,
                (payload, context) -> {
                    ShopUiNetwork.Snapshot snapshot;
                    try {
                        snapshot = JSON.fromJson(payload.json(), ShopUiNetwork.Snapshot.class);
                    } catch (RuntimeException invalid) {
                        ClientDiagnostics.invalid("shop", invalid);
                        return;
                    }
                    if (snapshot == null) return;
                    context.client()
                            .execute(
                                    () -> {
                                        ClientDiagnostics.received("shop", payload.json().length());
                                        ShopScreen.receive(snapshot);
                                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(
                ContributionSnapshotPayload.TYPE,
                (payload, context) -> {
                    ContributionUiNetwork.Snapshot snapshot;
                    try {
                        snapshot =
                                JSON.fromJson(payload.json(), ContributionUiNetwork.Snapshot.class);
                    } catch (RuntimeException invalid) {
                        ClientDiagnostics.invalid("contribution", invalid);
                        return;
                    }
                    if (snapshot == null) return;
                    context.client()
                            .execute(
                                    () -> {
                                        ClientDiagnostics.received(
                                                "contribution", payload.json().length());
                                        ContributionScreen.receive(snapshot);
                                    });
                });
    }
}
