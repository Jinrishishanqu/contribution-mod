package cn.contribution.runtime;

import cn.contribution.ContributionMod;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.RuleManager;
import cn.contribution.industry.StatisticsService;

import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One bounded administrator session; all SQL is off-thread and repairs use normal guards. */
final class MaintenanceDiagnostics {
    private boolean running;
    private long nextRequest;
    private int reviewTick = -1;
    private Consumer<String> feedback;

    int request(
            MinecraftServer server,
            DatabaseService database,
            ServerConfig config,
            StatisticsService statistics,
            Consumer<String> output,
            Runnable repair) {
        long now = System.nanoTime();
        if (running || now < nextRequest) {
            output.accept("诊断进行中或处于15秒冷却，请稍后重试。");
            return 0;
        }
        running = true;
        nextRequest = now + TimeUnit.SECONDS.toNanos(15);
        feedback = output;
        output.accept("开始诊断并安全恢复；不会删除数据、跳过关账或强制解锁交易。");
        output.accept(
                "服务器="
                        + config.serverId
                        + " 主服="
                        + config.mainServer
                        + " 数据库="
                        + database.state()
                        + " 规则："
                        + RuleManager.diagnosticState());
        output.accept(
                statistics == null
                        ? "统计服务未启动：规则或恢复日志异常，需查看服务端日志。"
                        : "统计：" + statistics.diagnosticState());
        database.start()
                .thenApply(state -> state)
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete(
                        (state, error) ->
                                server.execute(
                                        () -> {
                                            if (error != null || state != DatabaseState.AVAILABLE) {
                                                output.accept(
                                                        "数据库重连未成功；请检查连接配置、数据库可用性和服务端日志。未改写数据。");
                                                finish();
                                                return;
                                            }
                                            try {
                                                repair.run();
                                            } catch (RuntimeException failure) {
                                                output.accept("恢复请求失败；保护保持生效，请查看服务端日志。");
                                                ContributionMod.LOGGER.warn(
                                                        "CONTRIBUTION_MANUAL_RECOVERY_FAILED",
                                                        failure);
                                                finish();
                                                return;
                                            }
                                            output.accept("已检查数据库连接，并向已启动服务提交恢复请求；在途事务保持不变。");
                                            if (!RuleManager.settlementWindow(server))
                                                output.accept(
                                                        "当前不在8:00—18:00核算窗口，积压将在窗口内继续；不会强行开放交易。");
                                            query(server, database, config, false);
                                        }));
        return 1;
    }

    void tick(MinecraftServer server, DatabaseService database, ServerConfig config) {
        if (running && reviewTick >= 0 && server.getTickCount() >= reviewTick) {
            reviewTick = -1;
            query(server, database, config, true);
        }
    }

    private void query(
            MinecraftServer server, DatabaseService database, ServerConfig config, boolean review) {
        long day = RuleManager.day(server);
        database.transaction(connection -> read(connection, config, day))
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete(
                        (report, error) ->
                                server.execute(
                                        () -> {
                                            if (error != null) {
                                                feedback.accept("诊断查询失败或超时；保护保持生效，请查看服务端日志。");
                                                ContributionMod.LOGGER.warn(
                                                        "CONTRIBUTION_MANUAL_DIAGNOSE_FAILED",
                                                        error);
                                                finish();
                                                return;
                                            }
                                            feedback.accept(review ? "恢复后复查：" : "诊断报告：");
                                            report.lines().forEach(feedback);
                                            if (review) {
                                                feedback.accept(
                                                        report.issues() == 0
                                                                ? "复查未发现数据库时钟、关账或核算阻塞；未测试玩家UI和网络延迟。"
                                                                : "仍有"
                                                                        + report.issues()
                                                                        + "项等待或异常；请按报告处理，积压会继续安全追赶。");
                                                finish();
                                            } else {
                                                feedback.accept("约5秒后复查；若游戏或空服已暂停，复查等待恢复运行。");
                                                reviewTick = server.getTickCount() + 100;
                                            }
                                        }));
    }

    private void finish() {
        running = false;
        reviewTick = -1;
        feedback = null;
    }

