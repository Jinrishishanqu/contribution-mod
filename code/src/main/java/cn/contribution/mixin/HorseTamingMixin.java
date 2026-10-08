package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractHorse.class)
public abstract class HorseTamingMixin {
    @WrapMethod(method = "tameWithName")
    private boolean contribution$tamed(Player player, Operation<Boolean> original) {
        boolean before = ((AbstractHorse) (Object) this).isTamed();
        boolean result = original.call(player);
        var service = ContributionRuntime.statistics();
        if (result && !before && player instanceof ServerPlayer actor && service != null)
            service.gameEvent(actor.level().getServer(), "contribution:entity/tame", 1, actor);
        return result;
    }
}
