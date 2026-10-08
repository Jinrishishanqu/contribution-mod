package cn.contribution.api;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public record BalanceChangeResult(
        BalanceChangeStatus status,
        UUID idempotencyId,
        Optional<UUID> transactionId,
        OptionalInt balanceBefore,
        OptionalInt balanceAfter,
        boolean replayed,
        String message) {
    public boolean successful() {
        return status == BalanceChangeStatus.SUCCESS;
    }

    public static BalanceChangeResult rejected(
            BalanceChangeStatus status, UUID id, String message) {
        return new BalanceChangeResult(
                status,
                id,
                Optional.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                false,
                message);
    }

    public static BalanceChangeResult success(
            UUID id, UUID transaction, int before, int after, boolean replayed) {
        return new BalanceChangeResult(
                BalanceChangeStatus.SUCCESS,
                id,
                Optional.of(transaction),
                OptionalInt.of(before),
                OptionalInt.of(after),
                replayed,
                "操作成功");
    }
}
