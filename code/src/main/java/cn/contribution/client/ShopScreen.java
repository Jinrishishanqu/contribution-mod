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
import com.mojang.blaze3d.platform.cursor.CursorTypes;

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
    private String selectedId;
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

    private record Layout(int left, int right, int listRight, int detailLeft, int contentBottom) { }

    private Layout layout() {
        int panelWidth = Math.min(340, width - 24);
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        int listRight = left + panelWidth * 56 / 100;
        return new Layout(left, right, listRight, listRight + 8, Math.max(112, height - 89));
    }

    private int capacity(Layout layout) { return Math.max(1, (layout.contentBottom() - 84) / 30); }

    private ShopUiNetwork.Offer selectedOffer(List<ShopUiNetwork.Offer> rows) {
        if (rows.isEmpty()) return null;
        for (var offer : rows) if (offer.id().equals(selectedId)) return offer;
        selectedId = rows.getFirst().id();
        return rows.getFirst();
    }

    @Override protected void init() {
        Layout layout = layout();
        int left = layout.left();
        int searchWidth = layout.listRight() - left - 17;
        search = addRenderableWidget(new EditBox(font, left + 10, 53, searchWidth, 19, Component.literal("搜索商品")));
        search.setHint(Component.literal("搜索名称或物品"));
        search.setMaxLength(64);
        sortButton = addRenderableWidget(Button.builder(Component.literal("名称排序"), button -> {
            sort = (sort + 1) % 3;
            sortButton.setMessage(Component.literal(sort == 0 ? "名称排序" : sort == 1 ? "价格从低到高" : "价格从高到低"));
            scroll = 0;
        }).bounds(layout.detailLeft(), 53, layout.right() - layout.detailLeft() - 9, 19).build());
        int inputX = layout.detailLeft() + 29;
        quantity = addRenderableWidget(new EditBox(font, inputX, height - 67, 34, 19, Component.literal("份数")));
        quantity.setValue("1"); quantity.setMaxLength(2);
        addRenderableWidget(Button.builder(Component.literal("购买所选"), button -> buy())
                .bounds(inputX + 39, height - 67, layout.right() - inputX - 47, 19).build());
        addRenderableWidget(Button.builder(Component.literal("领取待发物品"), button -> command("shop claim"))
                .bounds(layout.detailLeft(), height - 41, layout.right() - layout.detailLeft() - 9, 19).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> command("shop"))
                .bounds(left + 10, height - 41, 55, 19).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(left + 72, height - 41, 55, 19).build());
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
        ShopUiNetwork.Offer offer = selectedOffer(rows);
        if (offer == null) return;
        try {
            int amount = Integer.parseInt(quantity.getValue());
            if (amount >= 1 && amount <= 64) command("shop buy " + offer.id() + " " + amount);
        } catch (NumberFormatException ignored) { }
    }

    private static void command(String command) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) listener.sendCommand(command);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        Layout layout = layout();
        if (event.button() != 0 || event.x() < layout.left() + 8 || event.x() >= layout.listRight() - 2
                || event.y() < 84 || event.y() >= layout.contentBottom()) return false;
        int index = scroll + ((int) event.y() - 84) / 30;
        List<ShopUiNetwork.Offer> rows = visible();
        if (index >= 0 && index < rows.size()) { selectedId = rows.get(index).id(); return true; }
        return false;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        Layout layout = layout();
        if (mouseX < layout.left() + 8 || mouseX >= layout.listRight() || mouseY < 84
                || mouseY >= layout.contentBottom()) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scroll = Math.max(0, Math.min(Math.max(0, visible().size() - capacity(layout)),
                scroll - (int) Math.signum(vertical)));
        return true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        Layout layout = layout();
        int left = layout.left(), right = layout.right();
        graphics.fill(left, 15, right, height - 15, PANEL);
        graphics.outline(left, 15, right - left, height - 30, EDGE);
        graphics.fill(left + 2, 17, right - 2, 44, 0xFF4D3D29);
        graphics.text(font, "✦  服务器商店", left + 12, 26, GOLD, true);
        String balance = "余额 " + snapshot.balance();
        graphics.text(font, balance, right - 12 - font.width(balance), 26, TEXT, false);
        graphics.text(font, "商品目录", left + 10, 76, MUTED, false);
        graphics.text(font, "选中商品", layout.detailLeft(), 76, MUTED, false);
        List<ShopUiNetwork.Offer> rows = visible();
        ShopUiNetwork.Offer chosen = selectedOffer(rows);
        int capacity = capacity(layout);
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        for (int i = scroll; i < Math.min(rows.size(), scroll + capacity); i++) {
            var row = rows.get(i);
            int y = 84 + (i - scroll) * 30;
            boolean hovered = mouseX >= left + 8 && mouseX < layout.listRight() - 2
                    && mouseY >= y && mouseY < y + 28;
            graphics.fill(left + 8, y, layout.listRight() - 2, y + 28,
                    row == chosen ? 0xFF695331 : hovered ? 0xFF514431 : 0xFF3B3428);
            graphics.outline(left + 8, y, layout.listRight() - left - 10, 28, row == chosen ? GOLD : EDGE);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            var item = DeliveryService.findItem(row.itemId());
            if (item != null) graphics.item(new ItemStack(item), left + 12, y + 6);
            int textWidth = layout.listRight() - left - 40;
            graphics.text(font, font.plainSubstrByWidth(row.name(), textWidth), left + 34, y + 4, TEXT, false);
            graphics.text(font, font.plainSubstrByWidth(row.itemCount() + " 个 · " + row.price() + " 贡献值", textWidth),
                    left + 34, y + 16, MUTED, false);
        }
        graphics.fill(layout.detailLeft(), 84, right - 8, layout.contentBottom(), 0xFF352F25);
        graphics.outline(layout.detailLeft(), 84, right - layout.detailLeft() - 8,
                layout.contentBottom() - 84, EDGE);
        if (chosen != null) {
            var item = DeliveryService.findItem(chosen.itemId());
            if (item != null) graphics.item(new ItemStack(item), layout.detailLeft() + 7, 91);
            int detailTextX = layout.detailLeft() + 28;
            int detailWidth = right - detailTextX - 12;
            graphics.text(font, font.plainSubstrByWidth(chosen.name(), detailWidth), detailTextX, 94, GOLD, false);
            graphics.text(font, "每份 " + chosen.itemCount() + " 个", layout.detailLeft() + 7, 114, TEXT, false);
            graphics.text(font, "价格 " + chosen.price() + " 贡献值", layout.detailLeft() + 7, 130, TEXT, false);
            graphics.text(font, font.plainSubstrByWidth(chosen.itemId(), right - layout.detailLeft() - 19),
                    layout.detailLeft() + 7, 146, MUTED, false);
        } else graphics.text(font, "没有符合条件的商品", layout.detailLeft() + 7, 99, MUTED, false);
        graphics.text(font, font.plainSubstrByWidth("背包放不下的物品会掉落在玩家附近", right - left - 20),
                left + 10, height - 83, MUTED, false);
        graphics.text(font, "份数", layout.detailLeft(), height - 62, MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
