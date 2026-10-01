package cn.contribution.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.math.BigDecimal;
import java.time.ZoneId;
import cn.contribution.industry.BuiltInIndustry;

public final class ConfigLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PASSWORD_ENVIRONMENT_VARIABLE = "CONTRIBUTION_DB_PASSWORD";

    private ConfigLoader() {
    }

    public static ServerConfig load() throws IOException {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("contribution/server.json");
        ServerConfig config;
        if (Files.notExists(path)) {
            Files.createDirectories(path.getParent());
            config = new ServerConfig();
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } else {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                config = parse(root);
            }
        }
        validate(config);

        String environmentPassword = System.getenv(PASSWORD_ENVIRONMENT_VARIABLE);
        if (environmentPassword != null && !environmentPassword.isBlank()) {
            config.database.password = environmentPassword;
        }
        return config;
    }

    static ServerConfig parse(JsonObject root) {
        ServerConfig config = GSON.fromJson(root, ServerConfig.class);
        // Existing 0.0.1 configurations used enabled=true for MySQL.
        // enabled=false was the unconfigured default and now becomes embedded mode.
        if (config != null && config.database != null && root.has("database") && root.get("database").isJsonObject()) {
            JsonObject database = root.getAsJsonObject("database");
            if (!database.has("mode") && database.has("enabled") && database.get("enabled").getAsBoolean()) {
                config.database.mode = "mysql";
            }
        }
        return config;
    }

    private static void validate(ServerConfig config) {
        if (config == null || config.database == null) {
            throw new IllegalArgumentException("The contribution server configuration is empty or incomplete");
        }
        if (config.serverId == null || !config.serverId.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("serverId must contain 1 to 64 characters");
        }
        if (config.statisticsServers == null || config.statisticsServers.length == 0) {
            throw new IllegalArgumentException("statisticsServers must contain at least one server ID");
        }
        for (String server : config.statisticsServers) {
            if (server == null || !server.matches("[A-Za-z0-9_.-]{1,64}")) {
                throw new IllegalArgumentException("statisticsServers contains an invalid server ID");
            }
        }
        if (Arrays.stream(config.statisticsServers).distinct().count() != config.statisticsServers.length) {
            throw new IllegalArgumentException("statisticsServers contains duplicate server IDs");
        }
        if (config.mainServer && Arrays.stream(config.statisticsServers).noneMatch(config.serverId::equals)) {
            throw new IllegalArgumentException("The main server must be included in statisticsServers");
        }
        if (config.databaseThreads < 1 || config.databaseThreads > 16) {
            throw new IllegalArgumentException("databaseThreads must be between 1 and 16");
        }
        if (config.databaseQueueCapacity < 64 || config.databaseQueueCapacity > 65_536) {
            throw new IllegalArgumentException("databaseQueueCapacity must be between 64 and 65536");
        }
        if (config.database.maximumPoolSize < 1 || config.database.maximumPoolSize > 32) {
            throw new IllegalArgumentException("database.maximumPoolSize must be between 1 and 32");
        }
        if (config.database.minimumIdle < 0 || config.database.minimumIdle > config.database.maximumPoolSize) {
            throw new IllegalArgumentException("database.minimumIdle must be between 0 and maximumPoolSize");
        }
        if (!"embedded".equals(config.database.mode) && !"mysql".equals(config.database.mode)) {
            throw new IllegalArgumentException("database.mode must be embedded or mysql");
        }
        if ("embedded".equals(config.database.mode)
                && (!config.mainServer || config.statisticsServers.length != 1)) {
            throw new IllegalArgumentException("embedded database is single-server only; use database.mode=mysql for a server group");
        }
        if ("mysql".equals(config.database.mode) && (config.database.jdbcUrl == null || !config.database.jdbcUrl.startsWith("jdbc:mysql:"))) {
            throw new IllegalArgumentException("database.jdbcUrl must be a MySQL JDBC URL");
        }
        RewardConfig rewards = config.rewards;
        if (rewards == null || rewards.developmentIntervalSeconds < 30 || rewards.developmentIntervalSeconds > 86400
                || rewards.dailyRequiredSeconds < 60 || rewards.dailyRequiredSeconds > 86400
                || rewards.dailyCycleRewards == null || rewards.dailyCycleRewards.length == 0
                || rewards.dailyCycleRewards.length > 366 || rewards.developmentWeights == null
                || rewards.shopOffers == null || rewards.shopOffers.length > 128) {
            throw new IllegalArgumentException("rewards configuration is invalid");
        }
        try { ZoneId.of(rewards.timeZone); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("rewards.timeZone is invalid", invalid); }
        for (int value : rewards.dailyCycleRewards) if (value < 0 || value > 1_000_000)
            throw new IllegalArgumentException("rewards.dailyCycleRewards contains an invalid amount");
        for (BuiltInIndustry industry : BuiltInIndustry.values()) {
            String raw = rewards.developmentWeights.get(industry.path());
            if (raw == null || new BigDecimal(raw).signum() < 0 || new BigDecimal(raw).compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("Invalid development weight: " + industry.path());
        }
        if (rewards.developmentWeights.size() != BuiltInIndustry.values().length)
            throw new IllegalArgumentException("rewards.developmentWeights must contain exactly the nine built-in industries");
        java.util.Set<String> offerIds = new java.util.HashSet<>();
        for (RewardConfig.ShopOffer offer : rewards.shopOffers) {
            if (offer == null || offer.id == null || !offer.id.matches("[a-z0-9_-]{1,64}")
                    || !offerIds.add(offer.id)
                    || offer.name == null || offer.name.isBlank() || offer.name.codePointCount(0, offer.name.length()) > 64
                    || offer.itemId == null || !offer.itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || offer.itemCount < 1 || offer.itemCount > 64 || offer.price < 1) {
                throw new IllegalArgumentException("Invalid shop offer");
            }
        }
    }
}
