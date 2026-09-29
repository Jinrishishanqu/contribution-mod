package cn.contribution.command;

import net.minecraft.commands.CommandSourceStack;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared budget for chat and native-dialog requests; called on the server thread. */
public final class RequestLimiter {
    private static final Map<UUID, Bucket> PLAYERS = new HashMap<>();
    private static long globalTick;
    private static int globalCount;
    private RequestLimiter() { }
    public static boolean allow(CommandSourceStack source) {
        long tick = source.getServer().getTickCount();
        if (tick != globalTick) { globalTick = tick; globalCount = 0; }
        if (++globalCount > 40) return false;
        var player = source.getPlayer();
        if (player == null) return true;
        if (PLAYERS.size() > 10_000) PLAYERS.entrySet().removeIf(entry -> tick - entry.getValue().tick > 1200);
        Bucket bucket = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new Bucket(tick));
        if (tick < bucket.tick) { bucket.tokens = 8; bucket.tick = tick; }
        bucket.tokens = Math.min(8, bucket.tokens + (tick - bucket.tick) / 10.0);
        bucket.tick = tick;
        if (bucket.tokens < 1) return false;
        bucket.tokens--; return true;
    }
    public static void clear() { PLAYERS.clear(); }
    private static final class Bucket {
        double tokens = 8; long tick;
        Bucket(long tick) { this.tick = tick; }
    }
}
