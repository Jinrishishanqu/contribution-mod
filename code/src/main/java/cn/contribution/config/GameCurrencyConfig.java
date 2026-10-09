package cn.contribution.config;

/** Tunables for the isolated game-currency (GC) wallet used by the speculation gameplay. */
public final class GameCurrencyConfig {
    /**
     * Milli-GC granted per one destroyed contribution point. 950 means 1 CP : 0.95 GC. Kept
     * adjustable so operators can retune the sink without a schema change.
     */
    public long exchangeRateMilli = 950;

    public static final long MINIMUM_EXCHANGE_RATE_MILLI = 1;
    public static final long MAXIMUM_EXCHANGE_RATE_MILLI = 1_000_000_000;
}
