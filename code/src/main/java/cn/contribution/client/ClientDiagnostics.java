package cn.contribution.client;

import cn.contribution.ContributionMod;
import cn.contribution.stock.StockUiNetwork;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Logs metadata only; bounded fixed channels and real-time throttling avoid payload spam. */
final class ClientDiagnostics {
    private static final Map<String, Long> NEXT = new HashMap<>();

    private ClientDiagnostics() {}

    private static synchronized boolean due(String channel) {
        long now = System.nanoTime();
        if (now < NEXT.getOrDefault(channel, 0L)) return false;
        NEXT.put(channel, now + TimeUnit.MINUTES.toNanos(1));
        return true;
    }

    static void received(String channel, int bytes) {
        if (due(channel))
            ContributionMod.LOGGER.info(
                    "CONTRIBUTION_CLIENT_RECEIVE channel={} jsonChars={}", channel, bytes);
    }

    static void invalid(String channel, RuntimeException error) {
        if (due("invalid-" + channel))
            ContributionMod.LOGGER.warn(
                    "CONTRIBUTION_CLIENT_DECODE_FAILED channel={}", channel, error);
    }

    static void stock(StockUiNetwork.Snapshot snapshot) {
        if (!due("stock-state-" + (snapshot.dashboard() == null ? "clock" : "market"))) return;
        var market = snapshot.dashboard() == null ? null : snapshot.dashboard().market();
        ContributionMod.LOGGER.info(
                "CONTRIBUTION_CLIENT_STOCK view={} day={} time={} revision={} settledDay={}"
                        + " fresh={}",
                snapshot.view(),
                snapshot.day(),
                snapshot.time(),
                snapshot.clockRevision(),
                market == null ? "unknown" : market.day(),
                market == null ? "unknown" : market.clockFresh());
    }

    static synchronized void disconnected() {
        NEXT.clear();
        ContributionMod.LOGGER.info("CONTRIBUTION_CLIENT_DISCONNECT cleared UI clock state");
    }
}
