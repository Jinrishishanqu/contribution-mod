package cn.contribution.command;

import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.shop.ShopCatalog;
import cn.contribution.shop.ShopUiNetwork;
import cn.contribution.ui.ShopDialogs;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Command grammar and authorization; catalog policy lives in ShopCatalog/ShopService. */
public final class ShopCommands {
    private ShopCommands() { }
    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        var putPrice = Commands.argument("price", IntegerArgumentType.integer(1))
                .executes(context -> put(context, false))
                .then(Commands.argument("description", StringArgumentType.greedyString()).executes(context -> put(context, true)));
        var modifyName = Commands.argument("name", StringArgumentType.string()).executes(context -> modify(context, 1))
                .then(Commands.argument("count", StringArgumentType.word()).executes(context -> modify(context, 2))
                        .then(Commands.argument("price", StringArgumentType.word()).executes(context -> modify(context, 3))
                                .then(Commands.argument("description", StringArgumentType.greedyString()).executes(context -> modify(context, 4)))));
        var admin = Commands.literal("admin").requires(ShopUiNetwork::admin)
                .executes(context -> ui(context, () -> ShopUiNetwork.management(context.getSource())))
                .then(Commands.literal("create").executes(context -> ui(context, () -> ShopUiNetwork.create(context.getSource()))))
                .then(Commands.literal("edit").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> ui(context, () -> ShopUiNetwork.edit(context.getSource(), id(context), "")))))
                .then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(0))
                        .executes(context -> ui(context, () -> ShopDialogs.management(context.getSource(),
                                IntegerArgumentType.getInteger(context, "page"), "")))))
                .then(Commands.literal("publish").then(editorArguments(false)))
                .then(Commands.literal("save").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.argument("revision", LongArgumentType.longArg(0)).then(editorArguments(true)))));
        return Commands.literal("shop")
                .executes(context -> ui(context, () -> ShopUiNetwork.openDefault(context.getSource())))
                .then(Commands.literal("ui_vanilla").executes(context -> ui(context, () -> ShopUiNetwork.openVanilla(context.getSource()))))
                .then(Commands.literal("refresh").executes(context -> ui(context, () -> ShopUiNetwork.refresh(context.getSource()))))
                .then(Commands.literal("back").executes(context -> ui(context, () -> ShopUiNetwork.back(context.getSource()))))
                .then(Commands.literal("buy").then(Commands.argument("offer", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                .executes(context -> buy(context, false, false))
                                .then(Commands.argument("revision", LongArgumentType.longArg(0)).executes(context -> buy(context, false, true))))))
                .then(Commands.literal("retry").then(Commands.argument("orderId", StringArgumentType.word())
                        .then(Commands.argument("offer", StringArgumentType.word())
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64)).executes(context -> buy(context, true, false))))))
                .then(Commands.literal("page").then(Commands.argument("page", IntegerArgumentType.integer(0))
                        .executes(context -> ui(context, () -> ShopDialogs.page(context.getSource(), IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("claim").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    if (!allow(context)) return 0;
                    ContributionRuntime.deliveries().claim(player);
                    context.getSource().sendSuccess(() -> Component.literal("正在领取待发物品"), false); return 1;
                }))
                .then(admin)
                .then(Commands.literal("put_on").requires(ShopUiNetwork::admin).then(itemArgument()
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).then(putPrice)))))
                .then(Commands.literal("take_off").requires(ShopUiNetwork::admin).then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> {
                            if (!allow(context)) return 0;
                            result(context.getSource(), ContributionRuntime.shop().modify(id(context),
                                    new ShopCatalog.Change(null, null, null, null, null, false, null), null), false);
                            return 1;
                        })))
                .then(Commands.literal("modify").requires(ShopUiNetwork::admin).then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> ui(context, () -> ShopUiNetwork.edit(context.getSource(), id(context), "")))
                        .then(modifyName)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> itemArgument() {
        return Commands.argument("item", IdentifierArgument.id()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(BuiltInRegistries.ITEM.keySet().stream().map(Object::toString), builder));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> editorArguments(boolean update) {
        return itemArgument().then(Commands.argument("name", StringArgumentType.string())
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("price", IntegerArgumentType.integer(1))
                                .then(Commands.argument("order", IntegerArgumentType.integer())
                                        .then(Commands.argument("listed", BoolArgumentType.bool())
                                                .then(Commands.argument("description", StringArgumentType.string())
                                                        .executes(context -> save(context, update))))))));
    }
    private static int put(CommandContext<CommandSourceStack> context, boolean description) {
        if (!allow(context)) return 0;
        try {
            result(context.getSource(), ContributionRuntime.shop().create(new ShopCatalog.Change(
                    string(context, "name"), item(context), IntegerArgumentType.getInteger(context, "count"),
                    IntegerArgumentType.getInteger(context, "price"), description ? description(context) : "", true, 0)), false);
        } catch (IllegalArgumentException invalid) { return fail(context.getSource(), invalid.getMessage()); }
        return 1;
    }
    private static int modify(CommandContext<CommandSourceStack> context, int supplied) {
        if (!allow(context)) return 0;
        try {
            String name = string(context, "name");
            String description = supplied == 4 ? description(context) : null;
            result(context.getSource(), ContributionRuntime.shop().modify(id(context), new ShopCatalog.Change(
                    name.equals("-") ? null : name, null, supplied >= 2 ? optionalInteger(string(context, "count")) : null,
                    supplied >= 3 ? optionalInteger(string(context, "price")) : null,
                    "-".equals(description) ? null : description, null, null), null), false);
            return 1;
        } catch (IllegalArgumentException invalid) { return fail(context.getSource(), "数量或售价应为整数；用 - 保持该字段不变"); }
    }
    private static int save(CommandContext<CommandSourceStack> context, boolean update) {
        if (!allow(context)) return 0;
        try {
            var change = new ShopCatalog.Change(string(context, "name"), item(context),
                    IntegerArgumentType.getInteger(context, "count"), IntegerArgumentType.getInteger(context, "price"),
                    string(context, "description"), BoolArgumentType.getBool(context, "listed"), IntegerArgumentType.getInteger(context, "order"));
            result(context.getSource(), update ? ContributionRuntime.shop().modify(id(context), change,
                    LongArgumentType.getLong(context, "revision")) : ContributionRuntime.shop().create(change), true);
            return 1;
        } catch (IllegalArgumentException invalid) { return fail(context.getSource(), invalid.getMessage()); }
    }
    private static void result(CommandSourceStack source, CompletableFuture<ShopCatalog.Result> future, boolean showUi) {
        future.whenComplete((result, error) -> source.getServer().execute(() -> {
            if (error != null) { fail(source, "商店数据暂时不可用，请稍后重试"); return; }
            if (result.success()) {
                source.sendSuccess(() -> Component.literal(result.message()), false);
                if (showUi && source.getPlayer() != null && !source.getPlayer().hasDisconnected()) ShopUiNetwork.management(source, result.message());
            } else {
                fail(source, result.message());
                // Keep the editor and typed values intact on failure; the player can correct them immediately.
            }
        }));
    }
    private static int buy(CommandContext<CommandSourceStack> context, boolean retry, boolean revision) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!allow(context)) return 0;
        UUID order;
        try { order = retry ? UUID.fromString(string(context, "orderId")) : UUID.randomUUID(); }
        catch (IllegalArgumentException invalid) { return fail(context.getSource(), "订单 ID 无效"); }
        var source = context.getSource();
        source.sendSuccess(() -> Component.literal("订单请求 ID：" + order + "；结果不明时可用 /shop retry 重试"), false);
        ContributionRuntime.shop().buy(player.getUUID(), string(context, "offer"), IntegerArgumentType.getInteger(context, "quantity"),
                order, revision ? LongArgumentType.getLong(context, "revision") : null).whenComplete((message, error) -> source.getServer().execute(() -> {
                    if (error != null) { fail(source, "商店数据暂时不可用，请稍后重试"); return; }
                    if (message.startsWith("购买成功") || message.startsWith("订单已提交")) {
                        source.sendSuccess(() -> Component.literal(message), false);
                        if (!player.hasDisconnected()) {
                            ContributionRuntime.deliveries().claim(player);
                            ShopUiNetwork.refresh(source);
                        }
                    } else fail(source, message);
                }));
        return 1;
    }
    private static int ui(CommandContext<CommandSourceStack> context, Runnable action) {
        if (!allow(context)) return 0; action.run(); return 1;
    }
    private static boolean allow(CommandContext<CommandSourceStack> context) {
        if (!RequestLimiter.allow(context.getSource())) { fail(context.getSource(), "操作过快，请稍后重试"); return false; }
        if (ContributionRuntime.shop() == null) { fail(context.getSource(), "商店尚未启动"); return false; }
        return true;
    }
    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text)); ShopUiNetwork.feedback(source, text); return 0;
    }
    private static long id(CommandContext<CommandSourceStack> context) { return LongArgumentType.getLong(context, "id"); }
    private static String string(CommandContext<CommandSourceStack> context, String name) { return StringArgumentType.getString(context, name); }
    private static String item(CommandContext<CommandSourceStack> context) {
        return IdentifierArgument.getId(context, "item").toString();
    }
    private static Integer optionalInteger(String value) { return value.equals("-") ? null : Integer.valueOf(value); }
    private static String description(CommandContext<CommandSourceStack> context) {
        String raw = string(context, "description");
        if (!raw.startsWith("\"")) return raw;
        try {
            StringReader reader = new StringReader(raw);
            String result = reader.readString();
            if (reader.canRead()) throw new IllegalArgumentException("描述引号格式无效");
            return result;
        } catch (CommandSyntaxException invalid) { throw new IllegalArgumentException("描述引号格式无效"); }
    }
}
