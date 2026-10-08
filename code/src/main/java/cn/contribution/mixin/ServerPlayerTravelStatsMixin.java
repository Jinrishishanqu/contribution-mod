package cn.contribution.mixin;

import cn.contribution.industry.CompletedPlayerEvents;
import cn.contribution.industry.PlayerDistanceAdapter;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's completed travel stat is the narrowest available 26.3 server-side distance hook. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTravelStatsMixin {
    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
    private void contribution$completedTravel(Stat<?> stat, int amount, CallbackInfo callback) {
        CompletedPlayerEvents.completed((ServerPlayer) (Object) this, stat, amount);
        if (stat.getType() == Stats.CUSTOM && stat.getValue() instanceof Identifier statistic) {
            PlayerDistanceAdapter.completed((ServerPlayer) (Object) this, statistic, amount);
        }
    }
}
