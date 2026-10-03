package cn.contribution.client;

import cn.contribution.reward.DeliveryService;
import cn.contribution.shop.ShopOffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Registered widget, not a painted hit region: uses the game's standard click dispatch. */
final class ShopCardWidget extends AbstractWidget {
    private final Font font;
    private final Supplier<ShopOffer> offer;
    private final BooleanSupplier selected;
    private final Runnable select;
    ShopCardWidget(Font font, int x, int y, int width, int height, Supplier<ShopOffer> offer,
                   BooleanSupplier selected, Runnable select) {
        super(x, y, width, height, Component.empty());
        this.font = font; this.offer = offer; this.selected = selected; this.select = select;
    }
    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { select.run(); }
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        ShopOffer row = offer.get(); if (row == null) return;
        g.fill(getX(), getY(), getRight(), getBottom(), selected.getAsBoolean() ? 0xFF695331 : isHoveredOrFocused() ? 0xFF514431 : 0xFF3B3428);
        g.outline(getX(), getY(), width, height, selected.getAsBoolean() ? 0xFFFFD36C : 0xFF806A43);
        if (isHoveredOrFocused()) g.requestCursor(CursorTypes.POINTING_HAND);
        var item = ShopScreen.preview(row);
        if (!item.isEmpty()) g.item(item, getX() + 5, getY() + 6);
        g.text(font, font.plainSubstrByWidth(row.name(), width - 31), getX() + 26, getY() + 5, 0xFFF4EBD5, false);
        g.text(font, "#" + row.id() + (row.listed() ? "" : " 下架"), getX() + 26, getY() + 16, 0xFFBAAB8B, false);
        g.text(font, font.plainSubstrByWidth(row.itemCount() + "个 · " + row.price() + "贡献值", width - 10),
                getX() + 5, getY() + 28, 0xFFBAAB8B, false);
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
