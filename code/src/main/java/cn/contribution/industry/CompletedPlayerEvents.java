package cn.contribution.industry;

import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;

/** Selective bridge from vanilla's successful-action statistics. Never mirrors generic uses. */
public final class CompletedPlayerEvents {
    private static final Map<Identifier, String> CUSTOM =
            Map.ofEntries(
                    Map.entry(Stats.FILL_CAULDRON, "culture/cauldron"),
                    Map.entry(Stats.USE_CAULDRON, "culture/cauldron"),
                    Map.entry(Stats.CLEAN_ARMOR, "culture/clean"),
                    Map.entry(Stats.CLEAN_BANNER, "culture/clean"),
                    Map.entry(Stats.CLEAN_SHULKER_BOX, "culture/clean"),
                    Map.entry(Stats.POT_FLOWER, "culture/pot_flower"),
                    Map.entry(Stats.BELL_RING, "culture/bell"),
                    Map.entry(Stats.RAID_TRIGGER, "combat/raid"),
                    Map.entry(Stats.RAID_WIN, "combat/hero_of_village"),
                    Map.entry(Stats.DAMAGE_BLOCKED_BY_SHIELD, "combat/shield_block"));
    private static final Map<Item, String> ITEMS =
            Map.of(
                    Items.MAP, "culture/map_draw",
                    Items.SPLASH_POTION, "food/throw_potion",
                    Items.LINGERING_POTION, "food/throw_potion",
                    Items.ENDER_EYE, "tech/ender_eye",
                    Items.TOTEM_OF_UNDYING, "combat/used_totem");

    private CompletedPlayerEvents() {}

    public static void completed(ServerPlayer player, Stat<?> statistic, int amount) {
        var service = ContributionRuntime.statistics();
        if (service == null || amount <= 0) return;
        String event =
                statistic.getType() == Stats.CUSTOM
                        ? CUSTOM.get(statistic.getValue())
                        : statistic.getType() == Stats.ITEM_USED
                                ? ITEMS.get(statistic.getValue())
                                : null;
        if (event != null) {
            // Shield statistics are damage tenths, not a count of successful blocks.
            long count = statistic.getValue().equals(Stats.DAMAGE_BLOCKED_BY_SHIELD) ? 1 : amount;
            service.gameEvent(player.level().getServer(), "contribution:" + event, count, player);
        }
    }
}
