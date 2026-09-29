package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {
    @Shadow @Final private Merchant merchant;

    @Inject(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;awardStat(Lnet/minecraft/resources/Identifier;)V",
            shift = At.Shift.AFTER))
    private void contribution$afterTrade(Player player, ItemStack result, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer && merchant instanceof Villager
                && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(serverPlayer.level().getServer(),
                    "contribution:service/villager_trade", 1, serverPlayer);
        }
    }
}
