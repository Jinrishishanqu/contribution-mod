package cn.contribution.ui;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.stock.StockChart;
import cn.contribution.stock.StockService;
import cn.contribution.stock.StockView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dialog.ActionButton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** All stock screens are native vanilla dialogs, including the Braille price chart. */
final class StockDialogs {
    private StockDialogs() { }

    static void market(CommandSourceStack source, int page, String sort, String filter) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.market(source.getPlayer().getUUID()), market -> {
            List<StockView.Listing> stocks = new ArrayList<>(market.listings());
            if (!filter.equals("all")) stocks.removeIf(stock -> !stock.name().contains(filter)
                    && !stock.industry().contains(filter) && !stock.itemId().contains(filter));
            stocks.sort(sort.equals("price") ? Comparator.comparingInt(StockView.Listing::price).reversed()
                    : Comparator.comparing(StockView.Listing::name));
            int count = Math.max(1, (stocks.size() + 7) / 8);
            int current = Math.max(0, Math.min(page, count - 1));
            List<ActionButton> buttons = new ArrayList<>();
            for (int i = current * 8; i < Math.min(stocks.size(), current * 8 + 8); i++) {
                StockView.Listing stock = stocks.get(i);
                buttons.add(ContributionDialogs.button(stock.name() + " " + stock.price() + " · 持有 " + stock.owned(),
                        "stock-detail " + stock.id() + " 7"));
            }
            if (current > 0) buttons.add(ContributionDialogs.button("上一页", "stock " + (current - 1) + " " + sort + " " + filter));
            if (current + 1 < count) buttons.add(ContributionDialogs.button("下一页", "stock " + (current + 1) + " " + sort + " " + filter));
            buttons.add(ContributionDialogs.button("按名称排序", "stock 0 name " + filter));
            buttons.add(ContributionDialogs.button("按股价排序", "stock 0 price " + filter));
            buttons.add(ContributionDialogs.template("筛选", "contribution ui stock 0 " + sort + " $(filter)"));
            buttons.add(ContributionDialogs.button("首页", "home"));
            List<String> lines = new ArrayList<>();
            lines.add("核算日 " + market.day() + " · 交易时间 10:00—12:00 · 手续费 2%");
            lines.add(stocks.isEmpty() ? "暂无符合条件的上市股票" : "第 " + (current + 1) + "/" + count + " 页，点击股票查看走势和交易");
            ContributionDialogs.show(source, "股票市场", lines,
                    List.of(ContributionDialogs.input("filter", "股票名称、物品 ID 或行业；all 显示全部", filter, 64)), buttons);
        });
    }

    static void detail(CommandSourceStack source, String symbol, int days) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.detail(source.getPlayer().getUUID(), symbol, days), detail -> {
            if (detail == null) { message(source, "未找到这支股票"); return; }
            List<StockView.PricePoint> points = StockChart.aggregate(detail.prices(), days);
            List<String> lines = new ArrayList<>();
            StockView.Listing stock = detail.listing();
            lines.add(stock.industry() + " · " + stock.itemId() + " · " + stock.status());
            lines.add("现价 " + stock.price() + " · 初始价 " + stock.initialPrice() + " · 持有 " + stock.owned());
            lines.addAll(StockChart.draw(points));
            List<ActionButton> buttons = new ArrayList<>();
            buttons.add(ContributionDialogs.button("近 7 日", "stock-detail " + stock.id() + " 7"));
            buttons.add(ContributionDialogs.button("近 30 日", "stock-detail " + stock.id() + " 30"));
            buttons.add(ContributionDialogs.button("近 360 日", "stock-detail " + stock.id() + " 360"));
            if (stock.status().equals("ACTIVE")) {
                buttons.add(ContributionDialogs.template("买入", "contribution stock buy " + stock.id() + " $(quantity)"));
            }
            if (!stock.status().equals("DELISTED")) {
                buttons.add(ContributionDialogs.template("卖出", "contribution stock sell " + stock.id() + " $(quantity)"));
            }
            buttons.add(ContributionDialogs.button("返回市场", "stock 0 name all"));
            ContributionDialogs.show(source, stock.name() + " · 股价曲线", lines,
                    List.of(ContributionDialogs.input("quantity", "交易股数（1—10000）", "1", 5)), buttons);
        });
    }

    private static void message(CommandSourceStack source, String text) {
        ContributionDialogs.show(source, "股票", List.of(text), List.of(),
                List.of(ContributionDialogs.button("返回市场", "stock 0 name all")));
    }

    private static <T> void query(CommandSourceStack source, CompletableFuture<T> task, Consumer<T> done) {
        task.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (source.getPlayer() == null || source.getPlayer().hasDisconnected()) return;
            if (error != null) message(source, "股票数据暂时不可用，请稍后重试");
            else done.accept(value);
        }));
    }
}
