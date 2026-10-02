package cn.contribution.ui;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.stock.StockChart;
import cn.contribution.stock.StockService;
import cn.contribution.stock.StockView;
import cn.contribution.stock.StockPricing;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Vanilla-client fallback; modded clients use the optional pixel-rendered screen. */
public final class StockDialogs {
    private StockDialogs() { }

    public static void market(CommandSourceStack source, int page, String sort, String filter) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.dashboard(source.getPlayer().getUUID()), dashboard -> {
            StockView.Market market = dashboard.market();
            List<StockView.Listing> stocks = new ArrayList<>(market.listings());
            if (!filter.equals("all")) stocks.removeIf(stock -> !stock.name().contains(filter)
                    && !stock.industry().contains(filter) && !stock.itemId().contains(filter));
            stocks.sort(switch (sort) {
                case "price" -> Comparator.comparingInt(StockView.Listing::price).reversed();
                case "change" -> Comparator.comparingDouble((StockView.Listing value) -> change(dashboard, value)).reversed();
                case "risk" -> Comparator.comparingDouble((StockView.Listing value) -> risk(dashboard, value));
                case "owned" -> Comparator.comparingInt(StockView.Listing::owned).reversed();
                default -> Comparator.comparing(StockView.Listing::name);
            });
            List<ActionButton> buttons = new ArrayList<>();
            for (StockView.Listing stock : stocks) {
                String change = String.format(java.util.Locale.ROOT, "%+.1f%%", change(dashboard, stock));
                int high = dashboard.ranges().get(stock.id()) == null ? stock.price() : dashboard.ranges().get(stock.id()).high();
                int threshold = StockPricing.retirementThreshold(stock.initialPrice(), high);
                buttons.add(button(stock.name() + " #" + stock.id() + "  " + stock.price() + "  " + change
                                + "  阈" + threshold + (stock.owned() > 0 ? "  持" + stock.owned() : ""),
                        "check " + stock.id() + " year"));
            }
            buttons.add(button("按名称排序", "browse name " + filter));
            buttons.add(button("按股价排序", "browse price " + filter));
            buttons.add(button("按涨跌排序", "browse change " + filter));
            buttons.add(button("按退市风险排序", "browse risk " + filter));
            buttons.add(button("按持仓排序", "browse owned " + filter));
            buttons.add(button("我的股票", "portfolio"));
            buttons.add(ContributionDialogs.template("筛选", "stock browse " + sort + " $(filter)"));
            List<String> lines = new ArrayList<>();
            boolean settled = market.day() == (market.clockTime() < 2000 ? market.clockDay() - 1 : market.clockDay());
            lines.add((market.clockFresh()
                    ? "主世界游戏日 " + market.clockDay() + " · 当前 " + timeText(market.clockTime())
                    : "主服务器时钟暂不可用")
                    + " · 市场核算日 " + market.day() + (settled ? "" : " · 正在等待日结")
                    + " · 交易时间 10:00—14:00 · 手续费 2%");
            lines.add(stocks.isEmpty() ? "暂无符合条件的上市股票" : "共 " + stocks.size() + " 支 · 每项依次为股价、当日涨跌、退市阈值及持仓；点击看详情");
            show(source, "股票市场", lines,
                    List.of(ContributionDialogs.input("filter", "股票名称、物品 ID 或行业；all 显示全部", filter, 64)), buttons);
        });
    }

    private static double change(StockView.Dashboard dashboard, StockView.Listing stock) {
        var points = dashboard.curves().get(stock.id());
        int previous = points == null || points.size() < 2 ? stock.price() : points.get(points.size() - 2).price();
        return previous == 0 ? 0 : (stock.price() - previous) * 100.0 / previous;
    }

    private static double risk(StockView.Dashboard dashboard, StockView.Listing stock) {
        var range = dashboard.ranges().get(stock.id());
        int high = range == null ? stock.price() : range.high();
        return stock.price() / (double) StockPricing.retirementThreshold(stock.initialPrice(), high);
    }

    public static void detail(CommandSourceStack source, String symbol, int days) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.detail(source.getPlayer().getUUID(), symbol, days), detail -> {
            if (detail == null) { message(source, "未找到这支股票"); return; }
            List<StockView.PricePoint> points = detail.prices();
            List<String> lines = new ArrayList<>();
            StockView.Listing stock = detail.listing();
            lines.add(stock.industry() + " · " + stock.itemId() + " · " + stock.status());
            lines.add("现价 " + stock.price() + " · 初始价 " + stock.initialPrice() + " · 持有 " + stock.owned());
            lines.add("历史最高 " + detail.range().high() + " · 历史最低 " + detail.range().low());
            int threshold = cn.contribution.stock.StockPricing.retirementThreshold(
                    stock.initialPrice(), detail.range().high());
            lines.add("上市游戏日 " + stock.listedDay() + " · 退市阈值 " + threshold + " · 上限 " + stock.initialPrice() * 10);
            if (points.size() >= 2) {
                int old = detail.previousPrice();
                lines.add("当日涨跌 " + String.format(java.util.Locale.ROOT, "%+.1f%%",
                        old == 0 ? 0 : (stock.price() - old) * 100.0 / old));
            }
            for (int span : new int[]{7, 30, 360}) {
                var trend = detail.trends().get(span);
                if (trend != null) lines.add("较前 " + span + " 日：" + (trend.change() >= 0 ? "+" : "")
                        + trend.change() + "，" + String.format(java.util.Locale.ROOT, "%+.1f%%", trend.percent()));
            }
            if (stock.owned() > 0 && detail.position() != null) {
                long gain = (long) stock.price() * stock.owned() - detail.position().costBasis();
                lines.add("持仓成本 " + detail.position().costBasis() + " · 未实现收益 " + gain
                        + " · 最近买入游戏日 " + detail.position().lastBuyDay()
                        + " · 最近买价 " + detail.position().lastBuyPrice());
            }
            lines.add("以下为历史股价文字走势图，横轴为游戏日；安装客户端模组可查看像素曲线");
            lines.addAll(StockChart.draw(points));
            List<ActionButton> buttons = new ArrayList<>();
            buttons.add(button("近 7 日", "check " + stock.id() + " week"));
            buttons.add(button("近 30 日", "check " + stock.id() + " month"));
            buttons.add(button("近 360 日", "check " + stock.id() + " year"));
            buttons.add(button("全部", "check " + stock.id() + " all"));
            if (stock.status().equals("ACTIVE")) {
                buttons.add(ContributionDialogs.template("买入", "stock buy " + stock.id() + " $(quantity)"));
            }
            if (!stock.status().equals("DELISTED")) {
                buttons.add(ContributionDialogs.template("卖出", "stock sell " + stock.id() + " $(quantity)"));
            }
            buttons.add(button("返回市场", "browse name all"));
            show(source, stock.name() + " · 股价曲线", lines,
                    List.of(ContributionDialogs.input("quantity", "交易股数（1—10000）", "1", 5)), buttons);
        });
    }

    public static void portfolio(CommandSourceStack source) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.dashboard(source.getPlayer().getUUID()), dashboard -> {
            var portfolio = dashboard.portfolio();
            List<String> lines = new ArrayList<>();
            lines.add("余额 " + portfolio.balance() + " · 持仓市值 " + portfolio.marketValue() + " · 成本 " + portfolio.costBasis());
            lines.add("未实现盈亏 " + portfolio.unrealizedProfit() + " · 已实现盈亏 " + portfolio.realizedProfit());
            List<ActionButton> buttons = new ArrayList<>();
            for (StockView.Listing stock : dashboard.market().listings()) if (stock.owned() > 0) {
                var position = portfolio.positions().get(stock.id());
                long gain = (long) stock.price() * stock.owned() - (position == null ? 0 : position.costBasis());
                buttons.add(button(stock.name() + " ×" + stock.owned() + " · 盈亏 " + gain,
                        "check " + stock.id() + " year"));
            }
            buttons.add(button("返回市场", "browse name all"));
            show(source, "我的股票", lines, List.of(), buttons);
        });
    }

    private static void message(CommandSourceStack source, String text) {
        show(source, "股票", List.of(text), List.of(), List.of(button("返回市场", "browse name all")));
    }

    private static ActionButton button(String label, String command) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 190),
                Optional.of(new StaticAction(new ClickEvent.RunCommand("/stock " + command))));
    }

    private static void show(CommandSourceStack source, String title, List<String> lines,
                             List<net.minecraft.server.dialog.Input> inputs, List<ActionButton> buttons) {
        source.getPlayer().openDialog(Holder.direct(ContributionDialogs.create(title, lines, inputs, buttons, false, 2)));
    }

    private static String timeText(int ticks) {
        int minute = Math.floorMod(ticks + 6000, 24000) * 1440 / 24000;
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }

    private static <T> void query(CommandSourceStack source, CompletableFuture<T> task, Consumer<T> done) {
        task.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (source.getPlayer() == null || source.getPlayer().hasDisconnected()) return;
            if (error != null) message(source, "股票数据暂时不可用，请稍后重试");
            else done.accept(value);
        }));
    }
}
