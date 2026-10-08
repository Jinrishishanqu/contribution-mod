package cn.contribution.stock;

import java.util.List;
import java.util.UUID;

/** Server-thread roster, invalidated by connections and reconciled every 60 game seconds. */
final class OnlinePlayerSnapshot {
    private List<StockSwanService.Player> players = List.of();
    private List<UUID> ids = List.of();
    private boolean dirty = true;
    private int nextReconcileTick;

    boolean needsRefresh(int tick) {
        return dirty || tick >= nextReconcileTick;
    }

    void replace(List<StockSwanService.Player> current, int tick) {
        players =
                current.stream()
                        .filter(
                                player ->
                                        !cn.contribution.account.AccountIdentityService.isBotName(
                                                player.name()))
                        .toList();
        ids = current.stream().map(StockSwanService.Player::id).toList();
        dirty = false;
        nextReconcileTick = tick + 1200;
    }

    void invalidate() {
        dirty = true;
    }

    List<StockSwanService.Player> players() {
        return players;
    }

    List<UUID> ids() {
        return ids;
    }
}
