package cn.contribution.command;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.stock.StockService;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class StockCommands {
    private StockCommands() { }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("stock")
                .executes(context -> { cn.contribution.stock.StockUiNetwork.market(context.getSource()); return 1; })
                .then(side("buy", true))
                .then(side("sell", false))
                .then(Commands.literal("portfolio")
                        .executes(context -> { cn.contribution.stock.StockUiNetwork.portfolio(context.getSource()); return 1; }))
                .then(Commands.literal("browse")
                        .then(Commands.argument("sort", StringArgumentType.word())
                                .executes(context -> browse(context, "all"))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(context -> browse(context, StringArgumentType.getString(context, "filter"))))))
                .then(Commands.literal("batch")
                        .then(Commands.argument("side", StringArgumentType.word())
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 10000))
                                        .then(Commands.argument("stockIds", StringArgumentType.greedyString())
                                                .executes(context -> batch(context, UUID.randomUUID(), false))))))
                .then(Commands.literal("batch-retry")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("side", StringArgumentType.word())
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 10000))
                                                .then(Commands.argument("stockIds", StringArgumentType.greedyString())
                                                        .executes(context -> batch(context, null, true)))))))
                .then(Commands.literal("check")
                        .then(Commands.argument("symbol", StringArgumentType.word())
                                .executes(context -> check(context, 7))
                                .then(Commands.argument("range", StringArgumentType.word())
                                        .executes(context -> check(context, days(StringArgumentType.getString(context, "range")))))))
                .then(Commands.literal("claim").executes(StockCommands::claim))
                .then(Commands.literal("retry")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("side", StringArgumentType.word())
                                        .then(Commands.argument("symbol", StringArgumentType.word())
                                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 10000))
                                                        .executes(StockCommands::retry))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> side(String name, boolean buy) {
        return Commands.literal(name).then(Commands.argument("symbol", StringArgumentType.word())
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 10000))
                        .executes(context -> trade(context, buy, UUID.randomUUID()))));
    }

    private static int browse(CommandContext<CommandSourceStack> context, String filter) {
        if (context.getSource().getPlayer() == null) { error(context.getSource(), "请在游戏内浏览股票"); return 0; }
        String sort = StringArgumentType.getString(context, "sort");
        if (!sort.equals("name") && !sort.equals("price")) { error(context.getSource(), "排序方式只能是 name 或 price"); return 0; }
        if (filter.length() > 64) { error(context.getSource(), "筛选词过长"); return 0; }
        cn.contribution.ui.StockDialogs.market(context.getSource(), 0, sort, filter);
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> context, int days) {
        if (days == 0) { error(context.getSource(), "查询范围只能是 week、month 或 year"); return 0; }
        cn.contribution.stock.StockUiNetwork.detail(context.getSource(), StringArgumentType.getString(context, "symbol"), days);
        return 1;
    }

    private static int days(String range) {
        return switch (range) {
            case "week" -> 7;
            case "month" -> 30;
            case "year" -> 360;
            default -> 0;
        };
    }

    private static int retry(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        UUID id;
        try { id = UUID.fromString(StringArgumentType.getString(context, "id")); }
        catch (IllegalArgumentException error) { error(context.getSource(), "请求 ID 格式无效"); return 0; }
        String side = StringArgumentType.getString(context, "side");
        if (!side.equals("buy") && !side.equals("sell")) { error(context.getSource(), "交易方向只能是 buy 或 sell"); return 0; }
        return trade(context, side.equals("buy"), id);
    }

    private static int batch(CommandContext<CommandSourceStack> context, UUID generated, boolean retry) {
        CommandSourceStack source = context.getSource();
        if (!RequestLimiter.allow(source)) { error(source, "操作过快，请稍后重试"); return 0; }
        if (source.getPlayer() == null) { error(source, "请在游戏内交易"); return 0; }
        StockService service = ContributionRuntime.stocks();
        if (service == null) { error(source, "股票市场尚未启动"); return 0; }
        String side = StringArgumentType.getString(context, "side");
        if (!side.equals("buy") && !side.equals("sell")) { error(source, "方向只能是 buy 或 sell"); return 0; }
        String ids = StringArgumentType.getString(context, "stockIds").strip();
        String[] parts = ids.split(",", -1);
        if (parts.length < 1 || parts.length > 20) { error(source, "一次最多选择 20 支股票"); return 0; }
        List<String> symbols = new ArrayList<>();
        try {
            for (String part : parts) {
                long id = Long.parseLong(part);
                if (id <= 0 || symbols.contains(part)) throw new IllegalArgumentException();
                symbols.add(part);
            }
        } catch (IllegalArgumentException invalid) { error(source, "股票 ID 列表无效或重复"); return 0; }
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        UUID request;
        try { request = retry ? UUID.fromString(StringArgumentType.getString(context, "id")) : generated; }
        catch (IllegalArgumentException invalid) { error(source, "批量请求 ID 无效"); return 0; }
        String retryCommand = "/stock batch-retry " + request + " " + side + " " + quantity + " " + ids;
        source.sendSuccess(() -> Component.literal("[股票] 批量请求 ID " + request + "；逐只成交，失败可用 " + retryCommand + " 重试"), false);
        UUID player = source.getPlayer().getUUID();
        service.openBatch(player, request, ids, quantity, side.equals("buy"))
                .whenComplete((accepted, failure) -> source.getServer().execute(() -> {
                    if (failure != null) { error(source, "批量请求登记失败；请保留原请求 ID 重试"); return; }
                    if (!accepted) { error(source, "批量请求 ID 已用于不同交易列表或参数"); return; }
                    submitBatch(source, service, player, request, side, quantity, symbols);
                }));
        return 1;
    }

    private static void submitBatch(CommandSourceStack source, StockService service, UUID player, UUID request,
                                    String side, int quantity, List<String> symbols) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String symbol : symbols) {
            UUID itemRequest = UUID.nameUUIDFromBytes((request + "|" + symbol).getBytes(StandardCharsets.UTF_8));
            chain = chain.thenCompose(ignored -> service.trade(player, symbol, quantity, side.equals("buy"), itemRequest)
                    .handle((result, failure) -> {
                        source.getServer().execute(() -> {
                            if (failure != null) error(source, symbol + " 数据暂时不可用，请保留批量请求 ID 重试");
                            else if (!result.success()) error(source, symbol + "：" + result.message());
                            else source.sendSuccess(() -> Component.literal("[股票] " + symbol + "：" + result.message()
                                    + "，成交价 " + result.price() + "，余额 " + result.balance()), false);
                        });
                        return null;
                    }));
        }
    }

    private static int trade(CommandContext<CommandSourceStack> context, boolean buy, UUID id) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!RequestLimiter.allow(source)) { error(source, "操作过快，请稍后重试"); return 0; }
        StockService service = ContributionRuntime.stocks();
        if (service == null) { error(source, "股票数据服务尚未启动"); return 0; }
        UUID player = source.getPlayerOrException().getUUID();
        String symbol = StringArgumentType.getString(context, "symbol");
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        source.sendSuccess(() -> Component.literal("[股票] 请求 ID：" + id + "；网络异常时使用 /stock retry "
                + id + " " + (buy ? "buy" : "sell") + " " + symbol + " " + quantity), false);
        service.trade(player, symbol, quantity, buy, id).whenComplete((result, error) -> source.getServer().execute(() -> {
            if (error != null) { error(source, "数据暂时不可用，请使用原请求 ID 重试"); return; }
            if (!result.success()) { error(source, result.message()); return; }
            source.sendSuccess(() -> Component.literal("[股票] " + result.message() + "；股数 " + result.quantity()
                    + "，成交价 " + result.price() + "，手续费 " + result.fee()
                    + "，当前余额 " + result.balance()), false);
        }));
        return 1;
    }

    private static int claim(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!RequestLimiter.allow(source)) { error(source, "操作过快，请稍后重试"); return 0; }
        StockService service = ContributionRuntime.stocks();
        if (service == null) { error(source, "股票数据服务尚未启动"); return 0; }
        UUID id = UUID.randomUUID();
        source.sendSuccess(() -> Component.literal("[股票] 退市返还请求 ID：" + id), false);
        service.claim(source.getPlayerOrException().getUUID(), id)
                .whenComplete((message, failure) -> source.getServer().execute(() -> {
                    if (failure != null) error(source, "退市返还暂时不可用");
                    else source.sendSuccess(() -> Component.literal("[股票] " + message), false);
                }));
        return 1;
    }

    private static void error(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[股票] " + message));
    }
}
