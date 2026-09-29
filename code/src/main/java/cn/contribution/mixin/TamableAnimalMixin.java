package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {
    @Unique private boolean contribution$previouslyTamed;

    @Inject(method = "tame", at = @At("HEAD"))
    private void contribution$beforeTame(Player player, CallbackInfo callback) {
        contribution$previouslyTamed = ((TamableAnimal) (Object) this).isTame();
    }

    @Inject(method = "tame", at = @At("TAIL"))
    private void contribution$afterTame(Player player, CallbackInfo callback) {
        TamableAnimal animal = (TamableAnimal) (Object) this;
        if (!contribution$previouslyTamed && animal.isTame() && player instanceof ServerPlayer serverPlayer
                && animal.level() instanceof ServerLevel level && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(level.getServer(), "contribution:entity/tame", 1, serverPlayer);
        }
    }
}
