package cn.contribution.client;

import cn.contribution.shop.ShopOffer;
import cn.contribution.shop.ShopUiNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.LinkedHashMap;
import java.util.Map;

/** Full product editor; the server authorizes and validates every submitted field. */
final class ShopAdminScreen extends Screen {
    private final ShopOffer offer;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, String> retained = new LinkedHashMap<>();
    private boolean listed;
    private String error = "";
    private int left, right, half;
    ShopAdminScreen(ShopUiNetwork.Snapshot snapshot) {
        super(Component.literal(snapshot.editing() == null ? "新建商品" : "编辑商品 #" + snapshot.editing().id()));
        offer = snapshot.editing(); listed = offer == null || offer.listed();
    }
    @Override protected void init() {
        fields.clear();
        int panel = Math.min(420, width - 24); left = (width - panel) / 2; right = left + panel; half = (panel - 30) / 2;
        field("name", "商品名称", offer == null ? "" : offer.name(), left + 10, 48, half, 64);
        field("item", "物品 ID", offer == null ? "minecraft:torch" : offer.itemId(), left + 20 + half, 48, half, 128);
        field("count", "每份数量（1—64）", offer == null ? "1" : "" + offer.itemCount(), left + 10, 85, half, 2);
        field("price", "售价", offer == null ? "1" : "" + offer.price(), left + 20 + half, 85, half, 10);
        field("order", "排序（小的在前）", offer == null ? "0" : "" + offer.sortOrder(), left + 10, 122, half, 11);
        addRenderableWidget(Button.builder(Component.literal(stateLabel()), button -> {
            listed = !listed; button.setMessage(Component.literal(stateLabel()));
        }).bounds(left + 20 + half, 136, half, 18).build());
        field("description", "描述（最多 512 字）", offer == null ? "" : offer.description(), left + 10, 159, right - left - 20, 512);
        if (offer != null) addRenderableWidget(Button.builder(Component.literal("重新读取"), b -> ShopScreen.command("shop admin edit " + offer.id()))
                .bounds(right - 75, 17, 65, 18).build());
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> save()).bounds(right - 90, height - 31, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回管理"), b -> onClose()).bounds(left + 10, height - 31, 80, 20).build());
    }
    private void field(String key, String label, String value, int x, int y, int width, int limit) {
        var box = addRenderableWidget(new EditBox(font, x, y + 14, width, 18, Component.literal(label)));
        box.setMaxLength(limit); box.setValue(retained.getOrDefault(key, value)); fields.put(key, box);
    }
    private String value(String key) { return fields.get(key).getValue(); }
    void feedback(String value) { error = value; }
    private String stateLabel() { return listed ? "状态：已上架（点击切换）" : "状态：已下架（点击切换）"; }
    static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private void save() {
        try {
            int count = Integer.parseInt(value("count")), price = Integer.parseInt(value("price"));
            int order = Integer.parseInt(value("order"));
            if (count < 1 || count > 64 || price < 1 || value("name").isBlank()) {
                error = "名称不能为空，数量为 1—64，售价必须为正整数"; return;
            }
            String command = offer == null ? "shop admin publish " : "shop admin save " + offer.id() + " " + offer.revision() + " ";
            ShopScreen.command(command + value("item").strip() + " " + quote(value("name")) + " " + count + " " + price
                    + " " + order + " " + listed + " " + quote(value("description")));
            error = "已提交，等待服务器确认";
        } catch (NumberFormatException invalid) { error = "数量、售价和排序需要填写整数"; }
    }
    @Override public void resize(int width, int height) {
        fields.forEach((key, field) -> retained.put(key, field.getValue())); super.resize(width, height);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { super.onClose(); ShopScreen.command("shop admin"); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xF0181712); g.fill(left, 10, right, height - 8, 0xF02C2820);
        g.text(font, getTitle(), left + 10, 22, 0xFFFFD36C, false);
        for (var field : fields.values()) g.text(font, field.getMessage(), field.getX(), field.getY() - 14, 0xFFBAAB8B, false);
        g.text(font, font.plainSubstrByWidth(error, right - left - 20), left + 10, height - 46, 0xFFFFD36C, false);
        super.extractRenderState(g, x, y, delta);
    }
}
