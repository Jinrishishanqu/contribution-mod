package cn.contribution.runtime;

import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;

import java.nio.file.Files;
import java.nio.file.Path;

/** Diagnostic reads must identify blockers and never mutate historical tables. */
public final class MaintenanceChecks {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        try (var db =
                new DatabaseService(
                        config,
                        Files.createTempDirectory(Path.of("build"), "diagnose-")
                                .resolve("contribution"))) {
            check(db.start().join() == DatabaseState.AVAILABLE, "database");
            db.transaction(
                            connection -> {
                                var first = MaintenanceDiagnostics.read(connection, config, 2);
                                check(first.issues() > 0, "uninitialized data exposes waits");
                                check(
                                        first.lines().stream()
                                                .anyMatch(line -> line.contains("缺少第0日关账")),
                                        "missing node and exact day");
                                try (var sql = connection.createStatement()) {
                                    sql.executeUpdate(
                                            "UPDATE stock_market_state SET game_day=2,"
                                                    + " observed_day=2, observed_time=6000,"
                                                    + " clock_server_id='survival',"
                                                    + " observed_at=CURRENT_TIMESTAMP(6)");
                                    sql.executeUpdate(
                                            "INSERT INTO contribution_rule_epoch (game_day,"
                                                + " config_hash) VALUES (2,"
                                                + " X'0000000000000000000000000000000000000000000000000000000000000000')");
                                    sql.executeUpdate(
                                            "INSERT INTO scheduled_task_run (task_name, game_day,"
                                                + " run_id, status, attempt_count, started_at,"
                                                + " updated_at, completed_at) VALUES"
                                                + " ('industry_daily_settlement', 1,"
                                                + " X'00000000000000000000000000000000',"
                                                + " 'SUCCEEDED', 1, CURRENT_TIMESTAMP(6),"
                                                + " CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
                                }
                                check(
                                        MaintenanceDiagnostics.read(connection, config, 2).issues()
                                                == 0,
                                        "fresh caught-up report");
                                try (var sql = connection.createStatement()) {
                                    sql.executeUpdate(
                                            "UPDATE stock_market_state SET"
                                                + " clock_server_id='other-main',"
                                                + " observed_at=CURRENT_TIMESTAMP(6)-INTERVAL '10'"
                                                + " SECOND");
                                }
                                var invalid = MaintenanceDiagnostics.read(connection, config, 1);
                                check(
                                        invalid.lines().stream()
                                                .anyMatch(line -> line.contains("不自动夺取时钟")),
                                        "owner conflict");
                                check(
                                        invalid.lines().stream()
                                                .anyMatch(line -> line.contains("超过5秒")),
                                        "stale clock");
                                check(
                                        invalid.lines().stream()
                                                .anyMatch(line -> line.contains("不能改写历史规则")),
                                        "future epoch");
                                try (var sql = connection.createStatement();
                                        var rows =
                                                sql.executeQuery(
                                                        "SELECT clock_server_id, game_day FROM"
                                                                + " stock_market_state")) {
                                    rows.next();
                                    check(
                                            rows.getString(1).equals("other-main")
                                                    && rows.getLong(2) == 2,
                                            "diagnostic never rewrites conflicts");
                                }
                                return null;
                            })
                    .join();
        }
        System.out.println(
                "MAINTENANCE_PASS: healthy, missing close, stale clock, foreign owner, future"
                        + " epoch, read-only report");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
