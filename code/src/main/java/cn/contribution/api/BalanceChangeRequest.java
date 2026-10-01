package cn.contribution.api;

import net.minecraft.resources.Identifier;

import java.util.UUID;

public record BalanceChangeRequest(
        UUID idempotencyId,
        AccountTarget target,
        int amount,
        BalanceChangeType type,
        Identifier source,
        String reason,
        String note,
        boolean affectTotalIncome
) {
    public BalanceChangeRequest(UUID idempotencyId, AccountTarget target, int amount,
                                BalanceChangeType type, Identifier source, String reason, String note) {
        this(idempotencyId, target, amount, type, source, reason, note,
                amount > 0 && type != BalanceChangeType.REFUND);
    }
}
