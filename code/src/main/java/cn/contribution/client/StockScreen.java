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
    private EditBox search;
    private EditBox quantity;
    private Button industryButton;
    private Button sortButton;
    private Button ownedButton;
    private boolean industryMenu;
    private int sort;
    private boolean ownedOnly;
    private int scroll;
    private long focusedRow = -1;
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
            client.gui.setScreen(new StockScreen(data, snapshot.detail(), snapshot.detail().requestedDays(), false));
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
            })
                    .bounds(17 + searchWidth, 31, 90, 19).build());
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

    private List<String> industryOptions() {
        return dashboard.market().listings().stream().map(StockView.Listing::industry).distinct().sorted().toList();
    }

    private void retainInputs() {
        if (search != null) retainedSearch = search.getValue();
        if (quantity != null) retainedQuantity = quantity.getValue();
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
        return Math.min(row.initialPrice() / 2, (priceRange == null ? row.price() : priceRange.high()) / 4);
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
        if (super.mouseClicked(event, doubleClick)) return true;
        if (detail != null || event.button() != 0) return false;
        if (!profile && industryMenu) {
            int searchWidth = Math.max(90, Math.min(145, width / 4));
            int menuX = 17 + searchWidth;
            if (event.x() >= menuX && event.x() < menuX + 115 && event.y() >= 52
                    && event.y() < 52 + (industryOptions().size() + 1) * 18) {
                int option = ((int) event.y() - 52) / 18;
                if (option == 0) industries.clear();
                else {
                    String value = industryOptions().get(option - 1);
                    if (!industries.add(value)) industries.remove(value);
                }
                industryButton.setMessage(Component.literal(industryLabel()));
                scroll = 0;
                return true;
            }
            industryMenu = false;
        }
        int firstY = profile ? 112 : ROW_TOP;
        if (event.y() < firstY || event.y() >= height - 54) return false;
        int index = scroll + ((int) event.y() - firstY) / ROW_HEIGHT;
        List<StockView.Listing> rows = profile ? ownedRows() : visible();
        if (index < 0 || index >= rows.size()) return false;
        StockView.Listing row = rows.get(index);
        if (!profile && event.x() < 29) {
            if (!selected.add(row.id())) selected.remove(row.id());
        } else if (doubleClick) command("stock check " + row.id() + " month");
        else focusedRow = row.id();
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
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
        scroll = Math.min(scroll, Math.max(0, rows.size() - capacity));
        int priceX = Math.max(225, width * 41 / 100);
        int changeX = width * 52 / 100;
        int riskX = width * 63 / 100;
        int ownedX = width * 75 / 100;
        int chartX = width * 82 / 100;
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
            boolean hovering = mouseX >= 10 && mouseX < width - 10 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            graphics.fill(10, y, width - 10, y + ROW_HEIGHT - 2,
                    hovering || focusedRow == row.id() ? 0xFF34445B : i % 2 == 0 ? 0xB0263444 : 0xB01C2838);
            if (hovering && !industryMenu) graphics.requestCursor(CursorTypes.POINTING_HAND);
            graphics.text(font, selected.contains(row.id()) ? "☑" : "□", 13, y + 20, selected.contains(row.id()) ? GREEN : MUTED, false);
            graphics.text(font, font.plainSubstrByWidth(row.name(), Math.max(30, priceX - 39)), 31, y + 5, TEXT, false);
            graphics.text(font, row.industry() + " · #" + row.id(), 31, y + 22, MUTED, false);
            graphics.text(font, "上市日 " + row.listedDay(), 31, y + 37, MUTED, false);
            int direction = row.price() > previousPrice(row) ? RED : row.price() < previousPrice(row) ? GREEN : MUTED;
            graphics.text(font, String.valueOf(row.price()), priceX, y + 7, direction, false);
            var range = dashboard.ranges().get(row.id());
            if (range != null) graphics.text(font, "高/低 " + range.high() + "/" + range.low(), priceX, y + 29, MUTED, false);
            graphics.text(font, changeText(dailyPercent(row)), changeX, y + 16, direction, false);
            double multiple = riskMultiple(row);
            graphics.text(font, "阈值 " + retirementThreshold(row), riskX, y + 8, multiple <= 1.25 ? RED : MUTED, false);
            graphics.text(font, String.format(Locale.ROOT, "%.2f 倍", multiple), riskX, y + 29,
                    multiple <= 1.25 ? RED : TEXT, false);
            graphics.text(font, String.valueOf(row.owned()), ownedX, y + 17, TEXT, false);
            curve(graphics, dashboard.curves().get(row.id()), chartX, y + 5, width - chartX - 19, 43, direction);
            if (row.status().equals("RETIRING")) graphics.text(font, "今日退市", ownedX, y + 34, RED, false);
        }
        graphics.text(font, "显示 " + rows.size() + " / " + dashboard.market().listings().size()
                + " · 已选 " + selected.size() + " · 双击看详情 · 批量操作逐只成交", 13, height - 43, MUTED, false);
        if (industryMenu) drawIndustryMenu(graphics, mouseX, mouseY);
    }

    private void drawIndustryMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int menuX = 17 + Math.max(90, Math.min(145, width / 4));
        List<String> options = industryOptions();
        graphics.fill(menuX, 52, menuX + 115, 52 + (options.size() + 1) * 18, 0xFF101720);
        graphics.outline(menuX, 52, 115, (options.size() + 1) * 18, MUTED);
        graphics.text(font, industries.isEmpty() ? "☑ 全部行业" : "□ 全部行业", menuX + 5, 57, TEXT, false);
        for (int i = 0; i < options.size(); i++) {
            int y = 52 + (i + 1) * 18;
            if (mouseX >= menuX && mouseX < menuX + 115 && mouseY >= y && mouseY < y + 18)
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
        int threshold = Math.min(row.initialPrice() / 2, detail.range().high() / 4);
        double multiple = row.price() / (double) Math.max(1, threshold);
        graphics.text(font, row.industry() + " · " + row.itemId() + " · "
                + (row.status().equals("RETIRING") ? "今日退市，14:00 自动返还" : "在市"), 12, 31,
                row.status().equals("RETIRING") ? RED : MUTED, false);
        graphics.text(font, "现价 " + row.price() + "  " + changeText(today) + "    昨收 " + previous
                + "    上市价 " + row.initialPrice(), 12, 48, direction, false);
        var rangeValue = detail.range();
        graphics.text(font, "历史最高 " + rangeValue.high() + " · 最低 " + rangeValue.low()
                + " · 上市日 " + row.listedDay() + " · 已上市 " + Math.max(1, clockDay - row.listedDay() + 1) + " 日",
                12, 65, MUTED, false);
        graphics.text(font, "退市阈值 " + threshold + " · 当前/阈值 "
                + String.format(Locale.ROOT, "%.2f", multiple) + " 倍 · 股价上限 " + row.initialPrice() * 10,
                12, 82, multiple <= 1.25 ? RED : MUTED, false);
        if (multiple <= 1.25) graphics.text(font, "⚠ 接近退市线，持有者请关注当天交易窗口", 12, 99, RED, false);
        var position = detail.position();
        if (row.owned() > 0 && position != null) {
            long gain = (long) row.price() * row.owned() - position.costBasis();
            graphics.text(font, "持仓 " + row.owned() + " 股 · 成本 " + position.costBasis() + " · 均价 "
                    + String.format(Locale.ROOT, "%.2f", position.costBasis() / (double) row.owned())
                    + " · 未实现 " + signed(gain) + " (" + percent(gain, position.costBasis()) + ")",
                    12, 116, gain >= 0 ? RED : GREEN, false);
            graphics.text(font, "首次买入游戏日 " + position.firstBuyDay() + " · 最近买入游戏日 "
                    + position.lastBuyDay() + " · 最近买价 " + position.lastBuyPrice()
                    + " · 已实现 " + signed(position.realizedProfit()), 12, 132, MUTED, false);
        } else graphics.text(font, "尚未持有 · 买卖手续费在成交时计算", 12, 116, MUTED, false);
        List<StockView.PricePoint> points = detail.prices();
        graphics.text(font, "较前 7 日 " + trend(detail.trends(), 7) + " · 较前 30 日 " + trend(detail.trends(), 30)
                + " · 较前 360 日 " + trend(detail.trends(), 360), 12, 150, MUTED, false);
        graphics.text(font, "下图为逐游戏日股价记录；不足对应天数时不计算区间涨跌", 12, 165, MUTED, false);
        int chartX = 25, chartY = 184, chartW = Math.max(50, width - 50), chartH = Math.max(35, height - 267);
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
        if (!points.isEmpty()) graphics.text(font, "游戏日 " + points.getFirst().day() + " → " + points.getLast().day(),
                chartX, chartY + chartH + 5, MUTED, false);
        graphics.text(font, "最高 " + max + " · 最低 " + min, chartX, chartY + chartH + 18, MUTED, false);
        graphics.text(font, "股数", width - 170, height - 66, MUTED, false);
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
