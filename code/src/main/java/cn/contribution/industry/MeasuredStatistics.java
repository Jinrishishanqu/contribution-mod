package cn.contribution.industry;

import cn.contribution.account.AccountService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/** Raw completed results and durable unit conversion, in the statistics batch transaction. */
final class MeasuredStatistics {
    static final UUID WORLD = new UUID(0, 0);

    record Key(long day, UUID actor, String event) {}

    private MeasuredStatistics() {}

    static void apply(
            Connection connection,
            String server,
            Map<Key, Long> raw,
            GameEventRules rules,
            Map<StatisticsService.IndustryDayKey, Long> industry,
            Map<StatisticsService.PlayerIndustryKey, Long> player)
            throws SQLException {
        var entries =
                raw.entrySet().stream()
                        .sorted(
                                Comparator.comparingLong(
                                                (Map.Entry<Key, Long> e) -> e.getKey().day())
                                        .thenComparing(e -> e.getKey().actor().toString())
                                        .thenComparing(e -> e.getKey().event()))
                        .toList();
        try (var activityCreate =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO event_activity_day"
                                    + " (server_id,game_day,actor_uuid,event_id,raw_amount) VALUES"
                                    + " (?,?,?,?,0)");
                var activityUpdate =
                        connection.prepareStatement(
                                "UPDATE event_activity_day SET"
                                        + " raw_amount=LEAST(9223372036854775807,CAST(raw_amount AS"
                                        + " DECIMAL(30,0))+?) WHERE server_id=? AND game_day=? AND"
                                        + " actor_uuid=? AND event_id=?");
                var create =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO measurement_remainder"
                                        + " (scope_id,actor_uuid,event_id,remainder_amount) VALUES"
                                        + " (?,?,?,0)");
                var query =
                        connection.prepareStatement(
                                "SELECT remainder_amount FROM measurement_remainder WHERE"
                                        + " scope_id=? AND actor_uuid=? AND event_id=? FOR UPDATE");
                var update =
                        connection.prepareStatement(
                                "UPDATE measurement_remainder SET remainder_amount=? WHERE"
                                        + " scope_id=? AND actor_uuid=? AND event_id=?")) {
            for (var entry : entries) {
                Key key = entry.getKey();
                var rule = rules.rule(key.event());
                if (rule == null || entry.getValue() <= 0)
                    throw new SQLException("Invalid measured event: " + key.event());
                byte[] actor = AccountService.uuidBytes(key.actor());
                activityCreate.setString(1, server);
                activityCreate.setLong(2, key.day());
                activityCreate.setBytes(3, actor);
                activityCreate.setString(4, key.event());
                activityCreate.executeUpdate();
                activityUpdate.setLong(1, entry.getValue());
                activityUpdate.setString(2, server);
                activityUpdate.setLong(3, key.day());
                activityUpdate.setBytes(4, actor);
                activityUpdate.setString(5, key.event());
                activityUpdate.executeUpdate();
                // Observations preserve raw amounts but must not create construction or rewards.
                if (rule.unitValue() == 0) continue;
                String scope = key.actor().equals(WORLD) ? server : "player";
                create.setString(1, scope);
                create.setBytes(2, actor);
                create.setString(3, key.event());
                create.executeUpdate();
                query.setString(1, scope);
                query.setBytes(2, actor);
                query.setString(3, key.event());
                long remainder;
                try (var result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Missing measurement remainder");
                    remainder = result.getLong(1);
                }
                var converted =
                        DistanceAccumulator.add(remainder, entry.getValue(), rule.unitSize());
                update.setLong(1, converted.remainder());
                update.setString(2, scope);
                update.setBytes(3, actor);
                update.setString(4, key.event());
                update.executeUpdate();
                long points = StatisticMath.multiply(converted.completeUnits(), rule.unitValue());
                if (points == 0) continue;
                industry.merge(
                        new StatisticsService.IndustryDayKey(key.day(), rule.industry().path()),
                        points,
                        StatisticMath::add);
                if (!key.actor().equals(WORLD))
                    player.merge(
                            new StatisticsService.PlayerIndustryKey(
                                    key.actor(), rule.industry().path()),
                            points,
                            StatisticMath::add);
            }
        }
    }
}
