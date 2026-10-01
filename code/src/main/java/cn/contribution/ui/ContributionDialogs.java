package cn.contribution.ui;

import cn.contribution.account.*;
import cn.contribution.api.AccountTarget;
import cn.contribution.command.RequestLimiter;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.runtime.ContributionRuntime;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.*;
import net.minecraft.server.dialog.body.*;
import net.minecraft.server.dialog.input.TextInput;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native 26.3 dialogs are rendered by both vanilla and Fabric clients. No client-only classes. */
public final class ContributionDialogs {
    private static final int DIALOG_PAGE_SIZE = 8;
    private static final Map<UUID, Deque<String>> BACK_STACK = new HashMap<>();
    private static final Map<UUID, String> CURRENT_PAGE = new HashMap<>();

    private ContributionDialogs() { }
    public static int open(CommandSourceStack source, String request) {
        if (source.getPlayer() == null) { source.sendFailure(Component.literal("请在游戏内打开界面")); return 0; }
        if (!RequestLimiter.allow(source)) { source.sendFailure(Component.literal("操作过快，请稍后重试")); return 0; }
        try {
            UUID playerId = source.getPlayer().getUUID();
            if (request.equals("back")) {
                Deque<String> stack = BACK_STACK.get(playerId);
                request = stack == null || stack.isEmpty() ? "home" : stack.pop();
            } else if (request.equals("home")) {
                BACK_STACK.remove(playerId);
            } else {
                String previous = CURRENT_PAGE.getOrDefault(playerId, "home");
                if (!previous.equals(request)) {
                    Deque<String> stack = BACK_STACK.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
                    if (stack.size() >= 20) stack.removeLast();
                    stack.push(previous);
                }
            }
            CURRENT_PAGE.put(playerId, request);
            String[] args = request.isBlank() ? new String[]{"home"} : request.trim().split("\\s+", 5);
            String target = args.length > 1 ? args[1] : "self";
            switch (args[0]) {
                case "prepare", "confirm" -> AdminDialogOperations.handle(source, request);
                case "home" -> home(source);
                case "admin" -> adminHome(source);
                case "account" -> account(source, target);
                case "stats" -> stats(source, target);
                case "industries" -> industries(source);
                case "accounts" -> accounts(source, args.length > 1 ? UUID.fromString(args[1]) : null);
                case "history" -> history(source, target, args.length > 2 && !args[2].equals("-") ? UUID.fromString(args[2]) : null,
                        args.length > 3 ? args[3] + (args.length > 4 ? " " + args[4] : "") : "");
                default -> home(source);
            }
        } catch (IllegalArgumentException | IllegalStateException error) { message(source, "暂时无法执行", error.getMessage()); }
        return 1;
    }

