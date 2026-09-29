package cn.contribution.industry;

/** Exercises the 16-block threshold across multiple durable statistics batches. */
public final class DistanceAccountingChecks {
    private DistanceAccountingChecks() {
    }

    public static void main(String[] args) {
        long threshold = 16_000_000L;
        DistanceAccumulator.Result first = DistanceAccumulator.add(0, 15_500_000, threshold);
        check(first.completeUnits() == 0 && first.remainder() == 15_500_000, "partial distance");
        DistanceAccumulator.Result second = DistanceAccumulator.add(first.remainder(), 1_000_000, threshold);
        check(second.completeUnits() == 1 && second.remainder() == 500_000, "cross-batch completion");
        DistanceAccumulator.Result third = DistanceAccumulator.add(second.remainder(), 32_000_000, threshold);
        check(third.completeUnits() == 2 && third.remainder() == 500_000, "multiple units");
        reject(-1, 1, threshold);
        check(DistanceAccumulator.add(threshold, 1, threshold).completeUnits() == 1, "smaller threshold after reload preserves old remainder");
        check(DistanceAccumulator.add(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE).completeUnits() == 2, "safe large values");
        reject(0, -1, threshold);
    }

    private static void reject(long remainder, long delta, long threshold) {
        try {
            DistanceAccumulator.add(remainder, delta, threshold);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid distance should be rejected");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
