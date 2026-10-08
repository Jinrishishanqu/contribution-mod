package cn.contribution.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OnlinePlayerSnapshotChecks {
    public static void main(String[] args) {
        OnlinePlayerSnapshot cache = new OnlinePlayerSnapshot();
        check(cache.needsRefresh(0), "initial reconciliation");
        var player = new StockSwanService.Player(UUID.randomUUID(), "RosterTester");
        var bot = new StockSwanService.Player(UUID.randomUUID(), "bot_test");
        var input = new ArrayList<>(List.of(player, bot));
        cache.replace(input, 0);
        input.clear();
        var captured = cache.players();
        check(captured.equals(List.of(player)), "bot excluded from swan candidates");
        check(cache.ids().size() == 2, "notice roster preserves all online players");
        check(!cache.needsRefresh(1199) && cache.needsRefresh(1200), "minute fallback");
        cache.invalidate();
        check(cache.needsRefresh(1), "connection change bypasses timer");
        cache.replace(List.of(), 1);
        check(cache.players().isEmpty() && cache.ids().isEmpty(), "disconnect refresh");
        check(captured.equals(List.of(player)), "in-flight snapshot is immutable");
        System.out.println("ONLINE_ROSTER_PASS: invalidation, fallback, bots, detached snapshots");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
