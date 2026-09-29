package cn.contribution.industry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ProductionClassifier {
    private static final TagKey<Item> MINERAL = tag("process_furnace_mineral");
    private static final TagKey<Item> CHEMICAL = tag("process_furnace_chemical");
    private static final TagKey<Item> MATERIAL = tag("process_furnace_material");
    private static final TagKey<Item> FOOD = tag("process_furnace_food");

    private ProductionClassifier() {
    }

    public static String furnaceEvent(ItemStack output) {
        if (RuleManager.current() != null) return RuleManager.current().production.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
        var holder = output.getItem().builtInRegistryHolder();
        if (holder.is(MINERAL)) return "contribution:process/furnace/mineral_output";
        if (holder.is(CHEMICAL)) return "contribution:process/furnace/chemical_output";
        if (holder.is(MATERIAL)) return "contribution:process/furnace/material_output";
        if (holder.is(FOOD)) return "contribution:process/furnace/food_output";
        return null;
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("contribution", path));
    }
}
