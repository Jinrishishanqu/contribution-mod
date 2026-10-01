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
import java.util.List;

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
        System.out.println("DIALOG_CODEC_PASS: native page and stock curve, inputs, buttons, command templates, JSON and network round-trip");
    }
}
