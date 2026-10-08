package cn.contribution.items;

import cn.contribution.shop.ItemStackSpec;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public final class WeaponSkinChecks {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(
                WeaponSkins.interactionSuccess(true)
                        == net.minecraft.world.InteractionResult.SUCCESS,
                "client must send Fabric UseItem packet");
        check(
                WeaponSkins.interactionSuccess(false)
                        == net.minecraft.world.InteractionResult.SUCCESS_SERVER,
                "server-authoritative interaction result");
        var lookup =
                VanillaRegistries.createReloadableLookup(VanillaRegistries.createWorldLookup());
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(lookup)
                .forEach(
                        net.minecraft.core.component.DataComponentInitializers.PendingComponents
                                ::apply);
        net.minecraft.client.renderer.item.ItemModels.bootstrap();
        net.minecraft.client.renderer.item.properties.select.SelectItemModelProperties.bootstrap();
        verifyAssetReferences();
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        boolean emptyRejected = false;
        try {
            new WeaponSkinPayload("test", "test", List.of());
        } catch (IllegalArgumentException expected) {
            emptyRejected = true;
        }
        check(emptyRejected, "empty skin payload rejected");
        Path spearRoot =
                Path.of("src/main/resources/assets/contribution/models/item/weapon_skin/spear");
        var spearTemplate =
                JsonParser.parseString(Files.readString(spearRoot.resolve("botanic_piercer.json")))
                        .getAsJsonObject();
        try (var models = Files.list(spearRoot)) {
            for (Path model :
                    models.filter(
                                    p ->
                                            p.toString().endsWith(".json")
                                                    && !p.getFileName()
                                                            .toString()
                                                            .equals("static.json"))
                            .toList()) {
                var json = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
                check(
                        json.get("parent").getAsString().equals("item/generated"),
                        "authored spear parent " + model);
                check(
                        json.get("display").equals(spearTemplate.get("display")),
                        "authored spear display " + model);
            }
        }
        var fingerprints =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "src/main/resources/contribution/weapon-skin-assets.json")))
                        .getAsJsonObject()
                        .getAsJsonObject("files");
        for (var entry : fingerprints.entrySet()) {
            String actual =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    Files.readAllBytes(
                                                            Path.of(
                                                                    "src/main/resources/assets/"
                                                                            + entry.getKey()))));
            check(
                    actual.equals(entry.getValue().getAsString()),
                    "approved user asset changed " + entry.getKey());
        }
        for (int w : new int[] {160, 240, 320, 640, 960, 1920})
            for (int h : new int[] {120, 180, 240, 480, 1080}) {
                var l = cn.contribution.client.WeaponSkinLayout.of(w, h);
                check(
                        l.capacity() > 0
                                && l.left() >= 0
                                && l.left() + l.columns() * l.cardWidth() <= w
                                && l.top() + l.rows() * l.cardHeight() <= h - 28,
                        "responsive skin grid bounds " + w + "x" + h);
            }
        check(
                cn.contribution.client.WeaponSkinLayout.of(480, 250).rows() >= 2,
                "normal GUI uses multiple rows");
        check(
                cn.contribution.client.WeaponSkinLayout.of(480, 120).rows() == 1,
                "short GUI uses one row");
        check(
                cn.contribution.client.WeaponSkinLayout.of(1920, 1080).cardHeight() <= 120,
                "cards never stretch vertically");
        for (String type : new String[] {"sword", "axe", "pickaxe", "hoe", "shovel", "spear"}) {
            Path definition =
                    Path.of(
                            "src/main/resources/assets/contribution/items/weapon_skin/"
                                    + type
                                    + ".json");
            var json = JsonParser.parseString(Files.readString(definition));
            net.minecraft.client.renderer.item.ClientItem.CODEC.parse(ops, json).getOrThrow();
            var enlarged =
                    JsonParser.parseString(
                            Files.readString(
                                    Path.of(
                                            "src/main/resources/assets/contribution/items/weapon_skin/preview/"
                                                    + type
                                                    + ".json")));
            net.minecraft.client.renderer.item.ClientItem.CODEC.parse(ops, enlarged).getOrThrow();
            check(
                    json.toString().contains("minecraft:item_name")
                            && !json.toString().contains("custom_name"),
                    "item_name selector");
            ItemStack skin = new ItemStack(Items.FIREWORK_STAR);
            skin.set(
                    DataComponents.ITEM_MODEL,
                    Identifier.fromNamespaceAndPath("contribution", "weapon_skin/" + type));
            var family = WeaponSkins.family(skin);
            ItemStack given =
                    ItemStackSpec.parse(
                            lookup,
                            "minecraft:firework_star[item_model='contribution:weapon_skin/"
                                    + type
                                    + "',item_name='"
                                    + family.initialName()
                                    + "']");
            check(WeaponSkins.eligible(given), "native give item is eligible " + type);
            var previewDialog = WeaponSkins.previewDialog(given, family, "test-token", 0);
            var itemBody =
                    (net.minecraft.server.dialog.body.ItemBody)
                            previewDialog.common().body().get(0);
            check(
                    itemBody.description().isEmpty()
                            && itemBody.width() == 96
                            && itemBody.height() == 96,
                    "centered Dialog preview, separate description");
            check(
                    itemBody.item()
                            .create()
                            .get(DataComponents.ITEM_MODEL)
                            .getPath()
                            .equals("weapon_skin/preview/" + type),
                    "isolated enlarged preview model");
            check(
                    previewDialog != null && WeaponSkins.eligible(given),
                    "preview does not consume or lock starter " + type);
            check(
                    WeaponSkins.previewDialog(given, family, "test-token", 999) == null,
                    "invalid preview rejected");
            var previewJson =
                    net.minecraft.server.dialog.Dialog.DIRECT_CODEC
                            .encodeStart(ops, previewDialog)
                            .getOrThrow();
            net.minecraft.server.dialog.Dialog.DIRECT_CODEC.parse(ops, previewJson).getOrThrow();
            ItemStack converted = WeaponSkins.select(given, given.copy(), family, 0);
            check(
                    converted
                            .get(DataComponents.ATTRIBUTE_MODIFIERS)
                            .equals(
                                    converted
                                            .getItem()
                                            .components()
                                            .get(DataComponents.ATTRIBUTE_MODIFIERS)),
                    "native golden weapon attributes " + type);
            check(
                    converted.getMaxStackSize() == 1 && converted.getMaxDamage() > 0,
                    "native golden durability and stack limit " + type);
            ItemStack legacy = new ItemStack(Items.WOODEN_SWORD);
            var upgradeRecipe =
                    (net.minecraft.world.item.crafting.SmithingTransformRecipe)
                            net.minecraft.world.item.crafting.Recipe.DIRECT_CODEC
                                    .parse(
                                            ops,
                                            JsonParser.parseString(
                                                    Files.readString(
                                                            Path.of(
                                                                    "src/main/resources/data/contribution/recipe/items/reinforcement/golden_"
                                                                            + type
                                                                            + ".json"))))
                                    .getOrThrow();
            converted.set(
                    DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(
                            List.of(Component.literal("保留自定义描述"))));
            var upgraded =
                    upgradeRecipe.assemble(
                            new net.minecraft.world.item.crafting.SmithingRecipeInput(
                                    new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                                    converted,
                                    new ItemStack(
                                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                                    .getValue(
                                                            Identifier.withDefaultNamespace(
                                                                    "netherite_" + type)))));
            SmithingUpgradeLore.merge(converted, upgraded, upgraded.get(DataComponents.LORE));
            for (var component :
                    List.of(
                            DataComponents.ITEM_NAME,
                            DataComponents.ITEM_MODEL,
                            DataComponents.CUSTOM_DATA))
                check(
                        java.util.Objects.equals(converted.get(component), upgraded.get(component)),
                        "reinforcement preserves skin component " + component + " " + type);
            check(!WeaponSkins.eligible(upgraded), "reinforced skin stays locked " + type);
            check(
                    upgraded.get(DataComponents.LORE).lines().size() == 2
                            && upgraded.get(DataComponents.LORE)
                                    .lines()
                                    .getFirst()
                                    .getString()
                                    .equals("保留自定义描述")
                            && upgraded.get(DataComponents.LORE)
                                    .lines()
                                    .getLast()
                                    .getString()
                                    .equals("下界合金强化"),
                    "reinforcement tooltip preserves lore " + type);
            legacy.set(DataComponents.ITEM_MODEL, skin.get(DataComponents.ITEM_MODEL));
            legacy.set(DataComponents.ITEM_NAME, Component.literal("未定型" + family.label()));
            check(WeaponSkins.eligible(legacy), "legacy unselected item remains usable " + type);
            skin.set(DataComponents.ITEM_NAME, Component.literal(family.initialName()));
            skin.set(DataComponents.CUSTOM_NAME, Component.literal("铁砧名不选择外观"));
            skin.set(DataComponents.DAMAGE, 7);
            skin.remove(DataComponents.ATTRIBUTE_MODIFIERS);
            check(WeaponSkins.eligible(skin), "blank is eligible");
            String spec = ItemStackSpec.serialize(lookup, skin);
            ItemStack restored = ItemStackSpec.parse(lookup, spec);
            check(
                    ItemStack.isSameItemSameComponents(skin, restored),
                    "hand snapshot roundtrip incl removals");
            ItemStack stale = skin.copy();
            stale.set(DataComponents.DAMAGE, 8);
            check(WeaponSkins.select(skin, stale, family, 0).isEmpty(), "stale item rejected");
            check(
                    WeaponSkins.select(skin, skin.copy(), family, 999).isEmpty(),
                    "invalid choice rejected");
            ItemStack before = skin.copy();
            skin = WeaponSkins.select(skin, skin.copy(), family, 0);
            check(!skin.isEmpty(), "one-shot selection");
            check(
                    before.is(Items.FIREWORK_STAR)
                            && before.get(DataComponents.ITEM_NAME)
                                    .getString()
                                    .equals(family.initialName()),
                    "input snapshot unchanged");
            check(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(skin.getItem())
                            .getPath()
                            .equals("golden_" + type),
                    "correct golden base " + type);
            check(!WeaponSkins.eligible(skin), "selected cannot reopen");
            check(
                    skin.get(DataComponents.DAMAGE) == 7
                            && skin.get(DataComponents.CUSTOM_NAME).getString().equals("铁砧名不选择外观"),
                    "other components preserved");
            skin.set(DataComponents.CUSTOM_NAME, Component.literal("新铁砧名"));
            check(
                    !WeaponSkins.eligible(
                            ItemStackSpec.parse(lookup, ItemStackSpec.serialize(lookup, skin))),
                    "anvil and reload cannot unlock");
        }
        check(
                !WeaponSkins.eligible(new ItemStack(Items.WOODEN_SWORD)),
                "ordinary weapon untouched");
        System.out.println(
                "WEAPON_SKINS_PASS: six families, native models, item_name, one-shot lock, stale"
                        + " item, hand serialization and removals");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static com.google.gson.JsonObject effectiveModel(
            Path file, Path modelRoot, String namespace, java.util.Set<Path> seen)
            throws Exception {
        check(seen.add(file), "cyclic inherited model " + file);
        var own = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!own.has("parent")) return own;
        String ref = own.get("parent").getAsString();
        if (!ref.contains(":")) ref = "minecraft:" + ref;
        if (!ref.startsWith(namespace + ":")) return own;
        Path parentFile = modelRoot.resolve(ref.substring(ref.indexOf(':') + 1) + ".json");
        if (!Files.exists(parentFile)) return own;
        var parent = effectiveModel(parentFile, modelRoot, namespace, seen).deepCopy();
        String terminal = parent.has("parent") ? parent.get("parent").getAsString() : null;
        for (var entry : own.entrySet()) {
            if (entry.getKey().equals("parent")) continue;
            if ((entry.getKey().equals("textures") || entry.getKey().equals("display"))
                    && parent.has(entry.getKey())) {
                var merged = parent.getAsJsonObject(entry.getKey()).deepCopy();
                entry.getValue()
                        .getAsJsonObject()
                        .entrySet()
                        .forEach(e -> merged.add(e.getKey(), e.getValue()));
                parent.add(entry.getKey(), merged);
            } else parent.add(entry.getKey(), entry.getValue());
        }
        if (terminal == null) parent.remove("parent");
        else parent.addProperty("parent", terminal);
        return parent;
    }

    private static void verifyAssetReferences() throws Exception {
        Path root = Path.of("src/main/resources/assets/contribution");
        try (var files = Files.walk(root.resolve("models/item/weapon_skin"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                String parent = json.has("parent") ? json.get("parent").getAsString() : "";
                String relative =
                        root.resolve("models/item/weapon_skin")
                                .relativize(path)
                                .toString()
                                .replace('\\', '/');
                try (var reader = Files.newBufferedReader(path)) {
                    net.minecraft.client.resources.model.cuboid.CuboidModel.fromStream(reader);
                }
                if (!relative.startsWith("base/")
                        && !relative.startsWith("preview/")
                        && !relative.startsWith("spear/")) {
                    Path originalRoot = Path.of("../datapack/assets/minecraft/models");
                    Path original = originalRoot.resolve("item/" + relative);
                    if (Files.exists(original)) {
                        var expected =
                                effectiveModel(
                                        original, originalRoot, "minecraft", new HashSet<>());
                        var actual =
                                effectiveModel(
                                        path,
                                        root.resolve("models"),
                                        "contribution",
                                        new HashSet<>());
                        expected.remove("textures");
                        actual.remove("textures");
                        for (var model : List.of(expected, actual)) {
                            if (model.has("parent")
                                    && !model.get("parent").getAsString().contains(":"))
                                model.addProperty(
                                        "parent", "minecraft:" + model.get("parent").getAsString());
                        }
                        check(
                                expected.equals(actual),
                                "effective original geometry/display changed " + relative);
                    }
                }
                if (parent.startsWith("contribution:"))
                    check(
                            Files.exists(root.resolve("models/" + parent.substring(13) + ".json")),
                            "missing parent " + path);
                if (json.has("textures"))
                    for (var texture : json.getAsJsonObject("textures").entrySet()) {
                        String ref = texture.getValue().getAsString();
                        if (ref.startsWith("contribution:"))
                            check(
                                    Files.exists(
                                            root.resolve("textures/" + ref.substring(13) + ".png")),
                                    "missing texture " + path);
                    }
            }
        }
        try (var vanilla =
                new java.util.zip.ZipFile(
                        "../.gradle-home/caches/fabric-loom/26.3/minecraft-client.jar")) {
            for (String type : new String[] {"sword", "axe", "pickaxe", "hoe", "shovel", "spear"}) {
                var definition =
                        JsonParser.parseString(
                                Files.readString(
                                        root.resolve("items/weapon_skin/" + type + ".json")));
                verifyBranches(root, vanilla, definition);
                verifyBranches(
                        root,
                        vanilla,
                        JsonParser.parseString(
                                Files.readString(
                                        root.resolve(
                                                "items/weapon_skin/preview/" + type + ".json"))));
                if (type.equals("spear")) {
                    for (var entry :
                            definition
                                    .getAsJsonObject()
                                    .getAsJsonObject("model")
                                    .getAsJsonArray("cases")) {
                        var selected = entry.getAsJsonObject().getAsJsonObject("model");
                        check(
                                selected.get("property")
                                        .getAsString()
                                        .equals("minecraft:display_context"),
                                "spear context selector");
                        String gui =
                                selected.getAsJsonArray("cases")
                                        .get(0)
                                        .getAsJsonObject()
                                        .getAsJsonObject("model")
                                        .get("model")
                                        .getAsString();
                        String hand =
                                selected.getAsJsonObject("fallback").get("model").getAsString();
                        check(
                                gui.contains("/spear/gui/") && !gui.equals(hand),
                                "separate spear GUI and hand models");
                    }
                }
            }
        }
    }

    private static void verifyBranches(
            Path root, java.util.zip.ZipFile vanilla, com.google.gson.JsonElement value)
            throws Exception {
        if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) verifyBranches(root, vanilla, child);
        } else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("model") && entry.getValue().isJsonPrimitive())
                    verifyModelChain(root, vanilla, entry.getValue().getAsString());
                else verifyBranches(root, vanilla, entry.getValue());
            }
        }
    }

    private static void verifyModelChain(Path root, java.util.zip.ZipFile vanilla, String ref)
            throws Exception {
        var seen = new java.util.HashSet<String>();
        var textures = new java.util.HashMap<String, String>();
        var aliases = new java.util.ArrayList<String>();
        while (!ref.equals("minecraft:builtin/generated")
                && !ref.equals("minecraft:builtin/entity")) {
            check(seen.add(ref), "cyclic parent " + ref);
            String[] id = ref.split(":", 2);
            String text;
            if (id[0].equals("contribution")) {
                text = Files.readString(root.resolve("models/" + id[1] + ".json"));
            } else {
                check(id[0].equals("minecraft"), "unknown model namespace " + ref);
                var entry = vanilla.getEntry("assets/minecraft/models/" + id[1] + ".json");
                check(entry != null, "non-vanilla parent left in minecraft namespace " + ref);
                try (var stream = vanilla.getInputStream(entry)) {
                    text =
                            new String(
                                    stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            net.minecraft.client.resources.model.cuboid.CuboidModel.fromStream(
                    new java.io.StringReader(text));
            var model = JsonParser.parseString(text).getAsJsonObject();
            if (model.has("textures"))
                for (var entry : model.getAsJsonObject("textures").entrySet())
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
            if (model.has("elements"))
                for (var element : model.getAsJsonArray("elements"))
                    for (var face : element.getAsJsonObject().getAsJsonObject("faces").entrySet())
                        aliases.add(face.getValue().getAsJsonObject().get("texture").getAsString());
            if (!model.has("parent")) break;
            ref = model.get("parent").getAsString();
            if (!ref.contains(":")) ref = "minecraft:" + ref;
        }
        aliases.addAll(textures.values());
        for (String texture : aliases) {
            var visited = new java.util.HashSet<String>();
            while (texture.startsWith("#")) {
                check(visited.add(texture), "cyclic texture alias " + texture);
                String next = textures.get(texture.substring(1));
                check(next != null, "missing texture alias " + texture);
                texture = next;
            }
            if (!texture.contains(":")) texture = "minecraft:" + texture;
            String[] id = texture.split(":", 2);
            if (id[0].equals("contribution")) {
                Path png = root.resolve("textures/" + id[1] + ".png");
                check(javax.imageio.ImageIO.read(png.toFile()) != null, "invalid PNG " + png);
            } else
                check(
                        vanilla.getEntry("assets/" + id[0] + "/textures/" + id[1] + ".png") != null,
                        "missing native texture " + texture);
        }
    }
}
