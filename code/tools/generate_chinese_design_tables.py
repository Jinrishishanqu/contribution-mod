#!/usr/bin/env python3
"""Generate human-readable Chinese design tables from the active JSON definitions."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


INDUSTRIES = {
    "construction_landscaping": "土建园林",
    "mining_metallurgy": "采矿冶金",
    "energy_chemical": "能源化工",
    "processing_manufacturing": "加工制造",
    "technology_magic": "科技魔法",
    "agriculture_forestry_livestock_fishery": "农林牧渔",
    "military_food_medicine": "军工食药",
    "circulation_services": "流通服务",
    "culture_education_livelihood": "文教民生",
}

EVENT_NAMES = {
    "process/furnace/mineral_output": "熔炉产出矿冶产品",
    "process/furnace/chemical_output": "熔炉产出化工产品",
    "process/furnace/material_output": "熔炉产出加工材料",
    "process/furnace/food_output": "熔炉产出食物",
    "process/blast_furnace/output": "高炉完成加工",
    "process/smoker/output": "烟熏炉完成加工",
    "process/crafter/output": "合成器完成自动合成",
    "entity/breed": "繁殖实体",
    "entity/tame": "驯服实体",
    "entity/shear": "剪毛或剪取产物",
    "entity/milk": "挤奶",
    "entity/cure": "治愈实体",
    "entity/kill_hostile": "击杀敌对生物",
    "service/villager_trade": "完成村民交易",
    "collect/fishing": "钓鱼获得产物",
    "modify/smithing": "完成锻造",
    "modify/enchanting": "完成附魔",
    "modify/anvil_repair": "完成铁砧修复",
    "process/brewing": "完成酿造",
    "logistics/distance/elytra": "鞘翅飞行",
    "logistics/distance/horse": "骑马移动",
    "logistics/distance/boat": "乘船或木筏移动",
    "logistics/distance/minecart": "乘矿车移动",
    "logistics/distance/pig": "骑猪移动",
    "logistics/distance/strider": "骑炽足兽移动",
}

MEASURES = {
    "output_item": "实际产物数量",
    "successful_action": "成功动作次数",
    "entity": "实体数量",
    "completed_trade": "完成交易次数",
    "modified_item": "成功修改的物品数量",
    "distance_block": "有效移动距离（格）",
}


def identifier_path(identifier: str) -> tuple[str, str]:
    return tuple(identifier.split(":", 1))  # type: ignore[return-value]


def localized_name(identifier: str, kind: str, language: dict[str, str], fallback_item: str | None = None) -> str:
    namespace, value = identifier_path(identifier)
    prefixes = ("item", "block") if kind == "item" else ("block", "item")
    for prefix in prefixes:
        translation = language.get(f"{prefix}.{namespace}.{value}")
        if translation:
            return translation
    if fallback_item is not None:
        return localized_name(fallback_item, "item", language)
    return f"未提供中文名（{identifier}）"


def read_tag_entries(resources: Path, kind: str) -> list[tuple[str, str, str]]:
    folder = "item" if kind == "craft" else "block"
    entries: list[tuple[str, str, str]] = []
    for industry_path, industry_name in INDUSTRIES.items():
        file_name = f"{kind}_{industry_path}.json"
        data = json.loads((resources / f"data/contribution/tags/{folder}/{file_name}").read_text(encoding="utf-8"))
        for identifier in data["values"]:
            entries.append((industry_name, file_name, identifier))
    return entries


def write_object_table(
    target: Path,
    kind: str,
    resources: Path,
    language: dict[str, str],
    placement_reverse: dict[str, str],
) -> None:
    entries = read_tag_entries(resources, kind)
    with target.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(("序号", "行业", "对应JSON文件", "中文名称", "注册ID"))
        for index, (industry_name, file_name, identifier) in enumerate(entries, 1):
            object_kind = "item" if kind == "craft" else "block"
            fallback = placement_reverse.get(identifier) if kind == "place" else None
            name = localized_name(identifier, object_kind, language, fallback)
            writer.writerow((index, industry_name, file_name, name, identifier))


def write_event_table(target: Path, resources: Path) -> None:
    mapping = json.loads(
        (resources / "data/contribution/contribution/game_event_industry_map.json").read_text(encoding="utf-8")
    )
    with target.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(("序号", "中文事件", "事件ID", "行业", "计量方式", "每单位规模", "每单位建设度"))
        for index, event in enumerate(mapping["events"], 1):
            short_id = event["event_id"].split(":", 1)[1]
            industry_path = event["industry"].split(":", 1)[1]
            writer.writerow((index, EVENT_NAMES.get(short_id, "未定义中文事件名"), event["event_id"],
                             INDUSTRIES[industry_path], MEASURES.get(event["measure"], event["measure"]),
                             event["unit_size"], event["unit_value"]))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", type=Path, required=True)
    parser.add_argument("--resources", type=Path, default=Path("src/main/resources"))
    parser.add_argument("--output", type=Path, default=Path("../design/definitions"))
    parser.add_argument("--prefill-report", type=Path, default=Path("build/reports/industry-tag-prefill.json"))
    args = parser.parse_args()

    language = json.loads(args.language.read_text(encoding="utf-8"))
    placement = json.loads(
        (args.resources / "data/contribution/contribution/placement_map.json").read_text(encoding="utf-8")
    )["mappings"]
    placement_reverse = {
        block_id: item_id for item_id, block_ids in placement.items() for block_id in block_ids
    }

    args.output.mkdir(parents=True, exist_ok=True)
    write_event_table(args.output / "游戏事件-行业映射表.csv", args.resources)
    write_object_table(
        args.output / "玩家合成物品-行业定义表.csv",
        "craft",
        args.resources,
        language,
        placement_reverse,
    )

    report = json.loads(args.prefill_report.read_text(encoding="utf-8"))
    excluded = report["excluded_reversible_craft_items"]
    with (args.output / "可逆合成排除表.csv").open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(("序号", "中文名称", "注册ID"))
        for index, identifier in enumerate(excluded, 1):
            writer.writerow((index, localized_name(identifier, "item", language), identifier))
    write_object_table(
        args.output / "玩家放置方块-行业定义表.csv",
        "place",
        args.resources,
        language,
        placement_reverse,
    )
    write_object_table(
        args.output / "玩家挖掘方块-行业定义表.csv",
        "mine",
        args.resources,
        language,
        placement_reverse,
    )

    readme = """# 中文行业定义表

