package cn.contribution.industry;

import cn.contribution.account.AccountService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;

/** Same-transaction materialized totals; lock before applying player industry deltas. */
public final class PlayerDevelopmentTotals {
    private PlayerDevelopmentTotals() {}

    public static void lock(Connection connection, Collection<UUID> players) throws SQLException {
        try (var create =
                        connection.prepareStatement(
                                "INSERT IGNORE INTO player_development_total"
                                        + " (player_uuid,development) VALUES (?,0)");
                var lock =
                        connection.prepareStatement(
                                "SELECT development FROM player_development_total WHERE"
                                        + " player_uuid=? FOR UPDATE")) {
            for (UUID player : players) {
                byte[] uuid = AccountService.uuidBytes(player);
                create.setBytes(1, uuid);
                create.executeUpdate();
                lock.setBytes(1, uuid);
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing development total lock");
                }
            }
        }
    }

    /** Call with the same sorted unique players whose total rows were locked above. */
    public static void refresh(Connection connection, Collection<UUID> players)
            throws SQLException {
        String placeholders =
                String.join(
                        ",", java.util.Collections.nCopies(BuiltInIndustry.values().length, "?"));
        try (var update =
                connection.prepareStatement(
                        "UPDATE player_development_total SET development=(SELECT"
                                + " COALESCE(SUM(CAST(development AS DECIMAL(30,0))),0) FROM"
                                + " player_industry_stats WHERE player_uuid=? AND industry_id IN ("
                                + placeholders
                                + ")) WHERE player_uuid=?")) {
            for (UUID player : players) {
                byte[] uuid = AccountService.uuidBytes(player);
                update.setBytes(1, uuid);
                int parameter = 2;
                for (var industry : BuiltInIndustry.values())
                    update.setString(parameter++, "contribution:" + industry.path());
                update.setBytes(parameter, uuid);
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
