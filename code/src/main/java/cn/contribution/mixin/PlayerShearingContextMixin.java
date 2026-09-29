package cn.contribution.mixin;

import cn.contribution.industry.ShearingContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerShearingContextMixin {
    @Inject(method = "interactOn", at = @At("HEAD"))
    private void contribution$beforeEntityInteraction(Entity target, InteractionHand hand, Vec3 hit,
            CallbackInfoReturnable<InteractionResult> callback) {
        ShearingContext.leave();
        Player player = (Player) (Object) this;
        if (player instanceof ServerPlayer serverPlayer && target instanceof LivingEntity living
                && target instanceof Shearable && player.getItemInHand(hand).is(Items.SHEARS)) {
            ShearingContext.enter(serverPlayer, living);
        }
    }

    @Inject(method = "interactOn", at = @At("RETURN"))
    private void contribution$afterEntityInteraction(Entity target, InteractionHand hand, Vec3 hit,
            CallbackInfoReturnable<InteractionResult> callback) {
        ShearingContext.leave();
    }
}
