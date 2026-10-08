package cn.contribution.industry;

public final class StatisticMath {
    private static final java.util.concurrent.atomic.AtomicBoolean REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private StatisticMath() {}

    public static long add(long left, long right) {
        if (left < 0 || right < 0)
            throw new IllegalArgumentException("Statistics must be nonnegative");
        if (Long.MAX_VALUE - left < right) {
            if (REPORTED.compareAndSet(false, true))
                cn.contribution.ContributionMod.LOGGER.error(
                        "Statistics reached signed BIGINT maximum; retaining maximum");
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public static long multiply(long value, long factor) {
        if (value < 0 || factor < 0)
            throw new IllegalArgumentException("Statistics must be nonnegative");
        if (factor != 0 && value > Long.MAX_VALUE / factor) return add(Long.MAX_VALUE, 1);
        return value * factor;
    }
}
