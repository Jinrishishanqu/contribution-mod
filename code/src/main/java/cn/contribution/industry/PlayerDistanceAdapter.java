package cn.contribution.industry;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/** Interprets completed vanilla travel-stat increments, not movement attempts. */
public final class PlayerDistanceAdapter {
    private static final int MAX_INCREMENT_CENTIMETERS = 1_600;
    private static final long MICROBLOCKS_PER_CENTIMETER = 10_000L;

    private PlayerDistanceAdapter() {
    }

    public static void completed(ServerPlayer player, Identifier statistic, int centimeters) {
        StatisticsService service = ContributionRuntime.statistics();
        if (service == null || centimeters <= 0 || centimeters > MAX_INCREMENT_CENTIMETERS) {
            return;
        }
        Entity vehicle = player.getVehicle();
        String mode = null;
        if (statistic.equals(Stats.AVIATE_ONE_CM) && vehicle == null && player.isFallFlying()) {
            mode = "elytra";
        } else if (statistic.equals(Stats.HORSE_ONE_CM) && vehicle instanceof AbstractHorse) {
            mode = "horse";
        } else if (statistic.equals(Stats.BOAT_ONE_CM) && vehicle instanceof AbstractBoat) {
            mode = "boat";
        } else if (statistic.equals(Stats.MINECART_ONE_CM) && vehicle instanceof AbstractMinecart) {
            mode = "minecart";
        } else if (statistic.equals(Stats.PIG_ONE_CM) && vehicle instanceof Pig) {
            mode = "pig";
        } else if (statistic.equals(Stats.STRIDER_ONE_CM) && vehicle instanceof Strider) {
            mode = "strider";
        }
        if (mode != null) {
            service.distance(player, mode, centimeters * MICROBLOCKS_PER_CENTIMETER);
        }
    }
}
