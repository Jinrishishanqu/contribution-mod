package cn.contribution;

import cn.contribution.command.ContributionCommands;
import cn.contribution.industry.IndustryMatcher;
import cn.contribution.industry.IndustryRegistry;
import cn.contribution.runtime.ContributionRuntime;
import cn.contribution.shop.ShopSnapshotPayload;
import cn.contribution.stock.StockSnapshotPayload;
import cn.contribution.ui.ContributionSnapshotPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ContributionMod implements ModInitializer {
    public static final String MOD_ID = "contribution";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        cn.contribution.items.WeaponSkins.initialize();
        PayloadTypeRegistry.clientboundPlay()
                .register(
                        cn.contribution.items.WeaponSkinPayload.TYPE,
                        cn.contribution.items.WeaponSkinPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(StockSnapshotPayload.TYPE, StockSnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(ShopSnapshotPayload.TYPE, ShopSnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(ContributionSnapshotPayload.TYPE, ContributionSnapshotPayload.CODEC);
        cn.contribution.network.VersionCompatibility.register();
        IndustryRegistry.bootstrap();
        ServerLifecycleEvents.SERVER_STARTED.register(
                cn.contribution.items.ItemResourcePack::export);
        ServerLifecycleEvents.SERVER_STARTED.register(ContributionRuntime::start);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resources, success) -> {
                    if (success && ContributionRuntime.statistics() != null)
                        cn.contribution.industry.RuleManager.stage(server);
                });
        ServerLifecycleEvents.SERVER_STOPPING.register(ContributionRuntime::stop);
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> cn.contribution.items.WeaponSkins.clear());
        ServerTickEvents.END_SERVER_TICK.register(ContributionRuntime::tick);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, selection) ->
                        ContributionCommands.register(dispatcher, registry));
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    if (ContributionRuntime.stocks() != null)
                        ContributionRuntime.stocks().invalidateOnlinePlayers();
                    if (cn.contribution.account.AccountIdentityService.isBotName(
                            handler.player.getGameProfile().name())) return;
                    if (ContributionRuntime.checkins() != null)
                        ContributionRuntime.checkins().joined(handler.player.getUUID());
                    if (ContributionRuntime.accounts() != null) {
                        ServerPlayer player = handler.player;
                        ContributionRuntime.accounts()
                                .registerPlayer(player.getUUID(), player.getGameProfile().name())
                                .thenCompose(
                                        ignored -> {
                                            if (ContributionRuntime.currencies() == null)
                                                return java.util.concurrent.CompletableFuture
                                                        .completedFuture(null);
                                            return ContributionRuntime.currencies()
                                                    .registerPlayer(
                                                            player.getUUID(),
                                                            player.getGameProfile().name());
                                        })
                                .thenAccept(
                                        ignored -> {
                                            if (ContributionRuntime.deliveries() != null)
                                                server.execute(
                                                        () ->
                                                                ContributionRuntime.deliveries()
                                                                        .claim(player));
                                            if (ContributionRuntime.stocks() != null)
                                                ContributionRuntime.stocks()
                                                        .claim(
                                                                player.getUUID(),
                                                                java.util.UUID.randomUUID());
                                        })
                                .exceptionally(
                                        error -> {
                                            LOGGER.warn(
                                                    "Player account registration is pending: {}",
                                                    error.toString());
                                            return null;
                                        });
                    }
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    cn.contribution.items.WeaponSkins.forget(handler.player.getUUID());
                    if (ContributionRuntime.stocks() != null)
                        ContributionRuntime.stocks().invalidateOnlinePlayers();
                    cn.contribution.command.RequestLimiter.forget(handler.player.getUUID());
                    if (ContributionRuntime.checkins() != null)
                        ContributionRuntime.checkins().left(handler.player.getUUID());
                    cn.contribution.ui.ContributionDialogs.forget(handler.player.getUUID());
                    cn.contribution.stock.StockUiNetwork.forget(handler.player.getUUID());
                    cn.contribution.shop.ShopUiNetwork.forget(handler.player.getUUID());
                });
        PlayerBlockBreakEvents.AFTER.register(
                (level, player, pos, state, entity) -> {
                    if (player instanceof ServerPlayer serverPlayer
                            && ContributionRuntime.statistics() != null) {
                        ContributionRuntime.statistics()
                                .block(serverPlayer, pos, state, IndustryMatcher.Action.MINE);
                    }
                });
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(
                (level, killer, victim, damage) -> {
                    if (killer instanceof ServerPlayer player
                            && victim instanceof net.minecraft.world.entity.LivingEntity
                            && ContributionRuntime.statistics() != null) {
                        ContributionRuntime.statistics()
                                .gameEvent(
                                        level.getServer(), "contribution:entity/kill", 1, player);
                    }
                });
        LOGGER.info(
                "Loaded {} built-in contribution industries", IndustryRegistry.builtIns().size());
    }
}
