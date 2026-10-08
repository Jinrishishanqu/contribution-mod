package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonObservationMixin {
    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void contribution$moved(
            Level level,
            BlockPos pos,
            Direction direction,
            boolean extending,
            CallbackInfoReturnable<Boolean> callback) {
        var service = ContributionRuntime.statistics();
        if (callback.getReturnValueZ() && level instanceof ServerLevel server && service != null)
            service.gameEvent(server.getServer(), "contribution:device/piston_move", 1, null);
    }
}
