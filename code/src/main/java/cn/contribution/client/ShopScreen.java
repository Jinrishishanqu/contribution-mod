package cn.contribution.client;

import cn.contribution.reward.DeliveryService;
import cn.contribution.shop.ShopUiNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Client-only catalog; all prices and purchases are validated again by the server. */
final class ShopScreen extends Screen {
    private static final int BACKGROUND = 0xF0181712;
    private static final int PANEL = 0xF02C2820;
    private static final int EDGE = 0xFF806A43;
    private static final int TEXT = 0xFFF4EBD5;
    private static final int MUTED = 0xFFBAAB8B;
    private static final int GOLD = 0xFFFFD36C;
    private final ShopUiNetwork.Snapshot snapshot;
    private EditBox search;
    private EditBox quantity;
    private int selected;
    private int scroll;
    private int sort;
    private Button sortButton;

    private ShopScreen(ShopUiNetwork.Snapshot snapshot) {
        super(Component.literal("服务器商店"));
        this.snapshot = snapshot;
    }

    static void receive(ShopUiNetwork.Snapshot snapshot) {
        Minecraft.getInstance().gui.setScreen(new ShopScreen(snapshot));
    }

    @Override protected void init() {
        int left = Math.max(12, width / 2 - 170);
        search = addRenderableWidget(new EditBox(font, left + 10, 54, 155, 19, Component.literal("搜索商品")));
        search.setHint(Component.literal("搜索名称或物品"));
        search.setMaxLength(64);
        sortButton = addRenderableWidget(Button.builder(Component.literal("名称排序"), button -> {
            sort = (sort + 1) % 3;
            sortButton.setMessage(Component.literal(sort == 0 ? "名称排序" : sort == 1 ? "价格从低到高" : "价格从高到低"));
            scroll = 0;
        }).bounds(left + 172, 54, 116, 19).build());
        quantity = addRenderableWidget(new EditBox(font, left + 205, height - 71, 40, 19, Component.literal("份数")));
        quantity.setValue("1"); quantity.setMaxLength(2);
        addRenderableWidget(Button.builder(Component.literal("购买所选"), button -> buy())
                .bounds(left + 251, height - 71, 83, 19).build());
        addRenderableWidget(Button.builder(Component.literal("领取待发物品"), button -> command("shop claim"))
                .bounds(left + 205, height - 45, 129, 19).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> command("shop"))
                .bounds(left + 10, height - 45, 55, 19).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(left + 72, height - 45, 55, 19).build());
    }

    private List<ShopUiNetwork.Offer> visible() {
        String term = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        List<ShopUiNetwork.Offer> result = new ArrayList<>();
        for (var offer : snapshot.offers()) if (term.isEmpty() || offer.name().toLowerCase(Locale.ROOT).contains(term)
                || offer.itemId().contains(term)) result.add(offer);
        result.sort(switch (sort) {
            case 1 -> Comparator.comparingInt(ShopUiNetwork.Offer::price);
            case 2 -> Comparator.comparingInt(ShopUiNetwork.Offer::price).reversed();
            default -> Comparator.comparing(ShopUiNetwork.Offer::name);
        });
        return result;
    }

    private void buy() {
        List<ShopUiNetwork.Offer> rows = visible();
        if (selected < 0 || selected >= rows.size()) return;
        try {
            int amount = Integer.parseInt(quantity.getValue());
            if (amount >= 1 && amount <= 64) command("shop buy " + rows.get(selected).id() + " " + amount);
        } catch (NumberFormatException ignored) { }
    }

    private static void command(String command) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) listener.sendCommand(command);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int left = Math.max(12, width / 2 - 170);
        if (event.button() != 0 || event.x() < left + 8 || event.x() > left + 192
                || event.y() < 84 || event.y() > height - 82) return false;
        int index = scroll + ((int) event.y() - 84) / 30;
        if (index >= 0 && index < visible().size()) { selected = index; return true; }
        return false;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int rows = Math.max(1, (height - 175) / 30);
        scroll = Math.max(0, Math.min(Math.max(0, visible().size() - rows), scroll - (int) Math.signum(vertical)));
        return true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        int left = Math.max(12, width / 2 - 170), right = left + 340;
        graphics.fill(left, 15, right, height - 15, PANEL);
        graphics.outline(left, 15, 340, height - 30, EDGE);
        graphics.fill(left + 2, 17, right - 2, 44, 0xFF4D3D29);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, "✦  服务器商店", left + 12, 26, GOLD, true);
        graphics.text(font, "余额 " + snapshot.balance(), right - 91, 26, TEXT, false);
        graphics.text(font, "商品目录", left + 11, 78, MUTED, false);
        graphics.text(font, "点击商品查看详情", left + 209, 78, MUTED, false);
        List<ShopUiNetwork.Offer> rows = visible();
        int capacity = Math.max(1, (height - 175) / 30);
        selected = Math.min(selected, Math.max(0, rows.size() - 1));
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        for (int i = scroll; i < Math.min(rows.size(), scroll + capacity); i++) {
            var row = rows.get(i);
            int y = 84 + (i - scroll) * 30;
            graphics.fill(left + 8, y, left + 192, y + 28, i == selected ? 0xFF695331 : 0xFF3B3428);
            graphics.outline(left + 8, y, 184, 28, i == selected ? GOLD : EDGE);
            var item = DeliveryService.findItem(row.itemId());
            if (item != null) graphics.item(new ItemStack(item), left + 12, y + 6);
            graphics.text(font, font.plainSubstrByWidth(row.name(), 122), left + 34, y + 4, TEXT, false);
            graphics.text(font, row.itemCount() + " 个 · " + row.price() + " 贡献值", left + 34, y + 16, MUTED, false);
        }
        graphics.fill(left + 200, 84, right - 7, height - 82, 0xFF352F25);
        graphics.outline(left + 200, 84, 133, height - 166, EDGE);
        if (!rows.isEmpty()) {
            var row = rows.get(selected);
            var item = DeliveryService.findItem(row.itemId());
            if (item != null) graphics.item(new ItemStack(item), left + 257, 100);
            graphics.centeredText(font, row.name(), left + 266, 126, GOLD);
            graphics.text(font, "每份 " + row.itemCount() + " 个", left + 209, 151, TEXT, false);
            graphics.text(font, "价格 " + row.price() + " 贡献值", left + 209, 169, TEXT, false);
            graphics.text(font, font.plainSubstrByWidth(row.itemId(), 117), left + 209, 187, MUTED, false);
            graphics.text(font, "商品以服务器标价为准", left + 209, 210, MUTED, false);
        } else graphics.text(font, "没有符合条件的商品", left + 208, 103, MUTED, false);
        graphics.text(font, "份数", left + 205, height - 84, MUTED, false);
        graphics.text(font, "物品不足背包容量时会掉落在玩家附近", left + 10, height - 82, MUTED, false);
    }
}
