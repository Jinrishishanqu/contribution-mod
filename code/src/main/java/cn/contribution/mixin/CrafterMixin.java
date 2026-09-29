package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrafterBlock.class)
public abstract class CrafterMixin {
    @Unique private static final ThreadLocal<ItemStack> contribution$result = new ThreadLocal<>();

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void contribution$beforeCraft(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo callback) {
        contribution$result.remove();
        if (!(level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter)) {
            return;
        }
        var input = crafter.asCraftInput();
        CrafterBlock.getPotentialResults(level, input).ifPresent(recipe -> {
            ItemStack output = recipe.value().assemble(input);
            if (!output.isEmpty()) {
                contribution$result.set(output.copy());
            }
        });
    }

    @Inject(method = "dispenseFrom", at = @At("TAIL"))
    private void contribution$afterCraft(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo callback) {
        ItemStack produced = contribution$result.get();
        contribution$result.remove();
        if (produced != null && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(level.getServer(),
                    "contribution:process/crafter/output", produced.getCount(), null);
        }
    }
}
