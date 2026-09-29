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
        String note
) {
}
