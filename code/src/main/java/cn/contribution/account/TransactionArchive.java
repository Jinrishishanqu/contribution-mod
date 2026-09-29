package cn.contribution.account;

import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import net.minecraft.server.MinecraftServer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;

/** Bounded background archival. A moved transaction retains its identity and idempotency record. */
public final class TransactionArchive {
    private final DatabaseService database;
    private boolean running;
    private int nextTick;

    public TransactionArchive(DatabaseService database) { this.database = database; }

    public void tick(MinecraftServer server) {
        if (running || server.getTickCount() < nextTick
                || database.state() != DatabaseState.AVAILABLE
                || !cn.contribution.industry.RuleManager.settlementWindow(server)) return;
        running = true;
        nextTick = server.getTickCount() + 600;
        database.transaction(TransactionArchive::moveBatch).whenComplete((count, error) -> server.execute(() -> {
            running = false;
            if (error != null) cn.contribution.ContributionMod.LOGGER.warn("Transaction archival pending: {}", error.getClass().getSimpleName());
        }));
    }

    public static int moveBatch(Connection connection) throws SQLException {
        // Whole expired UTC months; at most 500 rows/transaction.
        Timestamp cutoff = Timestamp.from(LocalDate.now(ZoneOffset.UTC).minusYears(1)
                .withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        try (var select = connection.prepareStatement("SELECT record_no FROM contribution_transaction "
                + "WHERE created_at < ? "
                + "ORDER BY created_at, record_no LIMIT 500 FOR UPDATE");
             var copy = connection.prepareStatement("INSERT INTO contribution_transaction_archive SELECT * "
                     + "FROM contribution_transaction WHERE record_no = ?");
             var remove = connection.prepareStatement("DELETE FROM contribution_transaction WHERE record_no = ?")) {
            select.setTimestamp(1, cutoff, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            int count = 0;
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    copy.setLong(1, rows.getLong(1)); copy.executeUpdate();
                    remove.setLong(1, rows.getLong(1)); remove.executeUpdate(); count++;
                }
            }
            return count;
        }
    }
}
