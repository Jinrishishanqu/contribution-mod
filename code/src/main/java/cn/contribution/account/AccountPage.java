package cn.contribution.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record AccountPage(List<AccountRecord> rows, boolean hasMore, boolean validCursor) {
    public AccountPage {
        rows = List.copyOf(rows);
    }

    public static AccountPage invalidCursor() {
        return new AccountPage(List.of(), false, false);
    }

    public Optional<UUID> nextCursor() {
        return hasMore && !rows.isEmpty() ? Optional.of(rows.getLast().playerUuid()) : Optional.empty();
    }
}
