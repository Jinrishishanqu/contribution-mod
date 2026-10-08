package cn.contribution.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record HistoryPage(List<TransactionRecord> rows, boolean hasMore, boolean validCursor) {
    public HistoryPage {
        rows = List.copyOf(rows);
    }

    public static HistoryPage invalidCursor() {
        return new HistoryPage(List.of(), false, false);
    }

    public Optional<UUID> nextCursor() {
        return hasMore && !rows.isEmpty()
                ? Optional.of(rows.getLast().transactionId())
                : Optional.empty();
    }
}
