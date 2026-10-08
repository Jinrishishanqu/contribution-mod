"""Read reviewed XLSX main sheets and emit a scoped apply_patch; never overwrite workbooks.

Uses only the Python standard library. Existing archives and proposal sheets are not rules.
"""
import collections
import difflib
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "code/src/main/resources/data/contribution"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
INDUSTRIES = dict(zip(
    ["土建园林", "采矿冶金", "能源化工", "加工制造", "科技魔法", "农林牧渔", "军工食药", "流通服务", "文教民生"],
    ["construction_landscaping", "mining_metallurgy", "energy_chemical", "processing_manufacturing", "technology_magic", "agriculture_forestry_livestock_fishery", "military_food_medicine", "circulation_services", "culture_education_livelihood"]))

def rows(path):
    with zipfile.ZipFile(path) as archive:
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared = ["".join(t.itertext()) for t in ET.fromstring(archive.read("xl/sharedStrings.xml")).findall("m:si", NS)]
        for row in ET.fromstring(archive.read("xl/worksheets/sheet1.xml")).findall("m:sheetData/m:row", NS):
            result = {}
            for cell in row.findall("m:c", NS):
                column = "".join(c for c in cell.get("r") if c.isalpha())
                node = cell.find("m:v", NS)
                value = node.text if node is not None else ""
                if cell.get("t") == "s": value = shared[int(value)]
                if cell.get("t") == "inlineStr": value = "".join(cell.find("m:is", NS).itertext())
                result[column] = value
            yield result

def patch_file(path, value):
    new = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    if path.exists():
        old = path.read_text(encoding="utf-8")
        if old == new: return
        print("*** Update File: " + path.as_posix())
        for line in list(difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm=""))[2:]:
            print("@@" if line.startswith("@@") else line)
    else:
        print("*** Add File: " + path.as_posix())
        print("\n".join("+" + line for line in new.splitlines()))

def main():
    catalog = json.loads((DATA / "contribution/registry_catalog.json").read_text())
    manifest = {"schema_version": 1, "sources": [], "events": []}
    print("*** Begin Patch")
    for label, action, count in [("合成物品", "craft", 1065), ("放置方块", "place", 1206), ("挖掘方块", "mine", 1129)]:
        path = ROOT / ("design/definitions/玩家" + label + "-行业定义表.xlsx")
        entries = list(rows(path))[1:]
        assert len(entries) == count, (path, len(entries))
        known = set(catalog["items" if action == "craft" else "blocks"])
        seen = set()
        groups = collections.defaultdict(list)
        for row in entries:
            identity, industry = row["E"], INDUSTRIES[row["B"]]
            assert identity in known and identity not in seen, identity
            assert row["C"] == action + "_" + industry + ".json", row
            seen.add(identity)
            groups[industry].append(identity)
        if action == "craft":
            excluded = set(json.loads((DATA / "contribution/reversible_craft_exclusions.json").read_text()))
            assert not seen & excluded, seen & excluded
        for industry in INDUSTRIES.values():
            patch_file(DATA / "tags" / ("item" if action == "craft" else "block") / (action + "_" + industry + ".json"), {"replace": False, "values": sorted(groups[industry])})
        manifest["sources"].append({"file": path.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "action": action, "rows": count})
    path = ROOT / "design/definitions/游戏事件-行业映射表.xlsx"
    for row in list(rows(path))[1:]:
        # The human target column wins over stale derived IDs (notably brewing/effects).
        target = "contribution:" + INDUSTRIES[row["C"]] if row["C"] in INDUSTRIES else row["Y"]
        manifest["events"].append({"event_id": row["A"], "name": row["B"], "industry": target, "measure": row["G"], "unit_size": int(row["J"]), "unit_value": int(row["K"]), "actor": row["L"], "constraint": row["AC"], "accepted_error": row["AD"]})
    assert len(manifest["events"]) == 88
    manifest["sources"].append({"file": path.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "rows": 88})
    patch_file(DATA / "contribution/definition_manifest.json", manifest)
    print("*** End Patch")

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
