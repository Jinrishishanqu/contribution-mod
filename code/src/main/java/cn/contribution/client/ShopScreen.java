package cn.contribution.client;

import cn.contribution.reward.DeliveryService;
import cn.contribution.shop.ShopOffer;
import cn.contribution.shop.ShopUiNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A snapshot-backed shop: real card widgets, cached filtering, and no database work while drawing. */
final class ShopScreen extends Screen {
    private static final int TEXT = 0xFFF4EBD5, MUTED = 0xFFBAAB8B, GOLD = 0xFFFFD36C;
    private final ShopUiNetwork.Snapshot snapshot;
    private final boolean management;
    private EditBox search, quantity;
    private List<ShopOffer> rows = List.of();
    private final List<ShopCardWidget> cards = new ArrayList<>();
    private long selectedId;
    private int scroll, sort;
    private String term = "", amount = "1";
    private Button purchase;
    private String message = "";

    private ShopScreen(ShopUiNetwork.Snapshot snapshot) {
        super(Component.literal(snapshot.view().equals("admin") ? "商品管理" : "服务器商店"));
        this.snapshot = snapshot;
        this.management = snapshot.view().equals("admin");
        this.message = snapshot.message();
    }
    static void receive(ShopUiNetwork.Snapshot snapshot) {
        var minecraft = Minecraft.getInstance();
        if (snapshot.view().equals("notice")) {
            if (minecraft.gui.screen() instanceof ShopAdminScreen editor) editor.feedback(snapshot.message());
            else if (minecraft.gui.screen() instanceof ShopScreen shop) shop.message = snapshot.message();
            return;
        }
        if (snapshot.view().equals("edit") || snapshot.view().equals("create")) {
            minecraft.gui.setScreen(new ShopAdminScreen(snapshot)); return;
        }
        if (snapshot.view().equals("catalog_refresh") && !(minecraft.gui.screen() instanceof ShopScreen)) return;
        ShopScreen next = new ShopScreen(snapshot);
        if ((snapshot.view().equals("catalog_refresh") || snapshot.view().equals("admin"))
                && minecraft.gui.screen() instanceof ShopScreen old && old.management == next.management) {
            next.term = old.search.getValue(); next.amount = old.quantity == null ? "1" : old.quantity.getValue();
            next.selectedId = old.selectedId; next.sort = old.sort; next.scroll = old.scroll;
        }
        minecraft.gui.setScreen(next);
    }
    private record Layout(int left, int right, int listRight, int detailLeft, int bottom) {
        int listWidth() { return listRight - left - 10; }
        int detailWidth() { return right - detailLeft - 10; }
    }
    private Layout layout() {
        int panelWidth = Math.min(460, width - 16);
        int left = (width - panelWidth) / 2, right = left + panelWidth;
        int listRight = left + panelWidth * 58 / 100;
        return new Layout(left, right, listRight, listRight + 10, height - 65);
    }
    private int capacity() { return Math.max(1, (layout().bottom - 86) / 44); }
    @Override protected void init() {
        Layout l = layout();
        cards.clear();
        search = addRenderableWidget(new EditBox(font, l.left + 10, 45, l.listWidth(), 18, Component.literal("搜索商品")));
        search.setMaxLength(64); search.setHint(Component.literal("搜索名称、编号或物品")); search.setValue(term);
        search.setResponder(value -> { term = value; scroll = 0; filter(); });
        addRenderableWidget(Button.builder(Component.literal(sortLabel()), button -> {
            sort = (sort + 1) % 4; button.setMessage(Component.literal(sortLabel())); scroll = 0; filter();
        }).bounds(l.detailLeft, 45, l.detailWidth(), 18).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> command(management ? "shop admin" : "shop refresh"))
                .bounds(l.right - 76, 17, 30, 18).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(l.right - 42, 17, 30, 18).build());
        if (snapshot.admin() && !management)
            addRenderableWidget(Button.builder(Component.literal("管理"), b -> command("shop admin"))
                    .bounds(l.right - 110, 17, 30, 18).build());
        int cardWidth = (l.listWidth() - 6) / 2;
        for (int slot = 0; slot < capacity() * 2; slot++) {
            final int index = slot;
            cards.add(addRenderableWidget(new ShopCardWidget(font, l.left + 10 + (slot % 2) * (cardWidth + 6),
                    86 + (slot / 2) * 44, cardWidth, 40, () -> cardOffer(index),
                    () -> cardOffer(index) != null && cardOffer(index).id() == selectedId,
                    () -> { ShopOffer offer = cardOffer(index); if (offer != null) selectedId = offer.id(); })));
        }
        if (!management) {
            quantity = addRenderableWidget(new EditBox(font, l.detailLeft + 27, height - 55, 32, 18, Component.literal("份数")));
            quantity.setMaxLength(2); quantity.setValue(amount);
        }
        purchase = addRenderableWidget(Button.builder(Component.literal(management ? "编辑商品" : "购买所选"), b -> act())
                .bounds(management ? l.detailLeft : l.detailLeft + 64, height - 55,
                        management ? l.detailWidth() : l.detailWidth() - 64, 18).build());
        addRenderableWidget(Button.builder(Component.literal(management ? "新建商品" : "领取待发物品"),
                b -> command(management ? "shop admin create" : "shop claim"))
                .bounds(l.detailLeft, height - 29, l.detailWidth(), 18).build());
        if (management) addRenderableWidget(Button.builder(Component.literal("返回商店"), b -> command("shop back"))
                .bounds(l.left + 10, height - 29, 68, 18).build());
        filter();
    }
    private static final java.util.Map<String, ItemStack> STACKS = new java.util.HashMap<>();
    static void clearPreviews() { STACKS.clear(); }
    static ItemStack preview(ShopOffer offer) {
        String spec = offer.itemSpec();
        if (STACKS.size() >= 512 && !STACKS.containsKey(spec)) STACKS.clear();
        return STACKS.computeIfAbsent(spec, key -> {
            var level = Minecraft.getInstance().level;
            if (level == null) return ItemStack.EMPTY;
            try { return cn.contribution.shop.ItemStackSpec.parse(level.registryAccess(), key); }
            catch (Exception invalid) { return ItemStack.EMPTY; }
        });
    }
    private String sortLabel() {
        return switch (sort) { case 1 -> "名称排序"; case 2 -> "价格从低到高"; case 3 -> "价格从高到低"; default -> "商品排序"; };
    }
    private void filter() {
        String query = term.strip().toLowerCase(Locale.ROOT);
        Comparator<ShopOffer> order = switch (sort) {
            case 1 -> Comparator.comparing(ShopOffer::name);
            case 2 -> Comparator.comparingInt(ShopOffer::price);
            case 3 -> Comparator.comparingInt(ShopOffer::price).reversed();
            default -> Comparator.comparingInt(ShopOffer::sortOrder);
        };
        rows = snapshot.offers().stream().filter(offer -> query.isEmpty() || offer.name().toLowerCase(Locale.ROOT).contains(query)
                || offer.itemId().contains(query) || Long.toString(offer.id()).equals(query))
                .sorted(order.thenComparingLong(ShopOffer::id)).toList();
        if (rows.stream().noneMatch(offer -> offer.id() == selectedId)) selectedId = rows.isEmpty() ? 0 : rows.getFirst().id();
        syncCards();
    }
    private ShopOffer cardOffer(int slot) {
        int index = scroll * 2 + slot;
        return index < rows.size() ? rows.get(index) : null;
    }
    private ShopOffer selected() { return rows.stream().filter(offer -> offer.id() == selectedId).findFirst().orElse(null); }
    private void syncCards() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, (rows.size() + 1) / 2 - capacity())));
        for (int slot = 0; slot < cards.size(); slot++) {
            var offer = cardOffer(slot);
            cards.get(slot).visible = offer != null; cards.get(slot).active = offer != null;
            cards.get(slot).setMessage(Component.literal(offer == null ? "" : "#" + offer.id() + " " + offer.name()));
        }
    }
    private void act() {
        ShopOffer chosen = selected(); if (chosen == null) return;
        if (management) { command("shop admin edit " + chosen.id()); return; }
        try {
            int count = Integer.parseInt(quantity.getValue());
            if (count >= 1 && count <= 64) command("shop buy " + chosen.id() + " " + count + " " + chosen.revision());
            else message = "份数需要填写 1—64";
        } catch (NumberFormatException invalid) { message = "份数需要填写 1—64"; }
    }
    static void command(String value) {
        var connection = Minecraft.getInstance().getConnection(); if (connection != null) connection.sendCommand(value);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        super.onClose();
        if (management) command("shop back");
    }
    @Override public void resize(int width, int height) {
        if (search != null) term = search.getValue(); if (quantity != null) amount = quantity.getValue();
        super.resize(width, height);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Layout l = layout();
        if (x >= l.left + 10 && x < l.listRight && y >= 86 && y < l.bottom) {
            scroll -= (int) Math.signum(vertical); syncCards(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        Layout l = layout();
        g.fill(0, 0, width, height, 0xF0181712); g.fill(l.left, 10, l.right, height - 6, 0xF02C2820);
        g.outline(l.left, 10, l.right - l.left, height - 16, 0xFF806A43);
        g.text(font, getTitle(), l.left + 10, 22, GOLD, false);
        if (!management) {
            String balance = "余额 " + snapshot.balance();
            int end = l.right - (snapshot.admin() ? 116 : 82);
            g.text(font, balance, end - font.width(balance), 22, TEXT, false);
        }
        g.text(font, "商品目录", l.left + 10, 71, MUTED, false);
        g.text(font, "选中商品", l.detailLeft, 71, MUTED, false);
        g.fill(l.detailLeft, 86, l.right - 10, l.bottom, 0xFF352F25);
        g.outline(l.detailLeft, 86, l.detailWidth(), l.bottom - 86, 0xFF806A43);
        ShopOffer chosen = selected();
        purchase.active = chosen != null;
        g.enableScissor(l.detailLeft + 1, 87, l.right - 11, l.bottom - 1);
        if (chosen != null) {
            var item = preview(chosen);
            if (!item.isEmpty()) g.item(item, l.detailLeft + 6, 92);
            g.text(font, font.plainSubstrByWidth(chosen.name(), l.detailWidth() - 32), l.detailLeft + 27, 94, GOLD, false);
            int textX = l.detailLeft + 7, textWidth = l.detailWidth() - 14;
            g.text(font, "#" + chosen.id() + (chosen.listed() ? " · 已上架" : " · 已下架"), textX, 113, MUTED, false);
            g.text(font, chosen.itemCount() + " 个 / 份 · " + chosen.price() + " 贡献值", textX, 127, TEXT, false);
            g.text(font, font.plainSubstrByWidth(chosen.itemId(), textWidth), textX, 141, MUTED, false);
            int lineY = 156;
            for (var line : font.split(Component.literal(chosen.description().isBlank() ? "暂无商品描述" : chosen.description()), textWidth)) {
                if (lineY + font.lineHeight > l.bottom - 4) break;
                g.text(font, line, textX, lineY, TEXT, false); lineY += font.lineHeight + 2;
            }
        } else g.text(font, "没有符合条件的商品", l.detailLeft + 7, 96, MUTED, false);
        g.disableScissor();
        int totalRows = (rows.size() + 1) / 2;
        if (totalRows > capacity()) {
            int track = capacity() * 44 - 4, thumb = Math.max(8, track * capacity() / totalRows);
            int thumbY = 86 + (track - thumb) * scroll / (totalRows - capacity());
            g.fill(l.listRight + 2, 86, l.listRight + 4, 86 + track, 0xFF352F25);
            g.fill(l.listRight + 2, thumbY, l.listRight + 4, thumbY + thumb, MUTED);
        }
        if (!management) {
            g.text(font, "份数", l.detailLeft, height - 50, MUTED, false);
            int lineY = height - 29;
            for (var line : font.split(Component.literal("背包放不下的物品会掉落在玩家附近"), l.listWidth())) {
                g.text(font, line, l.left + 10, lineY, MUTED, false); lineY += font.lineHeight + 1;
            }
        }
        if (!message.isEmpty()) g.text(font, font.plainSubstrByWidth(message, l.listWidth()),
                l.left + 10, height - 51, GOLD, false);
        super.extractRenderState(g, x, y, delta);
    }
}
