package cn.contribution.industry;

import cn.contribution.account.AccountService;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Real transactions test the shared quantity converter, not a duplicate model of it. */
public final class MeasuredStatisticsChecks {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        Path work = Files.createTempDirectory(Path.of("build"), "measured-events-");
        var snapshot = new RuleSnapshot();
        snapshot.events.putAll(GameEventRules.loadBuiltIn().entries());
        snapshot.events.put(
                "contribution:test/observe",
                new GameEventRules.Rule(BuiltInIndustry.values()[0], "successful_action", 1, 0));
        UUID actor = UUID.randomUUID();
        String sector = "processing_manufacturing", event = "contribution:craft/" + sector;
        var first = batch(snapshot, actor, event, 10, 63);
        var second = batch(snapshot, actor, event, 11, 2);
        try (var db = new DatabaseService(config, work.resolve("database"))) {
            check(db.start().join() == DatabaseState.AVAILABLE, "database startup");
            var stats = new StatisticsService(db, config, work.resolve("journal"), snapshot);
            try {
                check(
                        first.affectsThrough(10) && !first.affectsThrough(9),
                        "raw quantities participate in drain barrier");
                write(db, stats, first);
                write(db, stats, first);
                check(
                        value(db, "measurement_remainder", "remainder_amount", actor, event) == 63,
                        "63 persists without points and replay is harmless");
                write(db, stats, second);
                write(db, stats, second);
                check(
                        value(db, "measurement_remainder", "remainder_amount", actor, event) == 1,
                        "cross-day 65 outputs make one point and leave one");
                db.transaction(
                                c -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT development FROM player_industry_stats"
                                                            + " WHERE player_uuid=? AND"
                                                            + " industry_id=?")) {
                                        q.setBytes(1, AccountService.uuidBytes(actor));
                                        q.setString(2, "contribution:" + sector);
                                        try (var rows = q.executeQuery()) {
                                            check(
                                                    rows.next() && rows.getLong(1) == 1,
                                                    "personal point once");
                                        }
                                    }
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT game_day,development FROM"
                                                            + " industry_day_accumulator WHERE"
                                                            + " industry_id=?")) {
                                        q.setString(1, "contribution:" + sector);
                                        try (var rows = q.executeQuery()) {
                                            check(
                                                    rows.next()
                                                            && rows.getLong(1) == 11
                                                            && rows.getLong(2) == 1
                                                            && !rows.next(),
                                                    "only completion day receives server point");
                                        }
                                    }
                                    return null;
                                })
                        .join();
                UUID other = UUID.randomUUID();
                write(db, stats, batch(snapshot, other, event, 11, 63));
                write(
                        db,
                        stats,
                        batch(snapshot, actor, "contribution:craft/energy_chemical", 11, 63));
                check(
                        value(db, "measurement_remainder", "remainder_amount", actor, event) == 1,
                        "no cross-player or cross-industry mixing");
                var rejected = batch(snapshot, actor, event, 12, 63);
                try {
                    db.transaction(
                                    c -> {
                                        stats.writeBatch(c, rejected);
                                        throw new java.sql.SQLException("forced rollback");
                                    })
                            .join();
                    throw new AssertionError("rollback expected");
                } catch (java.util.concurrent.CompletionException expected) {
                }
                check(
                        value(db, "measurement_remainder", "remainder_amount", actor, event) == 1,
                        "transaction rolls remainder back");
                write(db, stats, rejected);
                write(db, stats, rejected);
                check(
                        value(db, "measurement_remainder", "remainder_amount", actor, event) == 0,
                        "retry accounts exactly once");
                write(db, stats, batch(snapshot, actor, "contribution:test/observe", 12, 19));
                check(
                        value(
                                        db,
                                        "event_activity_day",
                                        "SUM(raw_amount)",
                                        actor,
                                        "contribution:test/observe")
                                == 19,
                        "zero-value observations preserve raw activity");
                check(
                        value(
                                        db,
                                        "measurement_remainder",
                                        "remainder_amount",
                                        actor,
                                        "contribution:test/observe")
                                == -1,
                        "observation creates no reward remainder");
                var writer =
                        StatisticsService.class.getDeclaredMethod(
                                "writeJournal", StatisticsService.Batch.class);
                writer.setAccessible(true);
                writer.invoke(stats, second);
                var reader = StatisticsService.class.getDeclaredMethod("readJournal", Path.class);
                reader.setAccessible(true);
                var recovered =
                        (StatisticsService.Batch)
                                reader.invoke(
                                        null,
                                        work.resolve("journal").resolve(second.id() + ".bin"));
                check(
                        recovered.id().equals(second.id())
                                && recovered.measured().equals(second.measured())
                                && java.util.Arrays.equals(
                                        recovered.configHash(), second.configHash()),
                        "CSU5 journal round-trip");
                write(db, stats, recovered);
                check(
                        value(db, "event_activity_day", "SUM(raw_amount)", actor, event) == 128,
                        "journal replay does not duplicate raw activity");
            } finally {
                stats.close();
            }
        }
        try (var db = new DatabaseService(config, work.resolve("database"))) {
            check(db.start().join() == DatabaseState.AVAILABLE, "database reopen");
            check(
                    value(db, "measurement_remainder", "remainder_amount", actor, event) == 0,
                    "remainder survives reopen");
        }
        System.out.println(
                "MEASURED_EVENTS_PASS: 64 outputs, separation, cross-day, replay, rollback,"
                        + " journal, reopen, observation");
    }

    private static StatisticsService.Batch batch(
            RuleSnapshot rules, UUID actor, String event, long day, long count) {
        return new StatisticsService.Batch(
                UUID.randomUUID(),
                1,
                rules.hash(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(new MeasuredStatistics.Key(day, actor, event), count));
    }

    private static void write(
            DatabaseService db, StatisticsService stats, StatisticsService.Batch batch) {
        db.transaction(
                        c -> {
                            stats.writeBatch(c, batch);
                            return null;
                        })
                .join();
    }

    private static long value(
            DatabaseService db, String table, String column, UUID actor, String event) {
        return db.transaction(
                        c -> {
                            try (var q =
                                    c.prepareStatement(
                                            "SELECT "
                                                    + column
                                                    + " FROM "
                                                    + table
                                                    + " WHERE actor_uuid=? AND event_id=?")) {
                                q.setBytes(1, AccountService.uuidBytes(actor));
                                q.setString(2, event);
                                try (var rows = q.executeQuery()) {
                                    return rows.next() ? rows.getLong(1) : -1L;
                                }
                            }
                        })
                .join();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
