package cn.contribution.account;

import java.util.UUID;

public record AccountRecord(UUID playerUuid, String playerName, int balance, int totalIncome) {}
