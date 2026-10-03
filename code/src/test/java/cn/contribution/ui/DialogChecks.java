package cn.contribution.ui;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.network.chat.ClickEvent;
import cn.contribution.stock.StockChart;
import cn.contribution.stock.StockView;
import io.netty.buffer.Unpooled;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import com.google.gson.Gson;

/** Verifies the actual production builder against vanilla's JSON and network codecs. */
public final class DialogChecks {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var dialog = ContributionDialogs.create("贡献值系统", List.of("余额：123", "行业建设度与繁荣度"),
                List.of(ContributionDialogs.input("amount", "数量", "1", 11), ContributionDialogs.input("reason", "原因", "测试", 64)),
                List.of(ContributionDialogs.button("首页", "home"), ContributionDialogs.template("确认", "contribution ui prepare 00000000-0000-0000-0000-000000000001 $(amount) $(reason)")));
        var json = Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, dialog).getOrThrow();
        Dialog.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var buffer = Unpooled.buffer();
        try {
            Dialog.CONTEXT_FREE_STREAM_CODEC.encode(buffer, dialog);
            var decoded = Dialog.CONTEXT_FREE_STREAM_CODEC.decode(buffer);
            if (!decoded.common().title().getString().equals("贡献值系统")) throw new AssertionError("Dialog title round-trip");
        } finally { buffer.release(); }
        var chartLines = StockChart.draw(List.of(new StockView.PricePoint(2, 100),
                new StockView.PricePoint(3, 150), new StockView.PricePoint(4, 120)));
        var stockDialog = ContributionDialogs.create("股票股价曲线", chartLines,
                List.of(ContributionDialogs.input("quantity", "交易股数", "1", 5)),
                List.of(ContributionDialogs.template("买入", "contribution stock buy 1 $(quantity)")));
        var stockJson = Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, stockDialog).getOrThrow();
        Dialog.DIRECT_CODEC.parse(JsonOps.INSTANCE, stockJson).getOrThrow();
        var chartBuffer = Unpooled.buffer();
        try {
            Dialog.CONTEXT_FREE_STREAM_CODEC.encode(chartBuffer, stockDialog);
            Dialog.CONTEXT_FREE_STREAM_CODEC.decode(chartBuffer);
        } finally { chartBuffer.release(); }
        var backDialog = ContributionDialogs.create("子页面", List.of(), List.of(),
                List.of(ContributionDialogs.button("刷新", "account self")), true);
        if (!(backDialog.onCancel().orElseThrow() instanceof StaticAction action)
                || !(action.value() instanceof ClickEvent.RunCommand command)
                || !command.command().equals("/contribution ui back")) {
            throw new AssertionError("Escape must invoke previous-page action");
        }
        String header = DialogTable.row(new int[]{10, 12, 12}, new boolean[]{false, true, true},
                "行业", "当日", "总建设度");
        String first = DialogTable.row(new int[]{10, 12, 12}, new boolean[]{false, true, true},
                "土建园林", "17", "23805");
        String second = DialogTable.row(new int[]{10, 12, 12}, new boolean[]{false, true, true},
                "能源化工", "0", "200000");
        if (DialogTable.displayWidth(header) != DialogTable.displayWidth(first)
                || DialogTable.displayWidth(first) != DialogTable.displayWidth(second)
                || !LedgerDisplay.time(Instant.parse("2026-10-02T07:39:28Z"), ZoneId.of("Asia/Shanghai"))
                .equals("2026-10-02 15:39:28")
                || !LedgerDisplay.shortTime(Instant.parse("2026-10-02T07:39:28Z"), ZoneId.of("Asia/Shanghai"))
                .equals("10-02 15:39")) {
            throw new AssertionError("Aligned columns or local ledger time");
        }
        var table = ContributionDialogs.createTable("行业表", List.of(header, first, second),
                List.of(), List.of(ContributionDialogs.button("首页", "home")), false);
        Dialog.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow()).getOrThrow();
        var tableBuffer = Unpooled.buffer();
        try {
            Dialog.CONTEXT_FREE_STREAM_CODEC.encode(tableBuffer, table);
            Dialog.CONTEXT_FREE_STREAM_CODEC.decode(tableBuffer);
        } finally { tableBuffer.release(); }
        var uiSnapshot = new ContributionUiNetwork.Snapshot("管理员", "admin", List.of(), List.of(),
                List.of(new ContributionUiNetwork.Action("查询", "contribution ui account $(target)")), "玩家查询",
                List.of(new ContributionUiNetwork.Field("target", "玩家名称或 UUID", "", 64)), List.of());
        var decodedUi = new Gson().fromJson(new Gson().toJson(uiSnapshot), ContributionUiNetwork.Snapshot.class);
        if (decodedUi.fields().size() != 1 || !decodedUi.actions().getFirst().command().contains("$(target)"))
            throw new AssertionError("Optional client form snapshot round-trip");
        for (var editor : List.of(ShopDialogs.editorDialog(null, ""),
                ShopDialogs.editorDialog(new cn.contribution.shop.ShopOffer(4, "测试商品", "minecraft:torch", 16, 20,
                        "描述正文", true, 0, 3), ""))) {
            Dialog.DIRECT_CODEC.parse(JsonOps.INSTANCE, Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, editor).getOrThrow()).getOrThrow();
            var editorBuffer = Unpooled.buffer();
            try {
                Dialog.CONTEXT_FREE_STREAM_CODEC.encode(editorBuffer, editor);
                Dialog.CONTEXT_FREE_STREAM_CODEC.decode(editorBuffer);
            } finally { editorBuffer.release(); }
            if (editor.common().inputs().size() != 6) throw new AssertionError("Native shop editor fields");
        }
        var shopSnapshot = new cn.contribution.shop.ShopUiNetwork.Snapshot("edit", -1, List.of(), true,
                new cn.contribution.shop.ShopOffer(4, "商品", "minecraft:apple", 1, 20, "描述", false, 3, 5), "");
        var decodedShop = new Gson().fromJson(new Gson().toJson(shopSnapshot), cn.contribution.shop.ShopUiNetwork.Snapshot.class);
        if (!decodedShop.editing().equals(shopSnapshot.editing())) throw new AssertionError("Shop editor metadata round-trip");
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        dispatcher.register(cn.contribution.command.ShopCommands.root(net.minecraft.commands.CommandBuildContext.simple(
                net.minecraft.data.registries.VanillaRegistries.createReloadableLookup(
                        net.minecraft.data.registries.VanillaRegistries.createWorldLookup()),
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS)));
        var source = new net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
                net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null,
                net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS, net.minecraft.network.chat.Component.literal("test"), null);
        for (String input : List.of(
                "shop put_on minecraft:diamond \"测试礼包\" 4 100 建设者精选礼包",
                "shop modify 4 - - 35",
                "shop modify 4 - - - \"\"",
                "shop take_off 4",
                "shop admin publish minecraft:apple \"中文商品\" 3 20 -3 true \"描述正文\"",
                "shop admin save 4 0 minecraft:apple \"中文商品\" 3 20 -3 false \"含 空格 描述\"",
                "shop buy 4 1 0")) {
            var parsed = dispatcher.parse(input, source);
            if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null)
                throw new AssertionError("Shop command grammar: " + input + " " + parsed.getExceptions());
        }
        System.out.println("DIALOG_CODEC_PASS: native page and stock curve, inputs, buttons, command templates, JSON and network round-trip");
    }
}
