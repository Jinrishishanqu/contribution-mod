package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayerGameMode.class)
public abstract class BlockInteractionCompletionMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult contribution$interaction(ServerPlayer player, Level level, ItemStack stack,
            InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        var before = level.getBlockState(hit.getBlockPos());
        int count = stack.getCount();
        boolean placing = stack.getItem() instanceof BlockItem;
        InteractionResult result = original.call(player, level, stack, hand, hit);
        if (!placing && ContributionRuntime.statistics() != null
                && (before != level.getBlockState(hit.getBlockPos()) || stack.getCount() < count))
            ContributionRuntime.statistics().interacted(player, hit.getBlockPos(), before);
        return result;
    }
}
