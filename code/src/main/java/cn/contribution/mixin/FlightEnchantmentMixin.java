package cn.contribution.mixin;

import cn.contribution.items.FlightDurability;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(EnchantmentHelper.class)
public abstract class FlightEnchantmentMixin {
    @WrapMethod(method = "processDurabilityChange")
    private static int contribution$flightEnchantment(
            ServerLevel level, ItemStack stack, int amount, Operation<Integer> original) {
        return original.call(level, FlightDurability.enchantmentInput(stack), amount);
    }
}
