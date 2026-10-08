package cn.contribution.industry;

import cn.contribution.account.AccountService;

import org.flywaydb.core.Flyway;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

/** Upgrade a real V20 database containing historical data, never the user's world. */
public final class DevelopmentTotalsChecks {
    public static void main(String[] args) throws Exception {
        String url =
                "jdbc:h2:file:"
                        + Files.createTempDirectory(Path.of("build"), "totals-upgrade-")
                                .resolve("db")
                                .toAbsolutePath()
                                .toString()
                                .replace('\\', '/')
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .target("20")
                .load()
                .migrate();
        UUID player = new UUID(0, 1);
        byte[] uuid = AccountService.uuidBytes(player);
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO player_industry_stats"
                                    + " (player_uuid,industry_id,development,updated_at) VALUES"
                                    + " (?,?,?,CURRENT_TIMESTAMP(6))")) {
                insert.setBytes(1, uuid);
                insert.setLong(3, Long.MAX_VALUE);
                for (String industry :
                        List.of(
                                "contribution:construction_landscaping",
                                "contribution:mining_metallurgy",
                                "unknown:ignored")) {
                    insert.setString(2, industry);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        var latest =
                Flyway.configure()
                        .dataSource(url, "sa", "")
                        .locations("classpath:db/migration/h2")
                        .target("21")
                        .load();
        check(latest.migrate().migrationsExecuted == 1, "V20 upgrades by exactly V21");
        check(latest.migrate().migrationsExecuted == 0, "migration not applied twice");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            check(
                    total(connection).equals(new BigDecimal("18446744073709551614")),
                    "historical precise backfill, unknown excluded");
            connection.setAutoCommit(false);
            PlayerDevelopmentTotals.lock(connection, List.of(player));
            try (var update =
                    connection.prepareStatement(
                            "UPDATE player_industry_stats SET development=1 WHERE player_uuid=? AND"
                                    + " industry_id='contribution:mining_metallurgy'")) {
                update.setBytes(1, uuid);
                update.executeUpdate();
            }
            PlayerDevelopmentTotals.refresh(connection, List.of(player));
            check(
                    total(connection).equals(new BigDecimal("9223372036854775808")),
                    "affected player refreshed exactly");
            connection.rollback();
            check(
                    total(connection).equals(new BigDecimal("18446744073709551614")),
                    "rollback preserves source and total");
            try (var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT COUNT(*) FROM player_industry_stats")) {
                check(rows.next() && rows.getInt(1) == 3, "old rows untouched");
            }
        }
        System.out.println(
                "DEVELOPMENT_TOTALS_PASS: V20 historical upgrade, precise backfill, repeat"
                        + " migration, refresh and rollback");
    }

    private static BigDecimal total(java.sql.Connection connection) throws Exception {
        try (var query = connection.createStatement();
                var rows = query.executeQuery("SELECT development FROM player_development_total")) {
            check(rows.next(), "materialized row exists");
            return rows.getBigDecimal(1);
        }
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
