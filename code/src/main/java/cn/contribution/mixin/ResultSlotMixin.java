package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @Shadow private int removeCount;
    @Shadow @Final private Player player;

    // Vanilla reports the transferred quantity here for normal, swap and shift-click crafting,
    // then resets removeCount. onTake's stack may already be empty after a shift-click.
    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void contribution$afterCraft(ItemStack result, CallbackInfo callback) {
        if (removeCount > 0 && player instanceof ServerPlayer serverPlayer && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().craft(serverPlayer, result.copyWithCount(removeCount));
        }
    }
}
