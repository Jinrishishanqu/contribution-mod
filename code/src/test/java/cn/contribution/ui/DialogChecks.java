package cn.contribution.ui;

import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dialog.Dialog;
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
        System.out.println("DIALOG_CODEC_PASS: native page, inputs, buttons, command templates, JSON and network round-trip");
    }
}
