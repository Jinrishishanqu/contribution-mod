package cn.contribution.mixin;

import cn.contribution.industry.ProductionClassifier;
import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceMixin {
    // Vanilla invokes this only when a valid fuel is actually consumed, including bucket fuels.
    @Inject(method = "consumeFuel", at = @At("TAIL"))
    private static void contribution$fuel(
            ServerLevel level,
            BlockPos pos,
            NonNullList<ItemStack> items,
            ItemStack fuel,
            CallbackInfo callback) {
        var service = ContributionRuntime.statistics();
        if (service != null)
            service.gameEvent(level.getServer(), "contribution:process/furnace/fuel", 1, null);
    }

    // Only executes on completed cooking, not on every furnace tick.
    @WrapOperation(
            method = "serverTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;burn(Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"))
    private static void contribution$produced(
            NonNullList<ItemStack> items,
            ItemStack input,
            ItemStack output,
            Operation<Void> original,
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            AbstractFurnaceBlockEntity furnace) {
        int before = items.get(2).getCount();
        original.call(items, input, output);
        int produced = items.get(2).getCount() - before;
        if (produced <= 0 || ContributionRuntime.statistics() == null) return;
        String event =
                state.is(Blocks.BLAST_FURNACE)
                        ? "contribution:process/blast_furnace/output"
                        : state.is(Blocks.SMOKER)
                                ? "contribution:process/smoker/output"
                                : ProductionClassifier.furnaceEvent(items.get(2));
        if (event != null)
            ContributionRuntime.statistics().gameEvent(level.getServer(), event, produced, null);
    }
}
