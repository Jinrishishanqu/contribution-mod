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

final class StockCommands {
    private StockCommands() { }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("stock")
                .executes(context -> cn.contribution.ui.ContributionDialogs.open(context.getSource(), "stock 0 name all"))
                .then(side("buy", true))
                .then(side("sell", false))
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

    private static int check(CommandContext<CommandSourceStack> context, int days) {
        if (days == 0) { error(context.getSource(), "查询范围只能是 week、month 或 year"); return 0; }
        return cn.contribution.ui.ContributionDialogs.open(context.getSource(),
                "stock-detail " + StringArgumentType.getString(context, "symbol") + " " + days);
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

    private static int trade(CommandContext<CommandSourceStack> context, boolean buy, UUID id) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!RequestLimiter.allow(source)) { error(source, "操作过快，请稍后重试"); return 0; }
        StockService service = ContributionRuntime.stocks();
        if (service == null) { error(source, "股票数据服务尚未启动"); return 0; }
        UUID player = source.getPlayerOrException().getUUID();
        String symbol = StringArgumentType.getString(context, "symbol");
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        source.sendSuccess(() -> Component.literal("[股票] 请求 ID：" + id + "；网络异常时使用 /contribution stock retry "
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
