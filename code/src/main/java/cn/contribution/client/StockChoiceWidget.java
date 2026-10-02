package cn.contribution.client;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** One stable checkbox widget for market rows and industry filter options. */
final class StockChoiceWidget extends AbstractWidget {
    private static final int BORDER = 0xFF9AA8BC;
    private static final int TEXT = 0xFFE7EBF2;
    private static final int CHECKED = 0xFF57D78C;

    private final Font font;
    private final BooleanSupplier selected;
    private final Runnable toggle;

    StockChoiceWidget(Font font, int x, int y, int width, int height, Component label,
                      BooleanSupplier selected, Runnable toggle) {
        super(x, y, width, height, label);
        this.font = font;
        this.selected = selected;
        this.toggle = toggle;
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        toggle.run();
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (isHoveredOrFocused()) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xFF34445B);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        int boxX = getX() + (getMessage().getString().isEmpty() ? (width - 11) / 2 : 4);
        int boxY = getY() + (height - 11) / 2;
        graphics.outline(boxX, boxY, 11, 11, isHoveredOrFocused() ? TEXT : BORDER);
        if (selected.getAsBoolean()) graphics.fill(boxX + 3, boxY + 3, boxX + 8, boxY + 8, CHECKED);
        if (!getMessage().getString().isEmpty()) {
            graphics.text(font, font.plainSubstrByWidth(getMessage().getString(), width - 23),
                    getX() + 20, getY() + (height - font.lineHeight) / 2, TEXT, false);
        }
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
