package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class HopperObservationMixin {
    @WrapMethod(method = "tryMoveInItem")
    private static ItemStack contribution$transferred(
            Container source,
            Container target,
            ItemStack input,
            int slot,
            Direction direction,
            Operation<ItemStack> original) {
        int before = input.getCount();
        ItemStack result = original.call(source, target, input, slot, direction);
        int transferred = before - result.getCount();
        HopperBlockEntity hopper =
                source instanceof HopperBlockEntity h
                        ? h
                        : target instanceof HopperBlockEntity h ? h : null;
        var service = ContributionRuntime.statistics();
        if (transferred > 0
                && hopper != null
                && hopper.getLevel() instanceof ServerLevel level
                && service != null)
            service.gameEvent(
                    level.getServer(), "contribution:auto/hopper_transfer", transferred, null);
        return result;
    }
}
