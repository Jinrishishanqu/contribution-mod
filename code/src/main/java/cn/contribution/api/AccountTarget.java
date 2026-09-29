package cn.contribution.api;

import java.util.Objects;
import java.util.UUID;

public record AccountTarget(UUID playerUuid, String playerName) {
    public AccountTarget {
        if ((playerUuid == null) == (playerName == null)) {
            throw new IllegalArgumentException("Exactly one account identifier is required");
        }
        if (playerName != null && !playerName.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Invalid player name");
        }
    }

    public static AccountTarget byUuid(UUID uuid) {
        return new AccountTarget(Objects.requireNonNull(uuid), null);
    }

    public static AccountTarget byName(String name) {
        return new AccountTarget(null, Objects.requireNonNull(name));
    }
}
