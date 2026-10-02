package cn.contribution.client;

import cn.contribution.stock.StockUiNetwork;
import cn.contribution.stock.StockView;
import cn.contribution.stock.StockPricing;
import cn.contribution.stock.StockLineRaster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** Draws actual GUI pixels for stock curves; data and trades remain server-authoritative. */
final class StockScreen extends Screen {
    private static final int BACKGROUND = 0xF018202B;
    private static final int TEXT = 0xFFE7EBF2;
    private static final int MUTED = 0xFF9AA8BC;
    private static final int GREEN = 0xFF57D78C;
    private static final int RED = 0xFFF07575;
    private static final int AMBER = 0xFFFFCA66;
    private static final int ROW_HEIGHT = 55;
    private static final int ROW_TOP = 78;
    private static StockScreen lastMarket;
    private static long clockDay = -1;
    private static int clockTime = -1;

    private final StockView.Dashboard dashboard;
    private final StockView.Detail detail;
    private final int range;
    private final boolean profile;
    private final Set<Long> selected = new HashSet<>();
    private final Set<String> industries = new HashSet<>();
    private final List<Button> rowSelectButtons = new ArrayList<>();
    private final List<Button> rowDetailButtons = new ArrayList<>();
    private final List<Button> industryOptionButtons = new ArrayList<>();
    private EditBox search;
    private EditBox quantity;
    private Button industryButton;
    private Button sortButton;
    private Button ownedButton;
    private boolean industryMenu;
    private int sort;
    private boolean ownedOnly;
    private int scroll;
    private int detailScroll;
    private int detailScrollMax;
    private long focusedRow = -1;
    private long lastClickedRow = -1;
    private long lastClickedAtNanos;
    private String retainedSearch = "";
    private String retainedQuantity = "1";

    private StockScreen(StockView.Dashboard dashboard, StockView.Detail detail, int range, boolean profile) {
        super(Component.literal(detail != null ? "股票详情" : profile ? "我的股票" : "股票市场"));
        this.dashboard = dashboard;
        this.detail = detail;
        this.range = range;
        this.profile = profile;
    }

    static void receive(StockUiNetwork.Snapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if ("clock".equals(snapshot.view())) {
            clockDay = snapshot.day(); clockTime = snapshot.time(); return;
        }
        if (("market".equals(snapshot.view()) || "profile".equals(snapshot.view())) && snapshot.dashboard() != null) {
            clockDay = snapshot.day(); clockTime = snapshot.time();
            StockScreen previous = lastMarket;
            boolean wasProfile = "profile".equals(snapshot.view());
            StockScreen updated = new StockScreen(snapshot.dashboard(), null, 30, false);
            if (previous != null) updated.copyMarketState(previous);
            lastMarket = updated;
            client.gui.setScreen(wasProfile ? new StockScreen(snapshot.dashboard(), null, 30, true) : updated);
        } else if ("detail".equals(snapshot.view()) && snapshot.detail() != null) {
            StockView.Dashboard data = lastMarket == null ? null : lastMarket.dashboard;
            StockScreen updated = new StockScreen(data, snapshot.detail(), snapshot.detail().requestedDays(), false);
            if (client.gui.screen() instanceof StockScreen previous && previous.detail != null
                    && previous.detail.listing().id() == snapshot.detail().listing().id()) {
                updated.detailScroll = previous.detailScroll;
            }
            client.gui.setScreen(updated);
        }
    }

    static void clear() {
        lastMarket = null; clockDay = -1; clockTime = -1;
    }

    private void copyMarketState(StockScreen other) {
        selected.addAll(other.selected);
        selected.retainAll(dashboard.market().listings().stream().map(StockView.Listing::id).toList());
        industries.addAll(other.industries);
        sort = other.sort;
        ownedOnly = other.ownedOnly;
        scroll = other.scroll;
        focusedRow = other.focusedRow;
        retainedSearch = other.search == null ? other.retainedSearch : other.search.getValue();
        retainedQuantity = other.quantity == null ? other.retainedQuantity : other.quantity.getValue();
    }

