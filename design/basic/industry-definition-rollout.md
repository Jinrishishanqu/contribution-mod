# 行业定义实施进度（0.1.13）

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
| `process/furnace/mineral_output` | 熔炉产出矿冶产品 | 已接入 |
| `process/furnace/chemical_output` | 熔炉产出化工产品 | 已接入 |
| `process/furnace/material_output` | 熔炉产出加工材料 | 已接入 |
| `process/furnace/food_output` | 熔炉产出食物 | 已接入 |
| `process/blast_furnace/output` | 高炉完成加工 | 已接入 |
| `process/smoker/output` | 烟熏炉完成加工 | 已接入 |
| `process/crafter/output` | 合成器完成自动合成 | 已接入 |
| `process/brewing` | 酿造台产物栏发生变化 | 已接入 |
| `process/furnace/fuel` | 熔炉实际消耗燃料 | 已接入 |
| `device/piston_move` | 活塞成功推出 | 已接入 |
| `auto/hopper_transfer` | 漏斗流出物品 | 已接入 |
| `auto/dispenser_fire` | 发射器自动发射 | 已接入 |
| `auto/dropper_fire` | 投掷器成功输出物品 | 已接入 |
| `auto/tnt_explode` | TNT 爆炸 | 已接入 |
| `auto/villager_farm` | 村民自动收割作物 | 待接入 |
| `auto/iron_golem_farm` | 村庄成功生成铁傀儡（活动指标） | 待接入 |
| `auto/copper_golem_sort` | 铜傀儡分拣物品 | 待接入 |
| `process/campfire_cook` | 营火烹饪食物 | 已接入 |
| `auto/composter_fill` | 堆肥桶自动产出骨粉 | 待接入 |
| `auto/trial_spawner_reward` | 试炼刷怪笼发放奖励 | 待接入 |
| `modify/strip_log` | 给原木或木头去皮 | 已接入 |
| `modify/smithing` | 锻造台成功改造物品 | 已接入 |
| `modify/anvil_enchant` | 铁砧附魔 | 已接入 |
| `modify/anvil_repair` | 铁砧修复（含合并） | 已接入 |
| `modify/anvil_rename` | 铁砧重命名 | 已接入 |
| `modify/enchanting` | 附魔台附魔 | 已接入 |
| `modify/bone_meal` | 骨粉催熟作物 | 已接入 |
| `modify/compost` | 堆肥桶产出骨粉 | 已接入 |
| `device/iron_golem` | 建造铁傀儡或雪傀儡 | 待接入 |
| `entity/breed` | 繁殖实体 | 已接入 |
| `entity/tame` | 驯服实体 | 已接入 |
| `entity/shear` | 剪取动物产物 | 已接入 |
| `entity/shear_equipment` | 剪刀卸下动物装备 | 待接入 |
| `entity/milk` | 挤奶 | 已接入 |
| `entity/name_tag` | 命名牌命名实体 | 已接入 |
| `entity/shear_block` | 剪刀修剪植物或雕刻南瓜 | 待接入 |
| `entity/honey` | 蜂巢或蜂箱收获 | 已接入 |
| `collect/fishing` | 钓鱼获得产物 | 已接入 |
| `entity/kill` | 击杀任意生物 | 已接入 |
| `combat/shield_block` | 盾牌格挡伤害 | 已接入 |
| `combat/raid` | 玩家成功触发袭击 | 已接入 |
| `service/villager_trade` | 完成村民交易 | 已接入 |
| `logistics/distance/elytra` | 鞘翅飞行移动 | 已接入 |
| `logistics/distance/horse` | 骑马移动 | 已接入 |
| `logistics/distance/boat` | 乘船或木筏移动 | 已接入 |
| `logistics/distance/minecart` | 乘矿车移动 | 已接入 |
| `logistics/distance/pig` | 骑猪移动 | 已接入 |
| `logistics/distance/strider` | 骑炽足兽移动 | 已接入 |
| `logistics/distance/happy_ghast` | 骑快乐恶魂移动 | 已接入 |
| `logistics/distance/nautilus` | 骑鹦鹉螺移动 | 已接入 |
| `survival/hunger` | 回复饱食度或饱和度 | 待接入 |
| `survival/health` | 回复血量 | 待接入 |
| `entity/cure` | 治愈僵尸村民 | 已接入 |
| `culture/pot_flower` | 与花盆交互使其变成盆栽 | 已接入 |
| `culture/bell` | 敲钟 | 已接入 |
| `culture/cauldron` | 炼药锅装水或使用 | 已接入 |
| `culture/clean` | 清洗盔甲、旗帜或潜影盒 | 已接入 |
| `farm/turtle_egg` | 海龟产卵或孵化 | 待接入 |
| `farm/fill_bucket_mob` | 用桶捕获生物 | 待接入 |
| `food/dragon_breath` | 采集龙息 | 待接入 |
| `food/throw_potion` | 投掷喷溅或滞留药水 | 已接入 |
| `combat/used_totem` | 消耗图腾 | 已接入 |
| `combat/effects_changed` | 获得状态效果 | 待接入 |
| `combat/hero_of_village` | 玩家参与袭击并获胜 | 已接入 |
| `tech/channeling` | 三叉戟引雷 | 待接入 |
| `tech/lightning_strike` | 闪电劈下 | 待接入 |
| `tech/beacon_construct` | 信标由未激活变为激活 | 待接入 |
| `tech/ender_eye` | 使用末影之眼 | 已接入 |
| `tech/build_wither` | 建造凋灵 | 待接入 |
| `culture/map_draw` | 创建已填充地图 | 已接入 |
| `culture/write_book` | 完成成书 | 待接入 |
| `growth/crop_wheat` | 小麦等作物生长至成熟 | 待接入 |
| `growth/crop_beetroot` | 甜菜生长至成熟 | 待接入 |
| `growth/stem_pumpkin_melon` | 南瓜或西瓜茎结果 | 待接入 |
| `growth/nether_wart` | 下界疣生长至成熟 | 待接入 |
| `growth/cocoa` | 可可豆生长至成熟 | 待接入 |
| `growth/sweet_berry` | 甜浆果丛生长至成熟 | 待接入 |
| `growth/sapling` | 树苗长成树木 | 待接入 |
| `growth/mangrove_propagule` | 红树胎生苗生长 | 待接入 |
| `growth/sugar_cane` | 甘蔗生长 | 待接入 |
| `growth/cactus` | 仙人掌生长 | 待接入 |
| `process/stonecutting` | 切石机成功加工 | 已接入 |
| `modify/grindstone` | 砂轮修复或祛魔 | 已接入 |
| `modify/loom` | 织布机应用旗帜图案 | 已接入 |
| `culture/sign_edit` | 玩家完成告示牌编辑 | 待接入 |
| `collect/archaeology` | 刷取考古产物 | 待接入 |
| `collect/lava_bucket` | 装桶采集岩浆 | 已接入 |
| `process/renewable_block` | 无主造石、生长或材料固化 | 待接入 |
