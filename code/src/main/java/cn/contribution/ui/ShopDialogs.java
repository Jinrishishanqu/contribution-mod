package cn.contribution.ui;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.shop.ShopSnapshotPayload;
import cn.contribution.shop.ShopUiNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.action.StaticAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Vanilla dialog fallback, with optional client-side catalog screen. */
public final class ShopDialogs {
    private ShopDialogs() { }

    public static void open(CommandSourceStack source) {
        if (source.getPlayer() == null) { source.sendFailure(Component.literal("请在游戏内打开商店")); return; }
        if (ContributionRuntime.shop() == null) { source.sendFailure(Component.literal("商店尚未启动")); return; }
        if (ServerPlayNetworking.canSend(source.getPlayer(), ShopSnapshotPayload.TYPE)) {
            ShopUiNetwork.open(source); return;
        }
        List<String> lines = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        lines.add("点击商品购买 1 份；输入数量可用 /shop buy <商品 ID> <数量>");
        for (var offer : ContributionRuntime.shop().offers()) {
            lines.add(offer.name + " · " + offer.itemCount + " 个 · " + offer.price + " 贡献值");
            buttons.add(new ActionButton(new CommonButtonData(Component.literal("购买 " + offer.name), 190),
                    Optional.of(new StaticAction(new ClickEvent.RunCommand("/shop buy " + offer.id + " 1")))));
        }
        buttons.add(new ActionButton(new CommonButtonData(Component.literal("领取待发物品"), 190),
                Optional.of(new StaticAction(new ClickEvent.RunCommand("/shop claim")))));
        source.getPlayer().openDialog(Holder.direct(ContributionDialogs.create("服务器商店", lines, List.of(), buttons, false, 3)));
    }
}
