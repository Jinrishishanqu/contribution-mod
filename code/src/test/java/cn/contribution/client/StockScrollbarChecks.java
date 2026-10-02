package cn.contribution.client;

/** Deterministic scrollbar checks without starting a client or a game world. */
public final class StockScrollbarChecks {
    private StockScrollbarChecks() { }

    public static void main(String[] args) {
        StockScrollbar list = new StockScrollbar(78, 238, 8, 16, 4);
        check(list.scrollable(), "long list has a scrollbar");
        check(list.thumbHeight() >= 12 && list.thumbHeight() < 160, "proportional thumb");
        check(list.scrollAt(78) == 0 && list.scrollAt(238) == 16, "track ends reach all rows");
        check(list.thumbTop() > 78 && list.thumbTop() < 238, "middle scroll has middle thumb");
        StockScrollbar detail = new StockScrollbar(29, 245, 130, 260, 180);
        check(detail.scrollAt(detail.thumbTop() + detail.thumbHeight() / 2.0) == 130,
                "dragging the detail thumb preserves scroll position");
        check(!new StockScrollbar(78, 238, 0, 0, 4).scrollable(), "short list needs no scrollbar");
        System.out.println("STOCK_SCROLLBAR_PASS: row list, detail, endpoints and thumb geometry");
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
