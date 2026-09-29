package cn.contribution.account;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Dependency-free checks for the query inputs and page boundaries. */
public final class AccountQueryChecks {
    private AccountQueryChecks() {
    }

    public static void main(String[] args) {
        String date = LocalDate.now(ZoneOffset.UTC).minusDays(5).toString();
        HistoryFilter filter = HistoryFilter.parse(
                "type=external source=contribution:admin_command server=survival from=" + date, 90);
        check("EXTERNAL".equals(filter.type()), "type should be canonicalized");
        check("contribution:admin_command".equals(filter.source()), "source should be retained");
        check("survival".equals(filter.serverId()), "server should be retained");
        check(filter.commandArguments().startsWith("type=EXTERNAL "), "page link should retain filters");
        reject("type=unknown", 90);
        reject("type=EXTERNAL type=REFUND", 90);
        reject("source=invalid;command", 90);
        reject("from=" + LocalDate.now(ZoneOffset.UTC).minusDays(91), 90);
        reject("from=" + date + " to=" + LocalDate.now(ZoneOffset.UTC).minusDays(6), 90);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AccountPage page = new AccountPage(List.of(
                new AccountRecord(first, "First", 0, 0),
                new AccountRecord(second, "Second", 1, 1)), true, true);
        check(page.nextCursor().orElseThrow().equals(second), "account cursor should use last visible row");
        check(AccountPage.invalidCursor().nextCursor().isEmpty(), "invalid cursor must not paginate");
        check(new AccountPage(List.of(), false, true).nextCursor().isEmpty(), "empty page must not paginate");
    }

    private static void reject(String input, int days) {
        try {
            HistoryFilter.parse(input, days);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Query should be rejected: " + input);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
