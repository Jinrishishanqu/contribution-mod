package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(DefaultDispenseItemBehavior.class)
public abstract class DispenserObservationMixin {
    @WrapMethod(method = "dispense")
    private ItemStack contribution$dispensed(
            BlockSource source, ItemStack input, Operation<ItemStack> original) {
        ItemStack before = input.copy();
        ItemStack result = original.call(source, input);
        var service = ContributionRuntime.statistics();
        if (service != null
                && !before.isEmpty()
                && (result.getCount() < before.getCount()
                        || !ItemStack.isSameItemSameComponents(before, result))) {
            String event =
                    source.state().is(Blocks.DROPPER)
                            ? "contribution:auto/dropper_fire"
                            : "contribution:auto/dispenser_fire";
            service.gameEvent(source.level().getServer(), event, 1, null);
        }
        return result;
    }
}
