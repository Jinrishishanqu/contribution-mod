package cn.contribution.reward;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Extension point for other mods' transactional event rewards. */
public final class EventRewardRegistry {
    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

    private EventRewardRegistry() {}

    public static void register(String key, Handler handler) {
        if (key == null
                || !key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || handler == null
                || HANDLERS.putIfAbsent(key, handler) != null)
            throw new IllegalArgumentException("Duplicate or invalid reward key");
    }

    public static boolean known(String key) {
        return key == null || HANDLERS.containsKey(key);
    }

    public static void apply(
            String key, Connection connection, UUID player, UUID claimId, String data)
            throws SQLException {
        if (key == null) return;
        Handler handler = HANDLERS.get(key);
        if (handler == null) throw new SQLException("Missing event reward provider: " + key);
        handler.apply(connection, player, claimId, data);
    }

    @FunctionalInterface
    public interface Handler {
        /** Must use the supplied transaction; no world or network access. */
        void apply(Connection connection, UUID player, UUID claimId, String data)
                throws SQLException;
    }
}
