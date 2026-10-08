package cn.contribution.client;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Real row control: table paints text; widget paints only the hover outline. */
final class ContributionRowWidget extends AbstractWidget {
    private final Runnable click;

    ContributionRowWidget(int x, int y, int width, int height, Runnable click) {
        super(x, y, width, height, Component.literal("查看详情"));
        this.click = click;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        click.run();
    }

    @Override
    protected void extractWidgetRenderState(
            GuiGraphicsExtractor graphics, int x, int y, float delta) {
        if (isHoveredOrFocused()) {
            graphics.outline(getX(), getY(), width, height, 0xFF6FC9DF);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
