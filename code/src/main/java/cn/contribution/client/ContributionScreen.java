package cn.contribution.client;

import cn.contribution.ui.ContributionActionLayout;
import cn.contribution.ui.ContributionUiNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Optional pixel-aligned account tables; commands and data remain server-authoritative. */
final class ContributionScreen extends Screen {
    private static final int BACKGROUND = 0xF01A2230;
    private static final int PANEL = 0xFF263448;
    private static final int PANEL_ALT = 0xFF203044;
    private static final int TEXT = 0xFFE7EDF7;
    private static final int MUTED = 0xFF9DAEC4;
    private static final int ACCENT = 0xFF6FC9DF;
    private ContributionUiNetwork.Snapshot snapshot;
    private long searchDue;
    private long lastNavigation;
    private String submittedSearch = "";
    private final Map<String, EditBox> fields = new HashMap<>();
    private int scroll;
    private String error = "";

    private ContributionScreen(ContributionUiNetwork.Snapshot snapshot) {
        super(Component.literal(snapshot.title()));
        this.snapshot = snapshot;
    }

    static void receive(ContributionUiNetwork.Snapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof ContributionScreen current
                && current.snapshot.view().equals(snapshot.view())) {
            current.retainedFields.clear();
            for (var entry : current.fields.entrySet())
                current.retainedFields.put(entry.getKey(), entry.getValue().getValue());
            current.snapshot = snapshot;
            current.rebuildWidgets();
            if (snapshot.view().equals("admin") && current.fields.containsKey("target"))
                current.setFocused(current.fields.get("target"));
            return;
        }
        client.gui.setScreen(new ContributionScreen(snapshot));
    }

    private final Map<String, String> retainedFields = new HashMap<>();

    private boolean home() {
        return "home".equals(snapshot.view());
    }

    private int top() {
        return snapshot.fields().isEmpty() ? 68 : 114;
    }

    private int bottom() {
        int localCount =
                (int) snapshot.actions().stream().filter(action -> !navigation(action)).count();
        int buttonRows = 1 + (localCount + 3) / 4;
        return height - 36 - (buttonRows - 1) * 24;
    }

    private int rowHeight() {
        return switch (snapshot.view()) {
            case "history", "ranking" -> 18;
            case "industries" -> 16;
            default -> 23;
        };
    }

    private int capacity() {
        return Math.max(1, (bottom() - top()) / rowHeight());
    }

    private boolean profile() {
        return "profile".equals(snapshot.view());
    }

    private int maxScroll() {
        return profile()
                ? Math.max(0, (172 - (bottom() - top()) + rowHeight() - 1) / rowHeight())
                : Math.max(0, snapshot.rows().size() - capacity());
    }

    private final java.util.List<ContributionRowWidget> rowWidgets = new java.util.ArrayList<>();

    private void syncRows() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (int slot = 0; slot < rowWidgets.size(); slot++) {
            int index = scroll + slot;
            var widget = rowWidgets.get(slot);
            widget.visible =
                    widget.active =
                            index < snapshot.rowCommands().size()
                                    && !snapshot.rowCommands().get(index).isBlank();
        }
    }

    @Override
    protected void init() {
        fields.clear();
        rowWidgets.clear();
        int fieldCount = snapshot.fields().size();
        if (fieldCount > 0) {
            int fieldWidth = (width - 36 - (fieldCount - 1) * 8) / fieldCount;
            for (int i = 0; i < fieldCount; i++) {
                var definition = snapshot.fields().get(i);
                EditBox box =
                        addRenderableWidget(
                                new EditBox(
                                        font,
                                        18 + i * (fieldWidth + 8),
                                        75,
                                        fieldWidth,
                                        19,
                                        Component.literal(definition.label())));
                box.setMaxLength(definition.maxLength());
                box.setValue(retainedFields.getOrDefault(definition.key(), definition.value()));
                fields.put(definition.key(), box);
                if (snapshot.view().equals("admin") && definition.key().equals("target")) {
                    submittedSearch = definition.value();
                    box.setResponder(value -> searchDue = System.currentTimeMillis() + 450);
                }
            }
        }
        var localActions =
                snapshot.actions().stream().filter(action -> !navigation(action)).toList();
        int count = localActions.size();
        for (int i = 0; i < count; i++) {
            var action = localActions.get(i);
            var bounds = ContributionActionLayout.bounds(snapshot.view(), count, i, width, height);
            Button button =
                    Button.builder(
                                    Component.literal(action.label()),
                                    ignored -> run(action.command()))
                            .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                            .build();
            button.active = !action.command().isBlank();
            addRenderableWidget(button);
        }
        int navWidth = Math.max(30, (width - 34) / 3);
        var parent =
                Button.builder(Component.literal("返回上级"), ignored -> run("contribution ui back"))
                        .bounds(12, height - 29, navWidth, 19)
                        .build();
        parent.active = !home();
        addRenderableWidget(parent);
        var root =
                Button.builder(Component.literal("首页"), ignored -> run("contribution ui home"))
                        .bounds(17 + navWidth, height - 29, navWidth, 19)
                        .build();
        root.active = !home();
        addRenderableWidget(root);
        addRenderableWidget(
                Button.builder(Component.literal("关闭"), ignored -> closeAll())
                        .bounds(22 + 2 * navWidth, height - 29, navWidth, 19)
                        .build());
        addRowWidgets();
    }

    private void addRowWidgets() {
        if (profile() || snapshot.rowCommands().isEmpty()) return;
        for (int slot = 0; slot < capacity(); slot++) {
            final int offset = slot;
            rowWidgets.add(
                    addRenderableWidget(
                            new ContributionRowWidget(
                                    12,
                                    top() + slot * rowHeight(),
                                    width - 24,
                                    rowHeight() - 2,
                                    () -> {
                                        int index = scroll + offset;
                                        if (index < snapshot.rowCommands().size())
                                            run(snapshot.rowCommands().get(index));
                                    })));
        }
        syncRows();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void command(String value) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null)
            connection.sendCommand(value.startsWith("/") ? value.substring(1) : value);
    }

    private void run(String template) {
        long now = System.nanoTime();
        if (now - lastNavigation < 250_000_000L) return;
        lastNavigation = now;
        String command = template;
        for (var field : snapshot.fields()) {
            EditBox box = fields.get(field.key());
            String value = box == null ? "" : box.getValue().strip();
            if (command.contains("$(" + field.key() + ")") && value.isBlank()) {
                error = "请填写" + field.label();
                return;
            }
            command = command.replace("$(" + field.key() + ")", value);
        }
        error = "";
        command(command);
    }

    private static boolean navigation(ContributionUiNetwork.Action action) {
        return action.command().equals("contribution ui back")
                || action.command().equals("contribution ui home")
                || action.command().equals("contribution ui close");
    }

    private void closeAll() {
        command("contribution ui close");
        super.onClose();
    }

    @Override
    public void onClose() {
        if (home()) closeAll();
        else command("contribution ui back");
    }

    @Override
    public void tick() {
        super.tick();
        if (searchDue > 0
                && System.currentTimeMillis() >= searchDue
                && fields.containsKey("target")) {
            searchDue = 0;
            String value = fields.get("target").getValue().strip();
            if (!value.equals(submittedSearch)) {
                submittedSearch = value;
                command(
                        value.isEmpty()
                                ? "contribution ui admin"
                                : "contribution ui admin_search " + value);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= top() && y <= bottom()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(vertical)));
            syncRows();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private int[] widths() {
        int available = width - 32;
        return switch (snapshot.view()) {
            case "ranking" -> new int[] {available / 8, available * 3 / 8, available / 2};
            case "history" ->
                    new int[] {
                        available * 18 / 100,
                        available * 13 / 100,
                        available * 11 / 100,
                        available * 15 / 100,
                        available * 17 / 100,
                        available
                                - available * 18 / 100
                                - available * 13 / 100
                                - available * 11 / 100
                                - available * 15 / 100
                                - available * 17 / 100
                    };
            case "industries" ->
                    new int[] {
                        available / 5,
                        available / 5,
                        available / 5,
                        available / 5,
                        available - 4 * (available / 5)
                    };
            case "stats" ->
                    new int[] {available / 3, available / 3, available - 2 * (available / 3)};
            case "account" ->
                    new int[] {
                        available / 6, available / 5, available - available / 6 - available / 5
                    };
            case "admin", "accounts" ->
                    new int[] {
                        available / 4, available / 6, available - available / 4 - available / 6
                    };
            case "checkin" ->
                    new int[] {
                        available / 4,
                        available * 2 / 5,
                        available - available / 4 - available * 2 / 5
                    };
            default -> new int[] {available};
        };
    }

    private void cells(
            GuiGraphicsExtractor graphics, List<String> values, int y, int[] widths, int color) {
        int x = 18;
        for (int column = 0; column < Math.min(values.size(), widths.length); column++) {
            String value = values.get(column);
            int max = Math.max(8, widths[column] - 7);
            graphics.text(font, font.plainSubstrByWidth(value, max), x, y, color, false);
            x += widths[column];
        }
    }

    private void renderProfile(GuiGraphicsExtractor g) {
        scroll = Math.min(scroll, maxScroll());
        int y = top() - scroll * rowHeight(), available = width - 32;
        int[] accountWidths = {
            available / 6, available / 5, available - available / 6 - available / 5
        };
        int[] thirds = {available / 3, available / 3, available - 2 * (available / 3)};
        g.enableScissor(12, top(), width - 12, bottom());
        cells(g, List.of("余额", "历史总收入", "UUID"), y, accountWidths, ACCENT);
        cells(g, snapshot.rows().get(0), y + 17, accountWidths, TEXT);
        cells(g, List.of("原始放置", "原始挖掘", "个人总建设度"), y + 45, thirds, ACCENT);
        cells(g, snapshot.rows().get(1), y + 62, thirds, TEXT);
        g.text(font, "行业建设度", 18, y + 88, ACCENT, false);
        for (int i = 2; i < snapshot.rows().size(); i++) {
            int rowY = y + 105 + (i - 2) * 22;
            g.fill(12, rowY - 4, width - 12, rowY + 15, i % 2 == 0 ? PANEL : PANEL_ALT);
            cells(g, snapshot.rows().get(i), rowY, thirds, TEXT);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            g.fill(width - 12, top(), width - 9, bottom(), 0xFF43546B);
            int thumb = Math.max(10, (bottom() - top()) * (bottom() - top()) / 172);
            int thumbY = top() + (bottom() - top() - thumb) * scroll / maxScroll();
            g.fill(width - 12, thumbY, width - 9, thumbY + thumb, ACCENT);
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(8, 8, width - 8, 52, PANEL);
        graphics.text(font, snapshot.title(), 18, 16, TEXT, true);
        graphics.text(
                font, font.plainSubstrByWidth(snapshot.note(), width - 36), 18, 34, MUTED, false);
        for (int i = 0; i < snapshot.fields().size(); i++) {
            int fieldWidth =
                    (width - 36 - (snapshot.fields().size() - 1) * 8) / snapshot.fields().size();
            graphics.text(
                    font,
                    snapshot.fields().get(i).label(),
                    18 + i * (fieldWidth + 8),
                    60,
                    MUTED,
                    false);
        }
        if (profile()) {
            renderProfile(graphics);
        } else if (!home()) {
            int[] widths = widths();
            if (!snapshot.headers().isEmpty()) {
                graphics.fill(12, top() - 14, width - 12, top() - 1, 0xFF30445C);
                cells(graphics, snapshot.headers(), top() - 11, widths, ACCENT);
            }
            int size = snapshot.rows().size();
            syncRows();
            if (size == 0 && !snapshot.headers().isEmpty())
                graphics.text(font, "暂无记录", 18, top() + 8, MUTED, false);
            for (int index = scroll; index < Math.min(size, scroll + capacity()); index++) {
                int y = top() + (index - scroll) * rowHeight();
                graphics.fill(
                        12, y, width - 12, y + rowHeight() - 2, index % 2 == 0 ? PANEL : PANEL_ALT);
                cells(graphics, snapshot.rows().get(index), y + 5, widths, TEXT);
            }
            if (size > capacity()) {
                int trackTop = top(), trackBottom = bottom() - 2;
                graphics.fill(width - 12, trackTop, width - 9, trackBottom, 0xFF43546B);
                int thumb = Math.max(10, (trackBottom - trackTop) * capacity() / size);
                int thumbY =
                        trackTop
                                + (trackBottom - trackTop - thumb)
                                        * scroll
                                        / Math.max(1, size - capacity());
                graphics.fill(width - 12, thumbY, width - 9, thumbY + thumb, ACCENT);
            }
        } else {
            graphics.text(font, "选择下方功能查看账户与服务器建设数据", 18, 78, TEXT, false);
        }
        if (!error.isEmpty()) graphics.text(font, error, 18, bottom() + 3, 0xFFF07575, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
