package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Unique private int contribution$generatedItemCount;

    @Inject(method = "retrieve", at = @At("HEAD"))
    private void contribution$beforeRetrieve(
            ItemStack rod, CallbackInfoReturnable<Integer> callback) {
        contribution$generatedItemCount = 0;
    }

    @WrapOperation(
            method = "retrieve",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> contribution$captureFishingLoot(
            LootTable table, LootParams params, Operation<ObjectArrayList<ItemStack>> original) {
        ObjectArrayList<ItemStack> generated = original.call(table, params);
        contribution$generatedItemCount = generated.stream().mapToInt(ItemStack::getCount).sum();
        return generated;
    }

    @Inject(method = "retrieve", at = @At("RETURN"))
    private void contribution$afterRetrieve(
            ItemStack rod, CallbackInfoReturnable<Integer> callback) {
        if (callback.getReturnValue() != 1
                || contribution$generatedItemCount <= 0
                || ContributionRuntime.statistics() == null) {
            return;
        }
        FishingHook hook = (FishingHook) (Object) this;
        if (hook.getPlayerOwner() instanceof ServerPlayer player) {
            ContributionRuntime.statistics()
                    .gameEvent(
                            player.level().getServer(),
                            "contribution:collect/fishing",
                            contribution$generatedItemCount,
                            player);
        }
    }
}
