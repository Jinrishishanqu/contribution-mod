package cn.contribution.stock;

import java.util.List;

public final class StockView {
    private StockView() { }

    public record Listing(long id, String itemId, String name, String industry, int price,
                          int initialPrice, String status, int owned) { }
    public record PricePoint(long day, int price) { }
    public record Market(long day, int time, List<Listing> listings) { }
    public record Detail(Listing listing, List<PricePoint> prices) { }
    public record TradeResult(boolean success, String message, long stockId, int quantity,
                              int price, long fee, int balance, boolean replay) { }
}
