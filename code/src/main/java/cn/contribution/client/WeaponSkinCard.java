package cn.contribution.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;

final class WeaponSkinCard extends AbstractWidget {
    private final Font font;
    final ItemStack item;
    private final Runnable select;

    WeaponSkinCard(
            Font font, int x, int y, int width, int height, ItemStack item, Runnable select) {
        super(x, y, width, height, item.getHoverName());
        this.font = font;
        this.item = item;
        this.select = select;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        select.run();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(
                getX(),
                getY(),
                getRight(),
                getBottom(),
                isHoveredOrFocused() ? 0xFF344B63 : 0xFF253243);
        g.outline(getX(), getY(), width, height, isHoveredOrFocused() ? 0xFF71D6EC : 0xFF455870);
        int side = Math.max(8, Math.min(width - 12, height - 32));
        g.enableScissor(getX() + 2, getY() + 2, getRight() - 2, getBottom() - 26);
        WeaponSkinScreen.largeItem(g, item, getX() + (width - side) / 2, getY() + 5, side);
        g.disableScissor();
        var lines = font.split(getMessage(), Math.max(8, width - 10));
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            g.text(
                    font,
                    lines.get(i),
                    getX() + (width - font.width(lines.get(i))) / 2,
                    getBottom() - 23 + i * 10,
                    0xFFE6EDF5,
                    false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
