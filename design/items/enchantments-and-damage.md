# 附魔、伤害与标签

## 自定义附魔

### `csumc:fast`

最高 5 级，支持锐器、主要用于剑，借用原版锋利附魔的翻译文本。每次攻击后对受害者额外造成固定 1 点 `csumc:attack_no_cooldown` 伤害；等级不会提高该附加伤害。它与原版伤害类附魔互斥。

### `csumc:fire_ring`

最高 1 级，不能正常附魔取得，主要由技能 modifier 写入物品。附魔逐刻读取持有者 `fire_ring` 分数，执行爆炸、清火、短暂防火和复位函数，同时提供 -50% 燃烧时间属性。

### `csumc:true_infinity`

最高 1 级，支持弓和弩。`ammo_use` 效果对箭、药箭、光灵箭和烟花火箭都把消耗量设为 0，因此不仅是弓的无限箭，也允许弩无限烟花。自定义互斥标签包含原版 `minecraft:infinity`。

### `csumc:walker`

最高 3 级，支持靴子。在 `#csumc:walker` 道路方块上或满足非着地分支时，提高移动速度并把移动效率加到 1；在道路上着地移动还有 4%×等级的耐久损耗判定。道路标签覆盖泥土路、混凝土、抛光石材及多种砖块。

## 自定义伤害

- `csumc:sonic_boom`：用于技能音爆，独立死亡消息，理论意图是无视护甲和附魔。
- `csumc:attack_no_cooldown`：用于 `fast` 的追加伤害，独立“连击致死”死亡消息。

中文死亡消息位于 `assets/minecraft/lang/zh_cn.json`。

## 伤害标签的实际作用域

仓库把 `bypasses_armor`、`bypasses_enchantments`、`bypasses_cooldown` 放在 `data/csumc/tags/damage_type`。原版伤害系统自动读取的是相应的 `minecraft` 命名空间标签；代码中也没有显式引用这些 `#csumc:*` 标签。因此这些文件目前只是同名自定义标签，不能单凭文件名让伤害获得原版绕过语义。

## 原版耐久附魔覆盖

`data/minecraft/enchantment/unbreaking.json` 整体替换原版耐久附魔定义。它把护甲与非护甲（或带 `glider` 的护甲）分开计算物品损伤移除概率，明确让翼装胸甲走非护甲分支。任何其他包对耐久附魔的修改都会与此覆盖冲突。

## 功能标签

- `#csumc:redstone`：放置统计用的大型功能方块集合，并嵌套玻璃标签。
- `#csumc:glasses`：各种玻璃与玻璃板。
- `#csumc:walker`：行路者附魔生效的道路材料。
- `#csumc:poke_can_catch`：精灵球允许捕捉的实体类型。
- `#csumc:fishing_more`：额外钓鱼附魔池，目前只有行路者。
- `#csumc_adv:*`：扩展成就专用的伤害、物品、唱片与珊瑚扇分类。

