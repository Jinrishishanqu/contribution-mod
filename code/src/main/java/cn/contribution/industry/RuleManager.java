package cn.contribution.industry;

import cn.contribution.ContributionMod;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;

import net.minecraft.server.MinecraftServer;

import java.util.*;

/**
 * Main-server publication of immutable rule epochs; all participants use the same database
 * snapshot.
 */
public final class RuleManager {
    private static volatile RuleSnapshot active;
    private static final Map<String, RuleSnapshot> KNOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static RuleSnapshot candidate;
    private static long candidateDay;
    private static long activeDay = -1;
    private static boolean running;
    private static int nextAttempt;
    private static boolean historyReady;
    private static boolean mainServer;
    private static long observedDay;

    private RuleManager() {}

    public static long day(MinecraftServer server) {
        return mainServer
                ? Math.max(
                        0,
                        Math.floorDiv(server.overworld().getOverworldClockTime() - 2_000, 24_000))
                : observedDay;
    }

    public static void start(MinecraftServer server, ServerConfig config) {
        mainServer = config.mainServer;
        observedDay = 0;
        KNOWN.clear();
        active = RuleSnapshot.capture(server);
        remember(active);
        activeDay = -1;
        historyReady = false;
        candidate = active;
        candidateDay = day(server) + 1;
        running = false;
        nextAttempt = 0;
    }

    private static void remember(RuleSnapshot snapshot) {
        KNOWN.put(HexFormat.of().formatHex(snapshot.hash()), snapshot);
    }

    public static RuleSnapshot current() {
        return active;
    }

    public static boolean settlementWindow(MinecraftServer server) {
        long time = Math.floorMod(server.overworld().getOverworldClockTime(), 24_000L);
        return time >= 2_000 && time < 12_000;
    }

    public static boolean historyReady() {
        return historyReady;
    }

    public static void requestRecovery() {
        nextAttempt = 0;
    }

    public static String diagnosticState() {
        return "activeDay="
                + activeDay
                + " observedDay="
                + observedDay
                + " historyReady="
                + historyReady
                + " synchronizing="
                + running;
    }

    public static RuleSnapshot snapshot(byte[] hash) {
        return KNOWN.get(HexFormat.of().formatHex(hash));
    }

    public static boolean collecting(MinecraftServer server) {
        return active != null && activeDay == day(server);
    }

    public static void stage(MinecraftServer server) {
        if (!mainServer) {
            ContributionMod.LOGGER.info(
                    "Secondary server follows the main-server industry snapshot");
            return;
        }
        try {
            RuleSnapshot value = RuleSnapshot.capture(server);
            if (!Arrays.equals(value.hash(), active.hash())) {
                candidate = value;
                candidateDay = day(server) + 1;
                remember(value);
            } else candidate = null;
            ContributionMod.LOGGER.info(
                    "Industry rule reload validated; effective accounting day={}", candidateDay);
        } catch (RuntimeException error) {
            candidate = null;
            ContributionMod.LOGGER.error(
                    "Industry reload rejected; active snapshot retained: {}", error.getMessage());
        }
    }

