package cn.contribution.ui;

import cn.contribution.api.*;
import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.*;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.StaticAction;
import java.util.*;

/** Confirmation tokens are owner-bound and reuse one idempotency ID across repeated clicks. */
public final class AdminDialogOperations {
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private AdminDialogOperations() { }
    public static void clear() { PENDING.clear(); }
    public static void handle(CommandSourceStack source, String request) {
        if (!Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)) throw new IllegalArgumentException("需要管理员权限");
        UUID owner = source.getPlayer().getUUID();
        String[] args = request.split("\\s+", 4);
        if (args[0].equals("prepare")) {
            if (args.length != 4 || args[3].isBlank() || args[3].codePointCount(0,args[3].length()) > 64) throw new IllegalArgumentException("请填写数量与最多 64 字的原因");
            int amount = Integer.parseInt(args[2]);
            if (amount == 0) throw new IllegalArgumentException("变动数量不能为 0");
            UUID token = UUID.randomUUID();
            var change = new BalanceChangeRequest(token, AccountTarget.byUuid(UUID.fromString(args[1])), amount,
                    BalanceChangeType.EXTERNAL, Identifier.fromNamespaceAndPath("contribution", "admin_dialog"), args[3], "");
            PENDING.entrySet().removeIf(entry -> entry.getValue().expires < System.nanoTime());
            PENDING.put(owner, new Pending(token, change, System.nanoTime() + 300_000_000_000L));
            ContributionDialogs.show(source, "确认账户变动", List.of("玩家 UUID：" + args[1], "变动：" + amount, "原因：" + args[3]), List.of(),
                    List.of(button("确认提交", "confirm " + token), button("取消", "account " + args[1])));
        } else {
            Pending pending = PENDING.get(owner);
            if (args.length < 2 || pending == null || pending.expires < System.nanoTime() || !pending.token.toString().equals(args[1]))
                throw new IllegalArgumentException("确认已过期，请重新填写");
            ContributionRuntime.accounts().changeAdmin(pending.request, source.getTextName()).whenComplete((result,error) -> source.getServer().execute(() -> {
                if (source.getPlayer().hasDisconnected()) return;
                String text = error != null ? "数据服务暂时不可用，可重新点击确认重试" : result.message();
                if (result != null && result.successful()) text = "余额 " + result.balanceBefore().orElseThrow() + " → " + result.balanceAfter().orElseThrow() + "；流水 " + result.transactionId().orElseThrow();
                ContributionDialogs.show(source, "账户操作结果", List.of(text), List.of(), List.of(button("返回账户", "account " + pending.request.target().playerUuid()), button("重试原请求", "confirm " + pending.token)));
            }));
        }
    }
    private static ActionButton button(String text, String request) {
        return new ActionButton(new CommonButtonData(Component.literal(text),190), Optional.of(new StaticAction(new ClickEvent.RunCommand("/contribution ui " + request))));
    }
    private record Pending(UUID token, BalanceChangeRequest request, long expires) { }
}
