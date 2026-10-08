package cn.contribution.client;

/** Deterministic scrollbar checks without starting a client or a game world. */
public final class StockScrollbarChecks {
    private StockScrollbarChecks() {}

    public static void main(String[] args) {
        var cache = new StockListCache<String>();
        var industries = new java.util.HashSet<String>();
        industries.add("mining");
        var rows = cache.replace("iron", 1, false, industries, java.util.List.of("a", "b"));
        check(cache.matches("iron", 1, false, industries), "unchanged filters reuse cached rows");
        check(rows == cache.rows(), "cache hit reuses the same immutable list");
        check(!cache.matches("gold", 1, false, industries), "search invalidates");
        check(!cache.matches("iron", 2, false, industries), "sorting invalidates");
        check(!cache.matches("iron", 1, true, industries), "ownership invalidates");
        industries.add("energy");
        check(!cache.matches("iron", 1, false, industries), "industry mutation invalidates");
        check(
                !new StockListCache<String>().matches("iron", 1, false, industries),
                "new dashboard has a new cache");
        StockScrollbar list = new StockScrollbar(78, 238, 8, 16, 4);
        check(list.scrollable(), "long list has a scrollbar");
        check(list.thumbHeight() >= 12 && list.thumbHeight() < 160, "proportional thumb");
        check(list.thumbTop() > 78 && list.thumbTop() < 238, "middle scroll has middle thumb");
        StockScrollbar detail = new StockScrollbar(29, 245, 130, 260, 180);
        check(
                detail.thumbTop() > detail.top()
                        && detail.thumbTop() + detail.thumbHeight() < detail.bottom(),
                "detail thumb stays within visual track");
        check(!new StockScrollbar(78, 238, 0, 0, 4).scrollable(), "short list needs no scrollbar");
        StockClockOrder clock = new StockClockOrder();
        check(clock.accept(10), "first server clock accepted");
        check(!clock.accept(9), "delayed dashboard cannot replace newer heartbeat");
        check(clock.accept(11), "new server sample accepted even if world time goes backwards");
        clock.reset();
        check(clock.accept(1), "new connection starts a new clock sequence");
        System.out.println("STOCK_SCROLLBAR_PASS: read-only row and detail indicators");
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
