package cn.contribution.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs native 26.3 codecs and real crafting assembly, not just JSON syntax validation. */
public final class ItemContentChecks {
    private static final Path ROOT = Path.of("src/main/resources");

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        int recipes = 0, advancements = 0, equipment = 0;
        try (var paths = Files.walk(ROOT.resolve("data/contribution/recipe/items"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                Recipe.DIRECT_CODEC.parse(ops, json(path)).getOrThrow(message -> new AssertionError(path + ": " + message));
                recipes++;
            }
        }
        try (var paths = Files.walk(ROOT.resolve("data/contribution/advancement"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var advancement = Advancement.CODEC.parse(ops, json(path))
                        .getOrThrow(message -> new AssertionError(path + ": " + message));
                if (json(path).toString().contains("csumc") || json(path).toString().contains("\"function\"") || advancement.criteria().isEmpty())
                    throw new AssertionError("Function-dependent or empty advancement: " + path);
                advancements++;
            }
        }
        try (var paths = Files.walk(ROOT.resolve("assets/contribution/equipment"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                EquipmentClientInfo.CODEC.parse(JsonOps.INSTANCE, json(path))
                        .getOrThrow(message -> new AssertionError(path + ": " + message));
                equipment++;
            }
        }
        var base = new ItemStack(Items.DIAMOND_CHESTPLATE);
        base.set(DataComponents.CUSTOM_NAME, Component.literal("保留我的名称"));
        base.set(DataComponents.DAMAGE, 17);
        base.enchant(lookup.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 3);
        base.set(DataComponents.TRIM, new net.minecraft.world.item.equipment.trim.ArmorTrim(
                lookup.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_MATERIAL)
                        .getOrThrow(net.minecraft.world.item.equipment.trim.TrimMaterials.GOLD),
                lookup.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_PATTERN)
                        .getOrThrow(net.minecraft.world.item.equipment.trim.TrimPatterns.SENTRY)));
        var wings = (SmithingTransformRecipe) Recipe.DIRECT_CODEC.parse(ops,
                json(ROOT.resolve("data/contribution/recipe/items/wings/diamond.json"))).getOrThrow();
        var winged = wings.assemble(new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                base, new ItemStack(Items.ELYTRA)));
        preserved(base, winged);
        if (!winged.has(DataComponents.GLIDER) || winged.getMaxDamage() != 592)
            throw new AssertionError("Winged chestplate components");
        var reinforce = (SmithingTransformRecipe) Recipe.DIRECT_CODEC.parse(ops,
                json(ROOT.resolve("data/contribution/recipe/items/reinforcement/diamond_chestplate.json"))).getOrThrow();
        var reinforced = reinforce.assemble(new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                winged, new ItemStack(Items.NETHERITE_CHESTPLATE)));
        preserved(base, reinforced);
        if (!reinforced.has(DataComponents.GLIDER) || reinforced.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size() != 3)
            throw new AssertionError("Reinforcement must preserve wings and replace, not stack, attributes");
        var themedBase = new ItemStack(Items.NETHERITE_CHESTPLATE);
        themedBase.set(DataComponents.CUSTOM_NAME, base.get(DataComponents.CUSTOM_NAME));
        themedBase.set(DataComponents.DAMAGE, 17);
        themedBase.set(DataComponents.ENCHANTMENTS, base.get(DataComponents.ENCHANTMENTS));
        themedBase.set(DataComponents.TRIM, base.get(DataComponents.TRIM));
        var theme = (TransmuteRecipe) Recipe.DIRECT_CODEC.parse(ops,
                json(ROOT.resolve("data/contribution/recipe/items/armor/mark_6_chestplate.json"))).getOrThrow();
        var grid = CraftingInput.of(2, 1, List.of(themedBase, new ItemStack(Items.IRON_INGOT)));
        if (!theme.matches(grid, null)) throw new AssertionError("Theme crafting inputs");
        preserved(themedBase, theme.assemble(grid));
        net.minecraft.server.packs.metadata.pack.PackMetadataSection.CLIENT_TYPE.codec().parse(JsonOps.INSTANCE,
                json(Path.of("src/itemResourcePack/pack.mcmeta")).getAsJsonObject().get("pack")).getOrThrow();
        var themes = ROOT.resolve("assets/contribution");
        try (var paths = Files.walk(themes)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".png")).toList()) {
                if (javax.imageio.ImageIO.read(path.toFile()) == null)
                    throw new AssertionError("Invalid PNG: " + path);
            }
        }
        if (recipes != 58 || advancements != 96 || equipment != 12)
            throw new AssertionError("Missing content: " + recipes + "/" + advancements + "/" + equipment);
        System.out.println("ITEM_CONTENT_PASS: 58 recipes, 96 advancements, 12 equipment definitions; vanilla assembly preserves name/damage/enchants/trim/wings; pack metadata and PNGs valid");
    }

    private static JsonElement json(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) { return JsonParser.parseReader(reader); }
    }

    private static void preserved(ItemStack before, ItemStack after) {
        if (!before.get(DataComponents.CUSTOM_NAME).equals(after.get(DataComponents.CUSTOM_NAME))
                || !before.get(DataComponents.DAMAGE).equals(after.get(DataComponents.DAMAGE))
                || !before.get(DataComponents.ENCHANTMENTS).equals(after.get(DataComponents.ENCHANTMENTS))
                || !before.get(DataComponents.TRIM).equals(after.get(DataComponents.TRIM)))
            throw new AssertionError("Equipment customization/damage was lost");
    }
}
