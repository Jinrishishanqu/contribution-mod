package cn.contribution.stock;

/** Shared integer price rules for server settlement and both stock interfaces. */
public final class StockPricing {
    private StockPricing() {}

    static double dailyReturn(double prosperity, double magnitudeEma, double gaussian) {
        double scale = Math.max(.5, Math.min(1.5, magnitudeEma));
        return .05 * Math.tanh(prosperity / scale) + .04 * gaussian;
    }

    static int ordinaryPrice(
            int currentPrice,
            int initialPrice,
            double prosperity,
            double magnitudeEma,
            double gaussian) {
        long proposed =
                Math.round(currentPrice * (1 + dailyReturn(prosperity, magnitudeEma, gaussian)));
        return (int) Math.max(1, Math.min(priceCap(initialPrice), proposed));
    }

    /** The comparison price is floored because stock prices are whole contribution points. */
    public static int retirementThreshold(int initialPrice, int historicalHigh) {
        if (initialPrice <= 0 || historicalHigh <= 0)
            throw new IllegalArgumentException("Stock prices must be positive");
        return Math.max(initialPrice / 2, historicalHigh / 4);
    }

    public static int priceCap(int initialPrice) {
        if (initialPrice <= 0 || initialPrice > Integer.MAX_VALUE / 10)
            throw new IllegalArgumentException("Invalid initial stock price");
        return initialPrice * 10;
    }

    /** One-time swan correction replaces, rather than adds to, the ordinary daily price change. */
    public static int swanPrice(int currentPrice, int initialPrice, boolean good) {
        if (currentPrice <= 0) throw new IllegalArgumentException("Stock prices must be positive");
        return swanPrice(currentPrice, initialPrice, good, 2);
    }

    /** In-flight events retain the rule version captured when they began. */
    static int swanPrice(int currentPrice, int initialPrice, boolean good, int ruleVersion) {
        if (currentPrice <= 0) throw new IllegalArgumentException("Stock prices must be positive");
        int percent = good ? 140 : ruleVersion < 2 ? 60 : 75;
        long adjusted = ((long) currentPrice * percent + 50) / 100;
        return (int) Math.max(1, Math.min(priceCap(initialPrice), adjusted));
    }
}
