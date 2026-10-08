package cn.contribution.client;

/** Orders server observations, not game dates: /time may legitimately move backwards. */
final class StockClockOrder {
    private long revision = -1;

    boolean accept(long candidate) {
        if (candidate < revision) return false;
        revision = candidate;
        return true;
    }

    void reset() {
        revision = -1;
    }
}
