package cn.contribution.shop;

import cn.contribution.api.AccountTarget;
import cn.contribution.runtime.ContributionRuntime;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class ShopUiNetwork {
    private static final Gson JSON = new Gson();
    private ShopUiNetwork() { }

    public static void open(CommandSourceStack source) {
        var player = source.getPlayer();
        var accounts = ContributionRuntime.accounts();
        if (player == null || accounts == null) return;
        accounts.account(AccountTarget.byUuid(player.getUUID())).whenComplete((account, error) -> source.getServer().execute(() -> {
            if (player.hasDisconnected()) return;
            if (error != null || account.isEmpty()) { source.sendFailure(Component.literal("商店账户暂时不可用")); return; }
            List<Offer> offers = ContributionRuntime.shop().offers().stream()
                    .map(value -> new Offer(value.id, value.name, value.itemId, value.itemCount, value.price)).toList();
            ServerPlayNetworking.send(player, new ShopSnapshotPayload(JSON.toJson(new Snapshot(account.get().balance(), offers))));
        }));
    }

    public record Offer(String id, String name, String itemId, int itemCount, int price) { }
    public record Snapshot(int balance, List<Offer> offers) { }
}
