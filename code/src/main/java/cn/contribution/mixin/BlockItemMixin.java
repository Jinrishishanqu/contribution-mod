package cn.contribution.mixin;

import cn.contribution.industry.IndustryMatcher;
import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void contribution$afterPlace(
            BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> result) {
        if (!result.getReturnValue().consumesAction()
                || !(context.getPlayer() instanceof ServerPlayer player)
                || ContributionRuntime.statistics() == null) {
            return;
        }
        var pos = context.getClickedPos();
        ContributionRuntime.statistics()
                .block(
                        player,
                        pos,
                        context.getLevel().getBlockState(pos),
                        IndustryMatcher.Action.PLACE);
    }
}
