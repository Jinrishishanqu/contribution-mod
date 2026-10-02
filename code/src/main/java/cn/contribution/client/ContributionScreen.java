package cn.contribution.client;

import cn.contribution.ui.ContributionUiNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Optional pixel-aligned account tables; commands and data remain server-authoritative. */
final class ContributionScreen extends Screen {
    private static final int BACKGROUND = 0xF01A2230;
    private static final int PANEL = 0xFF263448;
    private static final int PANEL_ALT = 0xFF203044;
    private static final int TEXT = 0xFFE7EDF7;
    private static final int MUTED = 0xFF9DAEC4;
    private static final int ACCENT = 0xFF6FC9DF;
    private final ContributionUiNetwork.Snapshot snapshot;
    private int scroll;

    private ContributionScreen(ContributionUiNetwork.Snapshot snapshot) {
        super(Component.literal(snapshot.title()));
        this.snapshot = snapshot;
    }

    static void receive(ContributionUiNetwork.Snapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        ContributionScreen next = new ContributionScreen(snapshot);
        if (client.gui.screen() instanceof ContributionScreen current
                && current.snapshot.view().equals(snapshot.view())) next.scroll = current.scroll;
        client.gui.setScreen(next);
    }

    private boolean home() { return "home".equals(snapshot.view()); }
    private int top() { return 68; }
    private int bottom() { return height - (snapshot.actions().size() > 4 ? 69 : 45); }
    private int rowHeight() {
        return switch (snapshot.view()) {
            case "history" -> 18;
            case "industries" -> 16;
            default -> 23;
        };
    }
    private int capacity() { return Math.max(1, (bottom() - top()) / rowHeight()); }

    @Override protected void init() {
        int count = snapshot.actions().size();
        int perRow = Math.min(4, Math.max(1, count));
        int buttonWidth = Math.max(55, (width - 24 - (perRow - 1) * 5) / perRow);
        for (int i = 0; i < count; i++) {
            ContributionUiNetwork.Action action = snapshot.actions().get(i);
            int x = 12 + (i % perRow) * (buttonWidth + 5);
            int y = height - 29 - (count > 4 && i < 4 ? 24 : 0);
            addRenderableWidget(Button.builder(Component.literal(action.label()), button -> command(action.command()))
                    .bounds(x, y, buttonWidth, 19).build());
        }
    }

    private static void command(String value) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand(value);
    }

    @Override public void onClose() {
        if (home()) super.onClose();
        else command("contribution ui home");
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= top() && y <= bottom()) {
            scroll = Math.max(0, Math.min(Math.max(0, snapshot.rows().size() - capacity()),
                    scroll - (int) Math.signum(vertical)));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private int[] widths() {
        int available = width - 32;
        return switch (snapshot.view()) {
            case "history" -> new int[]{82, 58, 47, 65, 75, Math.max(25, available - 327)};
            case "industries" -> new int[]{92, 57, 74, 72, Math.max(40, available - 295)};
            case "stats" -> new int[]{available / 3, available / 3, available - 2 * (available / 3)};
            case "account" -> new int[]{available / 6, available / 5, available - available / 6 - available / 5};
            default -> new int[]{available};
        };
    }

    private void cells(GuiGraphicsExtractor graphics, List<String> values, int y, int[] widths, int color) {
        int x = 18;
        for (int column = 0; column < Math.min(values.size(), widths.length); column++) {
            String value = values.get(column);
            int max = Math.max(8, widths[column] - 7);
            graphics.text(font, font.plainSubstrByWidth(value, max), x, y, color, false);
            x += widths[column];
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(8, 8, width - 8, 52, PANEL);
        graphics.text(font, snapshot.title(), 18, 16, TEXT, true);
        graphics.text(font, font.plainSubstrByWidth(snapshot.note(), width - 36), 18, 34, MUTED, false);
        if (!home()) {
            int[] widths = widths();
            graphics.fill(12, 54, width - 12, top() - 1, 0xFF30445C);
            cells(graphics, snapshot.headers(), 57, widths, ACCENT);
            int size = snapshot.rows().size();
            scroll = Math.min(scroll, Math.max(0, size - capacity()));
            if (size == 0) graphics.text(font, "暂无记录", 18, top() + 8, MUTED, false);
            for (int index = scroll; index < Math.min(size, scroll + capacity()); index++) {
                int y = top() + (index - scroll) * rowHeight();
                graphics.fill(12, y, width - 12, y + rowHeight() - 2,
                        index % 2 == 0 ? PANEL : PANEL_ALT);
                cells(graphics, snapshot.rows().get(index), y + 5, widths, TEXT);
            }
            if (size > capacity()) {
                int trackTop = top(), trackBottom = bottom() - 2;
                graphics.fill(width - 12, trackTop, width - 9, trackBottom, 0xFF43546B);
                int thumb = Math.max(10, (trackBottom - trackTop) * capacity() / size);
                int thumbY = trackTop + (trackBottom - trackTop - thumb) * scroll / Math.max(1, size - capacity());
                graphics.fill(width - 12, thumbY, width - 9, thumbY + thumb, ACCENT);
            }
        } else {
            graphics.text(font, "选择下方功能查看账户与服务器建设数据", 18, 78, TEXT, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
