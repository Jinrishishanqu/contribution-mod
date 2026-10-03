package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import java.util.function.Consumer;

@Mixin(ItemStack.class)
public abstract class ItemUseCompletionMixin {
    @WrapMethod(method = "finishUsingItem")
    private ItemStack contribution$consumed(Level level, LivingEntity user, Operation<ItemStack> original) {
        ItemStack stack = (ItemStack)(Object)this;
        ItemStack before = stack.copy();
        ItemStack result = original.call(level, user);
        if (user instanceof ServerPlayer player) {
            int consumed = Math.max(0, before.getCount() - stack.getCount());
            cn.contribution.items.SpecialItems.consumed(player, before, consumed);
            if (consumed > 0 && ContributionRuntime.statistics() != null)
                ContributionRuntime.statistics().usedItem(player, before, consumed);
        }
        return result;
    }
    @WrapMethod(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V")
    private void contribution$durability(int amount, ServerLevel level, ServerPlayer player, Consumer<ItemStack> onBreak, Operation<Void> original) {
        ItemStack stack = (ItemStack)(Object)this;
        ItemStack before = stack.copy();
        original.call(amount, level, player, onBreak);
        if (player != null && ContributionRuntime.statistics() != null) {
            int lost = stack.isEmpty() ? before.getMaxDamage() - before.getDamageValue() : stack.getDamageValue() - before.getDamageValue();
            if (lost > 0) ContributionRuntime.statistics().usedItem(player, before, lost);
        }
    }
}
