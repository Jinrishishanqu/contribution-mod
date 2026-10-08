package cn.contribution.mixin;

import cn.contribution.items.ElytraDuplication;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Only the reserved duplication group returns its elytra; all other shaped recipes use vanilla
 * remainders.
 */
@Mixin(ShapedRecipe.class)
public abstract class ElytraDuplicationRecipeMixin implements CraftingRecipe {
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return ElytraDuplication.remainingItems((ShapedRecipe) (Object) this, input);
    }
}
