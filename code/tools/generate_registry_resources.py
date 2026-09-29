#!/usr/bin/env python3
"""Generate Contribution registry catalogs and empty industry tags from Mojang reports."""

from __future__ import annotations

import argparse
import json
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


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", type=Path, help="Directory containing registries.json")
    parser.add_argument("resources", type=Path, help="src/main/resources directory")
    args = parser.parse_args()

    registries = json.loads((args.reports / "registries.json").read_text(encoding="utf-8"))
    items = sorted(registries["minecraft:item"]["entries"])
    blocks = sorted(registries["minecraft:block"]["entries"])

    data_root = args.resources / "data" / "contribution"
    write_json(data_root / "tags" / "item" / "all_items.json", {"replace": False, "values": items})
    write_json(data_root / "tags" / "block" / "all_blocks.json", {"replace": False, "values": blocks})

    empty_tag = {"replace": False, "values": []}
    for industry in INDUSTRIES:
        write_json(data_root / "tags" / "item" / f"craft_{industry}.json", empty_tag)
        write_json(data_root / "tags" / "block" / f"place_{industry}.json", empty_tag)
        write_json(data_root / "tags" / "block" / f"mine_{industry}.json", empty_tag)

    write_json(
        data_root / "contribution" / "registry_catalog.json",
        {
            "minecraft_version": "26.3",
            "item_count": len(items),
            "block_count": len(blocks),
            "items": items,
            "blocks": blocks,
        },
    )

    print(f"Generated {len(items)} items, {len(blocks)} blocks, and {len(INDUSTRIES) * 3} industry tags")


if __name__ == "__main__":
    main()