    private static boolean admin(CommandSourceStack source) { return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source); }
    private static void requireAdmin(CommandSourceStack source) {
        if (!admin(source)) throw new IllegalArgumentException("仅管理员可查询他人或全服数据");
    }
    private static AccountTarget target(CommandSourceStack source, String value) {
        if (value.equals("self")) return AccountTarget.byUuid(source.getPlayer().getUUID());
        requireAdmin(source);
        try { return AccountTarget.byUuid(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return AccountTarget.byName(value); }
    }
    private static AccountService service() {
        if (ContributionRuntime.accounts() == null) throw new IllegalArgumentException("数据服务尚未启动");
        return ContributionRuntime.accounts();
    }
    private static void home(CommandSourceStack source) {
        List<ActionButton> actions = new ArrayList<>(List.of(button("我的账户", "account self"), button("我的流水", "history self"),
                button("我的统计", "stats self"), button("行业建设度与繁荣度", "industries")));
        if (admin(source)) actions.add(button("管理员功能", "admin"));
        show(source, "贡献值系统", List.of(), List.of(), actions);
    }
    private static void adminHome(CommandSourceStack source) {
        requireAdmin(source);
        show(source, "管理员功能", List.of(),
                List.of(input("target", "玩家名称或 UUID", "", 64)),
                List.of(button("账户列表", "accounts"), button("全服流水", "history *"),
                        template("查询账户", "contribution ui account $(target)"),
                        template("查询玩家统计", "contribution ui stats $(target)"), button("首页", "home")));
    }
    private static void account(CommandSourceStack source, String who) {
        query(source, service().account(target(source, who)), result -> {
            if (result.isEmpty()) { message(source, "账户", "未找到该玩家账户"); return; }
            AccountRecord account = result.get();
            List<Input> inputs = new ArrayList<>();
            List<ActionButton> actions = new ArrayList<>(List.of(button("查看流水", "history " + who), button("玩家统计", "stats " + who), button("首页", "home")));
            if (admin(source)) {
                inputs.add(input("amount", "管理员变动数量（正数增加，负数扣除）", "", 11));
                inputs.add(input("reason", "原因（最多 64 字）", "", 64));
                actions.add(template("预览账户变动", "contribution ui prepare " + account.playerUuid() + " $(amount) $(reason)"));
            }
            show(source, account.playerName() + " 的账户", List.of("余额：" + account.balance(), "历史总收入：" + account.totalIncome(),
                    "UUID：" + account.playerUuid()), inputs, actions);
        });
    }
    private static void stats(CommandSourceStack source, String who) {
        query(source, service().account(target(source, who)), account -> {
            if (account.isEmpty() || ContributionRuntime.statistics() == null) { message(source, "统计", "暂无统计数据"); return; }
            query(source, ContributionRuntime.statistics().playerSummary(account.get().playerUuid()), text ->
                    show(source, account.get().playerName() + " 的统计", Arrays.asList(text.split("；")), List.of(), List.of(button("刷新", "stats " + who), button("首页", "home"))));
        });
    }
    private static void industries(CommandSourceStack source) {
        long day = cn.contribution.industry.RuleManager.day(source.getServer());
        query(source, ContributionRuntime.database().transaction(connection -> {
            List<String> lines = new ArrayList<>();
            for (var industry : cn.contribution.industry.BuiltInIndustry.values()) {
                long daily = 0;
                try (var statement = connection.prepareStatement("SELECT development FROM industry_day_accumulator WHERE game_day = ? AND industry_id = ?")) {
                    statement.setLong(1, day); statement.setString(2, "contribution:" + industry.path());
                    try (var row = statement.executeQuery()) { if (row.next()) daily = row.getLong(1); }
                }
                try (var statement = connection.prepareStatement("SELECT total_development, prosperity, last_settled_game_day FROM industry_state WHERE industry_id = ?")) {
                    statement.setString(1, "contribution:" + industry.path());
                    try (var row = statement.executeQuery()) {
                        lines.add(industry.displayName() + " | 当日 " + daily + (row.next() ? " | 总建设度 " + row.getLong(1) + " | 繁荣度 " + row.getBigDecimal(2).toPlainString() + " | 核算日 " + row.getLong(3) : " | 暂无已结算数据"));
                    }
                }
            }
            return lines;
        }), lines -> show(source, "行业建设度与繁荣度", lines, List.of(), List.of(button("刷新", "industries"), button("首页", "home"))));
    }
    private static void accounts(CommandSourceStack source, UUID cursor) {
        requireAdmin(source);
        query(source, service().allAccountsPage(cursor, DIALOG_PAGE_SIZE), page -> {
            List<ActionButton> actions = new ArrayList<>();
            for (AccountRecord row : page.rows()) actions.add(button(row.playerName() + "：" + row.balance(), "account " + row.playerUuid()));
            page.nextCursor().ifPresent(next -> actions.add(button("下一页", "accounts " + next)));
            actions.add(button("首页", "home"));
            show(source, "账户列表", List.of(page.validCursor() ? "点击账户查看详情" : "翻页位置已失效，请从首页重试"), List.of(), actions);
        });
    }
    private static void history(CommandSourceStack source, String who, UUID cursor, String rawFilter) {
        if (who.equals("*")) requireAdmin(source);
        int days = admin(source) ? 365 : 90;
        HistoryFilter filter = rawFilter.isBlank() ? HistoryFilter.empty() : HistoryFilter.parse(rawFilter, days);
        CompletableFuture<HistoryPage> future = who.equals("*") ? service().allHistoryPage(cursor, DIALOG_PAGE_SIZE, days, filter)
                : service().historyPage(target(source, who), cursor, DIALOG_PAGE_SIZE, days, filter).thenApply(value -> value.orElse(new HistoryPage(List.of(), false, true)));
        query(source, future, page -> {
            List<String> lines = new ArrayList<>();
            for (TransactionRecord row : page.rows()) lines.add(row.createdAt() + " | " + row.playerName() + " | " + row.amount()
                    + " → " + row.balanceAfter() + " | " + row.type() + " | " + row.reason());
            if (lines.isEmpty()) lines.add(page.validCursor() ? "暂无符合条件的流水" : "翻页位置已失效");
            List<ActionButton> actions = new ArrayList<>();
            actions.add(button("全部流水", "history " + who + " -"));
            actions.add(button("股票买入", "history " + who + " - type=STOCK_BUY"));
            actions.add(button("股票卖出", "history " + who + " - type=STOCK_SELL"));
            actions.add(button("管理员调整", "history " + who + " - type=ADMIN"));
            actions.add(button("退市返还", "history " + who + " - type=REFUND"));
            actions.add(template("应用筛选", "contribution ui history " + who + " - $(filters)"));
            page.nextCursor().ifPresent(next -> actions.add(button("下一页", "history " + who + " " + next + " " + filter.commandArguments())));
            actions.add(button("第一页", "history " + who + " - " + filter.commandArguments())); actions.add(button("首页", "home"));
            show(source, "流水 · " + who, lines, List.of(input("filters", "更多筛选（可选）：type/source/server/from/to", filter.commandArguments(), 256)), actions);
        });
    }
    private static <T> void query(CommandSourceStack source, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> source.getServer().execute(() -> {
            if (source.getPlayer() == null || source.getPlayer().hasDisconnected()) return;
            if (error != null) {
                DatabaseService database = ContributionRuntime.database();
                String detail = database.state() == DatabaseState.AVAILABLE ? "数据暂时不可用，请稍后重试"
                        : database.usesEmbeddedDatabase() ? "本地数据库未就绪，请检查世界目录是否可写及磁盘空间"
                        : "共享 MySQL 未就绪，请检查数据库服务与连接配置";
                message(source, "数据服务", detail);
            } else success.accept(result);
        }));
    }
    static Input input(String key, String label, String value, int length) {
        return new Input(key, new TextInput(400, Component.literal(label), true, value, length, Optional.empty()));
    }
    static ActionButton button(String label, String request) {
        return new ActionButton(new CommonButtonData(Component.literal(label), 190), Optional.of(new StaticAction(new ClickEvent.RunCommand("/contribution ui " + request))));
    }
    static ActionButton template(String label, String command) {
        ParsedTemplate parsed = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(command)).getOrThrow();
        return new ActionButton(new CommonButtonData(Component.literal(label), 190), Optional.of(new CommandTemplate(parsed)));
    }
    private static void message(CommandSourceStack source, String title, String message) { show(source, title, List.of(message), List.of(), List.of(button("首页", "home"))); }
    public static void show(CommandSourceStack source, String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons) {
        boolean hasPrevious = !BACK_STACK.getOrDefault(source.getPlayer().getUUID(), new ArrayDeque<>()).isEmpty();
        source.getPlayer().openDialog(Holder.direct(create(title, lines, inputs, buttons, hasPrevious)));
    }
    static Dialog create(String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons) {
        return create(title, lines, inputs, buttons, false);
    }
    static Dialog create(String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons, boolean hasPrevious) {
        return create(title, lines, inputs, buttons, hasPrevious, 2);
    }
    static Dialog create(String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons,
                         boolean hasPrevious, int columns) {
        List<DialogBody> bodies = lines.stream().map(line -> (DialogBody)new PlainMessage(Component.literal(line), 430)).toList();
        var common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, bodies, inputs);
        ActionButton exit = hasPrevious ? button("返回上一页", "back")
                : new ActionButton(new CommonButtonData(Component.literal("关闭"), 190), Optional.empty());
        return new MultiActionDialog(common, buttons, Optional.of(exit), columns);
    }
    public static void clear() { BACK_STACK.clear(); CURRENT_PAGE.clear(); }
}
