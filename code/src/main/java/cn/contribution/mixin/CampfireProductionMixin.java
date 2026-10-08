package cn.contribution.mixin;

import cn.contribution.runtime.ContributionRuntime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CampfireBlockEntity.class)
public abstract class CampfireProductionMixin {
    @WrapOperation(
            method = "cookTick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"))
    private static void contribution$cooked(
            Level level, double x, double y, double z, ItemStack output, Operation<Void> original) {
        int count = output.getCount();
        original.call(level, x, y, z, output);
        var service = ContributionRuntime.statistics();
        if (level instanceof ServerLevel server && service != null && count > 0)
            service.gameEvent(
                    server.getServer(), "contribution:process/campfire_cook", count, null);
    }
}
