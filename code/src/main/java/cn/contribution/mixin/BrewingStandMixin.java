package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandMixin {
    @Unique private static final ThreadLocal<ItemStack[]> contribution$before = new ThreadLocal<>();

    @Inject(method = "doBrew", at = @At("HEAD"))
    private static void contribution$beforeBrew(ServerLevel level, BlockPos pos,
            BrewingStandBlockEntity stand, CallbackInfo callback) {
        ItemStack[] contents = new ItemStack[3];
        for (int index = 0; index < contents.length; index++) {
            contents[index] = stand.getItem(index).copy();
        }
        contribution$before.set(contents);
    }

    @Inject(method = "doBrew", at = @At("TAIL"))
    private static void contribution$afterBrew(ServerLevel level, BlockPos pos,
            BrewingStandBlockEntity stand, CallbackInfo callback) {
        ItemStack[] before = contribution$before.get();
        contribution$before.remove();
        if (before == null || ContributionRuntime.statistics() == null) {
            return;
        }
        long produced = 0;
        for (int index = 0; index < before.length; index++) {
            ItemStack after = stand.getItem(index);
            if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(before[index], after)) {
                produced += after.getCount();
            }
        }
        if (produced > 0) {
            ContributionRuntime.statistics().gameEvent(level.getServer(),
                    "contribution:process/brewing", produced, null);
        }
    }
}
