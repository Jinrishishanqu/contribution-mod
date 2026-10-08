package cn.contribution.mixin;

import cn.contribution.items.SmithingUpgradeLore;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Preview and actual output use the same bounded lore merge; no menu, inventory or tick scanning.
 */
@Mixin(SmithingTransformRecipe.class)
public abstract class SmithingUpgradeLoreMixin {
    @Shadow @Final private ItemStackTemplate result;

    @Inject(
            method =
                    "assemble(Lnet/minecraft/world/item/crafting/SmithingRecipeInput;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private void contribution$mergeUpgradeLore(
            SmithingRecipeInput input, CallbackInfoReturnable<ItemStack> callback) {
        SmithingUpgradeLore.merge(
                input.base(), callback.getReturnValue(), result.get(DataComponents.LORE));
    }
}
