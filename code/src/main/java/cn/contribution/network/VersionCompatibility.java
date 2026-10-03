package cn.contribution.network;

import cn.contribution.ContributionMod;
import cn.contribution.ui.ContributionSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;

/** Join-only compatibility notice: never requires a mod on vanilla clients or disconnects a player. */
public final class VersionCompatibility {
    private VersionCompatibility() { }
    public static VersionPayload local() {
        return new VersionPayload(FabricLoader.getInstance().getModContainer(ContributionMod.MOD_ID).orElseThrow()
                .getMetadata().getVersion().getFriendlyString(), SharedConstants.getCurrentVersion().name());
    }
    public static boolean matches(VersionPayload first, VersionPayload second) {
        return first.modVersion().equals(second.modVersion()) && first.gameVersion().equals(second.gameVersion());
    }
    public static String notice(VersionPayload client, VersionPayload server) {
        return "[贡献系统] 版本不匹配：客户端模组 " + client.modVersion() + " / MC " + client.gameVersion()
                + "，服务端模组 " + server.modVersion() + " / MC " + server.gameVersion()
                + "。请将客户端更新为服务端版本；原版客户端仍可正常进入。";
    }
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(VersionPayload.TYPE, VersionPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, VersionPayload.TYPE)) {
                ServerPlayNetworking.send(handler.player, local());
            } else if (ServerPlayNetworking.canSend(handler.player, ContributionSnapshotPayload.TYPE)) {
                handler.player.sendSystemMessage(Component.literal("[贡献系统] 客户端模组版本较旧，不支持版本检查；请更新为 "
                        + local().modVersion() + "，避免界面兼容问题。"));
            }
        });
    }
}
