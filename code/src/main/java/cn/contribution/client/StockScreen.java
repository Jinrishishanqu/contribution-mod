package cn.contribution.client;

import cn.contribution.stock.StockUiNetwork;
import cn.contribution.stock.StockView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Draws actual GUI pixels for stock curves; data and trades remain server-authoritative. */
final class StockScreen extends Screen {
    private static final int BACKGROUND = 0xF018202B;
    private static final int TEXT = 0xFFE7EBF2;
    private static final int MUTED = 0xFF9AA8BC;
    private static final int GREEN = 0xFF57D78C;
    private static final int RED = 0xFFF07575;
    private static StockScreen lastMarket;
    private static long clockDay = -1;
    private static int clockTime = -1;

    private final StockView.Dashboard dashboard;
    private final StockView.Detail detail;
    private final int range;
    private final Set<Long> selected = new HashSet<>();
    private EditBox search;
    private EditBox quantity;
    private Button industryButton;
    private Button sortButton;
    private Button ownedButton;
    private String industry = "全部行业";
    private int sort;
    private boolean ownedOnly;
    private int scroll;

    private StockScreen(StockView.Dashboard dashboard, StockView.Detail detail, int range) {
        super(Component.literal(detail == null ? "股票市场" : "股票详情"));
        this.dashboard = dashboard;
        this.detail = detail;
        this.range = range;
    }

    static void receive(StockUiNetwork.Snapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if ("clock".equals(snapshot.view())) {
            clockDay = snapshot.day(); clockTime = snapshot.time(); return;
        }
        if ("market".equals(snapshot.view()) && snapshot.dashboard() != null) {
            clockDay = snapshot.day(); clockTime = snapshot.time();
            lastMarket = new StockScreen(snapshot.dashboard(), null, 30);
            client.gui.setScreen(lastMarket);
        } else if ("detail".equals(snapshot.view()) && snapshot.detail() != null) {
            StockView.Dashboard data = lastMarket == null ? null : lastMarket.dashboard;
            client.gui.setScreen(new StockScreen(data, snapshot.detail(), snapshot.detail().requestedDays()));
        }
    }

    static void clear() {
        lastMarket = null; clockDay = -1; clockTime = -1;
    }

