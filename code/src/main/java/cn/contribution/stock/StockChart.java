package cn.contribution.stock;

import java.util.ArrayList;
import java.util.List;

/** Unicode Braille line plot: a real 80×32-pixel curve in a vanilla client dialog. */
public final class StockChart {
    private static final int WIDTH = 80;
    private static final int HEIGHT = 32;
    private static final int[] DOTS = {1, 2, 4, 64, 8, 16, 32, 128};

    private StockChart() { }

    public static List<StockView.PricePoint> aggregate(List<StockView.PricePoint> prices, int days) {
        if (days == 7) return prices;
        int group = days == 30 ? 3 : 30;
        List<StockView.PricePoint> output = new ArrayList<>();
        for (int start = Math.max(0, prices.size() - days); start < prices.size(); start += group) {
            List<Integer> values = new ArrayList<>();
            for (int index = start; index < Math.min(prices.size(), start + group); index++) values.add(prices.get(index).price());
            if (values.isEmpty()) continue;
            if (days == 360) {
                values.sort(Integer::compareTo);
                int trim = values.size() / 4;
                values = values.subList(trim, values.size() - trim);
            }
            long sum = 0;
            for (int value : values) sum += value;
            output.add(new StockView.PricePoint(prices.get(Math.min(prices.size() - 1, start + group - 1)).day(),
                    (int) Math.round(sum / (double) values.size())));
        }
        return List.copyOf(output);
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
