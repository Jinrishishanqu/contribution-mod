package cn.contribution.client;

import cn.contribution.items.WeaponSkinPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Local paginated gallery and enlarged confirmation; never changes inventory client-side. */
public final class WeaponSkinScreen extends Screen {
    private final String token;
    private final List<ItemStack> items;
    private final List<WeaponSkinCard> cards = new ArrayList<>();
    private int page;
    private int selected = -1;
    private WeaponSkinLayout layout;

    private WeaponSkinScreen(WeaponSkinPayload payload) {
        super(Component.literal(payload.title()));
        token = payload.token();
        items =
                payload.choices().stream()
                        .map(net.minecraft.world.item.ItemStackTemplate::create)
                        .toList();
    }

    public static void receive(WeaponSkinPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && !payload.choices().isEmpty())
            client.gui.setScreen(new WeaponSkinScreen(payload));
    }

    @Override
    protected void init() {
        cards.clear();
        layout = WeaponSkinLayout.of(width, height);
        page = Math.max(0, Math.min(page, (items.size() - 1) / layout.capacity()));
        int buttonWidth = Math.max(28, Math.min(140, (width - 32) / 3)), y = height - 28;
        if (selected >= 0) {
            addRenderableWidget(
                    Button.builder(Component.literal("返回列表"), b -> back())
                            .bounds(12, y, buttonWidth, 20)
                            .build());
            addRenderableWidget(
                    Button.builder(Component.literal("确认定型"), b -> confirm())
                            .bounds((width - buttonWidth) / 2, y, buttonWidth, 20)
                            .build());
        } else {
            for (int n = 0; n < layout.capacity(); n++) {
                int index = page * layout.capacity() + n;
                if (index >= items.size()) break;
                var card =
                        new WeaponSkinCard(
                                font,
                                layout.left() + n % layout.columns() * layout.cardWidth(),
                                layout.top() + n / layout.columns() * layout.cardHeight(),
                                layout.cardWidth() - 6,
                                layout.cardHeight() - 6,
                                items.get(index),
                                () -> {
                                    selected = index;
                                    rebuildWidgets();
                                });
                cards.add(card);
                addRenderableWidget(card);
            }
            var prev =
                    addRenderableWidget(
                            Button.builder(Component.literal("上一页"), b -> turn(-1))
                                    .bounds(12, y, buttonWidth, 20)
                                    .build());
            prev.active = page > 0;
            var next =
                    addRenderableWidget(
                            Button.builder(Component.literal("下一页"), b -> turn(1))
                                    .bounds((width - buttonWidth) / 2, y, buttonWidth, 20)
                                    .build());
            next.active = (page + 1) * layout.capacity() < items.size();
        }
        addRenderableWidget(
                Button.builder(Component.literal("关闭"), b -> minecraft.gui.setScreen(null))
                        .bounds(width - buttonWidth - 12, y, buttonWidth, 20)
                        .build());
    }

    private void back() {
        selected = -1;
        rebuildWidgets();
    }

    private void turn(int direction) {
        page += direction;
        rebuildWidgets();
    }

    private void confirm() {
        if (selected < 0 || selected >= items.size()) return;
        var connection = minecraft.getConnection();
        if (connection != null)
            connection.sendCommand("weapon_skin select " + token + " " + selected);
        minecraft.gui.setScreen(null);
    }

    @Override
    public void onClose() {
        if (selected >= 0) back();
        else super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (selected < 0 && vertical != 0) {
            int next = page + (vertical < 0 ? 1 : -1);
            if (next >= 0 && next * layout.capacity() < items.size()) {
                page = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    static void largeItem(GuiGraphicsExtractor g, ItemStack item, int x, int y, int side) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(side / 16.0f);
        g.item(item, 0, 0);
        g.pose().popMatrix();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xF01B2531);
        String label =
                selected >= 0
                        ? items.get(selected).getHoverName().getString()
                        : getTitle().getString()
                                + " · "
                                + (page + 1)
                                + "/"
                                + ((items.size() + layout.capacity() - 1) / layout.capacity());
        g.text(
                font,
                font.plainSubstrByWidth(label, Math.max(8, width - 24)),
                12,
                12,
                0xFFE6EDF5,
                false);
        if (selected >= 0) {
            int side = Math.max(16, Math.min(256, Math.min(width - 40, height - 88)));
            int left = (width - side) / 2, top = layout.top();
            g.enableScissor(0, top, width, height - 44);
            largeItem(g, items.get(selected), left, top, side);
            g.disableScissor();
            g.text(font, "确认后永久定型，不能重选", 12, height - 43, 0xFF9FADBF, false);
            if (x >= left && x < left + side && y >= top && y < top + side)
                g.setTooltipForNextFrame(font, items.get(selected), x, y);
        } else {
            int pages = (items.size() + layout.capacity() - 1) / layout.capacity();
            var bar =
                    new StockScrollbar(
                            layout.top(),
                            layout.top() + layout.rows() * layout.cardHeight(),
                            page,
                            pages - 1,
                            1);
            int barX = layout.left() + layout.columns() * layout.cardWidth() + 4;
            g.fill(barX, bar.top(), barX + 4, bar.bottom(), 0xFF455870);
            g.fill(barX, bar.thumbTop(), barX + 4, bar.thumbTop() + bar.thumbHeight(), 0xFF71D6EC);
        }
        super.extractRenderState(g, x, y, delta);
        for (var card : cards)
            if (card.isHoveredOrFocused()) {
                g.setTooltipForNextFrame(font, card.item, x, y);
                break;
            }
    }
}