    @Override protected void init() {
        int center = width / 2;
        if (detail == null) {
            int searchWidth = Math.max(90, Math.min(145, width / 4));
            search = addRenderableWidget(new EditBox(font, 12, 31, searchWidth, 19,
                    Component.literal("搜索股票名称或物品 ID")));
            search.setHint(Component.literal("搜索名称或物品 ID"));
            search.setMaxLength(64);
            industryButton = addRenderableWidget(Button.builder(Component.literal(industry), button -> cycleIndustry())
                    .bounds(17 + searchWidth, 31, 90, 19).build());
            sortButton = addRenderableWidget(Button.builder(Component.literal(sortLabel()), button -> {
                sort = (sort + 1) % 4; sortButton.setMessage(Component.literal(sortLabel())); scroll = 0;
            }).bounds(112 + searchWidth, 31, 85, 19).build());
            ownedButton = addRenderableWidget(Button.builder(Component.literal(ownedOnly ? "仅持仓：开" : "仅持仓：关"), button -> {
                ownedOnly = !ownedOnly; ownedButton.setMessage(Component.literal(ownedOnly ? "仅持仓：开" : "仅持仓：关")); scroll = 0;
            }).bounds(202 + searchWidth, 31, 86, 19).build());
            int footer = height - 27;
            quantity = addRenderableWidget(new EditBox(font, 12, footer, 45, 19, Component.literal("交易股数")));
            quantity.setValue("1"); quantity.setMaxLength(5);
            addRenderableWidget(Button.builder(Component.literal("批量买入"), button -> batch(true)).bounds(62, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("批量卖出"), button -> batch(false)).bounds(133, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("全选可见"), button -> {
                for (StockView.Listing row : visible()) selected.add(row.id());
            }).bounds(204, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("清空选择"), button -> selected.clear())
                    .bounds(275, footer, 66, 19).build());
            addRenderableWidget(Button.builder(Component.literal("刷新"), button -> command("stock"))
                    .bounds(width - 57, footer, 45, 19).build());
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
        return switch (sort) { case 1 -> "价格↓"; case 2 -> "价格↑"; case 3 -> "持仓↓"; default -> "名称"; };
    }

    private void cycleIndustry() {
        List<String> options = new ArrayList<>(); options.add("全部行业");
        for (StockView.Listing row : dashboard.market().listings())
            if (!options.contains(row.industry())) options.add(row.industry());
        industry = options.get((options.indexOf(industry) + 1) % options.size());
        industryButton.setMessage(Component.literal(industry)); scroll = 0;
    }

    private List<StockView.Listing> visible() {
        if (dashboard == null) return List.of();
        String term = search == null ? "" : search.getValue().strip().toLowerCase(java.util.Locale.ROOT);
        List<StockView.Listing> rows = new ArrayList<>();
        for (StockView.Listing row : dashboard.market().listings()) {
            if (ownedOnly && row.owned() == 0) continue;
            if (!industry.equals("全部行业") && !industry.equals(row.industry())) continue;
            if (!term.isEmpty() && !row.name().toLowerCase(java.util.Locale.ROOT).contains(term)
                    && !row.itemId().toLowerCase(java.util.Locale.ROOT).contains(term)) continue;
            rows.add(row);
        }
        rows.sort(switch (sort) {
            case 1 -> Comparator.comparingInt(StockView.Listing::price).reversed();
            case 2 -> Comparator.comparingInt(StockView.Listing::price);
            case 3 -> Comparator.comparingInt(StockView.Listing::owned).reversed();
            default -> Comparator.comparing(StockView.Listing::name);
        });
        return rows;
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
        if (super.mouseClicked(event, doubleClick)) return true;
        if (detail != null || event.button() != 0 || event.y() < 77 || event.y() >= height - 34) return false;
        int index = scroll + ((int) event.y() - 77) / 11;
        List<StockView.Listing> rows = visible();
        if (index < 0 || index >= rows.size()) return false;
        StockView.Listing row = rows.get(index);
        if (event.x() < 29) {
            if (!selected.add(row.id())) selected.remove(row.id());
        } else command("stock check " + row.id() + " month");
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (detail == null && mouseY >= 77 && mouseY < height - 34) {
            int capacity = Math.max(1, (height - 111) / 11);
            scroll = Math.max(0, Math.min(Math.max(0, visible().size() - capacity), scroll - (int) Math.signum(vertical) * 3));
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
        if (detail == null) drawMarket(graphics, mouseX, mouseY);
        else drawDetail(graphics);
    }

    private void drawMarket(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<StockView.Listing> rows = visible();
        int capacity = Math.max(1, (height - 111) / 11);
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        graphics.text(font, "选择   股票 / 行业                         现价       历史高/低      近 30 日曲线", 13, 62, MUTED, false);
        for (int i = scroll; i < Math.min(rows.size(), scroll + capacity); i++) {
            StockView.Listing row = rows.get(i);
            int y = 77 + (i - scroll) * 11;
            graphics.fill(10, y - 1, width - 10, y + 10,
                    mouseY >= y && mouseY < y + 11 ? 0xFF34445B : i % 2 == 0 ? 0xB0263444 : 0xB01C2838);
            graphics.text(font, selected.contains(row.id()) ? "☑" : "□", 13, y, selected.contains(row.id()) ? GREEN : MUTED, false);
            int priceX = Math.max(205, width / 2 - 15);
            graphics.text(font, font.plainSubstrByWidth(row.name() + " · " + row.industry(),
                    Math.max(30, priceX - 38)), 31, y, TEXT, false);
            graphics.text(font, String.valueOf(row.price()), priceX, y, row.status().equals("RETIRING") ? RED : GREEN, false);
            var range = dashboard.ranges().get(row.id());
            if (range != null) graphics.text(font, range.high() + "/" + range.low(), priceX + 55, y, MUTED, false);
            graphics.text(font, "持 " + row.owned(), width - 144, y, MUTED, false);
            curve(graphics, dashboard.curves().get(row.id()), width - 82, y, 68, 9, GREEN);
        }
        graphics.text(font, "显示 " + rows.size() + " / " + dashboard.market().listings().size()
                + " · 已选 " + selected.size() + " · 鼠标滚动查看全部 · 批量操作逐只成交", 13, height - 40, MUTED, false);
    }

    private void drawDetail(GuiGraphicsExtractor graphics) {
        StockView.Listing row = detail.listing();
        graphics.text(font, row.industry() + "  ·  " + row.itemId() + "  ·  " + row.status(), 12, 32, MUTED, false);
        graphics.text(font, "现价 " + row.price() + "    初始价 " + row.initialPrice() + "    持有 " + row.owned(), 12, 47, TEXT, false);
        var rangeValue = detail.range();
        if (rangeValue != null) graphics.text(font, "历史最高 " + rangeValue.high() + "    历史最低 " + rangeValue.low(), 12, 62, MUTED, false);
        int chartX = 25, chartY = 88, chartW = Math.max(50, width - 50), chartH = Math.max(35, height - 159);
        graphics.outline(chartX, chartY, chartW, chartH, MUTED);
        for (int k = 1; k < 4; k++) graphics.horizontalLine(chartX + 1, chartX + chartW - 2,
                chartY + k * chartH / 4, 0xFF344354);
        List<StockView.PricePoint> points = cn.contribution.stock.StockChart.aggregate(detail.prices(),
                range <= 7 ? 7 : range <= 30 ? 30 : 360);
        curve(graphics, points, chartX + 2, chartY + 2, chartW - 4, chartH - 4, GREEN);
        if (!points.isEmpty()) graphics.text(font, "游戏日 " + points.getFirst().day() + " → " + points.getLast().day(),
                chartX, chartY + chartH + 5, MUTED, false);
        graphics.text(font, "股数", width - 170, height - 66, MUTED, false);
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
            if (i == 0) graphics.fill(nextX, nextY, nextX + 1, nextY + 1, color);
            else for (int px = lastX; px <= nextX; px++) {
                double ratio = nextX == lastX ? 1 : (px - lastX) / (double) (nextX - lastX);
                int py = (int) Math.round(lastY + ratio * (nextY - lastY));
                graphics.fill(px, py, px + 1, py + 1, color);
            }
            lastX = nextX; lastY = nextY;
        }
    }

    private static String timeText(int ticks) {
        if (ticks < 0) return "--:--";
        int minute = Math.floorMod(ticks + 6000, 24000) * 1440 / 24000;
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }
}
