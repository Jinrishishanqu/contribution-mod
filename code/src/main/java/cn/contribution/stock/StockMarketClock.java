package cn.contribution.stock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Persists only authoritative main-world observations, independently of settlement. */
final class StockMarketClock {
    private StockMarketClock() {}

    static void publish(Connection connection, String serverId, long clock) throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement(
                        "UPDATE stock_market_state SET observed_day = ?, observed_time = ?,"
                            + " observed_at = CURRENT_TIMESTAMP(6), clock_server_id = ? WHERE"
                            + " singleton_id = 1 AND (clock_server_id IS NULL OR clock_server_id ="
                            + " ?)")) {
            update.setLong(1, Math.max(0, Math.floorDiv(clock, 24_000)));
            update.setInt(2, (int) Math.floorMod(clock, 24_000));
            update.setString(3, serverId);
            update.setString(4, serverId);
            if (update.executeUpdate() != 1)
                throw new SQLException("Market clock is owned by another server");
        }
    }
}
