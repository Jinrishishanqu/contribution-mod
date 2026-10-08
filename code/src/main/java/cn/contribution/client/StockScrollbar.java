package cn.contribution.client;

/** Read-only scrollbar geometry for row lists and the pixel-scrolled detail page. */
final class StockScrollbar {
    private static final int MIN_THUMB = 12;

    private final int top;
    private final int bottom;
    private final int scroll;
    private final int maximum;
    private final int viewport;

    StockScrollbar(int top, int bottom, int scroll, int maximum, int viewport) {
        this.top = top;
        this.bottom = bottom;
        this.maximum = Math.max(0, maximum);
        this.scroll = Math.clamp(scroll, 0, this.maximum);
        this.viewport = Math.max(1, viewport);
    }

    boolean scrollable() {
        return maximum > 0 && bottom > top;
    }

    int top() {
        return top;
    }

    int bottom() {
        return bottom;
    }

    int thumbHeight() {
        int track = Math.max(1, bottom - top);
        return scrollable()
                ? Math.clamp(
                        (int) Math.round(track * (double) viewport / (viewport + maximum)),
                        Math.min(MIN_THUMB, track),
                        track)
                : track;
    }

    int thumbTop() {
        int travel = bottom - top - thumbHeight();
        return top + (maximum == 0 ? 0 : (int) Math.round(travel * (double) scroll / maximum));
    }
}
