package cn.contribution.mixin;

import cn.contribution.items.SpecialItems;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
public abstract class PlayerHeadCollectionMixin {
    @WrapMethod(method = "hurtServer")
    private boolean contribution$head(
            ServerLevel level, DamageSource source, float amount, Operation<Boolean> original) {
        boolean accepted = original.call(level, source, amount);
        SpecialItems.collectHead((ServerPlayer) (Object) this, source, accepted);
        return accepted;
    }
}