本目录只用于玩法设计和人工审核，不由模组运行时代码读取。

- [游戏事件 → 行业映射表](游戏事件-行业映射表.csv)
- [玩家合成物品 → 行业定义表](玩家合成物品-行业定义表.csv)
- [玩家放置方块 → 行业定义表](玩家放置方块-行业定义表.csv)
- [玩家挖掘方块 → 行业定义表](玩家挖掘方块-行业定义表.csv)
- [可逆合成排除表](可逆合成排除表.csv)

合成、放置和挖掘表分别用于审核玩家手动合成产物、实际生成的方块及资源采集方块的行业归属，对应 `code/src/main/resources/data/contribution/tags` 下的 27 个 JSON。行业没有数据行即表示当前无定义。游戏事件表用于审核事件的行业和单位建设度，不由代码读取。可逆合成排除表列出的物品不计入合成建设度。

CSV 使用 UTF-8 BOM，方便 Windows 表格软件直接打开。中文名称来自 Minecraft 26.3 官方简体中文语言文件，注册 ID 是跨语言和运行时配置使用的稳定标识。

当前人工定义表与运行时 JSON 有一处待核对差异：`minecraft:bee_nest` 和 `minecraft:beehive` 在放置定义表中归为“农林牧渔”，但运行时 `place_construction_landscaping.json` 仍将其归为“土建园林”。本次只转换格式，不更改归属。

重新生成命令由 `code/tools/generate_chinese_design_tables.py` 提供。该脚本以运行时 JSON 为源重新生成 CSV；解决上述待核对差异前，不应直接覆盖人工定义表。修改玩法定义后，应同步更新 JSON 和本目录表格，并重新运行互斥检查。
"""
    (args.output / "README.md").write_text(readme, encoding="utf-8")


if __name__ == "__main__":
    main()
