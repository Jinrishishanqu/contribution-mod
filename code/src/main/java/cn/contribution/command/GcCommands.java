package cn.contribution.command;

import cn.contribution.gamecurrency.GameCurrencyService;
import cn.contribution.runtime.ContributionRuntime;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** The isolated game-currency wallet: view balance, destroy contribution to mint GC, or retry. */
public final class GcCommands {
    private GcCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("gc")
                .executes(GcCommands::wallet)
                .then(Commands.literal("balance").executes(GcCommands::wallet))
                .then(
                        Commands.literal("exchange")
                                .then(
                                        Commands.argument(
                                                        "amount",
                                                        IntegerArgumentType.integer(1, 1_000_000))
                                                .executes(
                                                        context ->
                                                                exchange(
                                                                        context,
                                                                        UUID.randomUUID(),
                                                                        false))))
                .then(
                        Commands.literal("retry")
                                .then(
                                        Commands.argument("id", StringArgumentType.word())
                                                .then(
                                                        Commands.argument(
                                                                        "amount",
                                                                        IntegerArgumentType.integer(
                                                                                1, 1_000_000))
                                                                .executes(
                                                                        context ->
                                                                                exchange(
                                                                                        context,
                                                                                        requestId(
                                                                                                context),
                                                                                        true)))));
    }

    private static UUID requestId(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        try {
            return UUID.fromString(StringArgumentType.getString(context, "id"));
        } catch (IllegalArgumentException invalid) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                    .dispatcherParseException()
                    .create("请求 ID 格式无效");
        }
    }

    private static int wallet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        GameCurrencyService service = ContributionRuntime.currencies();
        if (service == null) {
            failure(source, "游戏币服务尚未启动");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            failure(source, "请在游戏内查看游戏币");
            return 0;
        }
        service.wallet(player.getUUID())
                .whenComplete(
                        (wallet, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (error != null) {
                                                        failure(source, "游戏币数据暂时不可用，请稍后重试");
                                                        return;
                                                    }
                                                    success(
                                                            source,
                                                            "余额 "
                                                                    + GameCurrencyService.format(
                                                                            wallet.balanceMilli())
                                                                    + " 游戏币（1 贡献值 = "
                                                                    + GameCurrencyService.format(
                                                                            service
                                                                                    .exchangeRateMilli())
                                                                    + " 游戏币）");
                                                    if (wallet.entries().isEmpty()) {
                                                        success(
                                                                source,
                                                                "暂无游戏币流水；用 /gc exchange"
                                                                        + " <贡献值> 兑换");
                                                        return;
                                                    }
                                                    success(source, "最近流水：");
                                                    for (GameCurrencyService.Entry entry :
                                                            wallet.entries())
                                                        success(
                                                                source,
                                                                "· "
                                                                        + signed(
                                                                                entry.amountMilli())
                                                                        + "　"
                                                                        + entry.reason()
                                                                        + "　余额 "
                                                                        + GameCurrencyService
                                                                                .format(
                                                                                        entry
                                                                                                .balanceAfterMilli()));
                                                }));
        return 1;
    }

    private static int exchange(
            CommandContext<CommandSourceStack> context, UUID request, boolean retry) {
        CommandSourceStack source = context.getSource();
        if (!RequestLimiter.allow(source)) {
            failure(source, "操作过快，请稍后重试");
            return 0;
        }
        GameCurrencyService service = ContributionRuntime.currencies();
        if (service == null) {
            failure(source, "游戏币服务尚未启动");
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            failure(source, "请在游戏内兑换游戏币");
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(context, "amount");
        if (!retry)
            success(
                    source,
                    "兑换请求 ID：" + request + "；网络异常时使用 /gc retry " + request + " " + amount + " 重试");
        service.exchange(player.getUUID(), amount, request)
                .whenComplete(
                        (result, error) ->
                                source.getServer()
                                        .execute(
                                                () -> {
                                                    if (error != null) {
                                                        failure(source, "数据暂时不可用，请使用原请求 ID 重试");
                                                        return;
                                                    }
                                                    if (!result.success()) {
                                                        failure(source, result.message());
                                                        return;
                                                    }
                                                    success(
                                                            source,
                                                            "已销毁 "
                                                                    + result.cpSpent()
                                                                    + " 贡献值，兑换 "
                                                                    + GameCurrencyService.format(
                                                                            result.milliReceived())
                                                                    + " 游戏币；当前余额 "
                                                                    + GameCurrencyService.format(
                                                                            result.balanceMilli())
                                                                    + " 游戏币");
                                                }));
        return 1;
    }

    private static String signed(long milli) {
        return milli >= 0
                ? "+" + GameCurrencyService.format(milli)
                : GameCurrencyService.format(milli);
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[游戏币] " + message), false);
    }

    private static void failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[游戏币] " + message));
    }
}
