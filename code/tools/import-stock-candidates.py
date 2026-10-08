"""Import a reviewed stock-only XLSX without using or changing its serial numbers."""
from pathlib import Path
import argparse, collections, hashlib, json, sys, xml.etree.ElementTree as ET, zipfile

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "code/src/main/resources/data/contribution/contribution"
NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
INDUSTRIES = {
    "土建园林": "contribution:construction_landscaping",
    "采矿冶金": "contribution:mining_metallurgy",
    "能源化工": "contribution:energy_chemical",
    "加工制造": "contribution:processing_manufacturing",
    "科技魔法": "contribution:technology_magic",
    "农林牧渔": "contribution:agriculture_forestry_livestock_fishery",
    "军工食药": "contribution:military_food_medicine",
    "流通服务": "contribution:circulation_services",
    "文教民生": "contribution:culture_education_livelihood",
}

def read_rows(workbook):
    with zipfile.ZipFile(workbook) as archive:
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared = ["".join(value.itertext()) for value in ET.fromstring(
                archive.read("xl/sharedStrings.xml")).findall("m:si", NS)]
        book = ET.fromstring(archive.read("xl/workbook.xml"))
        sheets = book.findall("m:sheets/m:sheet", NS)
        sheet = next((value for value in sheets if value.get("name") == "股票行业映射"), None)
        if sheet is None:
            raise ValueError("Missing 股票行业映射 worksheet")
        relations = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relation = sheet.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
        target = next(value.get("Target") for value in relations if value.get("Id") == relation)
        xml_path = target.lstrip("/") if target.startswith("/") else "xl/" + target
        for row in ET.fromstring(archive.read(xml_path)).findall("m:sheetData/m:row", NS):
            values = {}
            for cell in row.findall("m:c", NS):
                column = "".join(letter for letter in cell.get("r") if letter.isalpha())
                node = cell.find("m:v", NS)
                value = node.text if node is not None else ""
                if cell.get("t") == "s":
                    value = shared[int(value)]
                elif cell.get("t") == "inlineStr":
                    value = "".join(cell.find("m:is", NS).itertext())
                values[column] = value.strip()
            yield row.get("r"), values

def convert(workbook):
    registry = json.loads((DATA / "registry_catalog.json").read_text(encoding="utf-8"))
    if registry["minecraft_version"] != "26.3":
        raise ValueError("Expected Minecraft 26.3 registry")
    known = set(registry["items"])
    rows = iter(read_rows(workbook))
    _, headers = next(rows)
    columns = {value.replace(" ", ""): key for key, value in headers.items()}
    industry_col = columns["行业"]
    item_col = columns["命名空间ID"]
    name_col = columns["中文名称"]
    mapping = {identifier: [] for identifier in INDUSTRIES.values()}
    names, locations = {}, {}
    for row_number, row in rows:
        identifier = row.get(item_col, "")
        category = row.get(industry_col, "")
        name = row.get(name_col, "")
        if not identifier and not category and not name:
            continue
        industry = INDUSTRIES.get(category, category)
        if industry not in mapping:
            raise ValueError(f"Row {row_number}: invalid industry {category!r}")
        if identifier not in known:
            raise ValueError(f"Row {row_number}: invalid 26.3 item {identifier!r}")
        if identifier in locations:
            raise ValueError(f"Row {row_number}: duplicate {identifier}, first row {locations[identifier]}")
        if not name:
            raise ValueError(f"Row {row_number}: missing item name")
        mapping[industry].append(identifier)
        names[identifier] = name
        locations[identifier] = row_number
    for industry, values in mapping.items():
        if len(values) < 2:
            raise ValueError(f"{industry}: need at least two stock candidates")
        values.sort()
    return mapping, dict(sorted(names.items()))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    args = parser.parse_args()
    original = args.workbook.read_bytes()
    mapping, names = convert(args.workbook)
    manifest = {
        "minecraft_version": "26.3",
        "source_workbook_sha256": hashlib.sha256(original).hexdigest(),
        "item_count": len(names),
        "industry_counts": {key: len(values) for key, values in mapping.items()},
    }
    for filename, value in [
        ("stock_industry_map.json", mapping), ("stock_item_names.json", names),
        ("stock_catalog_manifest.json", manifest)
    ]:
        (DATA / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    if args.workbook.read_bytes() != original:
        raise RuntimeError("Source workbook was unexpectedly changed")
    print(json.dumps(manifest, ensure_ascii=False))
if __name__ == "__main__":
    main()
