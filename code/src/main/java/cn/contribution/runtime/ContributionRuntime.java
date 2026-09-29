package cn.contribution.runtime;

import cn.contribution.ContributionMod;
import cn.contribution.account.AccountService;
import cn.contribution.api.ContributionApi;
import cn.contribution.config.ConfigLoader;
import cn.contribution.config.ServerConfig;
import cn.contribution.database.DatabaseService;
import cn.contribution.database.DatabaseState;
import cn.contribution.industry.IndustrySettlement;
import cn.contribution.industry.StatisticsService;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public final class ContributionRuntime {
    private static DatabaseService database;
    private static AccountService accounts;
    private static StatisticsService statistics;
    private static IndustrySettlement settlement;
    private static cn.contribution.account.TransactionArchive archive;
    private static int lastDatabaseRetryTick;
    private static ServerConfig activeConfig;

    private ContributionRuntime() {
    }

    public static synchronized void start(MinecraftServer server) {
        if (database != null) {
            return;
        }
        MixinLinkageCheck.verify();
        try {
            ServerConfig config = ConfigLoader.load();
            activeConfig = config;
            lastDatabaseRetryTick = 0;
            database = new DatabaseService(config, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("contribution/contribution"));
            accounts = new AccountService(database, config.serverId);
            archive = config.mainServer ? new cn.contribution.account.TransactionArchive(database) : null;
            ContributionApi.install(accounts);
            try {
                cn.contribution.industry.RuleManager.start(server, config);
                statistics = new StatisticsService(database, config);
                settlement = config.mainServer ? new IndustrySettlement(database, config) : null;
            } catch (RuntimeException error) {
                statistics = null;
                settlement = null;
                ContributionMod.LOGGER.error("Statistics are paused because industry rules or recovery data are invalid", error);
            }
            database.start().thenAccept(state -> registerOnlinePlayers(server, state));
        } catch (IOException | IllegalArgumentException exception) {
            ContributionMod.LOGGER.error("Contribution runtime configuration could not be loaded", exception);
        }
    }

    public static synchronized void stop() {
        if (database == null) {
            return;
        }
        if (statistics != null) {
            statistics.close();
        }
        database.close();
        database = null;
        accounts = null;
        statistics = null;
        settlement = null;
        archive = null;
        cn.contribution.command.RequestLimiter.clear();
        cn.contribution.ui.AdminDialogOperations.clear();
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

    public static StatisticsService statistics() {
        return statistics;
    }

    public static void tick(MinecraftServer server) {
        if (archive != null) archive.tick(server);
        DatabaseService current = database;
        if (current != null && current.state() == DatabaseState.UNAVAILABLE
                && server.getTickCount() - lastDatabaseRetryTick >= 600) {
            lastDatabaseRetryTick = server.getTickCount();
            current.start().thenAccept(state -> registerOnlinePlayers(server, state));
        }
        StatisticsService active = statistics;
        if (active != null) {
            active.tick(server);
            cn.contribution.industry.RuleManager.tick(server, current, activeConfig, active);
            if (settlement != null && server.getTickCount() % 20 == 0) {
                settlement.tick(server, active);
            }
        }
    }

    private static void registerOnlinePlayers(MinecraftServer server, DatabaseState state) {
        if (state == DatabaseState.AVAILABLE) {
            server.execute(() -> {
                AccountService currentAccounts = accounts;
                if (currentAccounts != null) {
                    server.getPlayerList().getPlayers().forEach(player ->
                            currentAccounts.registerPlayer(player.getUUID(), player.getGameProfile().name()));
                }
            });
        }
    }
}
