package cn.contribution.industry;

/** Converts measured microblocks into complete rule units without losing the remainder. */
public final class DistanceAccumulator {
    private DistanceAccumulator() {
    }

    public static Result add(long remainder, long delta, long threshold) {
        if (threshold <= 0 || remainder < 0 || delta < 0) {
            throw new IllegalArgumentException("Invalid distance accumulator input");
        }
        // Divide first to avoid overflowing when a long-lived counter approaches Long.MAX_VALUE.
        long left = remainder % threshold, right = delta % threshold;
        boolean carry = left >= threshold - right;
        long residual = carry ? left - (threshold - right) : left + right;
        return new Result(StatisticMath.add(StatisticMath.add(remainder / threshold, delta / threshold), carry ? 1 : 0), residual);
    }

    public record Result(long completeUnits, long remainder) { }
}
