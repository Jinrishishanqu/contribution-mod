package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CrafterBlock.class)
public abstract class CrafterMixin {
    // The first dispenseItem invocation is the actual recipe output, not container remainders.
    @WrapOperation(
            method = "dispenseFrom",
            at =
                    @At(
                            value = "INVOKE",
                            ordinal = 0,
                            target =
                                    "Lnet/minecraft/world/level/block/CrafterBlock;dispenseItem(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/CrafterBlockEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/crafting/RecipeHolder;)V"))
    private void contribution$produced(
            CrafterBlock block,
            ServerLevel level,
            BlockPos pos,
            CrafterBlockEntity crafter,
            ItemStack result,
            BlockState state,
            RecipeHolder<?> recipe,
            Operation<Void> original) {
        int count = result.getCount();
        original.call(block, level, pos, crafter, result, state, recipe);
        var service = ContributionRuntime.statistics();
        if (count > 0 && service != null)
            service.gameEvent(
                    level.getServer(), "contribution:process/crafter/output", count, null);
    }
}
