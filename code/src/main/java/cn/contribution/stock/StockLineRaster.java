package cn.contribution.stock;

/** Integer line rasterization shared by the stock chart and its regression check. */
public final class StockLineRaster {
    private StockLineRaster() { }

    @FunctionalInterface public interface PixelSink {
        void draw(int x, int y);
    }

    public static void trace(int x0, int y0, int x1, int y1, PixelSink sink) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            sink.draw(x0, y0);
            if (x0 == x1 && y0 == y1) return;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }
}
