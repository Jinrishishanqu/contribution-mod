package cn.contribution.stock;

import java.util.List;

/** Checks the actual Braille plot for sparse timestamps and same-column steep changes. */
public final class StockChartChecks {
    private static final int[] DOTS = {1, 2, 4, 64, 8, 16, 32, 128};

    public static void main(String[] args) {
        var payload = StockUiNetwork.clockPayload(454, 7600, List.of(), 42);
        var snapshot =
                new com.google.gson.Gson().fromJson(payload.json(), StockUiNetwork.Snapshot.class);
        require(
                snapshot.day() == 454 && snapshot.time() == 7600 && snapshot.clockRevision() == 42,
                "shared clock payload preserves time and sequence");
        var unavailable =
                new com.google.gson.Gson()
                        .fromJson(
                                StockUiNetwork.clockPayload(-1, -1, List.of(), 43).json(),
                                StockUiNetwork.Snapshot.class);
        require(
                unavailable.day() == -1
                        && unavailable.time() == -1
                        && unavailable.clockRevision() == 43,
                "unavailable replica clock remains fail closed");
        var plot =
                StockChart.draw(
                        List.of(
                                new StockView.PricePoint(0, 100),
                                new StockView.PricePoint(1, 200),
                                new StockView.PricePoint(100, 100)));
        require(pixel(plot, 1, 0), "peak at game day 1, not middle record index");
        require(!pixel(plot, 40, 0), "sparse time spacing preserved");
        var vertical =
                StockChart.draw(
                        List.of(
                                new StockView.PricePoint(0, 100),
                                new StockView.PricePoint(0, 200),
                                new StockView.PricePoint(100, 100)));
        for (int y = 0; y < 32; y++) require(pixel(vertical, 0, y), "vertical line has no gaps");
        var flat = StockChart.draw(List.of(new StockView.PricePoint(3, 100)));
        require(pixel(flat, 40, 16), "single point centered");
        require(StockChart.draw(List.of()).size() == 1, "empty history");
        System.out.println(
                "STOCK_CHART_PASS: actual timestamps, vertical continuity and empty/flat plots");
    }

    private static boolean pixel(List<String> plot, int x, int y) {
        int mask = plot.get(1 + y / 4).charAt(1 + x / 2) - 0x2800;
        return (mask & DOTS[(x % 2) * 4 + y % 4]) != 0;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
