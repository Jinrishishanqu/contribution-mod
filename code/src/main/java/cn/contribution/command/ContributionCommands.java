package cn.contribution.command;

import cn.contribution.account.AccountRecord;
import cn.contribution.account.AccountPage;
import cn.contribution.account.AccountService;
import cn.contribution.account.AccountIdentityService;
import cn.contribution.account.HistoryPage;
import cn.contribution.account.HistoryFilter;
import cn.contribution.account.TransactionRecord;
import cn.contribution.api.AccountTarget;
import cn.contribution.api.BalanceChangeRequest;
import cn.contribution.api.BalanceChangeType;
import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.ui.LedgerDisplay;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class ContributionCommands {
    private ContributionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, net.minecraft.commands.CommandBuildContext registry) {
        dispatcher.register(Commands.literal("contribution")
                .executes(context -> cn.contribution.ui.ContributionDialogs.openDefault(context.getSource()))
                .then(Commands.literal("ui_vanilla")
                        .executes(context -> cn.contribution.ui.ContributionDialogs.openVanilla(context.getSource())))
                .then(Commands.literal("ui").executes(context -> cn.contribution.ui.ContributionDialogs.openDefault(context.getSource()))
                        .then(Commands.argument("page", StringArgumentType.greedyString()).executes(context -> cn.contribution.ui.ContributionDialogs.open(context.getSource(), StringArgumentType.getString(context, "page")))))
                .then(Commands.literal("query")
                        .executes(context -> query(context, self(context)))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .requires(ContributionCommands::admin)
                                .executes(context -> queryTarget(context, StringArgumentType.getString(context, "target")))))
                .then(Commands.literal("accounts")
                        .requires(ContributionCommands::admin)
                        .executes(context -> accounts(context.getSource(), null)))
                .then(Commands.literal("bot_check").requires(ContributionCommands::admin)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (ContributionRuntime.database() == null) { failure(source, "数据服务尚未启动"); return 0; }
                            AccountIdentityService service = new AccountIdentityService(ContributionRuntime.database());
                            dispatch(source, service.removeBotAccounts(), count ->
                                    success(source, "已清理 " + count + " 个 bot_ 假人账户及其玩家关联记录"));
                            return 1;
                        }))
                .then(Commands.literal("accounts-next")
                        .requires(ContributionCommands::admin)
                        .then(Commands.argument("cursor", StringArgumentType.word())
                                .executes(context -> accountsNext(context))))
                .then(Commands.literal("history")
                        .executes(context -> history(context, self(context), null, null))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .requires(ContributionCommands::admin)
                                .executes(context -> historyTarget(context,
                                        StringArgumentType.getString(context, "target"), null))))
                .then(Commands.literal("history-next")
                        .then(Commands.argument("cursor", StringArgumentType.word())
                                .executes(context -> historyNext(context, null))
                                .then(Commands.argument("target", StringArgumentType.greedyString())
                                        .requires(ContributionCommands::admin)
                                        .executes(context -> historyNext(context,
                                                StringArgumentType.getString(context, "target"))))))
                .then(Commands.literal("history-search")
                        .then(Commands.argument("request", StringArgumentType.greedyString())
                                .executes(context -> historySearch(context, null))))
                .then(Commands.literal("history-search-next")
                        .then(Commands.argument("cursor", StringArgumentType.word())
                                .then(Commands.argument("request", StringArgumentType.greedyString())
                                        .executes(ContributionCommands::historySearchNext))))
                .then(Commands.literal("stats")
                        .executes(context -> stats(context, self(context)))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .requires(ContributionCommands::admin)
                                .executes(context -> statsTarget(context, StringArgumentType.getString(context, "target")))))
                .then(RewardCommands.checkinCommand())
                .then(Commands.literal("account").requires(ContributionCommands::admin)
                        .then(Commands.literal("create")
                                .then(Commands.argument("uuid", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ContributionCommands::createAccount))))
                        .then(Commands.literal("migrate")
                                .then(Commands.argument("sourceUuid", StringArgumentType.word())
                                        .then(Commands.argument("targetUuid", StringArgumentType.word())
                                                .then(Commands.literal("confirm")
                                                        .executes(ContributionCommands::migrateAccount))))))
                .then(balanceCommand("add", true))
                .then(Commands.literal("retry").requires(ContributionCommands::admin)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .then(Commands.argument("amount", LongArgumentType.longArg(Integer.MIN_VALUE, Integer.MAX_VALUE))
                                                .then(Commands.argument("reason", StringArgumentType.string())
                                                        .then(Commands.argument("affectTotalIncome", BoolArgumentType.bool())
                                                                .executes(context -> retry(context, ""))
                                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                                        .executes(context -> retry(context, StringArgumentType.getString(context, "note"))))))))))
                .then(balanceCommand("remove", false)));
        dispatcher.register(StockCommands.command());
        dispatcher.register(ShopCommands.root(registry));
    }

    private static int createAccount(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!permit(source)) return 0;
        try {
            var identities = new AccountIdentityService(ContributionRuntime.database());
            UUID id = UUID.fromString(StringArgumentType.getString(context, "uuid"));
            String name = StringArgumentType.getString(context, "name");
            dispatch(source, identities.create(id, name), result -> {
                if (result.startsWith("已创建")) success(source, result); else failure(source, result);
            });
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            failure(source, "UUID 无效或数据服务未就绪"); return 0;
        }
    }

    private static int migrateAccount(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!permit(source)) return 0;
        if (!ContributionRuntime.isMainServer()) {
            failure(source, "请在主服务器控制台执行账户迁移"); return 0;
        }
        if (source.getEntity() != null || !"Server".equals(source.getTextName())
                || !source.getServer().getPlayerList().getPlayers().isEmpty()) {
            failure(source, "迁移只能在主服务器控制台、所有玩家离线时执行；群组服请先停止其他子服");
            return 0;
        }
        try {
            UUID from = UUID.fromString(StringArgumentType.getString(context, "sourceUuid"));
            UUID to = UUID.fromString(StringArgumentType.getString(context, "targetUuid"));
            var identities = new AccountIdentityService(ContributionRuntime.database());
            dispatch(source, identities.migrate(from, to), result -> {
                if (result.startsWith("已迁移")) success(source, result); else failure(source, result);
            });
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) {
            failure(source, "UUID 无效或数据服务未就绪"); return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> balanceCommand(String name, boolean add) {
        return Commands.literal(name).requires(ContributionCommands::admin)
                .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1,
                                add ? Integer.MAX_VALUE : 2_147_483_648L))
                                .then(Commands.argument("reason", StringArgumentType.string())
                                        .then(Commands.argument("affectTotalIncome", BoolArgumentType.bool())
                                                .executes(context -> change(context, add, false, ""))
                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                        .executes(context -> change(context, add, false,
                                                                StringArgumentType.getString(context, "note"))))))))
                .then(Commands.argument("selected", EntityArgument.player())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1,
                                add ? Integer.MAX_VALUE : 2_147_483_648L))
                                .then(Commands.argument("reason", StringArgumentType.string())
                                        .then(Commands.argument("affectTotalIncome", BoolArgumentType.bool())
                                                .executes(context -> change(context, add, true, ""))
                                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                                        .executes(context -> change(context, add, true,
                                                                StringArgumentType.getString(context, "note"))))))));
    }

    private static boolean admin(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
    }

    private static boolean permit(CommandSourceStack source) {
        if (RequestLimiter.allow(source)) return true;
        failure(source, "操作过快，请稍后重试"); return false;
    }

    private static AccountTarget self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AccountTarget.byUuid(context.getSource().getPlayerOrException().getUUID());
    }

    private static AccountTarget target(CommandSourceStack source, String text) throws CommandSyntaxException {
        if (text.startsWith("@")) {
            var selector = new EntitySelectorParser(new StringReader(text), true).parse();
            List<ServerPlayer> players = selector.findPlayers(source);
            if (players.size() != 1) {
                throw new IllegalArgumentException("目标选择器必须恰好选择一名玩家");
            }
            return AccountTarget.byUuid(players.getFirst().getUUID());
        }
        try {
            return AccountTarget.byUuid(UUID.fromString(text));
        } catch (IllegalArgumentException ignored) {
            return AccountTarget.byName(text);
        }
    }

    private static int queryTarget(CommandContext<CommandSourceStack> context, String value) throws CommandSyntaxException {
        if (value.equals("*")) {
            failure(context.getSource(), "请使用 /contribution accounts 查看账户列表");
            return 0;
        }
        try {
            return query(context, target(context.getSource(), value));
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), error.getMessage());
            return 0;
        }
    }

    private static int accountsNext(CommandContext<CommandSourceStack> context) {
        UUID cursor;
        try {
            cursor = UUID.fromString(StringArgumentType.getString(context, "cursor"));
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), "账户翻页游标无效");
            return 0;
        }
        return accounts(context.getSource(), cursor);
    }

    private static int accounts(CommandSourceStack source, UUID cursor) {
        if (!permit(source)) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null) {
            failure(source, "数据服务尚未启动");
            return 0;
        }
        dispatch(source, service.allAccountsPage(cursor, 20), page -> {
            if (!page.validCursor()) {
                failure(source, "账户翻页游标无效或已过期");
                return;
            }
            if (page.rows().isEmpty()) {
                success(source, "暂无账户");
            }
            for (AccountRecord account : page.rows()) {
                success(source, account.playerName() + "：余额 " + account.balance()
                        + "，历史总收入 " + account.totalIncome());
            }
            page.nextCursor().ifPresent(next -> source.sendSuccess(
                    () -> Component.literal("[贡献值] 点击查看下一页账户")
                            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(
                                    "/contribution accounts-next " + next))), false));
        });
        return 1;
    }

    private static int query(CommandContext<CommandSourceStack> context, AccountTarget target) {
        if (!permit(context.getSource())) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null) {
            failure(context.getSource(), "数据服务尚未启动");
            return 0;
        }
        dispatch(context.getSource(), service.account(target), result -> {
            if (result.isEmpty()) {
                failure(context.getSource(), "未找到该玩家账户");
            } else {
                AccountRecord account = result.get();
                success(context.getSource(), account.playerName() + "：余额 " + account.balance()
                        + "，历史总收入 " + account.totalIncome());
            }
        });
        return 1;
    }

    private static int historyNext(CommandContext<CommandSourceStack> context, String targetText)
            throws CommandSyntaxException {
        UUID cursor;
        try {
            cursor = UUID.fromString(StringArgumentType.getString(context, "cursor"));
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), "流水翻页游标无效");
            return 0;
        }
        return targetText == null ? history(context, self(context), cursor, null)
                : historyTarget(context, targetText, cursor);
    }

    private static int historySearchNext(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        UUID cursor;
        try {
            cursor = UUID.fromString(StringArgumentType.getString(context, "cursor"));
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), "流水翻页游标无效");
            return 0;
        }
        return historySearch(context, cursor);
    }

    private static int historySearch(CommandContext<CommandSourceStack> context, UUID cursor)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!permit(source)) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null) {
            failure(source, "数据服务尚未启动");
            return 0;
        }
        String request = StringArgumentType.getString(context, "request").trim();
        int separator = request.indexOf(' ');
        if (separator < 1 || separator == request.length() - 1) {
            failure(source, "用法：/contribution history-search <范围> <筛选条件>");
            return 0;
        }
        String scope = request.substring(0, separator);
        String filters = request.substring(separator + 1).trim();
        int maxAgeDays = admin(source) ? 365 : 90;
        HistoryFilter filter;
        try {
            filter = HistoryFilter.parse(filters, maxAgeDays);
        } catch (IllegalArgumentException error) {
            failure(source, error.getMessage());
            return 0;
        }
        if (scope.equals("self")) {
            dispatch(source, service.historyPage(self(context), cursor, 20, maxAgeDays, filter), result -> {
                if (result.isEmpty()) {
                    failure(source, "未找到该玩家账户");
                } else {
                    displayHistory(source, result.get(), "self", filter);
                }
            });
            return 1;
        }
        if (!admin(source)) {
            failure(source, "你没有执行此命令的权限");
            return 0;
        }
        if (scope.equals("*")) {
            dispatch(source, service.allHistoryPage(cursor, 20, 365, filter),
                    page -> displayHistory(source, page, "*", filter));
            return 1;
        }
        try {
            AccountTarget selected = target(source, scope);
            String nextScope = selected.playerUuid() == null
                    ? selected.playerName() : selected.playerUuid().toString();
            dispatch(source, service.historyPage(selected, cursor, 20, 365, filter), result -> {
                if (result.isEmpty()) {
                    failure(source, "未找到该玩家账户");
                } else {
                    displayHistory(source, result.get(), nextScope, filter);
                }
            });
            return 1;
        } catch (IllegalArgumentException error) {
            failure(source, error.getMessage());
            return 0;
        }
    }

    private static int historyTarget(CommandContext<CommandSourceStack> context, String value, UUID cursor)
            throws CommandSyntaxException {
        if (value.equals("*")) {
            if (!permit(context.getSource())) return 0;
            AccountService service = ContributionRuntime.accounts();
            if (service != null) {
                dispatch(context.getSource(), service.allHistoryPage(cursor, 20, 365),
                        page -> displayHistory(context.getSource(), page, "*"));
            } else {
                failure(context.getSource(), "数据服务尚未启动");
            }
            return 1;
        }
        try {
            AccountTarget selected = target(context.getSource(), value);
            String nextTarget = selected.playerUuid() != null
                    ? selected.playerUuid().toString() : selected.playerName();
            return history(context, selected, cursor, nextTarget);
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), error.getMessage());
            return 0;
        }
    }

    private static int history(CommandContext<CommandSourceStack> context, AccountTarget target,
                               UUID cursor, String nextTarget) {
        if (!permit(context.getSource())) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null) {
            failure(context.getSource(), "数据服务尚未启动");
            return 0;
        }
        int maxAgeDays = admin(context.getSource()) ? 365 : 90;
        dispatch(context.getSource(), service.historyPage(target, cursor, 20, maxAgeDays), result -> {
            if (result.isEmpty()) {
                failure(context.getSource(), "未找到该玩家账户");
            } else {
                displayHistory(context.getSource(), result.get(), nextTarget);
            }
        });
        return 1;
    }

    private static void displayHistory(CommandSourceStack source, HistoryPage page, String nextTarget) {
        displayHistory(source, page, nextTarget, null);
    }

    private static void displayHistory(CommandSourceStack source, HistoryPage page, String nextTarget,
                                       HistoryFilter filter) {
        if (!page.validCursor()) {
            failure(source, "流水翻页游标无效或已过期");
            return;
        }
        if (page.rows().isEmpty()) {
            success(source, "暂无流水");
        }
        for (TransactionRecord row : page.rows()) {
            success(source, LedgerDisplay.time(row.createdAt(), ContributionRuntime.displayZone()) + " "
                    + row.playerName() + " " + (row.amount() > 0 ? "+" : "") + row.amount()
                    + "，余额 " + row.balanceAfter() + "，" + row.reason() + "，流水 ID " + row.transactionId());
        }
        page.nextCursor().ifPresent(next -> {
            String command = filter == null
                    ? "/contribution history-next " + next + (nextTarget == null ? "" : " " + nextTarget)
                    : "/contribution history-search-next " + next + " " + nextTarget + " "
                            + filter.commandArguments();
            source.sendSuccess(() -> Component.literal("[贡献值] 点击查看下一页")
                    .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))), false);
        });
    }

    private static int statsTarget(CommandContext<CommandSourceStack> context, String value) throws CommandSyntaxException {
        try {
            return stats(context, target(context.getSource(), value));
        } catch (IllegalArgumentException error) {
            failure(context.getSource(), error.getMessage());
            return 0;
        }
    }

    private static int stats(CommandContext<CommandSourceStack> context, AccountTarget target) {
        if (!permit(context.getSource())) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null || ContributionRuntime.statistics() == null) {
            failure(context.getSource(), "数据服务尚未启动");
            return 0;
        }
        dispatch(context.getSource(), service.account(target), account -> {
            if (account.isEmpty()) {
                failure(context.getSource(), "未找到该玩家账户");
                return;
            }
            dispatch(context.getSource(), ContributionRuntime.statistics().playerSummary(account.get().playerUuid()),
                    text -> success(context.getSource(), account.get().playerName() + "：" + text));
        });
        return 1;
    }

    private static int change(CommandContext<CommandSourceStack> context, boolean add, boolean selected,
                              String note) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        if (!permit(source)) return 0;
        AccountService service = ContributionRuntime.accounts();
        if (service == null) {
            failure(source, "数据服务尚未启动");
            return 0;
        }
        AccountTarget target;
        try {
            target = selected ? AccountTarget.byUuid(EntityArgument.getPlayer(context, "selected").getUUID())
                    : target(source, StringArgumentType.getString(context, "target"));
        } catch (IllegalArgumentException error) {
            failure(source, error.getMessage());
            return 0;
        }
        long quantity = LongArgumentType.getLong(context, "amount");
        int amount = (int) (add ? quantity : -quantity);
        String reason = StringArgumentType.getString(context, "reason");
        BalanceChangeRequest request = new BalanceChangeRequest(UUID.randomUUID(), target, amount,
                BalanceChangeType.EXTERNAL, Identifier.fromNamespaceAndPath("contribution", "admin_command"), reason, note,
                BoolArgumentType.getBool(context, "affectTotalIncome"));
        submitAdmin(source, service, request);
        return 1;
    }

    private static int retry(CommandContext<CommandSourceStack> context, String note) throws CommandSyntaxException {
        var source = context.getSource();
        if (!permit(source)) return 0;
        try {
            AccountService service = ContributionRuntime.accounts();
            if (service == null) { failure(source, "数据服务尚未启动"); return 0; }
            var request = new BalanceChangeRequest(UUID.fromString(StringArgumentType.getString(context, "id")),
                    target(source, StringArgumentType.getString(context, "target")), (int)LongArgumentType.getLong(context, "amount"),
                    BalanceChangeType.EXTERNAL, Identifier.fromNamespaceAndPath("contribution", "admin_command"),
                    StringArgumentType.getString(context, "reason"), note,
                    BoolArgumentType.getBool(context, "affectTotalIncome"));
            submitAdmin(source, service, request);
            return 1;
        } catch (IllegalArgumentException error) { failure(source, "重试参数无效"); return 0; }
    }

    private static void submitAdmin(CommandSourceStack source, AccountService service, BalanceChangeRequest request) {
        String who = request.target().playerUuid() == null ? request.target().playerName() : request.target().playerUuid().toString();
        String retry = "/contribution retry " + request.idempotencyId() + " " + who + " " + request.amount() + " "
                + StringArgumentType.escapeIfRequired(request.reason()) + " " + request.affectTotalIncome()
                + (request.note().isEmpty() ? "" : " " + request.note());
        source.sendSuccess(() -> Component.literal("[贡献值] 请求 ID " + request.idempotencyId() + " [保留原请求重试]")
                .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(retry))), false);
        dispatch(source, service.changeAdmin(request, source.getTextName()), result -> {
            if (result.successful()) {
                success(source, "操作成功，余额 " + result.balanceBefore().orElseThrow() + " → "
                        + result.balanceAfter().orElseThrow() + "，流水 ID " + result.transactionId().orElseThrow());
            } else {
                failure(source, result.message());
            }
        });
    }

    private static <T> void dispatch(CommandSourceStack source, CompletableFuture<T> future, Consumer<T> display) {
        future.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (error != null) {
                failure(source, "数据服务暂时不可用，请稍后重试");
            } else {
                display.accept(value);
            }
        }));
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[贡献值] " + message), false);
    }

    private static void failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[贡献值] " + message));
    }
}
