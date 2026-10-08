package cn.contribution.account;

import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.ui.ContributionActionLayout;
import cn.contribution.ui.ContributionNavigation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Public ranking ordering, bounds, cached reads, sum precision and navigation. */
public final class LeaderboardChecks {
    public static void main(String[] args) throws Exception {
        for (int width : new int[] {320, 640, 1920}) {
            for (int i = 0; i < 7; i++) {
                var bounds = ContributionActionLayout.bounds("ranking", 7, i, width, 480);
                check(bounds.y() == (i < 4 ? 401 : 425), "ranking fixed rows");
                check(
                        bounds.x() >= 12 && bounds.x() + bounds.width() <= width - 12,
                        "ranking inside horizontal margins");
                if (i != 0 && i != 4) {
                    var previous = ContributionActionLayout.bounds("ranking", 7, i - 1, width, 480);
                    check(bounds.x() - previous.x() - previous.width() == 5, "ranking row gaps");
                }
            }
            var refresh = ContributionActionLayout.bounds("ranking", 7, 5, width, 480);
            check(Math.abs(refresh.x() + refresh.width() / 2 - width / 2) <= 1, "refresh centered");
            check(
                    ContributionActionLayout.bounds("other", 7, 5, width, 480).width()
                            == ContributionActionLayout.bounds("other", 7, 0, width, 480).width(),
                    "other screens retain four-column grid");
        }
        ServerConfig config = new ServerConfig();
        config.database.mode = "embedded";
        try (var db =
                new DatabaseService(
                        config,
                        Files.createTempDirectory(Path.of("build"), "ranking-")
                                .resolve("contribution"))) {
            check(db.start().join() == DatabaseState.AVAILABLE, "database");
            db.transaction(
                            connection -> {
                                try (var query =
                                        connection.prepareStatement(
                                                "INSERT INTO contribution_account"
                                                    + " (player_uuid,player_name,player_name_normalized,balance,total_income,created_at,updated_at)"
                                                    + " VALUES"
                                                    + " (?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
                                    for (int i = 1; i <= 121; i++) {
                                        String name = i == 121 ? "bot_test" : "player" + i;
                                        query.setBytes(1, AccountService.uuidBytes(new UUID(0, i)));
                                        query.setString(2, name);
                                        query.setString(3, name);
                                        query.setInt(4, i == 121 ? 9999 : i % 3);
                                        query.setInt(5, i == 121 ? 9999 : 120 - i);
                                        query.addBatch();
                                    }
                                    query.executeBatch();
                                }
                                try (var query =
                                        connection.prepareStatement(
                                                "INSERT INTO player_industry_stats"
                                                    + " (player_uuid,industry_id,development,updated_at)"
                                                    + " VALUES (?,?,?,CURRENT_TIMESTAMP(6))")) {
                                    for (int i = 1; i <= 121; i++) {
                                        query.setBytes(1, AccountService.uuidBytes(new UUID(0, i)));
                                        query.setString(2, "contribution:construction_landscaping");
                                        query.setLong(
                                                3, i == 1 ? Long.MAX_VALUE : i == 120 ? 0 : i);
                                        query.addBatch();
                                    }
                                    query.setBytes(1, AccountService.uuidBytes(new UUID(0, 1)));
                                    query.setString(2, "contribution:mining_metallurgy");
                                    query.setLong(3, Long.MAX_VALUE);
                                    query.addBatch();
                                    query.setBytes(1, AccountService.uuidBytes(new UUID(0, 120)));
                                    query.setString(2, "unknown:industry");
                                    query.setLong(3, Long.MAX_VALUE);
                                    query.addBatch();
                                    query.executeBatch();
                                }
                                var players =
                                        java.util.stream.LongStream.rangeClosed(1, 121)
                                                .mapToObj(i -> new UUID(0, i))
                                                .toList();
                                cn.contribution.industry.PlayerDevelopmentTotals.lock(
                                        connection, players);
                                cn.contribution.industry.PlayerDevelopmentTotals.refresh(
                                        connection, players);
                                return null;
                            })
                    .join();
            var service = new LeaderboardService(db);
            var first = service.ranking("balance");
            check(first == service.ranking("balance"), "share in-flight or cached future");
            var rich = first.join();
            check(rich.size() == 100, "bounded top100");
            check(
                    rich.getFirst().playerUuid().equals(new UUID(0, 2)),
                    "descending balance with UUID tie break");
            check(rich.get(1).playerUuid().equals(new UUID(0, 5)), "stable ties");
            check(
                    rich.stream().noneMatch(row -> row.playerName().startsWith("bot_")),
                    "bots excluded");
            check(
                    service.ranking("income").join().getFirst().playerName().equals("player1"),
                    "historical income");
            var total = service.ranking("development").join();
            check(
                    total.getFirst().score().equals("18446744073709551614"),
                    "sum must not overflow signed long");
            check(
                    total.stream().noneMatch(row -> row.playerName().equals("player120")),
                    "unknown industry and zero excluded");
            var single = service.ranking("construction_landscaping").join();
            check(
                    single.getFirst().score().equals(String.valueOf(Long.MAX_VALUE)),
                    "industry whitelist");
            check(
                    LeaderboardService.metrics().size() == 12
                            && LeaderboardService.metric("balance").label().equals("富豪榜"),
                    "12 named boards");
            boolean rejected = false;
            try {
                service.ranking("balance; DELETE");
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "unknown metric rejected before SQL");
            check(
                    ContributionNavigation.parent("ranking balance 12").equals("home"),
                    "rich list escape root");
            check(
                    ContributionNavigation.parent("ranking income 3").equals("leaderboards"),
                    "page never escape parent");
            check(
                    ContributionNavigation.parent("ranking mining_metallurgy 3")
                            .equals("leaderboard_industries"),
                    "industry parent");
        }
        System.out.println(
                "LEADERBOARD_PASS: ordering, ties, cache, top100, bot exclusion, sum precision,"
                        + " hierarchy");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
