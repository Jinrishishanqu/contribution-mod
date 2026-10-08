package cn.contribution.industry;

import cn.contribution.account.AccountService;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real InnoDB transactions, including rollback and idempotent distance conversion. */
public final class StatisticsIntegrationChecks {
    public static void main(String[] args) throws Exception {
        boolean embedded = args.length > 0 && args[0].equals("embedded");
        ServerConfig config = new ServerConfig();
        config.database.mode = embedded ? "embedded" : "mysql";
        config.database.jdbcUrl =
                "jdbc:mysql://127.0.0.1:23306/contribution_stats_test?serverTimezone=UTC";
        config.database.username = "root";
        Path databasePath =
                embedded
                        ? Files.createTempDirectory(Path.of("build"), "embedded-statistics-")
                                .resolve("contribution")
                        : Path.of("unused-mysql-path");
        try (var db = new DatabaseService(config, databasePath)) {
            check(db.start().join() == DatabaseState.AVAILABLE, "startup");
            var snapshot = new RuleSnapshot();
            snapshot.events.putAll(GameEventRules.loadBuiltIn().entries());
            Files.createDirectories(Path.of("build/test-journals"));
            var stats =
                    new StatisticsService(
                            db,
                            config,
                            Files.createTempDirectory(
                                    Path.of("build/test-journals"), "statistics-"),
                            snapshot);
            try {
                UUID player = UUID.randomUUID();
                long day = System.currentTimeMillis(); // Isolated keys make the test repeatable
                // without deleting data.
                var rule = snapshot.events.get("contribution:logistics/distance/elytra");
                String industry = rule.industry().path();
                var first = batch(player, day, 9_500_000, snapshot.hash());
                var second = batch(player, day, 1_000_000, snapshot.hash());
                check(!first.affectsThrough(day - 1), "today's distance does not block yesterday");
                check(first.affectsThrough(day), "historical distance blocks its own day");
                var mixed =
                        new StatisticsService.Batch(
                                UUID.randomUUID(),
                                1,
                                snapshot.hash(),
                                Map.of(new StatisticsService.IndustryDayKey(day - 1, industry), 1L),
                                Map.of(),
                                Map.of(),
                                Map.of(
                                        new StatisticsService.DistanceDayKey(day, player, "elytra"),
                                        1L));
                verifyDrainBarrier(stats, first, mixed, day);
                db.transaction(
                                c -> {
                                    stats.writeBatch(c, first);
                                    return null;
                                })
                        .join();
                db.transaction(
                                c -> {
                                    stats.writeBatch(c, first);
                                    return null;
                                })
                        .join();
                db.transaction(
                                c -> {
                                    stats.writeBatch(c, second);
                                    return null;
                                })
                        .join();
                db.transaction(
                                c -> {
                                    stats.writeBatch(c, second);
                                    return null;
                                })
                        .join();
                db.transaction(
                                c -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT development FROM"
                                                        + " industry_day_accumulator WHERE game_day"
                                                        + " = ? AND industry_id = ?")) {
                                        q.setLong(1, day);
                                        q.setString(2, "contribution:" + industry);
                                        try (var r = q.executeQuery()) {
                                            check(
                                                    r.next() && r.getLong(1) == 1,
                                                    "exactly one server distance point");
                                        }
                                    }
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT development FROM player_industry_stats"
                                                        + " WHERE player_uuid = ? AND industry_id ="
                                                        + " ?")) {
                                        q.setBytes(1, AccountService.uuidBytes(player));
                                        q.setString(2, "contribution:" + industry);
                                        try (var r = q.executeQuery()) {
                                            check(
                                                    r.next() && r.getLong(1) == 1,
                                                    "matching player point");
                                        }
                                    }
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT development FROM"
                                                            + " player_development_total WHERE"
                                                            + " player_uuid=?")) {
                                        q.setBytes(1, AccountService.uuidBytes(player));
                                        try (var r = q.executeQuery()) {
                                            check(
                                                    r.next()
                                                            && r.getBigDecimal(1)
                                                                            .compareTo(
                                                                                    java.math
                                                                                            .BigDecimal
                                                                                            .ONE)
                                                                    == 0,
                                                    "same-transaction player total and replay"
                                                            + " safety");
                                        }
                                    }
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT remainder_micro FROM"
                                                            + " player_distance_remainder WHERE"
                                                            + " player_uuid = ? AND movement_type ="
                                                            + " 'elytra'")) {
                                        q.setBytes(1, AccountService.uuidBytes(player));
                                        try (var r = q.executeQuery()) {
                                            check(
                                                    r.next() && r.getLong(1) == 500_000,
                                                    "durable distance remainder");
                                        }
                                    }
                                    return null;
                                })
                        .join();
                // A conflicting hash must roll back the batch marker and every player/server delta.
                byte[] wrongHash = snapshot.hash();
                wrongHash[0] ^= 1;
                var rejected =
                        new StatisticsService.Batch(
                                UUID.randomUUID(),
                                1,
                                wrongHash,
                                Map.of(new StatisticsService.IndustryDayKey(day, industry), 1L),
                                Map.of(),
                                Map.of(),
                                Map.of());
                try {
                    db.transaction(
                                    c -> {
                                        stats.writeBatch(c, rejected);
                                        return null;
                                    })
                            .join();
                    throw new AssertionError("hash mismatch accepted");
                } catch (java.util.concurrent.CompletionException expected) {
                }
                db.transaction(
                                c -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT COUNT(*) FROM statistics_batch WHERE"
                                                            + " batch_id = ?")) {
                                        q.setBytes(1, AccountService.uuidBytes(rejected.id()));
                                        try (var r = q.executeQuery()) {
                                            r.next();
                                            check(
                                                    r.getInt(1) == 0,
                                                    "failed batch marker rolled back");
                                        }
                                    }
                                    return null;
                                })
                        .join();
                var settlement = new IndustrySettlement(db, config);
                var sector = BuiltInIndustry.values()[0];
                // New test schema initially has no daily state; successive runs continue from its
                // last day.
                long next =
                        db.transaction(
                                        c -> {
                                            try (var q =
                                                    c.prepareStatement(
                                                            "SELECT COALESCE(MAX(game_day), -1) + 1"
                                                                    + " FROM industry_daily WHERE"
                                                                    + " industry_id = ?")) {
                                                q.setString(1, "contribution:" + sector.path());
                                                try (var r = q.executeQuery()) {
                                                    r.next();
                                                    return r.getLong(1);
                                                }
                                            }
                                        })
                                .join();
                db.transaction(
                                c -> {
                                    settlement.settleIndustry(c, next, sector);
                                    settlement.settleIndustry(c, next + 1, sector);
                                    settlement.settleIndustry(c, next + 1, sector);
                                    return null;
                                })
                        .join();
                db.transaction(
                                c -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "SELECT long_ema, short_ema, prosperity FROM"
                                                            + " industry_state WHERE industry_id ="
                                                            + " ?")) {
                                        q.setString(1, "contribution:" + sector.path());
                                        try (var r = q.executeQuery()) {
                                            check(
                                                    r.next()
                                                            && r.getBigDecimal(1).signum() == 0
                                                            && r.getBigDecimal(2).signum() == 0
                                                            && r.getBigDecimal(3).intValueExact()
                                                                    == 0,
                                                    "zero-day EMA initialized and formula applied");
                                        }
                                    }
                                    return null;
                                })
                        .join();
                check(StatisticMath.add(Long.MAX_VALUE, 1) == Long.MAX_VALUE, "saturation");
                if (embedded) verifyCatchUp(db, config, stats, snapshot, day);
                check(
                        Arrays.equals(snapshot.hash(), RuleSnapshot.parse(snapshot.json()).hash()),
                        "stable rule snapshot hash");
                System.out.println(
                        "STATISTICS_INTEGRATION_PASS: batch idempotency, distance remainder, paired"
                                + " updates, rollback, zero EMA, saturation, snapshot round-trip");
            } finally {
                stats.close();
            }
        }
    }

    private static void verifyCatchUp(
            DatabaseService db,
            ServerConfig config,
            StatisticsService stats,
            RuleSnapshot snapshot,
            long firstDay) {
        db.transaction(
                        c -> {
                            try (var insert =
                                    c.prepareStatement(
                                            "INSERT INTO contribution_rule_epoch (game_day,"
                                                    + " config_hash) VALUES (?, ?)")) {
                                insert.setLong(1, firstDay);
                                insert.setBytes(2, snapshot.hash());
                                insert.executeUpdate();
                            }
                            return null;
                        })
                .join();
        var settlement = new IndustrySettlement(db, config);
        check(
                settlement.settleNext(firstDay + 32).join() == null,
                "missing server close blocks recovery");
        long closed = db.transaction(c -> stats.markClosedDays(c, firstDay + 32)).join();
        check(closed == firstDay + 31, "closure batch is bounded to 32 days");
        closed = db.transaction(c -> stats.markClosedDays(c, firstDay + 32)).join();
        check(closed == firstDay + 32, "next closure batch continues remaining day");
        for (long day = firstDay; day <= firstDay + 32; day++) {
            check(
                    settlement.settleNext(firstDay + 32).join() == day,
                    "33-day backlog advances chronologically");
        }
        check(
                settlement.settleNext(firstDay + 32).join() == firstDay + 32,
                "caught-up replay is idempotent");
        db.transaction(
                        c -> {
                            try (var query =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM industry_daily WHERE game_day"
                                                    + " BETWEEN ? AND ?")) {
                                query.setLong(1, firstDay);
                                query.setLong(2, firstDay + 32);
                                try (var rows = query.executeQuery()) {
                                    rows.next();
                                    check(
                                            rows.getInt(1) == 33 * BuiltInIndustry.values().length,
                                            "one daily result per industry and day");
                                }
                            }
                            return null;
                        })
                .join();
        System.out.println(
                "STATISTICS_CATCHUP_PASS: 33 days, bounded closure, missing-server barrier,"
                        + " chronological replay");
    }

    private static StatisticsService.Batch batch(
            UUID player, long day, long distance, byte[] hash) {
        return new StatisticsService.Batch(
                UUID.randomUUID(),
                1,
                hash,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(new StatisticsService.DistanceDayKey(day, player, "elytra"), distance));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static void verifyDrainBarrier(
            StatisticsService stats,
            StatisticsService.Batch current,
            StatisticsService.Batch historical,
            long day)
            throws Exception {
        var field = StatisticsService.class.getDeclaredField("pending");
        field.setAccessible(true);
        var queue = (List<StatisticsService.Batch>) field.get(stats);
        queue.add(current);
        byte[] hash = StatisticsService.ruleHash();
        check(
                RuleManager.canActivate(false, hash, hash.clone()),
                "date-only epoch advance permits pending data");
        byte[] changedHash = hash.clone();
        changedHash[0] ^= 1;
        check(
                !RuleManager.canActivate(false, hash, changedHash),
                "changed rules require full drain");
        check(
                RuleManager.canActivate(true, hash, changedHash),
                "changed rules activate after full drain");
        check(!stats.isDrained(), "current in-flight batch still prevents rule changes");
        check(
                stats.isDrainedThrough(day - 1),
                "current in-flight batch permits previous day close");
        queue.add(historical);
        check(!stats.isDrainedThrough(day - 1), "historical mixed batch prevents early close");
        queue.remove(historical);
        check(
                stats.isDrainedThrough(day - 1),
                "old-day close resumes after commit callback removes batch");
        queue.clear();
    }
}
