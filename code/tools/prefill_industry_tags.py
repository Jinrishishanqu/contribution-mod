#!/usr/bin/env python3
"""Prefill mutually-exclusive 26.3 craft/place/mine industry tags.

The Minecraft registry catalog and placement map are generated from the pinned
26.3 client. Craftability is read from the same client JAR, so non-crafting
recipes such as smelting and stonecutting are not mistaken for player crafting.
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from collections import Counter
from pathlib import Path


INDUSTRIES = (
    "construction_landscaping",
    "mining_metallurgy",
    "energy_chemical",
    "processing_manufacturing",
    "technology_magic",
    "agriculture_forestry_livestock_fishery",
    "military_food_medicine",
    "circulation_services",
    "culture_education_livelihood",
)

CONSTRUCTION = INDUSTRIES[0]
MINING = INDUSTRIES[1]
ENERGY = INDUSTRIES[2]
PROCESSING = INDUSTRIES[3]
TECHNOLOGY = INDUSTRIES[4]
AGRICULTURE = INDUSTRIES[5]
MILITARY = INDUSTRIES[6]
CIRCULATION = INDUSTRIES[7]
CULTURE = INDUSTRIES[8]


def path(identifier: str) -> str:
    return identifier.split(":", 1)[1]


def contains_any(value: str, fragments: tuple[str, ...]) -> bool:
    return any(fragment in value for fragment in fragments)


def ends_any(value: str, suffixes: tuple[str, ...]) -> bool:
    return value.endswith(suffixes)


FOODS = {
    "apple", "baked_potato", "beef", "beetroot", "beetroot_soup", "bread",
    "cake", "carrot", "chicken", "chorus_fruit", "cod", "cooked_beef",
    "cooked_chicken", "cooked_cod", "cooked_mutton", "cooked_porkchop",
    "cooked_rabbit", "cooked_salmon", "cookie", "dried_kelp", "dried_kelp_block",
    "enchanted_golden_apple", "glow_berries", "golden_apple", "golden_carrot",
    "honey_bottle", "melon_slice", "mushroom_stew", "mutton", "porkchop",
    "potato", "pufferfish", "pumpkin_pie", "rabbit", "rabbit_stew", "rotten_flesh",
    "salmon", "spider_eye", "suspicious_stew", "sweet_berries", "tropical_fish",
}

WEAPON_ARMOR_FRAGMENTS = (
    "_sword", "_spear", "_helmet", "_chestplate", "_leggings", "_boots",
    "_horse_armor", "wolf_armor", "arrow", "bow", "crossbow", "shield",
    "trident", "mace", "wind_charge", "tnt",
)

TRANSPORT_STORAGE_FRAGMENTS = (
    "_boat", "_raft", "minecart", "rail", "chest", "barrel", "hopper",
    "shulker_box", "bundle", "saddle", "harness", "lead",
)

CULTURE_FRAGMENTS = (
    "book", "paper", "map", "painting", "banner", "bed", "candle", "cushion",
    "decorated_pot", "flower_pot", "jukebox", "note_block", "music_disc",
    "goat_horn", "clock", "compass", "spyglass", "name_tag", "lectern",
    "cartography_table", "loom", "writing", "written", "shelf",
)

TECH_FRAGMENTS = (
    "redstone", "repeater", "comparator", "observer", "piston", "dispenser",
    "dropper", "daylight_detector", "sculk_sensor", "calibrated_sculk_sensor",
    "lightning_rod", "copper_bulb", "crafter", "target", "lever", "tripwire_hook",
    "enchanting_table", "ender_chest", "ender_eye", "eye_of_ender", "end_crystal",
    "beacon", "conduit", "lodestone", "respawn_anchor",
)

ENERGY_FRAGMENTS = (
    "_dye", "torch", "lantern", "campfire", "fire_charge", "firework_star",
    "firework_rocket", "blaze_powder", "magma_cream", "glowstone_dust", "coal_block",
)

AGRICULTURE_FRAGMENTS = (
    "_seeds", "sapling", "propagule", "wheat", "hay_block", "bone_meal",
    "fishing_rod", "carrot_on_a_stick", "warped_fungus_on_a_stick", "beehive",
    "bee_nest", "composter", "flowering_azalea", "cocoa_beans", "_hoe",
)

PROCESSING_FRAGMENTS = (
    "crafting_table", "furnace", "smoker", "stonecutter", "grindstone", "anvil",
    "smithing_table", "fletching_table", "cauldron", "bucket", "shears", "brush",
    "flint_and_steel", "_pickaxe", "_axe", "_shovel", "_hoe", "fishing_rod",
)

RESOURCE_BLOCKS = (
    "coal_block", "raw_iron_block", "raw_copper_block", "raw_gold_block",
    "iron_block", "copper_block", "gold_block", "diamond_block", "emerald_block",
    "lapis_block", "redstone_block", "netherite_block", "amethyst_block",
)


def classify_craft(identifier: str, category: str | None) -> str | None:
    name = path(identifier)

    if ends_any(name, ("_smithing_template",)):
        return MILITARY
    if name in {"armor_stand"}:
        return MILITARY
    if name in {"brewing_stand", "fermented_spider_eye", "glass_bottle", "glistering_melon_slice"}:
        return MILITARY
    if name in {"diamond", "emerald", "lapis_lazuli", "raw_copper", "raw_gold", "raw_iron"}:
        return MINING
    if name in {"slime_ball", "resin_clump"}:
        return ENERGY
    if name in {"leather", "sugar", "honeycomb_block", "shears"}:
        return AGRICULTURE
    if name in {"creaking_heart"}:
        return TECHNOLOGY
    if name in {"end_rod"}:
        return ENERGY
    if name in {"armor_stand"}:
        return MILITARY
    if name in {"item_frame", "glow_item_frame"}:
        return CULTURE
    if name in {"stick"}:
        return PROCESSING
    if name in {"golden_dandelion"}:
        return CONSTRUCTION
    if contains_any(name, TRANSPORT_STORAGE_FRAGMENTS):
        return CIRCULATION
    if name in FOODS or contains_any(name, WEAPON_ARMOR_FRAGMENTS):
        return MILITARY
    if contains_any(name, CULTURE_FRAGMENTS):
        return CULTURE
    if contains_any(name, TECH_FRAGMENTS) or ends_any(name, ("_button", "_pressure_plate")):
        return TECHNOLOGY
    if contains_any(name, ENERGY_FRAGMENTS) or name in {"charcoal", "coal", "lava_bucket"}:
        return ENERGY
    if contains_any(name, AGRICULTURE_FRAGMENTS):
        return AGRICULTURE
    if name in RESOURCE_BLOCKS or ends_any(name, ("_ingot", "_nugget")):
        return MINING
    if contains_any(name, PROCESSING_FRAGMENTS):
        return PROCESSING

    if category == "food":
        return MILITARY
    if category == "building":
        return CONSTRUCTION
    if category == "redstone":
        return TECHNOLOGY
    if category == "equipment":
        return PROCESSING

    # Miscellaneous recipes are intentionally conservative. Natural drops,
    # spawn eggs, command-only objects and uncertain one-off items remain out.
    if ends_any(name, (
        "_planks", "_wood", "_hyphae", "_bricks", "_tiles", "_wall", "_carpet",
        "_stained_glass_pane", "_bars", "_chain",
    )) or name in {"bamboo_mosaic", "glass_pane", "iron_bars", "iron_chain", "ladder", "scaffolding", "snow"}:
        return CONSTRUCTION
    if ends_any(name, ("_sign", "_hanging_sign", "_door", "_trapdoor", "_fence", "_fence_gate")):
        return CONSTRUCTION
    return None


def classify_place(item_id: str, block_id: str) -> str | None:
    item = path(item_id)
    block = path(block_id)
    name = f"{item} {block}"

    if block in {"beehive", "bee_nest"}:
        return AGRICULTURE

    if contains_any(name, TRANSPORT_STORAGE_FRAGMENTS):
        return CIRCULATION
    if contains_any(name, ("tnt", "target", "armor_stand")):
        return MILITARY
    if item in FOODS or item in {"brewing_stand", "cake"}:
        return MILITARY
    if contains_any(name, CULTURE_FRAGMENTS) or ends_any(block, ("_skull", "_head", "_wall_skull", "_wall_head")):
        return CULTURE
    if contains_any(name, TECH_FRAGMENTS) or ends_any(block, ("_button", "_pressure_plate")):
        return TECHNOLOGY
    if contains_any(name, ENERGY_FRAGMENTS) or block in {"fire", "soul_fire"}:
        return ENERGY
    if block == "powder_snow":
        return CONSTRUCTION
    if contains_any(name, PROCESSING_FRAGMENTS):
        return PROCESSING
    if item in RESOURCE_BLOCKS or block in RESOURCE_BLOCKS or block.endswith("_ore"):
        return MINING
    if block in {"ancient_debris", "cinnabar", "sulfur", "potent_sulfur", "sulfur_spike"}:
        return MINING
    if contains_any(name, ("crop", "wheat", "carrot", "potato", "beetroot", "cocoa", "melon", "pumpkin")):
        return AGRICULTURE
    if contains_any(name, ("sapling", "propagule", "mushroom", "fungus", "bamboo", "sugar_cane", "cactus")):
        return AGRICULTURE

    # Ornamentals and all ordinary building pieces belong to 土建园林.
    if contains_any(name, (
        "flower", "tulip", "orchid", "allium", "bluet", "dandelion", "poppy",
        "lily", "peony", "rose", "sunflower", "lilac", "azalea", "leaves",
        "grass", "fern", "bush", "vine", "moss", "coral", "dripleaf",
    )):
        return CONSTRUCTION
    if item_id.startswith("minecraft:") and block_id.startswith("minecraft:"):
        if contains_any(name, ("command_block", "structure_block", "jigsaw", "barrier", "light")):
            return None
        return CONSTRUCTION
    return None


def classify_mine(identifier: str) -> str | None:
    name = path(identifier)

    if name in {"bedrock", "barrier", "structure_void", "end_portal", "end_gateway", "nether_portal"}:
        return None
    if ends_any(name, ("_ore",)) or name == "ancient_debris":
        return MINING
    natural_minerals = {
        "stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite",
        "basalt", "blackstone", "netherrack", "crimson_nylium", "warped_nylium",
        "end_stone", "obsidian", "crying_obsidian", "sand", "red_sand", "gravel",
        "suspicious_sand", "suspicious_gravel", "clay", "terracotta", "mud", "dirt",
        "coarse_dirt", "rooted_dirt", "dirt_path", "podzol", "mycelium", "soul_soil",
        "soul_sand", "magma_block", "dripstone_block", "pointed_dripstone", "cinnabar",
        "sulfur", "potent_sulfur", "sulfur_spike", "glowstone", "gilded_blackstone",
    }
    if name in natural_minerals or name.startswith("infested_") or (name.endswith("_terracotta") and "glazed" not in name):
        return MINING
    if ends_any(name, ("_log", "_wood", "_leaves", "_sapling", "_stem", "_hyphae", "_propagule")):
        return AGRICULTURE
    if name in {
        "wheat", "carrots", "potatoes", "beetroots", "cocoa", "pumpkin", "melon",
        "cactus", "cactus_flower", "sugar_cane", "bamboo", "bamboo_sapling", "kelp",
        "kelp_plant", "seagrass", "tall_seagrass", "nether_wart", "chorus_flower",
        "chorus_plant", "brown_mushroom", "red_mushroom", "brown_mushroom_block",
        "red_mushroom_block", "mushroom_stem", "nether_wart_block", "warped_wart_block",
        "shroomlight", "vine", "twisting_vines", "twisting_vines_plant", "weeping_vines",
        "weeping_vines_plant", "crimson_roots", "warped_roots", "hanging_roots",
        "nether_sprouts", "shelf_mushroom", "mangrove_roots", "muddy_mangrove_roots",
        "attached_melon_stem", "attached_pumpkin_stem", "melon_stem", "pumpkin_stem",
    }:
        return AGRICULTURE
    if ends_any(name, ("_tulip", "_orchid", "_flower", "_bush", "_vines", "_vines_plant")):
        return AGRICULTURE
    if name in {
        "dandelion", "poppy", "allium", "azure_bluet", "cornflower", "lily_of_the_valley",
        "rose_bush", "sunflower", "lilac", "peony", "torchflower", "torchflower_crop",
        "pitcher_plant", "pitcher_crop", "open_eyeblossom", "closed_eyeblossom",
        "pink_petals", "wildflowers", "short_grass", "tall_grass", "fern", "large_fern",
        "dead_bush", "leaf_litter", "moss_block", "pale_moss_block", "moss_carpet",
        "pale_moss_carpet", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf",
        "spore_blossom", "sweet_berry_bush", "cave_vines", "cave_vines_plant",
    }:
        return AGRICULTURE
    if contains_any(name, ("amethyst_cluster", "amethyst_bud", "sculk")):
        return TECHNOLOGY
    return None


def single_item_conversion(recipe: dict) -> tuple[str, int, str, int] | None:
    result = recipe.get("result")
    if not isinstance(result, dict) or "id" not in result:
        return None
    recipe_type = recipe.get("type")
    ingredients: list[str] = []
    if recipe_type == "minecraft:crafting_shapeless":
        raw_ingredients = recipe.get("ingredients", [])
        if not raw_ingredients or not all(isinstance(value, str) for value in raw_ingredients):
            return None
        ingredients = list(raw_ingredients)
    elif recipe_type == "minecraft:crafting_shaped":
        key = recipe.get("key", {})
        pattern = recipe.get("pattern", [])
        for row in pattern:
            for symbol in row:
                if symbol == " ":
                    continue
                value = key.get(symbol)
                if not isinstance(value, str):
                    return None
                ingredients.append(value)
    else:
        return None
    if not ingredients or len(set(ingredients)) != 1:
        return None
    return ingredients[0], len(ingredients), result["id"], int(result.get("count", 1))


def read_crafting_outputs(client_jar: Path) -> tuple[dict[str, str | None], set[str]]:
    outputs: dict[str, str | None] = {}
    conversions: list[tuple[str, int, str, int]] = []
    with zipfile.ZipFile(client_jar) as archive:
        for name in archive.namelist():
            if not name.startswith("data/minecraft/recipe/") or not name.endswith(".json"):
                continue
            recipe = json.loads(archive.read(name))
            recipe_type = recipe.get("type", "")
            if not recipe_type.startswith("minecraft:crafting_"):
                continue
            result = recipe.get("result")
            if not isinstance(result, dict) or "id" not in result:
                continue
            item_id = result["id"]
            category = recipe.get("category")
            existing = outputs.get(item_id)
            if existing is None or category is not None:
                outputs[item_id] = category
            conversion = single_item_conversion(recipe)
            if conversion is not None:
                conversions.append(conversion)

    reversible: set[str] = set()
    for input_id, input_count, output_id, output_count in conversions:
        for reverse_input, reverse_input_count, reverse_output, reverse_output_count in conversions:
            if reverse_input != output_id or reverse_output != input_id:
                continue
            if output_count * reverse_output_count == input_count * reverse_input_count:
                reversible.add(input_id)
                reversible.add(output_id)
    return outputs, reversible


def add_unique(assignments: dict[str, dict[str, str]], kind: str, identifier: str, industry: str) -> None:
    previous = assignments[kind].get(identifier)
    if previous is not None and previous != industry:
        raise ValueError(f"{kind} identifier {identifier} was assigned to both {previous} and {industry}")
    assignments[kind][identifier] = industry


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    parser.add_argument("--resources", type=Path, default=Path("src/main/resources"))
    parser.add_argument("--report", type=Path, default=Path("build/reports/industry-tag-prefill.json"))
    args = parser.parse_args()

    catalog_path = args.resources / "data/contribution/contribution/registry_catalog.json"
    placement_path = args.resources / "data/contribution/contribution/placement_map.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    placement = json.loads(placement_path.read_text(encoding="utf-8"))["mappings"]
    craft_outputs, reversible_craft_items = read_crafting_outputs(args.minecraft_jar)

    assignments: dict[str, dict[str, str]] = {"craft": {}, "place": {}, "mine": {}}
    for item_id, category in craft_outputs.items():
        if item_id in reversible_craft_items:
            continue
        industry = classify_craft(item_id, category)
        if industry is not None:
            add_unique(assignments, "craft", item_id, industry)

    for item_id, block_ids in placement.items():
        for block_id in block_ids:
            industry = classify_place(item_id, block_id)
            if industry is not None:
                add_unique(assignments, "place", block_id, industry)

    for block_id in catalog["blocks"]:
        industry = classify_mine(block_id)
        if industry is not None:
            add_unique(assignments, "mine", block_id, industry)

    tag_root = args.resources / "data/contribution/tags"
    for kind in ("craft", "place", "mine"):
        target_root = tag_root / ("item" if kind == "craft" else "block")
        for industry in INDUSTRIES:
            values = sorted(
                identifier for identifier, assigned in assignments[kind].items() if assigned == industry
            )
            target = target_root / f"{kind}_{industry}.json"
            target.write_text(
                json.dumps({"replace": False, "values": values}, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

    report = {
        "minecraft_version": catalog["minecraft_version"],
        "counts": {
            kind: dict(sorted(Counter(mapping.values()).items()))
            for kind, mapping in assignments.items()
        },
        "unclassified_craft_outputs": sorted(
            set(craft_outputs) - set(assignments["craft"]) - reversible_craft_items
        ),
        "excluded_reversible_craft_items": sorted(reversible_craft_items),
        "unclassified_place_blocks": sorted(set(catalog["blocks"]) - set(assignments["place"])),
        "unclassified_mine_blocks": sorted(set(catalog["blocks"]) - set(assignments["mine"])),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for kind in ("craft", "place", "mine"):
        print(f"{kind}: {len(assignments[kind])} entries")
        for industry in INDUSTRIES:
            count = sum(assigned == industry for assigned in assignments[kind].values())
            print(f"  {industry}: {count}")


if __name__ == "__main__":
    main()
