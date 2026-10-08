package cn.contribution.industry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Server-thread accumulator; only detached batch snapshots box primitive counts. */
final class CounterBuffer<K> {
    private static final class Counter {
        long value;
    }

    private final Map<K, Counter> counters = new HashMap<>();

    void add(K key, long amount) {
        Counter counter = counters.get(key);
        if (counter == null) {
            counter = new Counter();
            counters.put(key, counter);
        }
        counter.value = StatisticMath.add(counter.value, amount);
    }

    boolean containsKey(K key) {
        return counters.containsKey(key);
    }

    int size() {
        return counters.size();
    }

    boolean isEmpty() {
        return counters.isEmpty();
    }

    Set<K> keySet() {
        return counters.keySet();
    }

    Map<K, Long> snapshot() {
        Map<K, Long> copy = new HashMap<>(counters.size());
        counters.forEach((key, counter) -> copy.put(key, counter.value));
        return copy;
    }

    void clear() {
        counters.clear();
    }
}
