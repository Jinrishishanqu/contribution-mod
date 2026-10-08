package cn.contribution.items;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

/** A recipe-scoped catalyst return: one valid output stack plus the original durable elytra. */
public final class ElytraDuplication {
    public static final String GROUP = "contribution:elytra_duplication";

    private ElytraDuplication() {}

    public static NonNullList<ItemStack> remainingItems(ShapedRecipe recipe, CraftingInput input) {
        var remaining = CraftingRecipe.defaultCraftingReminder(input);
        if (GROUP.equals(recipe.group())
                && recipe.getWidth() == 3
                && recipe.getHeight() == 3
                && input.width() == 3
                && input.height() == 3
                && recipe.matches(input, null)
                && recipe.assemble(input).is(Items.ELYTRA)
                && input.getItem(4).is(Items.ELYTRA)) {
            ItemStack catalyst = input.getItem(4).copyWithCount(1);
            catalyst.setDamageValue(catalyst.getMaxDamage() - 1);
            remaining.set(4, catalyst);
        }
        return remaining;
    }
}
