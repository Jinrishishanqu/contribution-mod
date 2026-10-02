package cn.contribution.stock;

/** Shared integer price rules for server settlement and both stock interfaces. */
public final class StockPricing {
    private StockPricing() { }

    /** The comparison price is floored because stock prices are whole contribution points. */
    public static int retirementThreshold(int initialPrice, int historicalHigh) {
        if (initialPrice <= 0 || historicalHigh <= 0) throw new IllegalArgumentException("Stock prices must be positive");
        return Math.max(initialPrice / 2, historicalHigh / 4);
    }

    public static int priceCap(int initialPrice) {
        if (initialPrice <= 0 || initialPrice > Integer.MAX_VALUE / 10)
            throw new IllegalArgumentException("Invalid initial stock price");
        return initialPrice * 10;
    }
}
