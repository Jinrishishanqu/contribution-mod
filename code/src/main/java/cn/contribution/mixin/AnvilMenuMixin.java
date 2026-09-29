package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Unique private int contribution$inputDamage;
    @Unique private boolean contribution$inputDamageable;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void contribution$beforeAnvil(Player player, ItemStack result, CallbackInfo callback) {
        ItemStack input = ((AnvilMenu) (Object) this).getSlot(0).getItem();
        contribution$inputDamageable = input.isDamageableItem();
        contribution$inputDamage = contribution$inputDamageable ? input.getDamageValue() : 0;
    }

    @Inject(method = "onTake", at = @At("TAIL"))
    private void contribution$afterAnvil(Player player, ItemStack result, CallbackInfo callback) {
        result = cn.contribution.industry.ResultTransferContext.result(result);
        if (player instanceof ServerPlayer serverPlayer && contribution$inputDamageable
                && result.isDamageableItem() && result.getDamageValue() < contribution$inputDamage
                && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(serverPlayer.level().getServer(),
                    "contribution:modify/anvil_repair", result.getCount(), serverPlayer);
        }
    }
}
