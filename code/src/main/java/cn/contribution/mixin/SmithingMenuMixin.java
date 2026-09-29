package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {
    @Inject(method = "onTake", at = @At("TAIL"))
    private void contribution$afterSmithing(Player player, ItemStack result, CallbackInfo callback) {
        result = cn.contribution.industry.ResultTransferContext.result(result);
        if (player instanceof ServerPlayer serverPlayer && !result.isEmpty()
                && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(serverPlayer.level().getServer(),
                    "contribution:modify/smithing", result.getCount(), serverPlayer);
        }
    }
}
