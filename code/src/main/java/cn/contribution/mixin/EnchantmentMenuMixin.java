package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @Inject(
            method = "lambda$clickMenuButton$0",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/entity/player/Player;awardStat(Lnet/minecraft/resources/Identifier;)V",
                            shift = At.Shift.AFTER))
    private void contribution$afterEnchant(
            ItemStack input,
            int option,
            Player player,
            int cost,
            ItemStack lapis,
            Level level,
            BlockPos pos,
            CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer
                && ContributionRuntime.statistics() != null) {
            ContributionRuntime.statistics()
                    .gameEvent(
                            serverPlayer.level().getServer(),
                            "contribution:modify/enchanting",
                            1,
                            serverPlayer);
        }
    }
}
