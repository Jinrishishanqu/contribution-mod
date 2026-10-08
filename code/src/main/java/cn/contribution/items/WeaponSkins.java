package cn.contribution.items;

import cn.contribution.command.RequestLimiter;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One-shot, server-authoritative skin selection. No polling or custom client packets. */
public final class WeaponSkins {
    private static final Map<String, Family> FAMILIES = load();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final int PAGE_SIZE = 12;
    private static final String LOCK = "contribution:weapon_skin_selected";

    private WeaponSkins() {}

    private static Map<String, Family> load() {
        try (var stream =
                WeaponSkins.class.getResourceAsStream("/contribution/weapon-skins.json")) {
            if (stream == null) throw new IllegalStateException("Missing weapon skin catalog");
            Family[] families =
                    new Gson()
                            .fromJson(
                                    new InputStreamReader(stream, StandardCharsets.UTF_8),
                                    Family[].class);
            Map<String, Family> result = new HashMap<>();
            for (Family family : families) {
                if (family.names().isEmpty()
                        || family.names().size() > 128
                        || result.put(family.type(), family) != null)
                    throw new IllegalStateException("Invalid weapon skin catalog");
            }
            return Map.copyOf(result);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read weapon skins", error);
        }
    }

    public static void register(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("weapon_skin")
                        .then(
                                Commands.literal("page")
                                        .then(
                                                Commands.argument(
                                                                "token", StringArgumentType.word())
                                                        .then(
                                                                Commands.argument(
                                                                                "index",
                                                                                IntegerArgumentType
                                                                                        .integer(0))
                                                                        .executes(
                                                                                context -> {
                                                                                    ServerPlayer
                                                                                            player =
                                                                                                    context.getSource()
                                                                                                            .getPlayerOrException();
                                                                                    Session
                                                                                            session =
                                                                                                    valid(
                                                                                                            player,
                                                                                                            StringArgumentType
                                                                                                                    .getString(
                                                                                                                            context,
                                                                                                                            "token"));
                                                                                    if (session
                                                                                            == null)
                                                                                        return 0;
                                                                                    show(
                                                                                            player,
                                                                                            session,
                                                                                            IntegerArgumentType
                                                                                                    .getInteger(
                                                                                                            context,
                                                                                                            "index"));
                                                                                    return 1;
                                                                                }))))
                        .then(
                                Commands.literal("preview")
                                        .then(
                                                Commands.argument(
                                                                "token", StringArgumentType.word())
                                                        .then(
                                                                Commands.argument(
                                                                                "index",
                                                                                IntegerArgumentType
                                                                                        .integer(0))
                                                                        .executes(
                                                                                context -> {
                                                                                    ServerPlayer
                                                                                            player =
                                                                                                    context.getSource()
                                                                                                            .getPlayerOrException();
                                                                                    Session
                                                                                            session =
                                                                                                    valid(
                                                                                                            player,
                                                                                                            StringArgumentType
                                                                                                                    .getString(
                                                                                                                            context,
                                                                                                                            "token"));
                                                                                    if (session
                                                                                            == null)
                                                                                        return 0;
                                                                                    int index =
                                                                                            IntegerArgumentType
                                                                                                    .getInteger(
                                                                                                            context,
                                                                                                            "index");
                                                                                    var dialog =
                                                                                            previewDialog(
                                                                                                    session
                                                                                                            .expected(),
                                                                                                    session
                                                                                                            .family(),
                                                                                                    session
                                                                                                            .token(),
                                                                                                    index);
                                                                                    if (dialog
                                                                                            == null)
                                                                                        return 0;
                                                                                    player
                                                                                            .openDialog(
                                                                                                    Holder
                                                                                                            .direct(
                                                                                                                    dialog));
                                                                                    return 1;
                                                                                }))))
                        .then(
                                Commands.literal("select")
                                        .then(
                                                Commands.argument(
                                                                "token", StringArgumentType.word())
                                                        .then(
                                                                Commands.argument(
                                                                                "index",
                                                                                IntegerArgumentType
                                                                                        .integer(0))
                                                                        .executes(
                                                                                context -> {
                                                                                    ServerPlayer
                                                                                            player =
                                                                                                    context.getSource()
                                                                                                            .getPlayerOrException();
                                                                                    Session
                                                                                            session =
                                                                                                    valid(
                                                                                                            player,
                                                                                                            StringArgumentType
                                                                                                                    .getString(
                                                                                                                            context,
                                                                                                                            "token"));
                                                                                    int index =
                                                                                            IntegerArgumentType
                                                                                                    .getInteger(
                                                                                                            context,
                                                                                                            "index");
                                                                                    if (session
                                                                                                    == null
                                                                                            || index
                                                                                                    >= session.family()
                                                                                                            .names()
                                                                                                            .size())
                                                                                        return 0;
                                                                                    ItemStack
                                                                                            selected =
                                                                                                    select(
                                                                                                            player
                                                                                                                    .getMainHandItem(),
                                                                                                            session
                                                                                                                    .expected(),
                                                                                                            session
                                                                                                                    .family(),
                                                                                                            index);
                                                                                    if (selected
                                                                                            .isEmpty())
                                                                                        return 0;
                                                                                    player
                                                                                            .setItemInHand(
                                                                                                    InteractionHand
                                                                                                            .MAIN_HAND,
                                                                                                    selected);
                                                                                    player
                                                                                            .containerMenu
                                                                                            .broadcastChanges();
                                                                                    forget(
                                                                                            player
                                                                                                    .getUUID());
                                                                                    player
                                                                                            .sendSystemMessage(
                                                                                                    Component
                                                                                                            .literal(
                                                                                                                    "外观已定型，不能再次右键更名。铁砧改名不会改变外观。"));
                                                                                    return 1;
                                                                                })))));
    }

    public static void initialize() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(
                (player, level, hand, hit) -> use(player, hand));
        UseItemCallback.EVENT.register((player, level, hand) -> use(player, hand));
    }

    private static InteractionResult use(
            net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !eligible(player.getMainHandItem()))
            return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            if (!RequestLimiter.allow(serverPlayer.createCommandSourceStack()))
                return InteractionResult.FAIL;
            Family family = family(player.getMainHandItem());
            Session session =
                    new Session(
                            UUID.randomUUID().toString(), player.getMainHandItem().copy(), family);
            SESSIONS.put(player.getUUID(), session);
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(
                    serverPlayer, WeaponSkinPayload.TYPE)) {
                var previews = new ArrayList<net.minecraft.world.item.ItemStackTemplate>();
                for (int i = 0; i < family.names().size(); i++)
                    previews.add(
                            net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(
                                    select(
                                            session.expected(),
                                            session.expected().copy(),
                                            family,
                                            i)));
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                        serverPlayer,
                        new WeaponSkinPayload(
                                session.token(), "选择" + family.label() + "的外观", previews));
            } else show(serverPlayer, session, 0);
        }
        return interactionSuccess(player.level().isClientSide());
    }

    static InteractionResult interactionSuccess(boolean clientSide) {
        // Fabric sends the client UseItem packet only for the exact SUCCESS constant.
        return clientSide ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    static Family family(ItemStack stack) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !model.getNamespace().equals("contribution")) return null;
        if (!model.getPath().startsWith("weapon_skin/")) return null;
        return FAMILIES.get(model.getPath().replaceFirst("^weapon_skin/", ""));
    }

    static boolean eligible(ItemStack stack) {
        Family family = family(stack);
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return (stack.is(Items.FIREWORK_STAR) || stack.is(Items.WOODEN_SWORD))
                && stack.getCount() == 1
                && family != null
                && initialName(stack, family)
                && (data == null || !data.copyTag().getBoolean(LOCK).orElse(false));
    }

    private static boolean initialName(ItemStack stack, Family family) {
        Component name = stack.get(DataComponents.ITEM_NAME);
        return Component.literal(family.initialName()).equals(name)
                || Component.literal("未定型" + family.label()).equals(name);
    }

    public record Starter(String type, String name, String itemSpec) {}

    public static List<Starter> starters() {
        return FAMILIES.values().stream()
                .sorted(java.util.Comparator.comparing(Family::type))
                .map(
                        family ->
                                new Starter(
                                        family.type(),
                                        family.initialName(),
                                        "minecraft:firework_star[max_stack_size=1,item_model='contribution:weapon_skin/"
                                                + family.type()
                                                + "',item_name='"
                                                + family.initialName()
                                                + "']"))
                .toList();
    }

    static ItemStack select(ItemStack actual, ItemStack expected, Family family, int index) {
        if (!eligible(actual)
                || !ItemStack.matches(actual, expected)
                || family(actual) != family
                || index < 0
                || index >= family.names().size()) return ItemStack.EMPTY;
        var item =
                switch (family.type()) {
                    case "sword" -> Items.GOLDEN_SWORD;
                    case "axe" -> Items.GOLDEN_AXE;
                    case "pickaxe" -> Items.GOLDEN_PICKAXE;
                    case "hoe" -> Items.GOLDEN_HOE;
                    case "shovel" -> Items.GOLDEN_SHOVEL;
                    case "spear" -> Items.GOLDEN_SPEAR;
                    default -> throw new IllegalStateException("Unknown weapon skin family");
                };
        ItemStack selected = actual.transmuteCopy(item);
        selected.set(DataComponents.ITEM_NAME, Component.literal(family.names().get(index)));
        CompoundTag data =
                actual.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putBoolean(LOCK, true);
        selected.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return selected;
    }

    private static Session valid(ServerPlayer player, String token) {
        if (!RequestLimiter.allow(player.createCommandSourceStack())) return null;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null
                || !session.token().equals(token)
                || !eligible(player.getMainHandItem())
                || !ItemStack.matches(player.getMainHandItem(), session.expected())) return null;
        return session;
    }

    private static void show(ServerPlayer player, Session session, int page) {
        int pages = (session.family().names().size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 0 || page >= pages) return;
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = page * PAGE_SIZE;
                i < Math.min((page + 1) * PAGE_SIZE, session.family().names().size());
                i++)
            buttons.add(button(session.family().names().get(i), "preview", session.token(), i));
        if (page > 0) buttons.add(button("上一页", "page", session.token(), page - 1));
        if (page + 1 < pages) buttons.add(button("下一页", "page", session.token(), page + 1));
        var common =
                new net.minecraft.server.dialog.CommonDialogData(
                        Component.literal("选择" + session.family().label() + "的外观"),
                        Optional.empty(),
                        true,
                        false,
                        net.minecraft.server.dialog.DialogAction.CLOSE,
                        List.of(
                                new net.minecraft.server.dialog.body.PlainMessage(
                                        Component.literal(
                                                "第 "
                                                        + (page + 1)
                                                        + "/"
                                                        + pages
                                                        + " 页 · 点击名称预览，确认后永久定型。关闭可稍后再选。"),
                                        430),
                                new net.minecraft.server.dialog.body.PlainMessage(
                                        Component.literal("原初物品为烟火之星。选择后转换为对应的木质武器或工具，不能再选肤。"),
                                        430)),
                        List.of());
        var exit =
                new ActionButton(
                        new CommonButtonData(Component.literal("关闭"), 140), Optional.empty());
        player.openDialog(
                Holder.direct(
                        new net.minecraft.server.dialog.MultiActionDialog(
                                common, buttons, Optional.of(exit), 3)));
    }

    private static ActionButton button(String label, String action, String token, int index) {
        return new ActionButton(
                new CommonButtonData(Component.literal(label), 140),
                Optional.of(
                        new StaticAction(
                                new ClickEvent.RunCommand(
                                        "/weapon_skin " + action + " " + token + " " + index))));
    }

    static net.minecraft.server.dialog.MultiActionDialog previewDialog(
            ItemStack expected, Family family, String token, int index) {
        ItemStack preview = select(expected, expected.copy(), family, index);
        if (preview.isEmpty()) return null;
        preview.set(
                DataComponents.ITEM_MODEL,
                Identifier.fromNamespaceAndPath(
                        "contribution", "weapon_skin/preview/" + family.type()));
        var body =
                new net.minecraft.server.dialog.body.ItemBody(
                        net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(preview),
                        Optional.empty(),
                        false,
                        true,
                        96,
                        96);
        var common =
                new net.minecraft.server.dialog.CommonDialogData(
                        Component.literal(family.names().get(index)),
                        Optional.empty(),
                        true,
                        false,
                        net.minecraft.server.dialog.DialogAction.CLOSE,
                        List.of(
                                body,
                                new net.minecraft.server.dialog.body.PlainMessage(
                                        Component.literal("仅预览，确认后才会定型。"), 300)),
                        List.of());
        return new net.minecraft.server.dialog.MultiActionDialog(
                common,
                List.of(
                        button("确认定型", "select", token, index),
                        new ActionButton(
                                new CommonButtonData(Component.literal("关闭"), 140),
                                Optional.empty())),
                Optional.of(button("返回列表", "page", token, index / PAGE_SIZE)),
                2);
    }

    public static void forget(UUID player) {
        SESSIONS.remove(player);
    }

    public static void clear() {
        SESSIONS.clear();
    }

    record Family(String type, String label, String initialName, List<String> names) {
        Family {
            names = List.copyOf(names);
        }
    }

    private record Session(String token, ItemStack expected, Family family) {}
}
