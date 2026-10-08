package cn.contribution.runtime;

import cn.contribution.ContributionMod;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.RuleManager;
import cn.contribution.industry.StatisticsService;

import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Bounded, read-only diagnostics: one background report per real minute per server. */
final class RuntimeDiagnostics {
    private long nextReport;
    private boolean running;

    void tick(
            MinecraftServer server,
            DatabaseService database,
            ServerConfig config,
            StatisticsService statistics) {
        long now = System.nanoTime();
        if (running || now < nextReport) return;
        nextReport = now + TimeUnit.MINUTES.toNanos(1);
        long clock = server.overworld().getOverworldClockTime();
        long day = RuleManager.day(server);
        ContributionMod.LOGGER.info(
                "CONTRIBUTION_SERVER_STATE server={} main={} statisticsServers={} worldClock={}"
                        + " accountingDay={} database={} rules=[{}] statistics=[{}]",
                config.serverId,
                config.mainServer,
                Arrays.toString(config.statisticsServers),
                clock,
                day,
                database.state(),
                RuleManager.diagnosticState(),
                statistics.diagnosticState());
        if (database.state() != DatabaseState.AVAILABLE) return;
        running = true;
        database.transaction(
                        connection -> {
                            StringBuilder report = new StringBuilder();
                            try (var query =
                                            connection.prepareStatement(
                                                    "SELECT game_day, observed_day, observed_time,"
                                                        + " clock_server_id, observed_at FROM"
                                                        + " stock_market_state WHERE singleton_id ="
                                                        + " 1");
                                    var rows = query.executeQuery()) {
                                if (rows.next())
                                    report.append("marketDay=")
                                            .append(rows.getLong(1))
                                            .append(" observedDay=")
                                            .append(rows.getLong(2))
                                            .append(" observedTime=")
                                            .append(rows.getInt(3))
                                            .append(" owner=")
                                            .append(rows.getString(4))
                                            .append(" sampledAt=")
                                            .append(rows.getTimestamp(5));
                            }
                            try (var query =
                                            connection.prepareStatement(
                                                    "SELECT MAX(game_day) FROM scheduled_task_run"
                                                        + " WHERE task_name ="
                                                        + " 'industry_daily_settlement' AND status"
                                                        + " = 'SUCCEEDED'");
                                    var rows = query.executeQuery()) {
                                rows.next();
                                report.append(" industrySettledThrough=").append(rows.getObject(1));
                            }
                            try (var query =
                                    connection.prepareStatement(
                                            "SELECT MAX(game_day) FROM statistics_day_close WHERE"
                                                    + " server_id = ?")) {
                                for (String id : config.statisticsServers) {
                                    query.setString(1, id);
                                    try (var rows = query.executeQuery()) {
                                        rows.next();
                                        report.append(" closedThrough[")
                                                .append(id)
                                                .append("]=")
                                                .append(rows.getObject(1));
                                    }
                                }
                            }
                            return report.toString();
                        })
                .whenComplete(
                        (report, error) ->
                                server.execute(
                                        () -> {
                                            running = false;
                                            if (error == null)
                                                ContributionMod.LOGGER.info(
                                                        "CONTRIBUTION_DATABASE_STATE server={} {}",
                                                        config.serverId,
                                                        report);
                                            else
                                                ContributionMod.LOGGER.warn(
                                                        "CONTRIBUTION_DIAGNOSTIC_FAILED server={}",
                                                        config.serverId,
                                                        error);
                                        }));
    }
}
