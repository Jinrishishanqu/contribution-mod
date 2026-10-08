package cn.contribution.reward;

import cn.contribution.config.RewardConfig;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.industry.BuiltInIndustry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/** Main-server reward fingerprint; product prices come from the shared database catalog. */
public final class RewardConfigGuard {
    private RewardConfigGuard() {}

    public static CompletableFuture<Void> initialize(
            DatabaseService database, ServerConfig config) {
        if (!config.mainServer) return CompletableFuture.completedFuture(null);
        return database.transaction(
                connection -> {
                    cn.contribution.shop.ShopCatalog.seed(connection, config.rewards.shopOffers);
                    cn.contribution.shop.ShopCatalog.seedWeaponSkins(connection);
                    try (PreparedStatement update =
                            connection.prepareStatement(
                                    "INSERT INTO reward_configuration (singleton_id, config_hash,"
                                        + " updated_at) VALUES (1, ?, CURRENT_TIMESTAMP(6)) ON"
                                        + " DUPLICATE KEY UPDATE config_hash = VALUES(config_hash),"
                                        + " updated_at = CURRENT_TIMESTAMP(6)")) {
                        update.setBytes(1, hash(config));
                        update.executeUpdate();
                    }
                    return null;
                });
    }

    public static void require(Connection connection, ServerConfig config) throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT config_hash FROM reward_configuration WHERE singleton_id = 1")) {
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || !MessageDigest.isEqual(row.getBytes(1), hash(config)))
                    throw new SQLException("Reward configuration differs from the main server");
            }
        }
    }

    private static byte[] hash(ServerConfig config) {
        RewardConfig reward = config.rewards;
        StringBuilder canonical =
                new StringBuilder()
                        .append(reward.timeZone)
                        .append('|')
                        .append(reward.dailyRequiredSeconds)
                        .append('|')
                        .append(reward.developmentIntervalSeconds)
                        .append('|')
                        .append(Arrays.toString(reward.dailyCycleRewards));
        for (BuiltInIndustry industry : BuiltInIndustry.values())
            canonical
                    .append('|')
                    .append(industry.path())
                    .append('=')
                    .append(
                            new java.math.BigDecimal(reward.developmentWeights.get(industry.path()))
                                    .stripTrailingZeros());
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
