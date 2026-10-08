package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ZombieVillager.class)
public abstract class ZombieVillagerCureMixin {
    @Shadow private UUID conversionStarter;

    @Inject(method = "lambda$finishConversion$0", at = @At("TAIL"))
    private void contribution$afterCure(
            ServerLevel level, Villager converted, CallbackInfo callback) {
        if (ContributionRuntime.statistics() == null) {
            return;
        }
        ServerPlayer actor =
                conversionStarter == null
                        ? null
                        : level.getServer().getPlayerList().getPlayer(conversionStarter);
        ContributionRuntime.statistics()
                .gameEvent(level.getServer(), "contribution:entity/cure", 1, actor);
    }
}
