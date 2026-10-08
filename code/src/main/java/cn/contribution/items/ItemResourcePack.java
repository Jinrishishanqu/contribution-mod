package cn.contribution.items;

import cn.contribution.ContributionMod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;

/** Exports a static pack once at startup; does not open a port or modify server.properties. */
public final class ItemResourcePack {
    private ItemResourcePack() {}

    public static void export(MinecraftServer server) {
        String version =
                FabricLoader.getInstance()
                        .getModContainer(ContributionMod.MOD_ID)
                        .orElseThrow()
                        .getMetadata()
                        .getVersion()
                        .getFriendlyString();
        var directory =
                server.getWorldPath(LevelResource.ROOT).resolve("contribution/resource-packs");
        var target = directory.resolve("CSU-YSU-items-" + version + ".zip");
        if (Files.exists(target)) return; // Never replace a file edited by the administrator.
        try (var input =
                ItemResourcePack.class.getResourceAsStream(
                        "/contribution-packs/items-resource-pack.zip")) {
            if (input == null) {
                ContributionMod.LOGGER.warn(
                        "Bundled cosmetic pack is absent; build with processResources");
                return;
            }
            Files.createDirectories(directory);
            Files.copy(input, target);
            ContributionMod.LOGGER.info(
                    "Exported optional vanilla-client cosmetic resource pack: {}", target);
        } catch (IOException error) {
            ContributionMod.LOGGER.warn(
                    "Could not export cosmetic resource pack; gameplay is unaffected", error);
        }
    }
}
