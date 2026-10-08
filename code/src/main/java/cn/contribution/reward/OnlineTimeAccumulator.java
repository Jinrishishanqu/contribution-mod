package cn.contribution.reward;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Carries sub-second time and attributes whole real seconds to the correct calendar day. */
final class OnlineTimeAccumulator {
    private final ZoneId zone;
    private final Map<UUID, Long> cursors = new HashMap<>();

    OnlineTimeAccumulator(ZoneId zone) {
        this.zone = zone;
    }

    void joined(UUID player, long nowMs) {
        cursors.putIfAbsent(player, nowMs);
    }

    Map<Key, Integer> sample(Collection<UUID> online, long nowMs) {
        Set<UUID> active = new HashSet<>(online);
        cursors.keySet().removeIf(id -> !active.contains(id));
        Map<Key, Integer> gained = new HashMap<>();
        for (UUID player : active) {
            Long previous = cursors.putIfAbsent(player, nowMs);
            if (previous != null) accumulate(gained, player, previous, nowMs);
        }
        return gained;
    }

    Map<Key, Integer> left(UUID player, long nowMs) {
        Map<Key, Integer> gained = new HashMap<>();
        Long previous = cursors.remove(player);
        if (previous != null) accumulate(gained, player, previous, nowMs);
        return gained;
    }

    private void accumulate(Map<Key, Integer> gained, UUID player, long previous, long nowMs) {
        if (nowMs < previous) {
            cursors.put(player, nowMs);
            return;
        }
        long seconds = (nowMs - previous) / 1000;
        if (seconds == 0) return;
        long cursor = previous;
        while (seconds > 0) {
            LocalDate day = Instant.ofEpochMilli(cursor + 1000).atZone(zone).toLocalDate();
            long nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long inDay = Math.max(1L, (nextMidnight - cursor - 1L) / 1000L);
            int chunk = (int) Math.min(Math.min(seconds, inDay), 86_400L);
            gained.merge(new Key(player, day), chunk, Integer::sum);
            cursor += chunk * 1000L;
            seconds -= chunk;
        }
        if (cursors.containsKey(player)) cursors.put(player, cursor);
    }

    record Key(UUID player, LocalDate day) {}
}