    @Override protected void init() {
        int center = width / 2;
        if (detail == null && !profile) {
            int searchWidth = Math.max(90, Math.min(145, width / 4));
            search = addRenderableWidget(new EditBox(font, 12, 31, searchWidth, 19,
                    Component.literal("搜索股票名称或物品 ID")));
            search.setHint(Component.literal("搜索名称或物品 ID"));
            search.setMaxLength(64);
            search.setValue(retainedSearch);
            industryButton = addRenderableWidget(Button.builder(Component.literal(industryLabel()), button -> {
                industryMenu = !industryMenu;
                updateIndustryOptionButtons();
            })
                    .bounds(17 + searchWidth, 31, 90, 19).build());
            List<String> options = industryOptions();
            int menuX = 17 + searchWidth;
            for (int option = 0; option <= options.size(); option++) {
                final int index = option;
                Button choice = addRenderableWidget(Button.builder(
                        Component.literal(option == 0 ? "全部行业" : options.get(option - 1)),
                        button -> toggleIndustry(index))
                        .bounds(menuX + 2, 53 + option * 17, 111, 17).build());
                industryOptionButtons.add(choice);
            }
            updateIndustryOptionButtons();
            sortButton = addRenderableWidget(Button.builder(Component.literal(sortLabel()), button -> {
                sort = (sort + 1) % 9; sortButton.setMessage(Component.literal(sortLabel())); scroll = 0;
            }).bounds(112 + searchWidth, 31, 85, 19).build());
            ownedButton = addRenderableWidget(Button.builder(Component.literal(ownedOnly ? "仅持仓：开" : "仅持仓：关"), button -> {
                ownedOnly = !ownedOnly; ownedButton.setMessage(Component.literal(ownedOnly ? "仅持仓：开" : "仅持仓：关")); scroll = 0;
            }).bounds(202 + searchWidth, 31, 86, 19).build());
            int footer = height - 27;
            quantity = addRenderableWidget(new EditBox(font, 12, footer, 45, 19, Component.literal("交易股数")));
            quantity.setValue(retainedQuantity); quantity.setMaxLength(5);
            addRenderableWidget(Button.builder(Component.literal("批量买入"), button -> batch(true)).bounds(62, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("批量卖出"), button -> batch(false)).bounds(133, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("全选可见"), button -> {
                for (StockView.Listing row : visible()) selected.add(row.id());
            }).bounds(204, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("清空选择"), button -> selected.clear())
                    .bounds(275, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("刷新"), button -> command("stock"))
                    .bounds(width - 57, footer, 45, 19).build());
            addRenderableWidget(Button.builder(Component.literal("我的股票"), button -> {
                retainInputs();
                minecraft.gui.setScreen(new StockScreen(dashboard, null, range, true));
            }).bounds(width - 137, footer, 75, 19).build());
            int slots = Math.max(1, (height - 54 - ROW_TOP) / ROW_HEIGHT);
            for (int slot = 0; slot < slots; slot++) {
                final int rowSlot = slot;
                int y = ROW_TOP + slot * ROW_HEIGHT;
                rowSelectButtons.add(addRenderableWidget(Button.builder(Component.literal("□"), button -> selectRow(rowSlot))
                        .bounds(15, y + 21, 14, 14).build()));
                rowDetailButtons.add(addRenderableWidget(Button.builder(Component.literal("详情"), button -> openRow(rowSlot))
                        .bounds(32, y + 5, Math.max(16, priceColumnX() - 40), 44).build()));
            }
        } else if (profile) {
            addRenderableWidget(Button.builder(Component.literal("返回市场"), button -> {
                if (lastMarket != null) minecraft.gui.setScreen(lastMarket);
            }).bounds(12, height - 27, 76, 19).build());
            addRenderableWidget(Button.builder(Component.literal("刷新"), button -> command("stock portfolio"))
                    .bounds(width - 57, height - 27, 45, 19).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("返回市场"), button -> {
                if (lastMarket != null) minecraft.gui.setScreen(lastMarket); else command("stock");
            }).bounds(12, height - 27, 76, 19).build());
            addRenderableWidget(Button.builder(Component.literal("7 日"), button -> requestRange(7))
                    .bounds(center - 91, height - 27, 56, 19).build());
            addRenderableWidget(Button.builder(Component.literal("30 日"), button -> requestRange(30))
                    .bounds(center - 30, height - 27, 56, 19).build());
            addRenderableWidget(Button.builder(Component.literal("360 日"), button -> requestRange(360))
                    .bounds(center + 31, height - 27, 63, 19).build());
            quantity = addRenderableWidget(new EditBox(font, width - 170, height - 52, 48, 19, Component.literal("股数")));
            quantity.setValue("1"); quantity.setMaxLength(5);
            addRenderableWidget(Button.builder(Component.literal("买入"), button -> trade(true))
                    .bounds(width - 116, height - 52, 49, 19).build());
            addRenderableWidget(Button.builder(Component.literal("卖出"), button -> trade(false))
                    .bounds(width - 62, height - 52, 49, 19).build());
        }
    }

    private String sortLabel() {
        return switch (sort) {
            case 1 -> "价格↓"; case 2 -> "价格↑";
            case 3 -> "涨跌幅↓"; case 4 -> "涨跌幅↑";
            case 5 -> "退市倍数↑"; case 6 -> "退市倍数↓";
            case 7 -> "持仓↓"; case 8 -> "上市时间↓";
            default -> "名称";
        };
    }

    private String industryLabel() {
        return industries.isEmpty() ? "全部行业 ▾" : "行业 " + industries.size() + " 项 ▾";
    }

    private void updateIndustryOptionButtons() {
        for (int index = 0; index < industryOptionButtons.size(); index++) {
            Button button = industryOptionButtons.get(index);
            button.visible = industryMenu;
            button.setMessage(Component.literal(index == 0
                    ? (industries.isEmpty() ? "☑ " : "□ ") + "全部行业"
                    : (industries.contains(industryOptions().get(index - 1)) ? "☑ " : "□ ")
                    + industryOptions().get(index - 1)));
        }
    }

    private void toggleIndustry(int option) {
        if (option == 0) industries.clear();
        else {
            String value = industryOptions().get(option - 1);
            if (!industries.add(value)) industries.remove(value);
        }
        industryButton.setMessage(Component.literal(industryLabel()));
        scroll = 0;
        updateIndustryOptionButtons();
    }

    private List<String> industryOptions() {
        return dashboard.market().listings().stream().map(StockView.Listing::industry).distinct().sorted().toList();
    }

    private void retainInputs() {
        if (search != null) retainedSearch = search.getValue();
        if (quantity != null) retainedQuantity = quantity.getValue();
    }

    private int priceColumnX() { return width * 31 / 100; }

    private StockView.Listing rowAt(int slot) {
        List<StockView.Listing> rows = visible();
        int index = scroll + slot;
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    private void selectRow(int slot) {
        StockView.Listing row = rowAt(slot);
        if (row == null) return;
        if (!selected.add(row.id())) selected.remove(row.id());
        lastClickedRow = -1;
    }

    private void openRow(int slot) {
        StockView.Listing row = rowAt(slot);
        if (row != null) command("stock check " + row.id() + " month");
    }

    private void syncRowButtons(List<StockView.Listing> rows, int capacity) {
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        for (int slot = 0; slot < rowSelectButtons.size(); slot++) {
            int index = scroll + slot;
            boolean exists = slot < capacity && index < rows.size();
            Button select = rowSelectButtons.get(slot);
            Button open = rowDetailButtons.get(slot);
            select.visible = exists;
            open.visible = exists;
            if (exists) select.setMessage(Component.literal(selected.contains(rows.get(index).id()) ? "☑" : "□"));
        }
    }

    private List<StockView.Listing> visible() {
        if (dashboard == null) return List.of();
        String term = (search == null ? retainedSearch : search.getValue()).strip().toLowerCase(Locale.ROOT);
        List<StockView.Listing> rows = new ArrayList<>();
        for (StockView.Listing row : dashboard.market().listings()) {
            if (ownedOnly && row.owned() == 0) continue;
            if (!industries.isEmpty() && !industries.contains(row.industry())) continue;
            if (!term.isEmpty() && !row.name().toLowerCase(java.util.Locale.ROOT).contains(term)
                    && !row.itemId().toLowerCase(java.util.Locale.ROOT).contains(term)) continue;
            rows.add(row);
        }
        rows.sort(switch (sort) {
            case 1 -> Comparator.comparingInt(StockView.Listing::price).reversed();
            case 2 -> Comparator.comparingInt(StockView.Listing::price);
            case 3 -> Comparator.comparingDouble(this::dailyPercent).reversed();
            case 4 -> Comparator.comparingDouble(this::dailyPercent);
            case 5 -> Comparator.comparingDouble(this::riskMultiple);
            case 6 -> Comparator.comparingDouble(this::riskMultiple).reversed();
            case 7 -> Comparator.comparingInt(StockView.Listing::owned).reversed();
            case 8 -> Comparator.comparingLong(StockView.Listing::listedDay).reversed();
            default -> Comparator.comparing(StockView.Listing::name);
        });
        return rows;
    }

    private int previousPrice(StockView.Listing row) {
        List<StockView.PricePoint> curve = dashboard.curves().get(row.id());
        return curve == null || curve.size() < 2 ? row.price() : curve.get(curve.size() - 2).price();
    }

    private double dailyPercent(StockView.Listing row) {
        int previous = previousPrice(row);
        return previous == 0 ? 0 : (row.price() - previous) * 100.0 / previous;
    }

    private int retirementThreshold(StockView.Listing row) {
        StockView.PriceRange priceRange = dashboard.ranges().get(row.id());
        return StockPricing.retirementThreshold(row.initialPrice(),
                priceRange == null ? row.price() : priceRange.high());
    }

    private double riskMultiple(StockView.Listing row) {
        return row.price() / (double) Math.max(1, retirementThreshold(row));
    }

    private static void command(String command) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) listener.sendCommand(command);
    }

    private int amount() {
        try {
            int value = Integer.parseInt(quantity.getValue());
            return value >= 1 && value <= 10000 ? value : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    private void trade(boolean buy) {
        int amount = amount();
        if (amount < 0 || detail == null) return;
        command("stock " + (buy ? "buy" : "sell") + " " + detail.listing().id() + " " + amount);
    }

    private void batch(boolean buy) {
        int amount = amount();
        if (amount < 0 || selected.isEmpty()) return;
        String ids = selected.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        command("stock batch " + (buy ? "buy" : "sell") + " " + amount + " " + ids);
    }

    private void requestRange(int days) {
        if (detail != null) command("stock check " + detail.listing().id() + " "
                + (days == 7 ? "week" : days == 30 ? "month" : "year"));
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (detail == null && event.button() == 0 && !profile && industryMenu) {
            int searchWidth = Math.max(90, Math.min(145, width / 4));
            int menuX = 17 + searchWidth;
            if (event.x() >= menuX && event.x() < menuX + 115 && event.y() >= 52
                    && event.y() < 53 + industryOptionButtons.size() * 17) {
                int option = Math.max(0, ((int) event.y() - 53) / 17);
                toggleIndustry(Math.min(option, industryOptionButtons.size() - 1));
                return true;
            }
            // The toggle button itself still goes through vanilla widget dispatch.
            if (event.x() < menuX || event.x() >= menuX + 90 || event.y() < 31 || event.y() >= 50) {
                industryMenu = false;
                updateIndustryOptionButtons();
                return true; // A dismissal click must not activate a stock behind the menu.
            }
        }
        // Registered row buttons use the same vanilla dispatch path as the working footer buttons.
        if (super.mouseClicked(event, doubleClick)) return true;
        if (detail == null && event.button() == 0) {
            int firstY = profile ? 112 : ROW_TOP;
            if (event.x() >= 10 && event.x() < width - 10
                    && event.y() >= firstY && event.y() < height - 54) {
                int index = scroll + ((int) event.y() - firstY) / ROW_HEIGHT;
                List<StockView.Listing> rows = profile ? ownedRows() : visible();
                if (index >= 0 && index < rows.size()) {
                    StockView.Listing row = rows.get(index);
                    int slot = index - scroll;
                    int rowY = firstY + slot * ROW_HEIGHT;
                    if (!profile && event.x() >= 32 && event.x() < priceColumnX() - 8
                            && event.y() >= rowY + 5 && event.y() < rowY + 49) {
                        openRow(slot); // Also works if a future widget implementation declines the click.
                    } else if (!profile && event.x() < 31) {
                        if (!selected.add(row.id())) selected.remove(row.id());
                        lastClickedRow = -1;
                    } else {
                        long now = System.nanoTime();
                        boolean repeated = lastClickedRow == row.id()
                                && now - lastClickedAtNanos <= 400_000_000L;
                        if (repeated || (doubleClick && lastClickedRow == row.id())) {
                            lastClickedRow = -1;
                            command("stock check " + row.id() + " month");
                        } else {
                            focusedRow = row.id();
                            lastClickedRow = row.id();
                            lastClickedAtNanos = now;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (industryMenu) return true;
        if (detail != null && mouseY >= 27 && mouseY < height - 57) {
            detailScroll = Math.max(0, Math.min(detailScrollMax, detailScroll - (int) Math.signum(vertical) * 23));
            return true;
        }
        int firstY = profile ? 112 : ROW_TOP;
        if (detail == null && mouseY >= firstY && mouseY < height - 54) {
            int capacity = Math.max(1, (height - 54 - firstY) / ROW_HEIGHT);
            int size = profile ? ownedRows().size() : visible().size();
            scroll = Math.max(0, Math.min(Math.max(0, size - capacity), scroll - (int) Math.signum(vertical)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, detail == null ? "股票市场" : detail.listing().name() + " · 股票详情", 12, 8, TEXT, true);
        graphics.text(font, "游戏日 " + clockDay + "  " + timeText(clockTime) + "  · 交易 10:00—14:00 "
                + (clockTime >= 4000 && clockTime < 8000 ? "交易中" : "已休市"),
                Math.max(130, width - 280), 8, clockTime >= 4000 && clockTime < 8000 ? GREEN : MUTED, false);
        if (detail == null && profile) drawProfile(graphics, mouseX, mouseY);
        else if (detail == null) drawMarket(graphics, mouseX, mouseY);
        else drawDetail(graphics);
    }

    private void drawMarket(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<StockView.Listing> rows = visible();
        int capacity = Math.max(1, (height - 54 - ROW_TOP) / ROW_HEIGHT);
        syncRowButtons(rows, capacity);
        // Minecraft GUI scaling can leave only ~480 logical pixels at 1920x1080.
        // Allocate every column from that logical width; never force a 225px price offset.
        int priceX = priceColumnX();
        int changeX = width * 43 / 100;
        int riskX = width * 57 / 100;
        int ownedX = width * 73 / 100;
        int chartX = width * 81 / 100;
        graphics.text(font, "选", 13, 62, MUTED, false);
        graphics.text(font, "股票 / 行业", 31, 62, MUTED, false);
        graphics.text(font, "现价", priceX, 62, MUTED, false);
        graphics.text(font, "当日涨跌", changeX, 62, MUTED, false);
        graphics.text(font, "退市风险", riskX, 62, MUTED, false);
        graphics.text(font, "持仓", ownedX, 62, MUTED, false);
        graphics.text(font, "历史股价", chartX, 62, MUTED, false);
        for (int i = scroll; i < Math.min(rows.size(), scroll + capacity); i++) {
            StockView.Listing row = rows.get(i);
            int y = ROW_TOP + (i - scroll) * ROW_HEIGHT;
            boolean hovering = !industryMenu && mouseX >= 10 && mouseX < width - 10
                    && mouseY >= y && mouseY < y + ROW_HEIGHT;
            graphics.fill(10, y, width - 10, y + ROW_HEIGHT - 2,
                    hovering || focusedRow == row.id() ? 0xFF34445B : i % 2 == 0 ? 0xB0263444 : 0xB01C2838);
            if (hovering) graphics.requestCursor(CursorTypes.POINTING_HAND);
            boolean selectHover = !industryMenu && mouseX >= 15 && mouseX < 29 && mouseY >= y + 21 && mouseY < y + 35;
            graphics.outline(16, y + 22, 12, 12, selectHover ? TEXT : MUTED);
            if (selected.contains(row.id())) graphics.fill(19, y + 25, 25, y + 31, GREEN);
            boolean detailHover = !industryMenu && mouseX >= 32 && mouseX < priceX - 8
                    && mouseY >= y + 5 && mouseY < y + 49;
            if (detailHover) graphics.fill(32, y + 5, priceX - 8, y + 49, 0x603E5369);
            if (selectHover || detailHover) graphics.requestCursor(CursorTypes.POINTING_HAND);
            columnText(graphics, row.name() + " · #" + row.id(), 35, y + 10, priceX - 16, TEXT);
            columnText(graphics, row.industry(), 35, y + 31, priceX - 16, MUTED);
            graphics.text(font, "›", priceX - 18, y + 22, detailHover ? TEXT : MUTED, false);
            int direction = row.price() > previousPrice(row) ? RED : row.price() < previousPrice(row) ? GREEN : MUTED;
            columnText(graphics, String.valueOf(row.price()), priceX, y + 10, changeX - 6, direction);
            var range = dashboard.ranges().get(row.id());
            if (range != null) columnText(graphics, range.high() + "/" + range.low(), priceX, y + 31, changeX - 6, MUTED);
            columnText(graphics, changeText(dailyPercent(row)), changeX, y + 10, riskX - 6, direction);
            columnText(graphics, signed(row.price() - previousPrice(row)), changeX, y + 31, riskX - 6, MUTED);
            double multiple = riskMultiple(row);
            columnText(graphics, "阈" + retirementThreshold(row), riskX, y + 10, ownedX - 6,
                    multiple <= 1.25 ? RED : MUTED);
            columnText(graphics, String.format(Locale.ROOT, "%.2f倍", multiple), riskX, y + 31,
                    ownedX - 6, multiple <= 1.25 ? RED : TEXT);
            columnText(graphics, String.valueOf(row.owned()), ownedX, y + 10, chartX - 5, TEXT);
            if (row.status().equals("RETIRING")) columnText(graphics, "退市", ownedX, y + 31, chartX - 5, RED);
            curve(graphics, dashboard.curves().get(row.id()), chartX, y + 6, width - chartX - 13, 41, direction);
        }
        columnText(graphics, "显示 " + rows.size() + " / " + dashboard.market().listings().size()
                + " · 已选 " + selected.size() + " · 高/低为历史价 · 点击详情", 13, height - 43, width - 10, MUTED);
        if (industryMenu) drawIndustryMenu(graphics, mouseX, mouseY);
    }

    private void columnText(GuiGraphicsExtractor graphics, String value, int x, int y, int right, int color) {
        if (right > x) graphics.text(font, font.plainSubstrByWidth(value, right - x), x, y, color, false);
    }

    private void drawIndustryMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int menuX = 17 + Math.max(90, Math.min(145, width / 4));
        List<String> options = industryOptions();
        int itemHeight = 17;
        graphics.fill(menuX, 52, menuX + 115, 53 + (options.size() + 1) * itemHeight, 0xFF101720);
        graphics.outline(menuX, 52, 115, 1 + (options.size() + 1) * itemHeight, MUTED);
        if (mouseX >= menuX && mouseX < menuX + 115 && mouseY >= 53 && mouseY < 70)
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        graphics.text(font, industries.isEmpty() ? "☑ 全部行业" : "□ 全部行业", menuX + 5, 57, TEXT, false);
        for (int i = 0; i < options.size(); i++) {
            int y = 53 + (i + 1) * itemHeight;
            if (mouseX >= menuX && mouseX < menuX + 115 && mouseY >= y && mouseY < y + itemHeight)
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            graphics.text(font, (industries.contains(options.get(i)) ? "☑ " : "□ ") + options.get(i),
                    menuX + 5, y + 4, industries.contains(options.get(i)) ? GREEN : TEXT, false);
        }
    }

    private List<StockView.Listing> ownedRows() {
        return dashboard.market().listings().stream().filter(row -> row.owned() > 0)
                .sorted(Comparator.comparingLong((StockView.Listing row) -> (long) row.price() * row.owned()).reversed())
                .toList();
    }

    private void drawProfile(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var portfolio = dashboard.portfolio();
        graphics.text(font, "我的股票 · 持仓概览", 12, 32, TEXT, false);
        graphics.text(font, "账户余额 " + portfolio.balance() + "    持仓市值 " + portfolio.marketValue()
                + "    持仓成本 " + portfolio.costBasis(), 12, 52, TEXT, false);
        graphics.text(font, "未实现盈亏 " + signed(portfolio.unrealizedProfit()) + "    收益率 "
                + percent(portfolio.unrealizedProfit(), portfolio.costBasis())
                + "    已实现盈亏 " + signed(portfolio.realizedProfit()), 12, 71,
                portfolio.unrealizedProfit() >= 0 ? RED : GREEN, false);
        graphics.text(font, "双击持仓查看详情 · 市值按当前股价估算，卖出还需扣手续费", 12, 91, MUTED, false);
        List<StockView.Listing> rows = ownedRows();
        int capacity = Math.max(1, (height - 166) / ROW_HEIGHT);
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        for (int i = scroll; i < Math.min(rows.size(), scroll + capacity); i++) {
            StockView.Listing row = rows.get(i);
            var holding = portfolio.positions().get(row.id());
            int y = 112 + (i - scroll) * ROW_HEIGHT;
            boolean hovering = mouseX >= 10 && mouseX < width - 10 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            graphics.fill(10, y, width - 10, y + ROW_HEIGHT - 2,
                    hovering || focusedRow == row.id() ? 0xFF34445B : 0xB0263444);
            if (hovering) graphics.requestCursor(CursorTypes.POINTING_HAND);
            graphics.text(font, row.name() + " · " + row.industry() + " · 持 " + row.owned() + " 股", 20, y + 6, TEXT, false);
            if (holding != null) {
                long gain = (long) row.price() * row.owned() - holding.costBasis();
                graphics.text(font, "均价 " + String.format(Locale.ROOT, "%.2f", holding.costBasis() / (double) row.owned())
                        + " · 现价 " + row.price() + " · 最近买入日 " + holding.lastBuyDay(), 20, y + 22, MUTED, false);
                graphics.text(font, "市值 " + ((long) row.price() * row.owned()) + " · 未实现 " + signed(gain)
                        + " (" + percent(gain, holding.costBasis()) + ")", 20, y + 38,
                        gain >= 0 ? RED : GREEN, false);
            }
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics) {
        StockView.Listing row = detail.listing();
        int previous = detail.prices().size() >= 2
                ? detail.prices().get(detail.prices().size() - 2).price() : row.price();
        double today = previous == 0 ? 0 : (row.price() - previous) * 100.0 / previous;
        int direction = today > 0 ? RED : today < 0 ? GREEN : MUTED;
        int threshold = StockPricing.retirementThreshold(row.initialPrice(), detail.range().high());
        double multiple = row.price() / (double) Math.max(1, threshold);
        int bottom = height - 58;
        graphics.enableScissor(8, 28, width - 8, bottom);
        int y = 35 - detailScroll;
        columnText(graphics, row.industry() + " · " + row.itemId(), 12, y, width - 105, MUTED);
        columnText(graphics, row.status().equals("RETIRING") ? "今日退市" : "在市", width - 100, y,
                width - 12, row.status().equals("RETIRING") ? RED : GREEN);
        y += 20;
        detailPair(graphics, y, "现价 " + row.price(), direction, "当日 " + changeText(today), direction);
        y += 16;
        detailPair(graphics, y, "昨收 " + previous, MUTED, "上市价 " + row.initialPrice(), MUTED);
        y += 24;
        y = detailHeading(graphics, "行情与风险", y);
        var rangeValue = detail.range();
        detailPair(graphics, y, "历史最高 " + rangeValue.high(), TEXT, "历史最低 " + rangeValue.low(), TEXT);
        y += 15;
        detailPair(graphics, y, "上市日 " + row.listedDay(), TEXT,
                "已上市 " + Math.max(1, clockDay - row.listedDay() + 1) + " 日", TEXT);
        y += 15;
        detailPair(graphics, y, "退市阈值 " + threshold, multiple <= 1.25 ? RED : TEXT,
                "股价上限 " + StockPricing.priceCap(row.initialPrice()), TEXT);
        y += 15;
        detailPair(graphics, y, String.format(Locale.ROOT, "当前/阈值 %.2f 倍", multiple),
                multiple <= 1.25 ? RED : TEXT, "卖出需扣手续费", MUTED);
        y += 21;
        if (multiple <= 1.25) {
            columnText(graphics, "⚠ 接近退市线，持有者请留意交易时间", 12, y, width - 12, RED);
            y += 18;
        }
        y = detailHeading(graphics, "我的持仓", y);
        var position = detail.position();
        if (row.owned() > 0 && position != null) {
            long gain = (long) row.price() * row.owned() - position.costBasis();
            detailPair(graphics, y, "持仓 " + row.owned() + " 股", TEXT,
                    "成本 " + position.costBasis(), TEXT);
            y += 15;
            detailPair(graphics, y, "均价 " + String.format(Locale.ROOT, "%.2f",
                    position.costBasis() / (double) row.owned()), TEXT,
                    "市值 " + ((long) row.price() * row.owned()), TEXT);
            y += 15;
            detailPair(graphics, y, "未实现 " + signed(gain) + " (" + percent(gain, position.costBasis()) + ")",
                    gain >= 0 ? RED : GREEN, "已实现 " + signed(position.realizedProfit()), MUTED);
            y += 15;
            detailPair(graphics, y, "首次买入日 " + position.firstBuyDay(), MUTED,
                    "最近买入日 " + position.lastBuyDay(), MUTED);
            y += 15;
            detailPair(graphics, y, "最近买入价 " + position.lastBuyPrice(), MUTED, "", MUTED);
            y += 21;
        } else {
            columnText(graphics, "尚未持有", 12, y, width - 12, MUTED);
            y += 21;
        }
        List<StockView.PricePoint> points = detail.prices();
        y = detailHeading(graphics, "区间涨跌", y);
        columnText(graphics, "较前 7 日  " + trend(detail.trends(), 7), 12, y, width - 12, MUTED);
        y += 15;
        columnText(graphics, "较前 30 日  " + trend(detail.trends(), 30), 12, y, width - 12, MUTED);
        y += 15;
        columnText(graphics, "较前 360 日  " + trend(detail.trends(), 360), 12, y, width - 12, MUTED);
        y += 24;
        y = detailHeading(graphics, "历史股价 · 逐游戏日", y);
        int chartX = 18, chartY = y, chartW = Math.max(50, width - 36);
        int chartH = Math.max(110, Math.min(180, height / 2));
        graphics.outline(chartX, chartY, chartW, chartH, MUTED);
        for (int k = 1; k < 4; k++) graphics.horizontalLine(chartX + 1, chartX + chartW - 2,
                chartY + k * chartH / 4, 0xFF344354);
        int min = points.stream().mapToInt(StockView.PricePoint::price).min().orElse(row.price());
        int max = points.stream().mapToInt(StockView.PricePoint::price).max().orElse(row.price());
        if (threshold >= min && threshold <= max && max > min) {
            int lineY = chartY + 2 + (int) Math.round((max - threshold) * (chartH - 5.0) / (max - min));
            graphics.horizontalLine(chartX + 1, chartX + chartW - 2, lineY, RED);
            graphics.text(font, "退市线 " + threshold, chartX + 5, Math.max(chartY + 3, lineY - 10), RED, false);
        }
        curve(graphics, points, chartX + 2, chartY + 2, chartW - 4, chartH - 4, direction);
        y += chartH + 7;
        if (!points.isEmpty()) detailPair(graphics, y,
                "游戏日 " + points.getFirst().day() + " → " + points.getLast().day(), MUTED,
                "最高 " + max + " · 最低 " + min, MUTED);
        y += 21;
        detailScrollMax = Math.max(0, y + detailScroll - bottom + 8);
        graphics.disableScissor();
        graphics.horizontalLine(8, width - 8, bottom, 0xFF344354);
        graphics.text(font, "股数", width - 170, height - 66, MUTED, false);
    }

    private int detailHeading(GuiGraphicsExtractor graphics, String label, int y) {
        graphics.horizontalLine(12, width - 12, y, 0xFF344354);
        graphics.text(font, label, 12, y + 5, AMBER, false);
        return y + 22;
    }

    private void detailPair(GuiGraphicsExtractor graphics, int y, String left, int leftColor,
                            String right, int rightColor) {
        int middle = width / 2;
        columnText(graphics, left, 12, y, middle - 8, leftColor);
        columnText(graphics, right, middle + 4, y, width - 12, rightColor);
    }

    private static String signed(long value) { return value >= 0 ? "+" + value : String.valueOf(value); }

    private static String percent(long difference, long base) {
        return base <= 0 ? "--" : String.format(Locale.ROOT, "%+.1f%%", difference * 100.0 / base);
    }

    private static String changeText(double change) {
        return change > 0 ? String.format(Locale.ROOT, "↑%.1f%%", change)
                : change < 0 ? String.format(Locale.ROOT, "↓%.1f%%", -change) : "→0.0%";
    }

    private static String trend(java.util.Map<Integer, StockView.PriceTrend> trends, int days) {
        var value = trends.get(days);
        return value == null ? "--" : signed(value.change()) + " ("
                + String.format(Locale.ROOT, "%+.1f%%", value.percent()) + ")";
    }

    private static void curve(GuiGraphicsExtractor graphics, List<StockView.PricePoint> points,
                              int x, int y, int width, int height, int color) {
        if (points == null || points.isEmpty() || width < 2 || height < 2) return;
        int min = points.stream().mapToInt(StockView.PricePoint::price).min().orElse(0);
        int max = points.stream().mapToInt(StockView.PricePoint::price).max().orElse(0);
        int lastX = x, lastY = y + height / 2;
        for (int i = 0; i < points.size(); i++) {
            int nextX = x + (points.size() == 1 ? width / 2 : i * (width - 1) / (points.size() - 1));
            int nextY = y + (min == max ? height / 2 : (int) Math.round((max - points.get(i).price())
                    * (height - 1.0) / (max - min)));
            if (i == 0) drawCurvePixel(graphics, nextX, nextY, x, y, width, height, color);
            else curveSegment(graphics, lastX, lastY, nextX, nextY, x, y, width, height, color);
            lastX = nextX; lastY = nextY;
        }
    }

    private static void curveSegment(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
                                     int x, int y, int width, int height, int color) {
        StockLineRaster.traceConnected(x0, y0, x1, y1,
                (px, py) -> drawCurvePixel(graphics, px, py, x, y, width, height, color));
    }

    private static void drawCurvePixel(GuiGraphicsExtractor graphics, int px, int py,
                                       int x, int y, int width, int height, int color) {
        if (px >= x && py >= y && px < x + width && py < y + height) {
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static String timeText(int ticks) {
        if (ticks < 0) return "--:--";
        int minute = Math.floorMod(ticks + 6000, 24000) * 1440 / 24000;
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }
}
