package cn.contribution.runtime;

import cn.contribution.ContributionMod;
import cn.contribution.account.AccountService;
import cn.contribution.api.ContributionApi;
import cn.contribution.config.ConfigLoader;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.DevelopmentRewardService;
import cn.contribution.industry.IndustrySettlement;
import cn.contribution.industry.StatisticsService;
import cn.contribution.reward.CheckinService;
import cn.contribution.reward.DeliveryService;
import cn.contribution.reward.EventCheckinService;
import cn.contribution.reward.RewardConfigGuard;
import cn.contribution.shop.ShopService;
import cn.contribution.stock.StockService;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContributionRuntime {
    private static DatabaseService database;
    private static AccountService accounts;
    private static cn.contribution.account.LeaderboardService leaderboards;
    private static StatisticsService statistics;
    private static IndustrySettlement settlement;
    private static StockService stocks;
    private static DevelopmentRewardService developmentRewards;
    private static CheckinService checkins;
    private static EventCheckinService eventCheckins;
    private static DeliveryService deliveries;
    private static ShopService shop;
    private static cn.contribution.account.TransactionArchive archive;
    private static int lastDatabaseRetryTick;
    private static ServerConfig activeConfig;
    private static RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();
    private static MaintenanceDiagnostics maintenance = new MaintenanceDiagnostics();

    private ContributionRuntime() {}

    public static synchronized void start(MinecraftServer server) {
        if (database != null) {
            return;
        }
        MixinLinkageCheck.verify();
        diagnostics = new RuntimeDiagnostics();
        maintenance = new MaintenanceDiagnostics();
        try {
            ServerConfig config = ConfigLoader.load();
            activeConfig = config;
            lastDatabaseRetryTick = 0;
            Path dataDirectory =
                    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("contribution");
            Files.createDirectories(dataDirectory);
            Path journalDirectory = dataDirectory.resolve("statistics-journal");
            Path previousJournal =
                    FabricLoader.getInstance()
                            .getConfigDir()
                            .resolve("contribution/statistics-journal");
            if (Files.exists(previousJournal)) {
                if (Files.exists(journalDirectory)) {
                    throw new IOException(
                            "Both old and new statistics journals exist; preserve both and resolve"
                                    + " before starting");
                }
                Files.move(previousJournal, journalDirectory);
                ContributionMod.LOGGER.info(
                        "Moved statistics recovery journal into world/contribution");
            }
            database = new DatabaseService(config, dataDirectory.resolve("contribution"));
            accounts = new AccountService(database, config.serverId);
            leaderboards = new cn.contribution.account.LeaderboardService(database);
            archive =
                    config.mainServer
                            ? new cn.contribution.account.TransactionArchive(database)
                            : null;
            stocks = new StockService(database, config);
            developmentRewards =
                    config.mainServer ? new DevelopmentRewardService(database, config) : null;
            checkins = new CheckinService(database, config);
            eventCheckins = new EventCheckinService(database, config);
            deliveries = new DeliveryService(database);
            shop = new ShopService(database, config);
            ContributionApi.install(accounts);
            try {
                cn.contribution.industry.RuleManager.start(server, config);
                statistics = new StatisticsService(database, config, journalDirectory);
                settlement = config.mainServer ? new IndustrySettlement(database, config) : null;
            } catch (RuntimeException error) {
                statistics = null;
                settlement = null;
                ContributionMod.LOGGER.error(
                        "Statistics are paused because industry rules or recovery data are invalid",
                        error);
            }
            database.start()
                    .thenAccept(
                            state -> {
                                if (state == DatabaseState.AVAILABLE)
                                    RewardConfigGuard.initialize(database, config)
                                            .exceptionally(
                                                    error -> {
                                                        ContributionMod.LOGGER.error(
                                                                "Reward configuration could not be"
                                                                        + " published",
                                                                error);
                                                        return null;
                                                    });
                                registerOnlinePlayers(server, state);
                            });
        } catch (IOException | IllegalArgumentException exception) {
            ContributionMod.LOGGER.error(
                    "Contribution runtime configuration could not be loaded", exception);
        }
    }

    public static synchronized void stop(MinecraftServer server) {
        if (database == null) {
            return;
        }
        if (statistics != null) {
            statistics.close();
        }
        if (checkins != null) {
            try {
                checkins.shutdown(server);
            } catch (RuntimeException error) {
                ContributionMod.LOGGER.error("Pending check-in time could not be flushed", error);
            }
        }
        database.close();
        database = null;
        accounts = null;
        leaderboards = null;
        statistics = null;
        settlement = null;
        stocks = null;
        developmentRewards = null;
        checkins = null;
        eventCheckins = null;
        deliveries = null;
        shop = null;
        cn.contribution.shop.ShopUiNetwork.clear();
        archive = null;
        cn.contribution.command.RequestLimiter.clear();
        cn.contribution.ui.ContributionDialogs.clear();
        lastDatabaseRetryTick = 0;
        ContributionApi.install(null);
    }

    public static synchronized DatabaseService database() {
        if (database == null) {
            throw new IllegalStateException("Contribution runtime has not started");
        }
        return database;
    }

    public static AccountService accounts() {
        return accounts;
    }

    public static cn.contribution.account.LeaderboardService leaderboards() {
        return leaderboards;
    }

    public static StatisticsService statistics() {
        return statistics;
    }

    public static StockService stocks() {
        return stocks;
    }

    public static CheckinService checkins() {
        return checkins;
    }

    public static EventCheckinService eventCheckins() {
        return eventCheckins;
    }

    public static DeliveryService deliveries() {
        return deliveries;
    }

    public static ShopService shop() {
        return shop;
    }

    public static boolean isMainServer() {
        return activeConfig != null && activeConfig.mainServer;
    }

    public static java.time.ZoneId displayZone() {
        return java.time.ZoneId.of(
                activeConfig == null ? "Asia/Shanghai" : activeConfig.rewards.timeZone);
    }

    public static int diagnose(net.minecraft.commands.CommandSourceStack source) {
        java.util.function.Consumer<String> output =
                message ->
                        source.sendSuccess(
                                () ->
                                        net.minecraft.network.chat.Component.literal(
                                                "[贡献诊断] " + message),
                                false);
        if (database == null || activeConfig == null) {
            output.accept("运行时未启动，无法自动修复；请检查server.json和服务端启动日志。");
            return 0;
        }
        boolean reconnecting = database.state() != DatabaseState.AVAILABLE;
        MinecraftServer server = source.getServer();
        return maintenance.request(
                server,
                database,
                activeConfig,
                statistics,
                output,
                () -> {
                    if (reconnecting) {
                        RewardConfigGuard.initialize(database, activeConfig)
                                .exceptionally(
                                        error -> {
                                            ContributionMod.LOGGER.warn(
                                                    "Diagnostic reconnect reward configuration"
                                                            + " check failed",
                                                    error);
                                            return null;
                                        });
                        registerOnlinePlayers(server, DatabaseState.AVAILABLE);
                    }
                    if (statistics != null) {
                        cn.contribution.industry.RuleManager.requestRecovery();
                        statistics.requestRecovery(server);
                        cn.contribution.industry.RuleManager.tick(
                                server, database, activeConfig, statistics);
                        if (settlement != null) {
                            settlement.requestRecovery();
                            settlement.tick(server, statistics);
                        }
                    }
                    if (stocks != null) {
                        stocks.requestRecovery();
                        stocks.tick(server);
                    }
                });
    }

    public static void tick(MinecraftServer server) {
        if (database != null && activeConfig != null)
            maintenance.tick(server, database, activeConfig);
        if (archive != null) archive.tick(server);
        if (stocks != null && server.getTickCount() % 20 == 0) stocks.tick(server);
        if (stocks != null) stocks.tickNotices(server);
        if (developmentRewards != null) developmentRewards.tick(server);
        if (checkins != null) checkins.tick(server);
        DatabaseService current = database;
        if (current != null
                && current.state() == DatabaseState.UNAVAILABLE
                && server.getTickCount() - lastDatabaseRetryTick >= 600) {
            lastDatabaseRetryTick = server.getTickCount();
            current.start()
                    .thenAccept(
                            state -> {
                                if (state == DatabaseState.AVAILABLE)
                                    RewardConfigGuard.initialize(current, activeConfig)
                                            .exceptionally(
                                                    error -> {
                                                        ContributionMod.LOGGER.error(
                                                                "Reward configuration could not be"
                                                                        + " published",
                                                                error);
                                                        return null;
                                                    });
                                registerOnlinePlayers(server, state);
                            });
        }
        StatisticsService active = statistics;
        if (active != null) {
            if (current != null && activeConfig != null)
                diagnostics.tick(server, current, activeConfig, active);
            active.tick(server);
            cn.contribution.industry.RuleManager.tick(server, current, activeConfig, active);
            if (settlement != null && server.getTickCount() % 20 == 0) {
                settlement.tick(server, active);
            }
        }
    }

    private static void registerOnlinePlayers(MinecraftServer server, DatabaseState state) {
        if (state == DatabaseState.AVAILABLE) {
            server.execute(
                    () -> {
                        AccountService currentAccounts = accounts;
                        if (currentAccounts != null) {
                            server.getPlayerList()
                                    .getPlayers()
                                    .forEach(
                                            player ->
                                                    currentAccounts.registerPlayer(
                                                            player.getUUID(),
                                                            player.getGameProfile().name()));
                        }
                    });
        }
    }
}
