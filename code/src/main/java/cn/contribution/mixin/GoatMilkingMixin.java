package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Goat.class)
public abstract class GoatMilkingMixin {
    @Inject(method = "mobInteract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;setItemInHand(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V",
            shift = At.Shift.AFTER))
    private void contribution$afterMilking(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {
        if (player instanceof ServerPlayer serverPlayer && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics().gameEvent(serverPlayer.level().getServer(),
                    "contribution:entity/milk", 1, serverPlayer);
        }
    }
}
