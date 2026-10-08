package cn.contribution.stock;

import cn.contribution.industry.BuiltInIndustry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/** One industry observation per completed day, committed with all daily stock prices. */
final class StockSignal {
    private StockSignal() {}

    static BigDecimal nextMagnitude(BigDecimal previous, BigDecimal prosperity) {
        return previous.multiply(new BigDecimal("0.95"))
                .add(prosperity.abs().multiply(new BigDecimal("0.05")))
                .setScale(8, RoundingMode.HALF_UP);
    }

    static Map<BuiltInIndustry, BigDecimal> advance(
            Connection connection,
            long day,
            Map<BuiltInIndustry, StockSettlement.IndustryValue> industries)
            throws SQLException {
        Map<BuiltInIndustry, BigDecimal> scales = new EnumMap<>(BuiltInIndustry.class);
        for (var industry : BuiltInIndustry.values()) {
            String id = "contribution:" + industry.path();
            try (var insert =
                    connection.prepareStatement(
                            "INSERT IGNORE INTO stock_industry_signal (industry_id, magnitude_ema,"
                                    + " last_signal_day) VALUES (?, 0.5, NULL)")) {
                insert.setString(1, id);
                insert.executeUpdate();
            }
            BigDecimal previous;
            Long last;
            try (var query =
                    connection.prepareStatement(
                            "SELECT magnitude_ema, last_signal_day FROM stock_industry_signal"
                                    + " WHERE industry_id = ? FOR UPDATE")) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing industry stock signal " + id);
                    previous = rows.getBigDecimal(1);
                    long value = rows.getLong(2);
                    last = rows.wasNull() ? null : value;
                }
            }
            if (last != null && last >= day)
                throw new SQLException("Stock signal day already consumed: " + id + " / " + day);
            scales.put(industry, previous.max(new BigDecimal("0.5")).min(new BigDecimal("1.5")));
            var observation = industries.get(industry);
            if (!observation.initialized()) continue;
            try (var update =
                    connection.prepareStatement(
                            "UPDATE stock_industry_signal SET magnitude_ema = ?, last_signal_day ="
                                    + " ? WHERE industry_id = ?")) {
                update.setBigDecimal(1, nextMagnitude(previous, observation.prosperity()));
                update.setLong(2, day);
                update.setString(3, id);
                update.executeUpdate();
            }
        }
        return scales;
    }
}
