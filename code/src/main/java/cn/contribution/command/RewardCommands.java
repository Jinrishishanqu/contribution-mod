package cn.contribution.command;

import cn.contribution.reward.EventCheckinService;
import cn.contribution.runtime.ContributionRuntime;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

public final class RewardCommands {
    private RewardCommands() { }

    public static LiteralArgumentBuilder<CommandSourceStack> checkinCommand() {
        var amount = Commands.argument("contribution", IntegerArgumentType.integer(0))
                .executes(context -> createEvent(context, false))
                .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                                .executes(context -> createEvent(context, true))));
        var create = Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("title", StringArgumentType.string())
                                .then(Commands.argument("start", StringArgumentType.word())
                                        .then(Commands.argument("end", StringArgumentType.word())
                                                .then(amount)))));
        var createExtension = Commands.literal("create-extension")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("title", StringArgumentType.string())
                                .then(Commands.argument("start", StringArgumentType.word())
                                        .then(Commands.argument("end", StringArgumentType.word())
                                                .then(Commands.argument("provider", StringArgumentType.word())
                                                        .then(Commands.argument("data", StringArgumentType.string())
                                                                .executes(RewardCommands::createExtension)))))));
        return Commands.literal("checkin")
                .executes(RewardCommands::status)
                .then(Commands.literal("events").executes(RewardCommands::events))
                .then(Commands.literal("claim").then(Commands.argument("eventId", StringArgumentType.word())
                        .executes(RewardCommands::claimEvent)))
                .then(Commands.literal("event")
                        .requires(source -> Commands.hasPermission(Commands.LEVEL_ADMINS).test(source))
                        .then(create).then(createExtension));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> shopCommand() {
        return ShopCommands.root();
    }

    private static int status(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!RequestLimiter.allow(context.getSource())) return fail(context.getSource(), "操作过快");
        if (ContributionRuntime.checkins() == null) return fail(context.getSource(), "签到服务尚未启动");
        reply(context.getSource(), ContributionRuntime.checkins().status(player.getUUID()));
        return 1;
    }

    private static int events(CommandContext<CommandSourceStack> context) {
        if (!RequestLimiter.allow(context.getSource())) return fail(context.getSource(), "操作过快");
        if (ContributionRuntime.eventCheckins() == null) return fail(context.getSource(), "签到服务尚未启动");
        ContributionRuntime.eventCheckins().list().whenComplete((events, error) -> context.getSource().getServer().execute(() -> {
            if (error != null) { fail(context.getSource(), "活动数据暂时不可用"); return; }
            if (events.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("当前没有活动签到"), false);
            for (var event : events) context.getSource().sendSuccess(() -> Component.literal(
                    event.id() + " · " + event.title() + " · " + event.start() + "—" + event.end()
                            + " · 贡献值 " + event.contribution() + " · 物品 " + event.itemCount()), false);
        }));
        return 1;
    }

    private static int claimEvent(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!RequestLimiter.allow(context.getSource())) return fail(context.getSource(), "操作过快");
        if (ContributionRuntime.eventCheckins() == null) return fail(context.getSource(), "签到服务尚未启动");
        reply(context.getSource(), ContributionRuntime.eventCheckins().claim(player.getUUID(),
                StringArgumentType.getString(context, "eventId")), true);
        return 1;
    }

    private static int createEvent(CommandContext<CommandSourceStack> context, boolean item) {
        if (!ContributionRuntime.isMainServer()) return fail(context.getSource(), "请在主服务器创建活动");
        if (ContributionRuntime.eventCheckins() == null) return fail(context.getSource(), "签到服务尚未启动");
        try {
            String id = StringArgumentType.getString(context, "id");
            String title = StringArgumentType.getString(context, "title");
            LocalDate start = LocalDate.parse(StringArgumentType.getString(context, "start"));
            LocalDate end = LocalDate.parse(StringArgumentType.getString(context, "end"));
            int amount = IntegerArgumentType.getInteger(context, "contribution");
            String itemId = item ? StringArgumentType.getString(context, "item") : null;
            int count = item ? IntegerArgumentType.getInteger(context, "count") : 0;
            reply(context.getSource(), ContributionRuntime.eventCheckins().create(
                    new EventCheckinService.Event(id, title, start, end, amount, itemId, count, null, null)));
            return 1;
        } catch (RuntimeException invalid) { return fail(context.getSource(), "日期格式应为 YYYY-MM-DD，且奖励必须有效"); }
    }

    private static int createExtension(CommandContext<CommandSourceStack> context) {
        if (!ContributionRuntime.isMainServer()) return fail(context.getSource(), "请在主服务器创建活动");
        if (ContributionRuntime.eventCheckins() == null) return fail(context.getSource(), "签到服务尚未启动");
        try {
            reply(context.getSource(), ContributionRuntime.eventCheckins().create(new EventCheckinService.Event(
                    StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "title"),
                    LocalDate.parse(StringArgumentType.getString(context, "start")),
                    LocalDate.parse(StringArgumentType.getString(context, "end")), 0, null, 0,
                    StringArgumentType.getString(context, "provider"), StringArgumentType.getString(context, "data"))));
            return 1;
        } catch (RuntimeException invalid) { return fail(context.getSource(), "活动日期或扩展奖励格式无效"); }
    }


    private static void reply(CommandSourceStack source, CompletableFuture<String> future) { reply(source, future, false); }
    private static void reply(CommandSourceStack source, CompletableFuture<String> future, boolean deliver) {
        future.whenComplete((text, error) -> source.getServer().execute(() -> {
            if (error != null) { fail(source, "数据服务暂时不可用，请稍后重试"); return; }
            boolean success = text.startsWith("今日") || text.startsWith("已创建")
                    || text.startsWith("活动签到成功") || text.startsWith("购买成功") || text.startsWith("订单已提交");
            if (success) source.sendSuccess(() -> Component.literal(text), false);
            else source.sendFailure(Component.literal(text));
            if (success && deliver && source.getPlayer() != null && ContributionRuntime.deliveries() != null)
                ContributionRuntime.deliveries().claim(source.getPlayer());
        }));
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text)); return 0;
    }
}
