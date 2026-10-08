"""Emit rules only for completed-result adapters; the full target remains in the manifest."""
import json
import runpy
import sys
from pathlib import Path

helper = runpy.run_path(str(Path(__file__).with_name("sync-industry-definitions.py")))
data = helper["DATA"] / "contribution"
manifest = json.loads((data / "definition_manifest.json").read_text(encoding="utf-8"))
path = data / "game_event_industry_map.json"
rules = {e["event_id"]: e for e in json.loads(path.read_text())["events"]}
added = {
    "device/piston_move", "auto/hopper_transfer", "auto/dispenser_fire", "auto/dropper_fire",
    "process/stonecutting", "modify/loom",
    "entity/honey", "modify/compost", "collect/lava_bucket", "food/throw_potion",
    "process/campfire_cook", "auto/tnt_explode", "entity/name_tag",
    "process/furnace/fuel", "modify/anvil_enchant", "modify/anvil_rename",
    "modify/strip_log", "modify/bone_meal", "entity/kill", "combat/shield_block",
    "combat/raid", "culture/pot_flower", "culture/bell", "culture/cauldron", "culture/clean",
    "combat/used_totem", "combat/hero_of_village", "culture/map_draw", "tech/ender_eye",
    "logistics/distance/happy_ghast", "logistics/distance/nautilus",
}
for entry in manifest["events"]:
    identity = entry["event_id"]
    if identity not in rules and identity.removeprefix("contribution:") not in added:
        continue
    if ";" in entry["industry"] or "；" in entry["industry"]:
        continue
    rules[identity] = {k: entry[k] for k in ["event_id", "industry", "measure", "unit_size", "unit_value"]}
for industry in helper["INDUSTRIES"].values():
    identity = "contribution:craft/" + industry
    rules[identity] = dict(event_id=identity, industry="contribution:"+industry, measure="output_item", unit_size=64, unit_value=1)
rules["contribution:modify/smithing_equipment"] = dict(event_id="contribution:modify/smithing_equipment", industry="contribution:military_food_medicine", measure="modified_item", unit_size=1, unit_value=1)
for event, industry in [("grindstone", "processing_manufacturing"), ("grindstone_disenchant", "technology_magic")]:
    identity = "contribution:modify/" + event
    rules[identity] = dict(event_id=identity, industry="contribution:"+industry, measure="modified_item", unit_size=1, unit_value=1)
sys.stdout.reconfigure(encoding="utf-8")
print("*** Begin Patch")
helper["patch_file"](path, {"schema_version": 1, "events": sorted(rules.values(), key=lambda e: e["event_id"])})
print("*** End Patch")
