package cn.contribution.industry;

/** Exercises the 10-block threshold across multiple durable statistics batches. */
public final class DistanceAccountingChecks {
    private DistanceAccountingChecks() {}

    public static void main(String[] args) {
        checkCounterBuffer();
        checkDedupExpiry();
        long threshold = 10_000_000L;
        DistanceAccumulator.Result first = DistanceAccumulator.add(0, 9_500_000, threshold);
        check(first.completeUnits() == 0 && first.remainder() == 9_500_000, "partial distance");
        DistanceAccumulator.Result second =
                DistanceAccumulator.add(first.remainder(), 1_000_000, threshold);
        check(
                second.completeUnits() == 1 && second.remainder() == 500_000,
                "cross-batch completion");
        DistanceAccumulator.Result third =
                DistanceAccumulator.add(second.remainder(), 20_000_000, threshold);
        check(third.completeUnits() == 2 && third.remainder() == 500_000, "multiple units");
        reject(-1, 1, threshold);
        check(
                DistanceAccumulator.add(threshold, 1, threshold).completeUnits() == 1,
                "smaller threshold after reload preserves old remainder");
        check(
                DistanceAccumulator.add(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
                                .completeUnits()
                        == 2,
                "safe large values");
        reject(0, -1, threshold);
    }

    private static void checkDedupExpiry() {
        var dedup = new ExpiringDedup<String>(2, 20);
        check(dedup.accept("a", 0), "first event");
        check(dedup.accept("b", 1), "second event");
        check(!dedup.accept("a", 19), "sliding duplicate refresh");
        dedup.expire(21);
        check(dedup.accept("c", 21), "expired prefix removed despite refreshed older key");
        check(!dedup.accept("d", 21), "capacity remains fail closed");
        check(!dedup.accept("a", 38), "refreshed window preserved");
        dedup.expire(58);
        check(dedup.accept("d", 58), "exact expiry boundary");
        var wrap = new ExpiringDedup<String>(1, 20);
        check(wrap.accept("a", Integer.MAX_VALUE - 9), "before tick wrap");
        check(!wrap.accept("a", Integer.MIN_VALUE), "wrapped duplicate");
        wrap.expire(Integer.MIN_VALUE + 20);
        check(wrap.accept("b", Integer.MIN_VALUE + 20), "wrapped expiry");
    }

    private static void reject(long remainder, long delta, long threshold) {
        try {
            DistanceAccumulator.add(remainder, delta, threshold);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid distance should be rejected");
    }

    private static void checkCounterBuffer() {
        CounterBuffer<String> buffer = new CounterBuffer<>();
        for (int i = 0; i < 100_000; i++) buffer.add("same-event", 1);
        check(buffer.size() == 1, "repeated events retain one counter");
        var batch = buffer.snapshot();
        check(batch.get("same-event") == 100_000, "all completed events accumulated");
        buffer.add("same-event", 64);
        check(batch.get("same-event") == 100_000, "batch detached from live updates");
        buffer.add("other-event", Long.MAX_VALUE);
        buffer.add("other-event", 1);
        check(buffer.snapshot().get("other-event") == Long.MAX_VALUE, "saturation retained");
        buffer.clear();
        check(buffer.isEmpty() && batch.size() == 1, "clear cannot mutate in-flight batch");
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
