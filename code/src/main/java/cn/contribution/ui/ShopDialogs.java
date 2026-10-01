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
    private static final int PAGE_SIZE = 9;
    private ShopDialogs() { }

    public static void open(CommandSourceStack source) {
        if (source.getPlayer() == null) { source.sendFailure(Component.literal("请在游戏内打开商店")); return; }
        if (ContributionRuntime.shop() == null) { source.sendFailure(Component.literal("商店尚未启动")); return; }
        if (ServerPlayNetworking.canSend(source.getPlayer(), ShopSnapshotPayload.TYPE)) {
            ShopUiNetwork.open(source); return;
        }
        page(source, 0);
    }

    public static void page(CommandSourceStack source, int page) {
        if (source.getPlayer() == null) { source.sendFailure(Component.literal("请在游戏内打开商店")); return; }
        if (ContributionRuntime.shop() == null) { source.sendFailure(Component.literal("商店尚未启动")); return; }
        var offers = ContributionRuntime.shop().offers();
        int pages = Math.max(1, (offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 0 || page >= pages) { source.sendFailure(Component.literal("商店页码无效")); return; }
        List<String> lines = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        lines.add("第 " + (page + 1) + "/" + pages + " 页 · 点击购买 1 份；更多数量用 /shop buy <商品ID> <份数>");
        for (int index = page * PAGE_SIZE; index < Math.min(offers.size(), (page + 1) * PAGE_SIZE); index++) {
            var offer = offers.get(index);
            lines.add(offer.name + " [" + offer.id + "] · " + offer.itemCount + " 个 · " + offer.price + " 贡献值");
            buttons.add(new ActionButton(new CommonButtonData(Component.literal("购买 " + offer.name), 190),
                    Optional.of(new StaticAction(new ClickEvent.RunCommand("/shop buy " + offer.id + " 1")))));
        }
        if (page > 0) buttons.add(button("上一页", "/shop page " + (page - 1)));
        if (page + 1 < pages) buttons.add(button("下一页", "/shop page " + (page + 1)));
        buttons.add(button("领取待发物品", "/shop claim"));
        source.getPlayer().openDialog(Holder.direct(ContributionDialogs.create("服务器商店", lines, List.of(), buttons, false, 3)));
    }

    private static ActionButton button(String label, String command) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 190),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));
    }
}
