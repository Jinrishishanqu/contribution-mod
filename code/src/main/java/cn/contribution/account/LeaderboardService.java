package cn.contribution.account;

import cn.contribution.database.DatabaseService;
import cn.contribution.industry.BuiltInIndustry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bounded public rankings; one shared asynchronous read per metric per cache interval. */
public final class LeaderboardService {
    public static final int LIMIT = 100;
    public static final int PAGE_SIZE = 8;
    private static final List<Metric> METRICS = createMetrics();
    private final DatabaseService database;
    private final Map<String, Entry> cache = new HashMap<>();

    private static final class Entry {
        CompletableFuture<List<Row>> future;
        long expires = Long.MAX_VALUE;
    }

    public LeaderboardService(DatabaseService database) {
        this.database = database;
    }

    public static List<Metric> metrics() {
        return METRICS;
    }

    public static Metric metric(String key) {
        return METRICS.stream()
                .filter(value -> value.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知排行榜"));
    }

    private static List<Metric> createMetrics() {
        var result = new ArrayList<Metric>();
        result.add(new Metric("balance", "富豪榜", null));
        result.add(new Metric("income", "历史贡献排行", null));
        result.add(new Metric("development", "总建设度排行", null));
        for (var industry : BuiltInIndustry.values())
            result.add(new Metric(industry.path(), industry.displayName() + "建设度排行", industry));
        return List.copyOf(result);
    }

    public synchronized CompletableFuture<List<Row>> ranking(String key) {
        Metric metric = metric(key);
        Entry previous = cache.get(key);
        if (previous != null && System.nanoTime() < previous.expires) return previous.future;
        Entry entry = new Entry();
        cache.put(key, entry);
        entry.future =
                database.transaction(connection -> read(connection, metric))
                        .orTimeout(10, TimeUnit.SECONDS);
        entry.future.whenComplete(
                (rows, failure) -> {
                    synchronized (this) {
                        if (cache.get(key) != entry) return;
                        if (failure != null) cache.remove(key);
                        else entry.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    }
                });
        return entry.future;
    }

    private static List<Row> read(Connection connection, Metric metric) throws SQLException {
        String score;
        String from = " FROM contribution_account a";
        String where = " WHERE LEFT(a.player_name_normalized,4)<>'bot_'";
        if (metric.key().equals("balance") || metric.key().equals("income")) {
            score = metric.key().equals("balance") ? "a.balance" : "a.total_income";
        } else if (metric.key().equals("development")) {
            from += " JOIN player_development_total s ON s.player_uuid=a.player_uuid";
            where += " AND s.development>0";
            score = "s.development";
        } else {
            from += " JOIN player_industry_stats s ON s.player_uuid=a.player_uuid";
            where += " AND s.development>0";
            score = "s.development";
            where += " AND s.industry_id=?";
        }
        String sql =
                "SELECT a.player_uuid,a.player_name,"
                        + score
                        + " AS score"
                        + from
                        + where
                        + " ORDER BY score DESC,a.player_uuid ASC LIMIT "
                        + LIMIT;
        var result = new ArrayList<Row>();
        try (var query = connection.prepareStatement(sql)) {
            if (metric.industry() != null)
                query.setString(1, "contribution:" + metric.industry().path());
            try (var rows = query.executeQuery()) {
                while (rows.next())
                    result.add(
                            new Row(
                                    AccountService.bytesUuid(rows.getBytes(1)),
                                    rows.getString(2),
                                    rows.getBigDecimal(3).toBigIntegerExact().toString()));
            }
        }
        return List.copyOf(result);
    }

    public record Metric(String key, String label, BuiltInIndustry industry) {}

    public record Row(UUID playerUuid, String playerName, String score) {}
}
