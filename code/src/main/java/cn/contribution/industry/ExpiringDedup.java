package cn.contribution.industry;

import java.util.LinkedHashMap;

/** Server-thread confined, bounded sliding-window deduplication with expiry-ordered cleanup. */
final class ExpiringDedup<K> {
    private final LinkedHashMap<K, Integer> seen = new LinkedHashMap<>();
    private final int capacity;
    private final int window;

    ExpiringDedup(int capacity, int window) {
        this.capacity = capacity;
        this.window = window;
    }

    boolean accept(K key, int tick) {
        Integer previous = seen.remove(key);
        if (previous == null && seen.size() >= capacity) return false;
        seen.put(key, tick);
        return previous == null || tick - previous >= window;
    }

    void expire(int tick) {
        var entries = seen.entrySet().iterator();
        while (entries.hasNext()) {
            if (tick - entries.next().getValue() < window) break;
            entries.remove();
        }
    }
}