    static Report read(Connection connection, ServerConfig config, long today) throws SQLException {
        List<String> lines = new ArrayList<>();
        int issues = 0;
        long marketDay = -1;
        try (var query =
                        connection.prepareStatement(
                                "SELECT game_day, observed_day, observed_time, clock_server_id,"
                                    + " observed_at > CURRENT_TIMESTAMP(6) - INTERVAL '5' SECOND"
                                    + " FROM stock_market_state WHERE singleton_id=1");
                var rows = query.executeQuery()) {
            if (rows.next()) {
                marketDay = rows.getLong(1);
                String owner = rows.getString(4);
                lines.add(
                        "市场已核算日="
                                + marketDay
                                + "；主服采样日="
                                + rows.getLong(2)
                                + " 时刻tick="
                                + rows.getInt(3)
                                + "；时钟归属="
                                + owner);
                if (!rows.getBoolean(5)) {
                    issues++;
                    lines.add("[等待] 主服时钟超过5秒未更新：主服暂停、离线或数据库延迟；请恢复主服运行。");
                }
                if (config.mainServer && owner != null && !config.serverId.equals(owner)) {
                    issues++;
                    lines.add(
                            "[需人工处理] 时钟属于"
                                    + owner
                                    + "，当前主服ID="
                                    + config.serverId
                                    + "；请统一主服配置，不自动夺取时钟。");
                }
                if (marketDay < today) {
                    issues++;
                    lines.add("[追赶] 市场落后" + (today - marketDay) + "日，等待行业结算；不会提前开放交易。");
                }
                if (config.mainServer && (marketDay > today || rows.getLong(2) > today)) {
                    issues++;
                    lines.add("[需人工处理] 数据库市场日期超前于主世界；请核对主世界和备份，不自动回退历史。");
                }
            } else {
                issues++;
                lines.add("[异常] 市场状态行缺失，请查看迁移日志。");
            }
        }
        long firstDay = 0, epochDay = -1, industryDay = -1;
        try (var query =
                        connection.prepareStatement(
                                "SELECT MIN(game_day), MAX(game_day) FROM contribution_rule_epoch");
                var rows = query.executeQuery()) {
            rows.next();
            if (rows.getObject(1) != null) {
                firstDay = rows.getLong(1);
                epochDay = rows.getLong(2);
            }
        }
        if (epochDay < today) {
            issues++;
            lines.add("[等待] 规则发布日=" + epochDay + "，核算日=" + today + "；规则同步正在重试。");
        }
        if (config.mainServer && epochDay > today) {
            issues++;
            lines.add("[需人工处理] 规则日期超前；主世界时间回退会阻止同步，不能改写历史规则。");
        }
        try (var query =
                        connection.prepareStatement(
                                "SELECT MAX(game_day) FROM scheduled_task_run WHERE"
                                        + " task_name='industry_daily_settlement' AND"
                                        + " status='SUCCEEDED'");
                var rows = query.executeQuery()) {
            rows.next();
            if (rows.getObject(1) != null) industryDay = rows.getLong(1);
        }
        lines.add("行业已结算日=" + industryDay + "；当前核算日=" + today);
        long neededDay = industryDay < 0 ? firstDay : industryDay + 1;
        if (neededDay < today) {
            byte[] expected = RuleManager.dayHash(connection, neededDay, new byte[32]);
            try (var query =
                    connection.prepareStatement(
                            "SELECT config_hash FROM statistics_day_close WHERE server_id=? AND"
                                    + " game_day=?")) {
                int shown = 0;
                for (String id : config.statisticsServers) {
                    query.setString(1, id);
                    query.setLong(2, neededDay);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            issues++;
                            if (shown++ < 16)
                                lines.add(
                                        "[等待] " + id + " 缺少第" + neededDay + "日关账；需该节点上线、升级并提交缓冲。");
                        } else if (!java.security.MessageDigest.isEqual(
                                expected, rows.getBytes(1))) {
                            issues++;
                            if (shown++ < 16)
                                lines.add("[需人工处理] " + id + " 第" + neededDay + "日规则哈希不一致，不自动覆盖。");
                        }
                    }
                }
                if (shown > 16) lines.add("另有" + (shown - 16) + "台统计节点异常，详见服务器配置。");
            }
        }
        if (marketDay >= 0 && marketDay < today && industryDay >= marketDay) {
            try (var query =
                    connection.prepareStatement(
                            "SELECT COUNT(*) FROM industry_daily WHERE game_day=?")) {
                query.setLong(1, marketDay);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1)
                            != cn.contribution.industry.BuiltInIndustry.values().length) {
                        issues++;
                        lines.add("[需人工处理] 第" + marketDay + "日行业快照不完整，不能补造历史股价输入。");
                    }
                }
            }
        }
        return new Report(List.copyOf(lines), issues);
    }

    record Report(List<String> lines, int issues) {}
}
