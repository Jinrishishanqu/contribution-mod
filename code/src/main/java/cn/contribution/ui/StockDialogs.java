package cn.contribution.ui;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.stock.StockChart;
import cn.contribution.stock.StockService;
import cn.contribution.stock.StockView;
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
        query(source, service.market(source.getPlayer().getUUID()), market -> {
            List<StockView.Listing> stocks = new ArrayList<>(market.listings());
            if (!filter.equals("all")) stocks.removeIf(stock -> !stock.name().contains(filter)
                    && !stock.industry().contains(filter) && !stock.itemId().contains(filter));
            stocks.sort(sort.equals("price") ? Comparator.comparingInt(StockView.Listing::price).reversed()
                    : Comparator.comparing(StockView.Listing::name));
            List<ActionButton> buttons = new ArrayList<>();
            for (StockView.Listing stock : stocks) {
                buttons.add(button(stock.name() + " " + stock.price(),
                        "check " + stock.id() + " week"));
            }
            buttons.add(button("按名称排序", "browse name all"));
            buttons.add(button("按股价排序", "browse price all"));
            buttons.add(ContributionDialogs.template("筛选", "stock browse " + sort + " $(filter)"));
            List<String> lines = new ArrayList<>();
            lines.add("核算日 " + market.day() + " · 当前 " + timeText(market.time()) + " · 交易时间 10:00—14:00 · 手续费 2%");
            lines.add(stocks.isEmpty() ? "暂无符合条件的上市股票" : "本页列出全部 " + stocks.size() + " 支股票；点击查看走势和交易");
            show(source, "股票市场", lines,
                    List.of(ContributionDialogs.input("filter", "股票名称、物品 ID 或行业；all 显示全部", filter, 64)), buttons);
        });
    }

    public static void detail(CommandSourceStack source, String symbol, int days) {
        StockService service = ContributionRuntime.stocks();
        if (service == null) { message(source, "股票数据服务尚未启动"); return; }
        query(source, service.detail(source.getPlayer().getUUID(), symbol, days), detail -> {
            if (detail == null) { message(source, "未找到这支股票"); return; }
            List<StockView.PricePoint> points = StockChart.aggregate(detail.prices(), days);
            List<String> lines = new ArrayList<>();
            StockView.Listing stock = detail.listing();
            lines.add(stock.industry() + " · " + stock.itemId() + " · " + stock.status());
            lines.add("现价 " + stock.price() + " · 初始价 " + stock.initialPrice() + " · 持有 " + stock.owned());
            lines.add("历史最高 " + detail.range().high() + " · 历史最低 " + detail.range().low());
            lines.addAll(StockChart.draw(points));
            List<ActionButton> buttons = new ArrayList<>();
            buttons.add(button("近 7 日", "check " + stock.id() + " week"));
            buttons.add(button("近 30 日", "check " + stock.id() + " month"));
            buttons.add(button("近 360 日", "check " + stock.id() + " year"));
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

    private static void message(CommandSourceStack source, String text) {
        show(source, "股票", List.of(text), List.of(), List.of(button("返回市场", "browse name all")));
    }

    private static ActionButton button(String label, String command) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 100),
                Optional.of(new StaticAction(new ClickEvent.RunCommand("/stock " + command))));
    }

    private static void show(CommandSourceStack source, String title, List<String> lines,
                             List<net.minecraft.server.dialog.Input> inputs, List<ActionButton> buttons) {
        source.getPlayer().openDialog(Holder.direct(ContributionDialogs.create(title, lines, inputs, buttons, false, 4)));
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
