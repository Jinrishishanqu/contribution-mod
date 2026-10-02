package cn.contribution.stock;

import java.util.ArrayList;
import java.util.List;

/** Bounded history sampling and the vanilla-client Braille price plot. */
public final class StockChart {
    private static final int WIDTH = 80;
    private static final int HEIGHT = 32;
    private static final int[] DOTS = {1, 2, 4, 64, 8, 16, 32, 128};

    private StockChart() { }

    /** Retain the most recent non-flat move when today's price is unchanged. */
    public static int lastMovement(List<StockView.PricePoint> prices) {
        if (prices == null) return 0;
        for (int index = prices.size() - 1; index > 0; index--) {
            int direction = Integer.compare(prices.get(index).price(), prices.get(index - 1).price());
            if (direction != 0) return direction;
        }
        return 0;
    }

    /** One-pass min/max buckets keep the full time span and spikes without sending every historic day. */
    public static final class ExtremaSampler {
        private final int total;
        private final int bucketCount;
        private final List<StockView.PricePoint> output = new ArrayList<>();
        private int index;
        private int bucket = -1;
        private StockView.PricePoint low;
        private StockView.PricePoint high;

        public ExtremaSampler(int total, int maxPoints) {
            if (total < 0 || maxPoints < 4) throw new IllegalArgumentException("Invalid chart sample size");
            this.total = total;
            this.bucketCount = Math.max(1, (maxPoints - 2) / 2);
        }

        public void accept(StockView.PricePoint point) {
            if (index >= total) throw new IllegalStateException("More price records than counted");
            if (total <= bucketCount * 2 + 2 || index == 0 || index == total - 1) {
                if (index == total - 1 && index > 0) flush();
                output.add(point);
            } else {
                int target = (int) ((long) (index - 1) * bucketCount / (total - 2));
                if (target != bucket) { flush(); bucket = target; }
                if (low == null || point.price() < low.price()) low = point;
                if (high == null || point.price() > high.price()) high = point;
            }
            index++;
        }

        private void flush() {
            if (low == null) return;
            if (low.day() <= high.day()) {
                output.add(low);
                if (high.day() != low.day()) output.add(high);
            } else {
                output.add(high);
                output.add(low);
            }
            low = high = null;
        }

        public List<StockView.PricePoint> finish() {
            if (index != total) throw new IllegalStateException("Incomplete price history");
            flush();
            return List.copyOf(output);
        }
    }

    public static List<String> draw(List<StockView.PricePoint> points) {
        if (points.isEmpty()) return List.of("暂无股价记录");
        int min = points.stream().mapToInt(StockView.PricePoint::price).min().orElse(0);
        int max = points.stream().mapToInt(StockView.PricePoint::price).max().orElse(0);
        boolean[][] pixels = new boolean[HEIGHT][WIDTH];
        int previousX = -1, previousY = -1;
        for (int i = 0; i < points.size(); i++) {
            int x = points.size() == 1 ? WIDTH / 2 : i * (WIDTH - 1) / (points.size() - 1);
            int y = min == max ? HEIGHT / 2 : (int) Math.round((max - points.get(i).price()) * (HEIGHT - 1.0) / (max - min));
            if (previousX < 0) pixels[y][x] = true;
            else {
                for (int cursor = previousX; cursor <= x; cursor++) {
                    double fraction = x == previousX ? 1 : (cursor - previousX) / (double) (x - previousX);
                    int lineY = (int) Math.round(previousY + (y - previousY) * fraction);
                    pixels[Math.max(0, Math.min(HEIGHT - 1, lineY))][cursor] = true;
                }
            }
            previousX = x; previousY = y;
        }
        List<String> lines = new ArrayList<>();
        lines.add("最高 " + max + "  ── 股价走势 ──  最低 " + min);
        for (int row = 0; row < HEIGHT / 4; row++) {
            StringBuilder text = new StringBuilder("│");
            for (int col = 0; col < WIDTH / 2; col++) {
                int mask = 0;
                for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 2; dx++) {
                    if (pixels[row * 4 + dy][col * 2 + dx]) mask |= DOTS[dx * 4 + dy];
                }
                text.append((char) (0x2800 + mask));
            }
            lines.add(text.toString());
        }
        lines.add("└" + "─".repeat(WIDTH / 2));
        lines.add("游戏日 " + points.getFirst().day() + " → " + points.getLast().day());
        return List.copyOf(lines);
    }
}
