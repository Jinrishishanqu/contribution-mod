package cn.contribution.ui;

import cn.contribution.account.*;
import cn.contribution.api.AccountTarget;
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
    private static final Map<UUID, ContributionNavigation> NAVIGATION = new HashMap<>();
    private static final Map<UUID, String> CURRENT_PAGE = new HashMap<>();
    private static final Map<UUID, Long> PAGE_REVISIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final Map<UUID, Object> PENDING_QUERY = new HashMap<>();
    private static final Set<UUID> FORCE_VANILLA = new HashSet<>();

    static String currentRequest(UUID player) { return CURRENT_PAGE.getOrDefault(player, "home"); }
    private ContributionDialogs() { }
    public static int openDefault(CommandSourceStack source) {
        if (source.getPlayer() != null) FORCE_VANILLA.remove(source.getPlayer().getUUID());
        return open(source, "home");
    }
    public static int openVanilla(CommandSourceStack source) {
        if (source.getPlayer() != null) FORCE_VANILLA.add(source.getPlayer().getUUID());
        return open(source, "home");
    }
    public static void forget(UUID player) {
        FORCE_VANILLA.remove(player);
        LAST_REQUEST.remove(player);
        PENDING_QUERY.remove(player);
        NAVIGATION.remove(player);
        CURRENT_PAGE.remove(player);
        PAGE_REVISIONS.remove(player);
    }
    private static boolean clientUi(CommandSourceStack source) {
        return !FORCE_VANILLA.contains(source.getPlayer().getUUID())
                && ContributionUiNetwork.available(source.getPlayer());
    }
    public static int open(CommandSourceStack source, String request) {
        if (source.getPlayer() == null) { source.sendFailure(Component.literal("请在游戏内打开界面")); return 0; }
        try {
            UUID playerId = source.getPlayer().getUUID();
            long now = System.nanoTime();
            if (!request.equals("close") && !request.equals("back") && !request.equals("home")) {
                // Do not queue more database reads while this player's page is still loading.
                if (PENDING_QUERY.containsKey(playerId)) return 1;
                Long previous = LAST_REQUEST.get(playerId);
                if (previous != null && now - previous < 250_000_000L) return 1;
                LAST_REQUEST.put(playerId, now);
            }
            PAGE_REVISIONS.merge(playerId, 1L, Long::sum);
            var navigation = NAVIGATION.computeIfAbsent(playerId, ignored -> new ContributionNavigation());
            if (request.equals("close")) {
                NAVIGATION.remove(playerId); CURRENT_PAGE.remove(playerId);
                return 1;
            }
            boolean previousPage = request.equals("previous");
            if (request.equals("back")) request = ContributionNavigation.parent(CURRENT_PAGE.getOrDefault(playerId, "home"));
            else if (previousPage) request = navigation.previous(CURRENT_PAGE.getOrDefault(playerId, "home"));
            if (request.equals("close")) { NAVIGATION.remove(playerId); CURRENT_PAGE.remove(playerId); return 1; }
            if (!previousPage) navigation.visit(request);
            CURRENT_PAGE.put(playerId, request);
            String[] args = request.isBlank() ? new String[]{"home"} : request.trim().split("\\s+", 5);
            String target = args.length > 1 ? args[1] : "self";
            switch (args[0]) {
                case "prepare", "confirm" -> throw new IllegalArgumentException("账户变动请使用 /contribution add 或 remove");
                case "home" -> home(source);
                case "admin" -> adminHome(source);
                case "account" -> account(source, target);
                case "stats" -> account(source, target);
                case "industries" -> industries(source);
                case "checkin" -> checkin(source);
                case "accounts" -> accounts(source, args.length > 1 ? UUID.fromString(args[1]) : null, "");
                case "admin_search" -> accounts(source, null, args.length > 1 ? args[1] : "");
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
        if (clientUi(source)) {
            List<ContributionUiNetwork.Action> actions = new ArrayList<>(List.of(
                    action("我的账户", "account self"), action("我的流水", "history self"),
                    action("行业建设度", "industries"),
                    action("签到", "checkin")));
            if (admin(source)) actions.add(action("管理员功能", "admin"));
            ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                    "贡献值系统", "home", List.of(), List.of(), actions, "账户、流水、玩家统计与服务器建设度"));
            return;
        }
        List<ActionButton> actions = new ArrayList<>(List.of(button("我的账户", "account self"), button("我的流水", "history self"),
                button("行业建设度与繁荣度", "industries"), button("每日签到与活动", "checkin")));
        if (admin(source)) actions.add(button("管理员功能", "admin"));
        show(source, "贡献值系统", List.of(), List.of(), actions);
    }
    private static void adminHome(CommandSourceStack source) {
        accounts(source, null, "");
    }
    private static void account(CommandSourceStack source, String who) {
        query(source, service().account(target(source, who)), result -> {
            if (result.isEmpty()) { message(source, "账户", "未找到该玩家账户"); return; }
            AccountRecord account = result.get();
            if (ContributionRuntime.statistics() == null) { message(source, "账户", "统计服务尚未启动"); return; }
            query(source, ContributionRuntime.statistics().playerSummaryData(account.playerUuid()), summary -> {
                var industries = cn.contribution.industry.BuiltInIndustry.values();
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of(String.valueOf(account.balance()), String.valueOf(account.totalIncome()),
                        account.playerUuid().toString()));
                long total = 0;
                for (var industry : industries) total += summary.development(industry);
                rows.add(List.of(String.valueOf(summary.placed()), String.valueOf(summary.mined()), String.valueOf(total)));
                for (int i = 0; i < industries.length; i += 3) {
                    List<String> cells = new ArrayList<>();
                    for (int column = 0; column < 3; column++) {
                        var industry = industries[i + column];
                        cells.add(industry.displayName() + " " + summary.development(industry));
                    }
                    rows.add(cells);
                }
                if (clientUi(source)) {
                    ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                            account.playerName() + " 的账户与统计", "profile", List.of(), rows,
                            List.of(action("查看流水", "history " + who), action("刷新", "account " + who),
                                    action("返回上级", "back"), action("首页", "home")),
                            "账户变动仅通过管理员 /contribution add、remove 命令"));
                    return;
                }
                List<String> lines = new ArrayList<>(List.of("余额：" + account.balance()
                        + " · 历史总收入：" + account.totalIncome(), "UUID：" + account.playerUuid(),
                        "原始放置：" + summary.placed() + " · 原始挖掘：" + summary.mined() + " · 总建设度：" + total));
                for (int i = 2; i < rows.size(); i++)
                    lines.add(DialogTable.row(new int[]{20, 20, 20}, new boolean[]{false, false, false},
                            rows.get(i).toArray(String[]::new)));
                showTable(source, account.playerName() + " 的账户与统计", lines, List.of(),
                        List.of(button("查看流水", "history " + who), button("刷新", "account " + who), button("首页", "home")));
            });
        });
    }
    private record IndustryRow(long daily, long total, String prosperity, long settledDay) { }

    private static void industries(CommandSourceStack source) {
        long day = cn.contribution.industry.RuleManager.day(source.getServer());
        query(source, ContributionRuntime.database().transaction(connection -> {
            Map<String, Long> daily = new HashMap<>();
            try (var statement = connection.prepareStatement(
                    "SELECT industry_id, development FROM industry_day_accumulator WHERE game_day = ?")) {
                statement.setLong(1, day);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) daily.put(rows.getString(1), rows.getLong(2));
                }
            }
            Map<String, IndustryRow> states = new HashMap<>();
            try (var statement = connection.prepareStatement(
                    "SELECT industry_id, total_development, prosperity, last_settled_game_day FROM industry_state")) {
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) states.put(rows.getString(1), new IndustryRow(0, rows.getLong(2),
                            rows.getBigDecimal(3).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), rows.getLong(4)));
                }
            }
            List<IndustryRow> result = new ArrayList<>();
            for (var industry : cn.contribution.industry.BuiltInIndustry.values()) {
                String id = "contribution:" + industry.path();
                IndustryRow state = states.getOrDefault(id, new IndustryRow(0, 0, "--", -1));
                result.add(new IndustryRow(daily.getOrDefault(id, 0L), state.total(), state.prosperity(), state.settledDay()));
            }
            return result;
        }), rows -> {
            var industries = cn.contribution.industry.BuiltInIndustry.values();
            if (clientUi(source)) {
                List<List<String>> values = new ArrayList<>();
                for (int i = 0; i < industries.length; i++) {
                    IndustryRow row = rows.get(i);
                    values.add(List.of(industries[i].displayName(), String.valueOf(row.daily()),
                            String.valueOf(row.total()), row.prosperity(),
                            row.settledDay() < 0 ? "--" : String.valueOf(row.settledDay())));
                }
                ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                        "行业建设度与繁荣度", "industries", List.of("行业", "当日", "总建设度", "繁荣度", "核算日"), values,
                        List.of(action("刷新", "industries"), action("首页", "home")), "服务器共享数据"));
                return;
            }
            int[] widths = {12, 12, 12, 12, 12};
            boolean[] aligned = {false, true, true, true, true};
            List<String> lines = new ArrayList<>();
            lines.add(DialogTable.row(widths, aligned, "行业", "当日", "总建设度", "繁荣度", "核算日"));
            for (int i = 0; i < industries.length; i++) {
                IndustryRow row = rows.get(i);
                lines.add(DialogTable.row(widths, aligned, industries[i].displayName(), String.valueOf(row.daily()),
                        String.valueOf(row.total()), row.prosperity(), row.settledDay() < 0 ? "--" : String.valueOf(row.settledDay())));
            }
            showTable(source, "行业建设度与繁荣度", lines, List.of(),
                    List.of(button("刷新", "industries"), button("首页", "home")));
        });
    }
    private static void checkin(CommandSourceStack source) {
        var service = ContributionRuntime.checkins();
        var events = ContributionRuntime.eventCheckins();
        if (service == null || events == null) { message(source, "签到", "签到服务尚未启动"); return; }
        query(source, service.status(source.getPlayer().getUUID()).thenCombine(events.list(),
                (status, list) -> new AbstractMap.SimpleEntry<>(status, list)), result -> {
            if (clientUi(source)) {
                List<List<String>> rows = new ArrayList<>();
                List<String> rowCommands = new ArrayList<>();
                for (var event : result.getValue()) {
                    rows.add(List.of(event.title(), event.start() + "—" + event.end(),
                            "贡献 " + event.contribution() + " · 物品 " + event.itemCount()));
                    rowCommands.add("contribution checkin claim " + event.id());
                }
                ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                        "签到", "checkin", List.of("活动", "期限", "奖励"), rows,
                        List.of(action("刷新", "checkin"), action("首页", "home")),
                        result.getKey() + " · 点击活动行领取；在线满 10 分钟自动每日签到",
                        List.of(), rowCommands));
                return;
            }
            List<String> lines = new ArrayList<>();
            List<ActionButton> actions = new ArrayList<>();
            lines.add(result.getKey());
            lines.add("在线满 10 分钟自动每日签到；时区由服务端配置");
            for (var event : result.getValue()) {
                lines.add(event.title() + " · " + event.start() + "—" + event.end()
                        + " · 贡献 " + event.contribution() + " · 物品 " + event.itemCount());
                actions.add(new ActionButton(new CommonButtonData(Component.literal("领取 " + event.title()), 190),
                        Optional.of(new StaticAction(new ClickEvent.RunCommand("/contribution checkin claim " + event.id())))));
            }
            if (result.getValue().isEmpty()) lines.add("当前没有开放的活动");
            actions.add(button("刷新", "checkin")); actions.add(button("首页", "home"));
            show(source, "签到", lines, List.of(), actions);
        });
    }
    private static void accounts(CommandSourceStack source, UUID cursor, String prefix) {
        requireAdmin(source);
        query(source, prefix.isBlank() ? service().allAccountsPage(cursor, DIALOG_PAGE_SIZE)
                : service().searchAccounts(prefix, DIALOG_PAGE_SIZE), page -> {
            if (clientUi(source)) {
                List<List<String>> rows = page.rows().stream().map(row -> List.of(
                        row.playerName(), String.valueOf(row.balance()), row.playerUuid().toString())).toList();
                List<String> rowCommands = page.rows().stream().map(row ->
                        "contribution ui account " + row.playerUuid()).toList();
                List<ContributionUiNetwork.Action> actions = new ArrayList<>();
                actions.add(action("全服流水", "history *"));
                actions.add(new ContributionUiNetwork.Action("查询玩家", "contribution ui account $(target)"));
                actions.add(new ContributionUiNetwork.Action("上一页", cursor == null ? "" : "contribution ui previous"));
                actions.add(new ContributionUiNetwork.Action("下一页", page.nextCursor().map(next -> "contribution ui accounts " + next).orElse("")));
                if (cursor != null || !prefix.isBlank()) actions.add(action("重置列表", "admin"));
                ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                        "管理员功能 · 账户列表", "admin", List.of("玩家", "余额", "UUID"), rows, actions,
                        page.validCursor() ? "点击玩家行查看账户" : "翻页位置已失效",
                        List.of(new ContributionUiNetwork.Field("target", "筛选玩家名称或 UUID", prefix, 64)), rowCommands));
                return;
            }
            List<ActionButton> actions = new ArrayList<>();
            actions.add(template("筛选玩家", "contribution ui admin_search $(target)"));
            actions.add(button("全服流水", "history *"));
            for (AccountRecord row : page.rows()) actions.add(button(row.playerName() + "：" + row.balance(), "account " + row.playerUuid()));
            if (cursor != null) actions.add(button("上一页", "previous"));
            page.nextCursor().ifPresent(next -> actions.add(button("下一页", "accounts " + next)));
            if (cursor != null || !prefix.isBlank()) actions.add(button("重置列表", "admin"));
            actions.add(button("首页", "home"));
            show(source, "管理员功能 · 账户列表", List.of(page.validCursor() ? "点击账户查看详情" : "翻页位置已失效，请重置列表"),
                    List.of(input("target", "筛选玩家名称或 UUID", prefix, 64)), actions);
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
            var zone = ContributionRuntime.displayZone();
            if (clientUi(source)) {
                List<List<String>> values = new ArrayList<>();
                for (TransactionRecord row : page.rows())
                    values.add(List.of(LedgerDisplay.shortTime(row.createdAt(), zone), row.playerName(),
                            String.valueOf(row.amount()), String.valueOf(row.balanceAfter()), row.type(), row.reason()));
                List<ContributionUiNetwork.Action> actions = new ArrayList<>(List.of(
                        action("全部", "history " + who + " -"),
                        action("股票买入", "history " + who + " - type=SPEND source=contribution:stock"),
                        action("股票卖出", "history " + who + " - type=STOCK source=contribution:stock"),
                        action("刷新", "history " + who + " " + (cursor == null ? "-" : cursor) + " " + filter.commandArguments())));
                actions.add(new ContributionUiNetwork.Action("上一页", cursor == null ? "" : "contribution ui previous"));
                actions.add(new ContributionUiNetwork.Action("下一页", page.nextCursor().map(next -> "contribution ui history " + who + " " + next + " " + filter.commandArguments()).orElse("")));
                actions.add(action("返回上级", "back"));
                if (cursor != null) actions.add(action("第一页", "history " + who + " - " + filter.commandArguments()));
                actions.add(action("首页", "home"));
                ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                        "流水 · " + who, "history", List.of("时间", "玩家", "变动", "余额", "类型", "原因"), values,
                        actions, "时间 " + zone + " · 每条流水一行"));
                return;
            }
            int[] widths = {12, 11, 8, 9, 10, 12};
            boolean[] aligned = {false, false, true, true, false, false};
            lines.add(DialogTable.row(widths, aligned, "时间", "玩家", "变动", "余额", "类型", "原因"));
            for (TransactionRecord row : page.rows()) {
                lines.add(DialogTable.row(widths, aligned, LedgerDisplay.shortTime(row.createdAt(), zone), row.playerName(),
                        String.valueOf(row.amount()), String.valueOf(row.balanceAfter()), row.type(), row.reason()));
            }
            if (page.rows().isEmpty()) lines.add(page.validCursor() ? "暂无符合条件的流水" : "翻页位置已失效");
            List<ActionButton> actions = new ArrayList<>();
            actions.add(button("全部流水", "history " + who + " -"));
            actions.add(button("股票买入", "history " + who + " - type=SPEND source=contribution:stock"));
            actions.add(button("股票卖出", "history " + who + " - type=STOCK source=contribution:stock"));
            actions.add(button("管理员调整", "history " + who + " - type=ADMIN"));
            actions.add(button("退市返还", "history " + who + " - type=REFUND"));
            actions.add(template("应用筛选", "contribution ui history " + who + " - $(filters)"));
            actions.add(button("刷新", "history " + who + " " + (cursor == null ? "-" : cursor)
                    + " " + filter.commandArguments()));
            if (cursor != null) actions.add(button("上一页", "previous"));
            page.nextCursor().ifPresent(next -> actions.add(button("下一页", "history " + who + " " + next + " " + filter.commandArguments())));
            if (cursor != null) actions.add(button("第一页", "history " + who + " - " + filter.commandArguments())); actions.add(button("首页", "home"));
            showTable(source, "流水 · " + who + " · " + zone, lines,
                    List.of(input("filters", "更多筛选（可选）：type/source/server/from/to（UTC 日期）", filter.commandArguments(), 256)), actions);
        });
    }
    private static <T> void query(CommandSourceStack source, CompletableFuture<T> future, Consumer<T> success) {
        UUID playerId = source.getPlayer().getUUID();
        long revision = PAGE_REVISIONS.getOrDefault(playerId, 0L);
        Object token = new Object();
        PENDING_QUERY.put(playerId, token);
        future.whenComplete((result, error) -> source.getServer().execute(() -> {
            PENDING_QUERY.remove(playerId, token);
            if (source.getPlayer() == null || source.getPlayer().hasDisconnected()) return;
            if (PAGE_REVISIONS.getOrDefault(playerId, 0L) != revision) return;
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
    private static ContributionUiNetwork.Action action(String label, String request) {
        return new ContributionUiNetwork.Action(label, "contribution ui " + request);
    }
    static ActionButton template(String label, String command) {
        ParsedTemplate parsed = ParsedTemplate.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(command)).getOrThrow();
        return new ActionButton(new CommonButtonData(Component.literal(label), 190), Optional.of(new CommandTemplate(parsed)));
    }
    private static void message(CommandSourceStack source, String title, String message) { show(source, title, List.of(message), List.of(), List.of(button("首页", "home"))); }
    public static void show(CommandSourceStack source, String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons) {
        if (clientUi(source) && inputs.isEmpty()) {
            List<ContributionUiNetwork.Action> actions = new ArrayList<>();
            for (ActionButton button : buttons) {
                if (button.action().isPresent() && button.action().get() instanceof StaticAction staticAction
                        && staticAction.value() instanceof ClickEvent.RunCommand run)
                    actions.add(new ContributionUiNetwork.Action(button.button().label().getString(), run.command().replaceFirst("^/", "")));
            }
            ContributionUiNetwork.send(source.getPlayer(), new ContributionUiNetwork.Snapshot(
                    title, "message", List.of("信息"), lines.stream().map(List::of).toList(), actions, ""));
            return;
        }
        boolean hasPrevious = !CURRENT_PAGE.getOrDefault(source.getPlayer().getUUID(), "home").equals("home");
        source.getPlayer().openDialog(Holder.direct(create(title, lines, inputs, buttons, hasPrevious)));
    }
    private static void showTable(CommandSourceStack source, String title, List<String> lines,
                                  List<Input> inputs, List<ActionButton> buttons) {
        boolean hasPrevious = !CURRENT_PAGE.getOrDefault(source.getPlayer().getUUID(), "home").equals("home");
        source.getPlayer().openDialog(Holder.direct(createTable(title, lines, inputs, buttons, hasPrevious)));
    }
    static Dialog createTable(String title, List<String> lines, List<Input> inputs,
                              List<ActionButton> buttons, boolean hasPrevious) {
        List<DialogBody> bodies = lines.stream()
                .map(line -> (DialogBody) new PlainMessage(DialogTable.text(line), 450)).toList();
        return createBodies(title, bodies, inputs, buttons, hasPrevious, 2);
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
        return createBodies(title, bodies, inputs, buttons, hasPrevious, columns);
    }
    static Dialog cancelTo(Dialog dialog, ActionButton parent) {
        MultiActionDialog page = (MultiActionDialog) dialog;
        return new MultiActionDialog(page.common(), page.actions(), Optional.of(parent), page.columns());
    }
    private static Dialog createBodies(String title, List<DialogBody> bodies, List<Input> inputs,
                                       List<ActionButton> buttons, boolean hasPrevious, int columns) {
        var common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, bodies, inputs);
        ActionButton exit = hasPrevious ? button("返回上级", "back")
                : new ActionButton(new CommonButtonData(Component.literal("关闭"), 190), Optional.empty());
        var controls = new ArrayList<>(buttons);
        if (hasPrevious) controls.add(button("关闭", "close"));
        return new MultiActionDialog(common, controls, Optional.of(exit), columns);
    }
    public static void clear() { NAVIGATION.clear(); CURRENT_PAGE.clear(); PAGE_REVISIONS.clear(); LAST_REQUEST.clear(); PENDING_QUERY.clear(); FORCE_VANILLA.clear(); }
}
