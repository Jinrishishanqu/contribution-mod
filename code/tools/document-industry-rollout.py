"""Emit scoped implementation-status and documentation patches, leaving reviewed XLSX untouched."""
import difflib
import json
from pathlib import Path
import runpy
import sys

helper = runpy.run_path(str(Path(__file__).with_name("sync-industry-definitions.py")))
root, data = helper["ROOT"], helper["DATA"] / "contribution"
manifest = json.loads((data/"definition_manifest.json").read_text(encoding="utf-8"))
rules = {e["event_id"]: e for e in json.loads((data/"game_event_industry_map.json").read_text())["events"]}
sys.stdout.reconfigure(encoding="utf-8")

def text_patch(path, new):
    if path.exists():
        old = path.read_text(encoding="utf-8")
        if old == new: return
        print("*** Update File: "+path.as_posix())
        for line in list(difflib.unified_diff(old.splitlines(),new.splitlines(),lineterm=""))[2:]:
            print("@@" if line.startswith("@@") else line)
    else:
        print("*** Add File: "+path.as_posix())
        print("\n".join("+"+line for line in new.splitlines()))

print("*** Begin Patch")
catalog_path = root/"documentation/source/catalog.json"
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
for guide in catalog["guides"]:
    if guide[0] == "账户与统计": guide[1] = guide[1].replace("1/1000","1/250")
    if guide[0] == "游戏事件审核与参与度":
        guide[1] = "0.1.13按四份人工XLSX启动分批实施，原表与存档不改写。合成1065、放置1206、挖掘1129个对象已同步到27个行业标签。合成按玩家＋行业累计实际产物，每64个增加1点；Shift按真实数量，同业不同产物可合计，不同行业/玩家隔离，余量跨日、重启与跨服保留。挖掘和放置仍按操作，不套用64阈值。交通八种方式每10格1点。默认贡献奖励权重1/250，只有完整统一旧默认权重自动升级，自定义权重保留。事件表88项中56项已接入或扩展，另32项尚待接入，具体覆盖与限制见design/basic/industry-definition-rollout.md。活塞、漏斗及发射/投掷观察保留原始活动但建设度为0。实际完成结果只选一个主入口，不新增轮询或跨钩子去重历史。新规则按原有核算日机制切换，历史累计不重算。"
helper["patch_file"](catalog_path,catalog)

rows = []
for event in manifest["events"]:
    rows.append("| `"+event["event_id"].removeprefix("contribution:")+"` | "+event["name"]+" | "+("已接入" if event["event_id"] in rules else "待接入")+" |")
doc = """# 行业定义实施进度（0.1.13）

本轮按四份人工审核XLSX分批实施。对象标签已全部同步，事件表88项中56项已接入/扩展，32项尚未接入。这里的接入表示有服务端完成适配器，不表示所有原版分支已进行游戏内人工测试，也不代表经过性能压测。

## 已落地规则

- craft1065、place1206、mine1129个唯一对象，同动作行业互斥，可逆合成37项仍排除。合成每玩家每行业累计64个实际产物为1点，同业不同物品可合计，余量跨批次、核算日、重启和跨服持久化，不跨玩家/行业混算。
- 交通八类（鞘翅、马、船、矿车、猪、炽足兽、快乐恶魂、鹦鹉螺）每10格1点，保持原版完成统计入口及16格单次异常增量过滤。
- 酿造归能源化工。普通熔炉重新区分矿冶、化工、材料、食物；石头、玻璃等归加工制造。
- 铁砧优先修复，再附魔，再改名；砂轮优先修复，再祛魔；锻造耐久装备/工具归军工食药，其余加工制造。每次结果仅一个行业。切石机与织布机支持实际结果数量与Shift转移。
- 燃料仅实际扣一份时增加一次。合成器仅实际产物发放分支计数，不重复查配方或计算容器返还。篝火按实际产物，TNT按完成爆炸，均不猜测玩家归属。
- 活塞、漏斗及发射/投掷观察的unit_value=0，仅保存原始量，不生成建设度、个人奖励或经济余量。
- 奖励默认九行业1/250；完整统一的旧1/1000或1/10000默认配置自动升级，混合/自定义值保持。新的奖励权重用于现有累计应发公式，扣除已发金额，不追回已有奖励。

## 采集边界与后续

普通村民交易仍不包括流浪商人。驯服在原有TamableAnimal基础上增加马科完成点。锅与清洗使用原版成功统计，屏蔽对应通用交互；命名要求实际名称变化，骨粉要求实际扣除，蜂蜜要求满蜜到清空的成功状态变化，空桶取熔岩必须实际取得熔岩桶。

漏斗观察目前限至少一端为HopperBlockEntity的插入成功分支，纯漏斗矿车路径尚未覆盖；投掷器直接插入容器的路径尚未覆盖。发射行为目前只记录实际减少/转换物品的DefaultDispenseItemBehavior路径。引雷、信标、结构召唤、生态生长、恢复量等待接入项目不得仅靠新增事件JSON宣称生效。

## 持久化与升级

新增V20，不改写V1—V19。event_activity_day按来源服/核算日/当次可靠主体/事件聚合原始数量，不存逐次事件。measurement_remainder按玩家/事件保存经济计量余量，无主机器按来源服隔离。真实数量折算、余量、双份建设度和批次幂等标记在同一事务，失败整体回滚。CSU5恢复日志新增原始量区，继续读取CSU2—CSU4；旧格式的批次内容哈希保持。旧日关账包含未完成的原始量批次。

旧日继续按已发布规则处理，新的事件直到切换到支持它的快照才采集；合成旧日保持原计数语义。标签重载在下一核算日8点生效，不重算旧累计。原XLSX的目标文字优先于派生ID列（酿造、效果观察存在旧派生ID）；多行业分支使用独立稳定事件键。

使用sync-industry-definitions.py只读四份主表并输出apply_patch；构建检查XLSX SHA、标签数量及已启用规则。不要用旧快照生成器覆盖人工表。Wiki与指令XLSX按仓库流程同步生成。

## 主表逐项状态

| 事件 | 名称 | 状态 |
| --- | --- | --- |
"""+"\n".join(rows)+"\n"
text_patch(root/"design/basic/industry-definition-rollout.md",doc)
print("*** End Patch")
