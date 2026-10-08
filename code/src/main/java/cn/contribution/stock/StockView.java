package cn.contribution.stock;

import java.util.List;
import java.util.Map;

public final class StockView {
    private StockView() {}

    public record Listing(
            long id,
            String itemId,
            String name,
            String industry,
            int price,
            int initialPrice,
            String status,
            int owned,
            long listedDay) {}

    public record PricePoint(long day, int price) {}

    public record Market(
            long day,
            int time,
            List<Listing> listings,
            long clockDay,
            int clockTime,
            boolean clockFresh) {}

    public record Detail(
            Listing listing,
            List<PricePoint> prices,
            int requestedDays,
            PriceRange range,
            PositionInfo position,
            Map<Integer, PriceTrend> trends,
            int lastDirection,
            int previousPrice) {}

    public record PriceRange(int high, int low) {}

    public record PriceTrend(int days, int change, double percent) {}

    public record PositionInfo(
            int quantity,
            long costBasis,
            long firstBuyDay,
            long lastBuyDay,
            int lastBuyPrice,
            long realizedProfit) {}

    public record Portfolio(
            int balance,
            long marketValue,
            long costBasis,
            long unrealizedProfit,
            long realizedProfit,
            Map<Long, PositionInfo> positions) {}

    public record Dashboard(
            Market market,
            Map<Long, List<PricePoint>> curves,
            Map<Long, PriceRange> ranges,
            Portfolio portfolio,
            Map<Long, Integer> lastDirections,
            List<News> news) {}

    public record News(String text, long untilClock, boolean good) {}

    public record TradeResult(
            boolean success,
            String message,
            long stockId,
            int quantity,
            int price,
            long fee,
            int balance,
            boolean replay) {}
}
