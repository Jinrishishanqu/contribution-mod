package cn.contribution.account;

import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
        UUID transactionId,
        String playerName,
        int amount,
        int balanceAfter,
        String type,
        String reason,
        Instant createdAt) {}
