package cn.contribution.client;

import cn.contribution.stock.StockSnapshotPayload;
import cn.contribution.stock.StockUiNetwork;
import cn.contribution.shop.ShopSnapshotPayload;
import cn.contribution.shop.ShopUiNetwork;
import cn.contribution.ui.ContributionSnapshotPayload;
import cn.contribution.ui.ContributionUiNetwork;
import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Optional client-only entrypoint. Dedicated servers never load this package. */
public final class ContributionClient implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    @Override public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> StockScreen.clear());
        ClientPlayNetworking.registerGlobalReceiver(StockSnapshotPayload.TYPE, (payload, context) -> {
            StockUiNetwork.Snapshot snapshot;
            try { snapshot = JSON.fromJson(payload.json(), StockUiNetwork.Snapshot.class); }
            catch (RuntimeException invalid) { return; }
            if (snapshot == null) return;
            context.client().execute(() -> StockScreen.receive(snapshot));
        });
        ClientPlayNetworking.registerGlobalReceiver(ShopSnapshotPayload.TYPE, (payload, context) -> {
            ShopUiNetwork.Snapshot snapshot;
            try { snapshot = JSON.fromJson(payload.json(), ShopUiNetwork.Snapshot.class); }
            catch (RuntimeException invalid) { return; }
            if (snapshot == null) return;
            context.client().execute(() -> ShopScreen.receive(snapshot));
        });
        ClientPlayNetworking.registerGlobalReceiver(ContributionSnapshotPayload.TYPE, (payload, context) -> {
            ContributionUiNetwork.Snapshot snapshot;
            try { snapshot = JSON.fromJson(payload.json(), ContributionUiNetwork.Snapshot.class); }
            catch (RuntimeException invalid) { return; }
            if (snapshot == null) return;
            context.client().execute(() -> ContributionScreen.receive(snapshot));
        });
    }
}
