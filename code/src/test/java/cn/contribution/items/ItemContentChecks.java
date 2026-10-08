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
        var lookup =
                VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(lookup)
                .forEach(
                        net.minecraft.core.component.DataComponentInitializers.PendingComponents
                                ::apply);
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        int recipes = 0, advancements = 0, equipment = 0;
        var recipeRegistry =
                new java.util.LinkedHashMap<
                        net.minecraft.resources.ResourceKey<Recipe<?>>, Recipe<?>>();
        try (var paths = Files.walk(ROOT.resolve("data/contribution/recipe/items"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var recipe =
                        Recipe.DIRECT_CODEC
                                .parse(ops, json(path))
                                .getOrThrow(message -> new AssertionError(path + ": " + message));
                String id =
                        ROOT.resolve("data/contribution/recipe")
                                .relativize(path)
                                .toString()
                                .replace(java.io.File.separatorChar, '/');
                id = id.substring(0, id.length() - 5);
                recipeRegistry.put(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.RECIPE,
                                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                        "contribution", id)),
                        recipe);
                recipes++;
            }
        }
        // 26.3 criteria resolve recipe holders: decode against the same real recipes, not an empty
        // registry.
        var withRecipes =
                new net.minecraft.core.RegistrySetBuilder()
                        .add(
                                net.minecraft.core.registries.Registries.RECIPE,
                                context -> recipeRegistry.forEach(context::register))
                        .build(
                                net.minecraft.core.HolderLookup.Provider.create(
                                        lookup.listRegistries()
                                                .filter(
                                                        registry ->
                                                                !registry.key()
                                                                        .equals(
                                                                                net.minecraft.core
                                                                                        .registries
                                                                                        .Registries
                                                                                        .RECIPE))));
        var advancementOps = RegistryOps.create(JsonOps.INSTANCE, withRecipes);
        try (var paths = Files.walk(ROOT.resolve("data/contribution/advancement"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var advancement =
                        Advancement.CODEC
                                .parse(advancementOps, json(path))
                                .getOrThrow(message -> new AssertionError(path + ": " + message));
                if (json(path).toString().contains("csumc")
                        || json(path).toString().contains("\"function\"")
                        || advancement.criteria().isEmpty())
                    throw new AssertionError("Function-dependent or empty advancement: " + path);
                advancements++;
            }
        }
        try (var paths = Files.walk(ROOT.resolve("assets/contribution/equipment"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                EquipmentClientInfo.CODEC
                        .parse(JsonOps.INSTANCE, json(path))
                        .getOrThrow(message -> new AssertionError(path + ": " + message));
                equipment++;
            }
        }
        var base = new ItemStack(Items.DIAMOND_CHESTPLATE);
        base.set(DataComponents.CUSTOM_NAME, Component.literal("保留我的名称"));
        base.set(DataComponents.DAMAGE, 17);
        base.enchant(
                lookup.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING),
                3);
        base.set(
                DataComponents.TRIM,
                new net.minecraft.world.item.equipment.trim.ArmorTrim(
                        lookup.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_MATERIAL)
                                .getOrThrow(
                                        net.minecraft.world.item.equipment.trim.TrimMaterials.GOLD),
                        lookup.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_PATTERN)
                                .getOrThrow(
                                        net.minecraft.world.item.equipment.trim.TrimPatterns
                                                .SENTRY)));
        var wings =
                (SmithingTransformRecipe)
                        Recipe.DIRECT_CODEC
                                .parse(
                                        ops,
                                        json(
                                                ROOT.resolve(
                                                        "data/contribution/recipe/items/wings/diamond.json")))
                                .getOrThrow();
        var winged =
                wings.assemble(
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                base,
                                new ItemStack(Items.ELYTRA)));
        SmithingUpgradeLore.merge(base, winged, winged.get(DataComponents.LORE));
        preserved(base, winged);
        if (!winged.has(DataComponents.GLIDER)
                || winged.getMaxDamage() != 592
                || !winged.get(DataComponents.ITEM_MODEL)
                        .toString()
                        .equals("contribution:winged/diamond")
                || winged.get(DataComponents.LORE).lines().stream()
                        .noneMatch(line -> line.getString().equals("添翼")))
            throw new AssertionError("Winged chestplate components");
        var reinforce =
                (SmithingTransformRecipe)
                        Recipe.DIRECT_CODEC
                                .parse(
                                        ops,
                                        json(
                                                ROOT.resolve(
                                                        "data/contribution/recipe/items/reinforcement/diamond_chestplate.json")))
                                .getOrThrow();
        var reinforced =
                reinforce.assemble(
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                winged,
                                new ItemStack(Items.NETHERITE_CHESTPLATE)));
        SmithingUpgradeLore.merge(winged, reinforced, reinforced.get(DataComponents.LORE));
        if (!reinforced.get(DataComponents.LORE).lines().stream()
                .map(Component::getString)
                .toList()
                .equals(List.of("添翼", "下界合金强化")))
            throw new AssertionError("Wings and reinforcement lore must coexist");
        var repeated =
                reinforce.assemble(
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                reinforced,
                                new ItemStack(Items.NETHERITE_CHESTPLATE)));
        SmithingUpgradeLore.merge(reinforced, repeated, repeated.get(DataComponents.LORE));
        if (!repeated.get(DataComponents.LORE).equals(reinforced.get(DataComponents.LORE)))
            throw new AssertionError("Repeated reinforcement duplicated lore");
        var firstUpgrade =
                reinforce.assemble(
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                base,
                                new ItemStack(Items.NETHERITE_CHESTPLATE)));
        SmithingUpgradeLore.merge(base, firstUpgrade, firstUpgrade.get(DataComponents.LORE));
        var reverse =
                wings.assemble(
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                firstUpgrade,
                                new ItemStack(Items.ELYTRA)));
        SmithingUpgradeLore.merge(firstUpgrade, reverse, reverse.get(DataComponents.LORE));
        if (!reverse.get(DataComponents.LORE).lines().stream()
                .map(Component::getString)
                .toList()
                .equals(List.of("下界合金强化", "添翼")))
            throw new AssertionError("Adding wings must preserve reinforcement lore");
        preserved(base, reinforced);
        if (!reinforced.has(DataComponents.GLIDER)
                || reinforced.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size() != 3)
            throw new AssertionError(
                    "Reinforcement must preserve wings and replace, not stack, attributes");
        var themedBase = new ItemStack(Items.NETHERITE_CHESTPLATE);
        themedBase.set(DataComponents.CUSTOM_NAME, base.get(DataComponents.CUSTOM_NAME));
        themedBase.set(DataComponents.DAMAGE, 17);
        themedBase.set(DataComponents.ENCHANTMENTS, base.get(DataComponents.ENCHANTMENTS));
        themedBase.set(DataComponents.TRIM, base.get(DataComponents.TRIM));
        var theme =
                (TransmuteRecipe)
                        Recipe.DIRECT_CODEC
                                .parse(
                                        ops,
                                        json(
                                                ROOT.resolve(
                                                        "data/contribution/recipe/items/armor/mark_6_chestplate.json")))
                                .getOrThrow();
        var grid = CraftingInput.of(2, 1, List.of(themedBase, new ItemStack(Items.IRON_INGOT)));
        if (!theme.matches(grid, null)) throw new AssertionError("Theme crafting inputs");
        preserved(themedBase, theme.assemble(grid));
        net.minecraft.server.packs.metadata.pack.PackMetadataSection.CLIENT_TYPE
                .codec()
                .parse(
                        JsonOps.INSTANCE,
                        json(Path.of("src/itemResourcePack/pack.mcmeta"))
                                .getAsJsonObject()
                                .get("pack"))
                .getOrThrow();
        var themes = ROOT.resolve("assets/contribution");
        try (var paths = Files.walk(themes)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".png")).toList()) {
                if (javax.imageio.ImageIO.read(path.toFile()) == null)
                    throw new AssertionError("Invalid PNG: " + path);
            }
        }
        checkExpandedEquipment(ops);
        checkVisualResources(ops, lookup);
        checkRestoredItems(ops, winged);
        if (recipes != 128 || advancements != 164 || equipment != 13)
            throw new AssertionError(
                    "Missing content: " + recipes + "/" + advancements + "/" + equipment);
        System.out.println(
                "ITEM_CONTENT_PASS: 128 recipes, 164 advancements, 13 equipment definitions; all"
                    + " material tools/spears and copper armor assemble; native tooltip IDs, wing"
                    + " models/lore, trim textures, cyan background and two durable elytra"
                    + " verified");
    }

    private static void checkExpandedEquipment(RegistryOps<JsonElement> ops) throws Exception {
        var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        for (String kind : List.of("tool", "armor")) {
            var definition =
                    json(ROOT.resolve(
                                    "data/contribution/advancement/items/upgrade/reinforce_"
                                            + kind
                                            + ".json"))
                            .getAsJsonObject();
            for (var entry : definition.getAsJsonObject("criteria").entrySet()) {
                var criterion = entry.getValue().getAsJsonObject();
                if (!criterion.get("trigger").getAsString().equals("minecraft:recipe_crafted")
                        || !criterion
                                .getAsJsonObject("conditions")
                                .getAsJsonArray("recipes")
                                .get(0)
                                .getAsString()
                                .equals("contribution:items/reinforcement/" + entry.getKey())
                        || !Files.exists(
                                ROOT.resolve(
                                        "data/contribution/recipe/items/reinforcement/"
                                                + entry.getKey()
                                                + ".json")))
                    throw new AssertionError(
                            "Upgrade advancement must match its actual recipe: " + entry.getKey());
            }
        }
        try (var paths = Files.list(ROOT.resolve("data/contribution/recipe/items/reinforcement"))) {
            for (var path : paths.toList()) {
                var components =
                        json(path)
                                .getAsJsonObject()
                                .getAsJsonObject("result")
                                .getAsJsonObject("components");
                if (components.has("minecraft:item_name")
                        || components.has("minecraft:item_model")
                        || !components.has("minecraft:lore"))
                    throw new AssertionError(
                            "Upgrade overwrites appearance or misses tooltip: " + path);
            }
        }
        for (var material : List.of("wooden", "stone", "copper", "iron", "golden", "diamond")) {
            for (var type : List.of("sword", "pickaxe", "axe", "shovel", "hoe", "spear")) {
                var id = material + "_" + type;
                var recipe =
                        (SmithingTransformRecipe)
                                Recipe.DIRECT_CODEC
                                        .parse(
                                                ops,
                                                json(
                                                        ROOT.resolve(
                                                                "data/contribution/recipe/items/reinforcement/"
                                                                        + id
                                                                        + ".json")))
                                        .getOrThrow();
                var item =
                        registry.getValue(
                                net.minecraft.resources.Identifier.withDefaultNamespace(id));
                var netherite =
                        registry.getValue(
                                net.minecraft.resources.Identifier.withDefaultNamespace(
                                        "netherite_" + type));
                var input =
                        new SmithingRecipeInput(
                                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                new ItemStack(item),
                                new ItemStack(netherite));
                if (!recipe.matches(input, null))
                    throw new AssertionError("Missing reinforcement: " + id);
                var result = recipe.assemble(input);
                if (List.of("pickaxe", "axe", "shovel", "hoe").contains(type)) {
                    // Tier is a drops rule, not durability or attack damage. Preserve mining speed.
                    var expectedTool =
                            net.minecraft.world.item.component.Tool.CODEC
                                    .encodeStart(ops, item.components().get(DataComponents.TOOL))
                                    .getOrThrow()
                                    .getAsJsonObject()
                                    .deepCopy();
                    var nativeTool =
                            net.minecraft.world.item.component.Tool.CODEC
                                    .encodeStart(
                                            ops, netherite.components().get(DataComponents.TOOL))
                                    .getOrThrow()
                                    .getAsJsonObject();
                    expectedTool
                            .getAsJsonArray("rules")
                            .get(0)
                            .getAsJsonObject()
                            .add(
                                    "blocks",
                                    nativeTool
                                            .getAsJsonArray("rules")
                                            .get(0)
                                            .getAsJsonObject()
                                            .get("blocks"));
                    var actualTool =
                            net.minecraft.world.item.component.Tool.CODEC
                                    .encodeStart(ops, result.get(DataComponents.TOOL))
                                    .getOrThrow();
                    if (!actualTool.equals(expectedTool)) {
                        throw new AssertionError(
                                "Reinforcement must upgrade drops tier and preserve mining speed: "
                                        + id);
                    }
                    if (!result.get(DataComponents.REPAIRABLE)
                            .equals(item.components().get(DataComponents.REPAIRABLE))) {
                        throw new AssertionError("Reinforcement changed repair material: " + id);
                    }
                }
                var actual = result.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (!sameNativeModifiers(
                        actual, netherite.components().get(DataComponents.ATTRIBUTE_MODIFIERS)))
                    throw new AssertionError(
                            "Native attack amounts/green tooltip IDs differ: "
                                    + id
                                    + " actual="
                                    + actual
                                    + " expected="
                                    + netherite
                                            .components()
                                            .get(DataComponents.ATTRIBUTE_MODIFIERS));
                if (type.equals("spear")
                        && (!result.get(DataComponents.KINETIC_WEAPON)
                                        .equals(
                                                netherite
                                                        .components()
                                                        .get(DataComponents.KINETIC_WEAPON))
                                || !result.get(DataComponents.ATTACK_ANIMATION)
                                        .equals(
                                                netherite
                                                        .components()
                                                        .get(DataComponents.ATTACK_ANIMATION))))
                    throw new AssertionError("Spear charge/stab strength differs: " + id);
            }
        }
        for (var type : List.of("helmet", "chestplate", "leggings", "boots")) {
            var recipe =
                    (SmithingTransformRecipe)
                            Recipe.DIRECT_CODEC
                                    .parse(
                                            ops,
                                            json(
                                                    ROOT.resolve(
                                                            "data/contribution/recipe/items/reinforcement/copper_"
                                                                    + type
                                                                    + ".json")))
                                    .getOrThrow();
            var input =
                    new SmithingRecipeInput(
                            new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                            new ItemStack(
                                    registry.getValue(
                                            net.minecraft.resources.Identifier.withDefaultNamespace(
                                                    "copper_" + type))),
                            new ItemStack(
                                    registry.getValue(
                                            net.minecraft.resources.Identifier.withDefaultNamespace(
                                                    "netherite_" + type))));
            if (!recipe.matches(input, null) || recipe.assemble(input).isEmpty())
                throw new AssertionError("Copper armor: " + type);
        }
        var elytra =
                (net.minecraft.world.item.crafting.ShapedRecipe)
                        Recipe.DIRECT_CODEC
                                .parse(
                                        ops,
                                        json(
                                                ROOT.resolve(
                                                        "data/contribution/recipe/items/elytra.json")))
                                .getOrThrow();
        var grid =
                CraftingInput.of(
                        3,
                        3,
                        List.of(
                                new ItemStack(Items.NETHERITE_SCRAP),
                                new ItemStack(Items.NETHER_STAR),
                                new ItemStack(Items.NETHERITE_SCRAP),
                                new ItemStack(Items.PHANTOM_MEMBRANE),
                                new ItemStack(Items.ELYTRA),
                                new ItemStack(Items.PHANTOM_MEMBRANE),
                                new ItemStack(Items.PHANTOM_MEMBRANE),
                                ItemStack.EMPTY,
                                new ItemStack(Items.PHANTOM_MEMBRANE)));
        var output = elytra.assemble(grid);
        var returned = ElytraDuplication.remainingItems(elytra, grid).get(4);
        if (!elytra.matches(grid, null)
                || output.getCount() != 1
                || returned.getCount() != 1
                || !returned.is(Items.ELYTRA)
                || output.getMaxStackSize() != 1
                || output.getMaxDamage() != 432)
            throw new AssertionError("Elytra duplication/stack component");
        grid.getItem(4).set(DataComponents.CUSTOM_NAME, Component.literal("原鞘翅"));
        grid.getItem(4).set(DataComponents.DAMAGE, 31);
        var returnedNamed = ElytraDuplication.remainingItems(elytra, grid).get(4);
        var expected = grid.getItem(4).copy();
        expected.setDamageValue(expected.getMaxDamage() - 1);
        if (!ItemStack.isSameItemSameComponents(returnedNamed, expected)
                || output.getDamageValue() != output.getMaxDamage() - 1)
            throw new AssertionError("Elytra must retain components and leave one durability");
        var ordinaryJson =
                json(ROOT.resolve("data/contribution/recipe/items/elytra.json")).getAsJsonObject();
        ordinaryJson.addProperty("group", "ordinary_recipe");
        var ordinary =
                (net.minecraft.world.item.crafting.ShapedRecipe)
                        Recipe.DIRECT_CODEC.parse(ops, ordinaryJson).getOrThrow();
        if (!ElytraDuplication.remainingItems(ordinary, grid).get(4).isEmpty())
            throw new AssertionError("Unrelated recipe affected");
    }

    private static void checkVisualResources(
            RegistryOps<JsonElement> ops, net.minecraft.core.HolderLookup.Provider lookup)
            throws Exception {
        var root =
                Advancement.CODEC
                        .parse(
                                ops,
                                json(ROOT.resolve("data/contribution/advancement/items/root.json")))
                        .getOrThrow();
        var background = root.display().orElseThrow().background().orElseThrow().texturePath();
        if (!background
                        .toString()
                        .equals(
                                "contribution:textures/gui/advancements/backgrounds/cyan_concrete_powder.png")
                || !Files.exists(
                        ROOT.resolve(
                                "assets/"
                                        + background.getNamespace()
                                        + "/"
                                        + background.getPath())))
            throw new AssertionError("Missing advancement background: " + background);
        for (var pattern :
                lookup.lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_PATTERN)
                        .listElements()
                        .toList()) {
            var asset = pattern.value().assetId();
            var path =
                    ROOT.resolve(
                            "assets/"
                                    + asset.getNamespace()
                                    + "/textures/trims/entity/wings/"
                                    + asset.getPath()
                                    + ".png");
            if (!Files.exists(path) || javax.imageio.ImageIO.read(path.toFile()) == null)
                throw new AssertionError("Missing wing trim for vanilla pattern: " + asset);
        }
        for (var material :
                List.of(
                        "leather",
                        "chainmail",
                        "copper",
                        "iron",
                        "golden",
                        "diamond",
                        "netherite")) {
            var definition =
                    json(ROOT.resolve("assets/contribution/items/winged/" + material + ".json"))
                            .getAsJsonObject()
                            .getAsJsonObject("model");
            if (definition.getAsJsonArray("cases").size() != 11)
                throw new AssertionError("Missing item trim variants: " + material);
            var fallback =
                    definition
                            .getAsJsonObject("fallback")
                            .get("model")
                            .getAsString()
                            .split(":", 2)[1];
            if (!Files.exists(ROOT.resolve("assets/contribution/models/" + fallback + ".json")))
                throw new AssertionError("Missing wing item model");
        }
    }

    private static void checkRestoredItems(RegistryOps<JsonElement> ops, ItemStack winged)
            throws Exception {
        if (FlightDurability.enchantmentInput(winged) != winged)
            throw new AssertionError("Combat path was changed");
        boolean previous = FlightDurability.enter();
        try {
            var flight = FlightDurability.enchantmentInput(winged);
            if (!flight.is(Items.ELYTRA)
                    || !flight.get(DataComponents.ENCHANTMENTS)
                            .equals(winged.get(DataComponents.ENCHANTMENTS)))
                throw new AssertionError(
                        "Flight must use tool item identity and preserve enchantments");
            var ordinary = new ItemStack(Items.DIAMOND_CHESTPLATE);
            if (FlightDurability.enchantmentInput(ordinary) != ordinary)
                throw new AssertionError("Ordinary armor affected");
            boolean nested = FlightDurability.enter();
            FlightDurability.leave(nested);
            if (!FlightDurability.enchantmentInput(winged).is(Items.ELYTRA))
                throw new AssertionError("Nested scope lost");
        } finally {
            FlightDurability.leave(previous);
        }
        if (FlightDurability.enchantmentInput(winged) != winged)
            throw new AssertionError("Flight scope leaked");
        int hats = 0;
        try (var paths = Files.list(ROOT.resolve("data/contribution/recipe/items/hats"))) {
            for (var path : paths.toList()) {
                var definition = json(path).getAsJsonObject();
                var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
                var input =
                        new ItemStack(
                                registry.getValue(
                                        net.minecraft.resources.Identifier.parse(
                                                definition.get("base").getAsString())));
                input.set(DataComponents.CUSTOM_NAME, Component.literal("保留名称"));
                var recipe =
                        (SmithingTransformRecipe)
                                Recipe.DIRECT_CODEC.parse(ops, definition).getOrThrow();
                var grid =
                        new SmithingRecipeInput(
                                new ItemStack(Items.STRING),
                                input,
                                new ItemStack(
                                        registry.getValue(
                                                net.minecraft.resources.Identifier.parse(
                                                        definition
                                                                .get("addition")
                                                                .getAsString()))));
                var result = recipe.assemble(grid);
                if (!recipe.matches(grid, null)
                        || result.isEmpty()
                        || result.getMaxStackSize() != 1
                        || result.get(DataComponents.EQUIPPABLE).slot()
                                != net.minecraft.world.entity.EquipmentSlot.HEAD
                        || !result.get(DataComponents.CUSTOM_NAME)
                                .equals(input.get(DataComponents.CUSTOM_NAME)))
                    throw new AssertionError("Invalid hat assembly: " + path);
                hats++;
            }
        }
        if (hats != 37) throw new AssertionError("Missing unique hats");
        for (var name : List.of("speaker", "dud")) {
            var recipe =
                    (net.minecraft.world.item.crafting.ShapedRecipe)
                            Recipe.DIRECT_CODEC
                                    .parse(
                                            ops,
                                            json(
                                                    ROOT.resolve(
                                                            "data/contribution/recipe/items/"
                                                                    + name
                                                                    + ".json")))
                                    .getOrThrow();
            var output =
                    recipe.assemble(CraftingInput.of(1, 1, List.of(new ItemStack(Items.STONE))));
            if (!output.has(DataComponents.CONSUMABLE)
                    || !output.has(DataComponents.USE_COOLDOWN)
                    || !output.get(DataComponents.CUSTOM_DATA)
                            .copyTag()
                            .getString("contribution:item")
                            .orElse("")
                            .equals(name))
                throw new AssertionError("Missing native consumable identity: " + name);
        }
        if (Files.exists(ROOT.resolve("data/contribution/advancement/items/gain/gain_torch.json"))
                || Files.exists(
                        ROOT.resolve("data/contribution/advancement/items/gain/soul_torch.json")))
            throw new AssertionError("Removed advancements still packaged");
    }

    private static boolean sameNativeModifiers(
            net.minecraft.world.item.component.ItemAttributeModifiers actual,
            net.minecraft.world.item.component.ItemAttributeModifiers expected) {
        if (actual.modifiers().size() != expected.modifiers().size()) return false;
        for (int i = 0; i < actual.modifiers().size(); i++) {
            var a = actual.modifiers().get(i);
            var e = expected.modifiers().get(i);
            // Vanilla tool constructors widen float speeds to double; JSON decimals need not encode
            // that rounding noise.
            if (!a.attribute().equals(e.attribute())
                    || !a.modifier().id().equals(e.modifier().id())
                    || a.modifier().operation() != e.modifier().operation()
                    || a.slot() != e.slot()
                    || !a.display().equals(e.display())
                    || (float) a.modifier().amount() != (float) e.modifier().amount()) return false;
        }
        return true;
    }

    private static JsonElement json(Path path) throws Exception {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static void preserved(ItemStack before, ItemStack after) {
        if (!before.get(DataComponents.CUSTOM_NAME).equals(after.get(DataComponents.CUSTOM_NAME))
                || !before.get(DataComponents.DAMAGE).equals(after.get(DataComponents.DAMAGE))
                || !before.get(DataComponents.ENCHANTMENTS)
                        .equals(after.get(DataComponents.ENCHANTMENTS))
                || !before.get(DataComponents.TRIM).equals(after.get(DataComponents.TRIM)))
            throw new AssertionError("Equipment customization/damage was lost");
    }
}
