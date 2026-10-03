package cn.contribution.ui;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.shop.ShopOffer;
import cn.contribution.shop.ShopUiNetwork;
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

/** Native dialog catalog and administrator editor for unmodified clients. */
public final class ShopDialogs {
    private static final int PAGE_SIZE = 8;
    private ShopDialogs() { }
    public static void open(CommandSourceStack source) { ShopUiNetwork.openDefault(source); }
    public static void openVanilla(CommandSourceStack source) { ShopUiNetwork.openVanilla(source); }
    public static void page(CommandSourceStack source, int page) { list(source, page, false, ""); }
    public static void management(CommandSourceStack source, int page, String message) {
        if (ShopUiNetwork.admin(source)) list(source, page, true, message);
    }
    private static void list(CommandSourceStack source, int page, boolean admin, String message) {
        if (source.getPlayer() == null || ContributionRuntime.shop() == null) return;
        ContributionRuntime.shop().offers(admin).whenComplete((offers, error) -> source.getServer().execute(() -> {
            if (source.getPlayer().hasDisconnected()) return;
            if (error != null) { source.sendFailure(Component.literal("商店数据暂时不可用")); return; }
            int pages = Math.max(1, (offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page < 0 || page >= pages) { source.sendFailure(Component.literal("商店页码无效")); return; }
            List<String> lines = new ArrayList<>();
            List<ActionButton> buttons = new ArrayList<>();
            if (!message.isEmpty()) lines.add(message);
            lines.add("第 " + (page + 1) + "/" + pages + " 页 · 商品编号跨服一致");
            for (var offer : offers.subList(page * PAGE_SIZE, Math.min(offers.size(), (page + 1) * PAGE_SIZE))) {
                lines.add("#" + offer.id() + " " + offer.name() + " · " + offer.itemCount() + " 个 · " + offer.price()
                        + " 贡献值" + (offer.listed() ? "" : " · 已下架")
                        + (offer.description().isBlank() ? "" : " · " + offer.description()));
                buttons.add(button((admin ? "编辑 #" : "购买 #") + offer.id() + " " + offer.name(),
                        admin ? "/shop admin edit " + offer.id() : "/shop buy " + offer.id() + " 1 " + offer.revision()));
            }
            String navigation = admin ? "/shop admin page " : "/shop page ";
            if (page > 0) buttons.add(button("上一页", navigation + (page - 1)));
            if (page + 1 < pages) buttons.add(button("下一页", navigation + (page + 1)));
            buttons.add(button("刷新", admin ? "/shop admin" : "/shop refresh"));
            if (admin) {
                buttons.add(button("新建商品", "/shop admin create")); buttons.add(button("返回商店", "/shop back"));
            } else {
                buttons.add(button("领取待发物品", "/shop claim"));
                if (ShopUiNetwork.admin(source)) buttons.add(button("商品管理", "/shop admin"));
                lines.add("更多份数：/shop buy <商品编号> <份数>；背包放不下的物品会掉落在玩家附近");
            }
            source.getPlayer().openDialog(Holder.direct(ContributionDialogs.create(admin ? "商品管理" : "服务器商店",
                    lines, List.of(), buttons, false, 2)));
        }));
    }
    public static void editor(CommandSourceStack source, ShopOffer offer, String message) {
        if (!ShopUiNetwork.admin(source) || source.getPlayer() == null) return;
        source.getPlayer().openDialog(Holder.direct(editorDialog(offer, message)));
    }
    static net.minecraft.server.dialog.Dialog editorDialog(ShopOffer offer, String message) {
        var inputs = List.of(
                ContributionDialogs.input("item", "物品 ID", offer == null ? "minecraft:torch" : offer.itemId(), 128),
                ContributionDialogs.input("name", "商品名称", offer == null ? "" : offer.name(), 64),
                ContributionDialogs.input("count", "每份数量（1—64）", offer == null ? "1" : "" + offer.itemCount(), 2),
                ContributionDialogs.input("price", "售价（正整数）", offer == null ? "1" : "" + offer.price(), 10),
                ContributionDialogs.input("order", "排序（小的在前）", offer == null ? "0" : "" + offer.sortOrder(), 11),
                ContributionDialogs.input("description", "描述（最多 512 字）", offer == null ? "" : offer.description(), 512));
        String prefix = offer == null ? "shop admin publish " : "shop admin save " + offer.id() + " " + offer.revision() + " ";
        String template = prefix + "$(item) \"$(name)\" $(count) $(price) $(order) ";
        return ContributionDialogs.create(
                offer == null ? "新建商品" : "编辑商品 #" + offer.id(),
                List.of("选择保存并上架或保存并下架。名称、描述中请不要输入双引号或反斜杠。", message),
                inputs, List.of(ContributionDialogs.template("保存并上架", template + "true \"$(description)\""),
                        ContributionDialogs.template("保存并下架", template + "false \"$(description)\""),
                        button("返回管理", "/shop admin")), false, 2);
    }
    private static ActionButton button(String label, String command) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 190),
                Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));
    }
}
