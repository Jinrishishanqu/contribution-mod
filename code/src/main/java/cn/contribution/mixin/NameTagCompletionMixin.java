package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;

import org.spongepowered.asm.mixin.Mixin;

import java.util.Objects;

@Mixin(NameTagItem.class)
public abstract class NameTagCompletionMixin {
    @WrapMethod(method = "interactLivingEntity")
    private InteractionResult contribution$named(
            ItemStack stack,
            Player player,
            LivingEntity entity,
            InteractionHand hand,
            Operation<InteractionResult> original) {
        var before = entity.getCustomName();
        var result = original.call(stack, player, entity, hand);
        var service = ContributionRuntime.statistics();
        if (player instanceof ServerPlayer actor
                && service != null
                && !Objects.equals(before, entity.getCustomName()))
            service.gameEvent(actor.level().getServer(), "contribution:entity/name_tag", 1, actor);
        return result;
    }
}