    public static void tick(
            MinecraftServer server,
            DatabaseService database,
            ServerConfig config,
            StatisticsService statistics) {
        if (database == null || database.state() != DatabaseState.AVAILABLE) return;
        long today = day(server);
        RuleSnapshot proposed = candidate != null && candidateDay <= today ? candidate : active;
        if ((mainServer && activeDay == today)
                || running
                || server.getTickCount() < nextAttempt
                || (mainServer
                        && historyReady
                        && !canActivate(statistics.isDrained(), active.hash(), proposed.hash())))
            return;
        running = true;
        nextAttempt = server.getTickCount() + 100;
        boolean loadHistory = !historyReady;
        database.transaction(
                        connection -> {
                            // Loading historical snapshots also makes distance journals replayable
                            // after rule changes.
                            if (loadHistory)
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT snapshot_json FROM"
                                                                + " contribution_rule_snapshot");
                                        var rows = query.executeQuery()) {
                                    while (rows.next())
                                        remember(RuleSnapshot.parse(rows.getString(1)));
                                }
                            if (config.mainServer) {
                                try (var query =
                                                connection.prepareStatement(
                                                        "SELECT MAX(game_day) FROM"
                                                                + " contribution_rule_epoch");
                                        var rows = query.executeQuery()) {
                                    rows.next();
                                    if (rows.getLong(1) > today)
                                        throw new java.sql.SQLException(
                                                "Main world clock is behind the recorded accounting"
                                                        + " day");
                                }
                                try (var insert =
                                        connection.prepareStatement(
                                                "INSERT IGNORE INTO contribution_rule_snapshot"
                                                        + " (config_hash, snapshot_json) VALUES (?,"
                                                        + " ?)")) {
                                    insert.setBytes(1, proposed.hash());
                                    insert.setString(2, proposed.json());
                                    insert.executeUpdate();
                                }
                                try (var insert =
                                        connection.prepareStatement(
                                                "INSERT IGNORE INTO contribution_rule_epoch"
                                                    + " (game_day, config_hash) VALUES (?, ?)")) {
                                    insert.setLong(1, today);
                                    insert.setBytes(2, proposed.hash());
                                    insert.executeUpdate();
                                }
                            }
                            try (var query =
                                    connection.prepareStatement(
                                            "SELECT e.game_day, s.snapshot_json FROM"
                                                    + " contribution_rule_epoch e JOIN"
                                                    + " contribution_rule_snapshot s USING"
                                                    + " (config_hash) ORDER BY e.game_day DESC"
                                                    + " LIMIT 1")) {
                                try (var rows = query.executeQuery()) {
                                    return rows.next()
                                            ? new Epoch(
                                                    rows.getLong(1),
                                                    RuleSnapshot.parse(rows.getString(2)))
                                            : null;
                                }
                            }
                        })
                .whenComplete(
                        (epoch, error) ->
                                server.execute(
                                        () -> {
                                            running = false;
                                            if (error != null && server.getTickCount() % 1200 < 100)
                                                ContributionMod.LOGGER.warn(
                                                        "Industry epoch synchronization is pending;"
                                                            + " collection remains on its confirmed"
                                                            + " epoch",
                                                        error);
                                            if (error == null) historyReady = true;
                                            if (error == null && epoch != null) {
                                                observedDay = epoch.day;
                                                remember(epoch.snapshot);
                                                boolean sameRules =
                                                        Arrays.equals(
                                                                active.hash(),
                                                                epoch.snapshot.hash());
                                                if (activeDay != epoch.day
                                                        && canActivate(
                                                                statistics.isDrained(),
                                                                active.hash(),
                                                                epoch.snapshot.hash())) {
                                                    active = epoch.snapshot;
                                                    activeDay = epoch.day;
                                                    if (!sameRules) statistics.activate(active);
                                                    if (candidate != null
                                                            && Arrays.equals(
                                                                    candidate.hash(),
                                                                    active.hash()))
                                                        candidate = null;
                                                }
                                            }
                                        }));
    }

    /** A date-only advance preserves dated batches; actual rule changes require a full drain. */
    static boolean canActivate(boolean drained, byte[] activeHash, byte[] nextHash) {
        return drained || Arrays.equals(activeHash, nextHash);
    }

    public static byte[] dayHash(java.sql.Connection connection, long day, byte[] fallback)
            throws java.sql.SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT config_hash FROM contribution_rule_epoch WHERE game_day <= ? ORDER"
                                + " BY game_day DESC LIMIT 1")) {
            query.setLong(1, day);
            try (var row = query.executeQuery()) {
                return row.next() ? row.getBytes(1) : fallback;
            }
        }
    }

    public static long firstDay(java.sql.Connection connection) throws java.sql.SQLException {
        try (var query =
                        connection.prepareStatement(
                                "SELECT COALESCE(MIN(game_day), 0) FROM contribution_rule_epoch");
                var rows = query.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private record Epoch(long day, RuleSnapshot snapshot) {}
}
