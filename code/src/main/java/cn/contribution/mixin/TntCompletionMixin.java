package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class TntCompletionMixin {
    @Inject(method = "explode", at = @At("TAIL"))
    private void contribution$exploded(CallbackInfo callback) {
        var entity = (PrimedTnt) (Object) this;
        var service = ContributionRuntime.statistics();
        if (entity.level() instanceof ServerLevel level && service != null)
            service.gameEvent(level.getServer(), "contribution:auto/tnt_explode", 1, null);
    }
}
