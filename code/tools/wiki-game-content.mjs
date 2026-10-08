import fs from 'node:fs/promises';
import path from 'node:path';
import { mediaKey } from './wiki-media.mjs';
import { displayName, conditionDescriptions } from './wiki-editorial.mjs';

// Derive catalog details from shipped data, not the historical datapack prototype.
export async function gameContent(root) {
  const media = JSON.parse(await fs.readFile(path.join(root, 'wiki/content/media.json'), 'utf8'));
  const resources = path.join(root, 'code/src/main/resources/data/contribution');
  const names = JSON.parse(await fs.readFile(path.join(root, 'wiki/content/names.json'), 'utf8'));
  const name = value => {
    if (Array.isArray(value)) return value.map(name).join(' / ');
    if (typeof value === 'object' && value) return name(value.item ?? value.id ?? value.tag ?? JSON.stringify(value));
    if (value === '#minecraft:logs') return '任意原版原木标签物品（含去皮原木、木头及菌柄）';
    return displayName(names[value] ?? String(value ?? '无'));
  };
  const text = value => displayName(typeof value === 'string' ? value : value?.text ?? value?.translate ?? JSON.stringify(value ?? ''));
  const code = value => ({type: 'code', language: 'json', text: JSON.stringify(value, null, 2)});
  const table = (columns, rows) => ({type: 'table', columns, rows});
  const paragraph = text => ({type: 'p', text});
  async function walk(directory) {
    const entries = await fs.readdir(directory, {withFileTypes: true});
    const nested = await Promise.all(entries.map(entry => entry.isDirectory() ? walk(path.join(directory, entry.name)) : [path.join(directory, entry.name)]));
    return nested.flat().filter(file => file.endsWith('.json')).sort();
  }
  const categories = [
    {id:'recipes-basics', title:'基础配方', prefix:'', intro:'基础配方提供鞘翅与海洋之心复制，以及用原木、深板岩圆石等材料制作常用物品。', use:'在工作台按图放入材料，空白格留空。有序配方可整体平移或水平镜像，每格每次消耗一件材料。'},
    {id:'recipes-wings', title:'添翼配方', prefix:'wings/', intro:'添翼将胸甲与鞘翅结合，使七种材质的胸甲具备滑翔能力。', use:'在锻造台依次放入下界合金升级锻造模板、胸甲和鞘翅，取出添翼胸甲。'},
    {id:'recipes-reinforcement', title:'强化配方', prefix:'reinforcement/', intro:'强化为原材质装备设置下界合金级耐久和战斗属性，保留装备种类、名称与皮肤。', use:'在锻造台依次放入下界合金升级锻造模板、待强化装备和同部位下界合金装备，取出强化结果。'},
    {id:'recipes-armor', title:'主题盔甲配方', prefix:'armor/', intro:'马克六型、纳米和量子盔甲提供三套主题外观，每套包括四件普通部件和一件翼装胸甲。', use:'普通部件由下界合金装备与主题材料无序合成；翼装胸甲在锻造台制作，槽位见各配方。'},
    {id:'recipes-hats', title:'帽子配方', prefix:'hats/', intro:'变形帽子通过锻造获得对应物品的外形，穿戴在头部装备槽。', use:'在锻造台以线作模板，放入指定基底和外形材料。帽子的属性与保留数据取决于基底。'},
    {id:'recipes-special', title:'特殊道具配方', prefix:'special', intro:'公示喇叭用于展示副手物品，哑炮用于播放点燃音效，两者均通过工作台合成。', use:'按图放入材料。用法、消耗和冷却见[特殊道具](page:special-items)。'},
  ];
  const recipes = [];
  for (const file of await walk(path.join(resources, 'recipe/items'))) {
    const data = JSON.parse(await fs.readFile(file, 'utf8'));
    const local = path.relative(path.join(resources, 'recipe/items'), file).replaceAll('\\', '/').slice(0, -5);
    const id = 'contribution:items/' + local;
    const special = ['speaker','dud'].includes(local);
    const category = special ? categories[5] : categories.find(item => item.prefix && local.startsWith(item.prefix)) ?? categories[0];
    const result = data.result;
    const display = text(result.components?.['minecraft:item_name'] ?? names[result.id] ?? result.id);
    const title = category.id === 'recipes-hats' ? `${name(data.addition)}外观帽子（${name(data.base)}底材）` : display + (local.startsWith('reinforcement/') ? ' · 强化' : local.startsWith('wings/') ? ' · 添翼' : '');
    const blocks = [paragraph(`产物：${display} × ${result.count ?? 1}。`)];
    if (data.type === 'minecraft:crafting_shaped') {
      const pattern = [...data.pattern];
      while (pattern.length < 3) pattern.push('');
      blocks.push({type:'recipe', pattern:pattern.map(row => row.padEnd(3, ' ')), key:Object.fromEntries(Object.entries(data.key).map(([symbol, value]) => [symbol, name(value)])), keyImages:Object.fromEntries(Object.entries(data.key).map(([symbol,value]) => [symbol,mediaKey(value)])), resultIcon:mediaKey(result), count:result.count ?? 1, result:`${display} × ${result.count ?? 1}`});
      const quantities = new Map();
      for (const symbol of data.pattern.join('')) if (symbol !== ' ') quantities.set(symbol, (quantities.get(symbol) ?? 0) + 1);
      blocks.push(table(['材料','数量'], [...quantities].map(([symbol, count]) => [name(data.key[symbol]), String(count)])));
    } else if (data.type === 'minecraft:smithing_transform') {
      blocks.push({type:'recipe',mode:'smithing',inputs:[data.template,data.base,data.addition].map(value => ({key:mediaKey(value),label:name(value)})),resultIcon:mediaKey(result),count:result.count ?? 1,result:display});
      blocks.push(table(['锻造槽','材料','数量'], [['左：模板',name(data.template),'1'],['中：基底',name(data.base),'1'],['右：附加材料',name(data.addition),'1']]));
      blocks.push(paragraph('取走结果时，各槽消耗一件材料，包括模板。结果继承基底数据，再应用配方指定的属性与外观。'));
    } else if (data.type === 'minecraft:crafting_transmute') {
      blocks.push({type:'recipe',mode:'transmute',inputs:[data.input,data.material].map(value => ({key:mediaKey(value),label:name(value)})),resultIcon:mediaKey(result),count:result.count ?? 1,result:display});
      blocks.push(table(['无序合成材料','数量'], [[name(data.input),'1'],[name(data.material),'1']]));
      blocks.push(paragraph('把装备与主题材料放入任意两个合成格，取出结果。转化继承基底数据，并设置对应的主题外观。'));
    } else throw new Error(`Undocumented recipe type ${data.type}: ${id}`);
    if (local === 'elytra') blocks.push(paragraph('制作后得到一件新鞘翅，并返还中心的原鞘翅，共两件，均剩 1 点耐久。原鞘翅保留其他组件，其他材料消耗。'));
    if (local === 'dud') blocks.push(paragraph('一次制作 8 个哑炮，水桶返还为空桶。'));
    if (local.startsWith('reinforcement/')) blocks.push(paragraph('强化后保留名称、皮肤及定型状态，显示「下界合金强化」提示。镐、斧、锹、锄获得下界合金级采集能力，挖掘速度与修复材料仍依原材质。耐久和最终属性见下方结果组件。'));
    if (local.startsWith('wings/')) blocks.push(paragraph('添翼胸甲显示对应材质的翼装外观和「添翼」提示，保留附魔、损耗耐久、纹饰及配方未覆盖的数据。飞行损耗按工具类耐久附魔规则计算，受击损耗按盔甲规则计算。'));
    if (local.startsWith('armor/')) blocks.push(paragraph(local.endsWith('_winged') ? '翼装胸甲具备滑翔能力，主题外观同时显示装甲和翅膀。' : '普通主题部件改变名称和外观，战斗性能沿用基底，已有滑翔能力保留。可见翅膀使用本套翼装胸甲配方。'));
    if (result.components) blocks.push({type:'details',title:'结果属性与组件',blocks:[code(result.components)]});
    blocks.push({type:'details',title:'配方数据',blocks:[paragraph(`配方标识符：\`${id}\`；产物物品标识符：\`${result.id}\`。结果组件在继承基底数据后应用。`),code(data)]});
    names[id] = title;
    recipes.push({id,category:category.id,section:{id:'recipe-'+local.replaceAll('/','-'),title:title+(local.endsWith('_winged')?' · 翼装':''),blocks}});
  }
  const pages = categories.map(category => ({id:category.id,title:category.title,summary:category.intro,sections:[{id:'使用说明',title:'制作',blocks:[paragraph(category.use),paragraph('表中数量均为单次制作所需。服务端加载世界时启用内置配方，外观通过[资源包](page:resourcepack)显示。')]},...recipes.filter(item => item.category === category.id).map(item => item.section)]}));
  const categoryImages = {'recipes-basics':'minecraft:elytra','recipes-wings':'model:contribution:winged/diamond','recipes-reinforcement':'minecraft:netherite_pickaxe','recipes-armor':'model:contribution:armor/nano_chestplate','recipes-hats':'model:minecraft:anvil','recipes-special':'model:contribution:speaker'};
  pages.unshift({id:'recipes',title:'配方目录',summary:`模组提供 ${recipes.length} 个配方，用于便利合成、添翼、强化、主题盔甲、帽子和特殊道具。`,sections:[{id:'分类入口',title:'按类别查找',blocks:[{type:'cards',columns:2,items:categories.map(category=>({page:category.id,title:category.title,image:categoryImages[category.id],text:`${recipes.filter(recipe=>recipe.category===category.id).length} 个配方。${category.intro}`}))},paragraph('材料通过原版玩法或服务器商店获取。配方页依次显示产物、材料槽位、数量和制作结果。服务器数据包可以调整同一标识符的配方。')]}]});
  const triggers = {location:'位置条件检查',bred_animals:'完成动物繁殖',player_killed_entity:'玩家击杀实体',player_hurt_entity:'玩家对实体造成伤害',entity_hurt_player:'玩家受到伤害',entity_killed_player:'玩家被实体击杀',lightning_strike:'附近发生雷击',inventory_changed:'背包内容发生变化',item_durability_changed:'物品耐久变化',placed_block:'成功放置方块',using_item:'正在使用物品',player_interacted_with_entity:'对实体使用物品',any_block_use:'与方块交互',recipe_crafted:'完成指定配方并取出结果'};
  const conditions = conditionDescriptions(names);
  const conditionRows = conditions.rows;
  const advancements = [];
  let hiddenCount = 0;
  for (const file of await walk(path.join(resources, 'advancement'))) {
    const data = JSON.parse(await fs.readFile(file,'utf8'));
    if (!data.display) {hiddenCount++;continue;}
    const id = 'contribution:' + path.relative(path.join(resources,'advancement'),file).replaceAll('\\','/').slice(0,-5);
    const blocks = [{type:'illustration',key:mediaKey(data.display.icon),label:text(data.display.title),text:text(data.display.description)}];
    const requirements = data.requirements ?? Object.keys(data.criteria).map(key => [key]);
    blocks.push(paragraph(requirements.length === 1 && requirements[0].length === 1 ? '完成条件：满足下列要求。' : `完成条件：共 ${requirements.length} 组，每组完成其中任意一项，全部组均完成后获得进度。`));
    const recipeCriteria = Object.values(data.criteria).every(criterion => criterion.trigger === 'minecraft:recipe_crafted');
    if (recipeCriteria) blocks.push(paragraph('完成对应配方并取出结果时触发，材料见[强化配方](page:recipes-reinforcement)。'));
    const color = Object.values(data.criteria).find(criterion => criterion.conditions?.entity?.predicate?.['minecraft:components']?.['minecraft:sheep/color'] && criterion.conditions?.item);
    if (color) blocks.push(paragraph(`目标羊交互前的颜色为 ${conditions.value(color.conditions.entity.predicate['minecraft:components']['minecraft:sheep/color'])}，使用物品为 ${name(color.conditions.item.items)}。`));
    for (const [index, [key, criterion]] of Object.entries(data.criteria).entries()) {
      const constraint = criterion.conditions ?? {};
      const blocksForCondition = [paragraph(triggers[criterion.trigger.replace('minecraft:','')] ?? `触发事件：\`${criterion.trigger}\``)];
      if (Object.keys(constraint).length) blocksForCondition.push(table(['要求','条件'],conditionRows(constraint)));
      const memberships = requirements.flatMap((group, i) => group.includes(key) ? [`第 ${i+1} 组 · 选项 ${group.indexOf(key)+1}`] : []);
      blocks.push({type:'subsection',title:requirements.length === 1 && requirements[0].length === 1 ? '操作与条件' : memberships.join('；'),blocks:blocksForCondition});
    }
    if (data.rewards) blocks.push(table(['奖励','内容'],conditionRows(data.rewards).map(([label,value])=>[label.replace(/^完成配方/,'解锁配方'),value])));
    blocks.push({type:'details',title:'进度数据',blocks:[paragraph(`进度标识符：\`${id}\`。父节点：\`${data.parent ?? '无（根节点）'}\`。显示类型：${({task:'普通进度',goal:'目标',challenge:'挑战'}[data.display.frame] ?? '普通进度')}。隐藏：${data.display.hidden ? '是' : '否'}。`),code(data)]});
    advancements.push({title:text(data.display.title),blocks});
  }
  pages.push({id:'advancement-details',title:'进度条件',summary:`「更多乐趣」包含 ${advancements.length} 个显示进度，完成相应的活动、装备和状态条件后获得。`,sections:[{id:'如何查看和触发',title:'查看与完成',blocks:[paragraph('默认按 `L` 打开进度界面，选择「更多乐趣」。下表依次列出操作、条件和奖励。父节点用于组织显示树。'),paragraph(`另有 ${hiddenCount} 个隐藏节点用于配方解锁。制作方式见[配方目录](page:recipes)。条件表中的「满足任一分支」表示任选其一，「满足全部分支」表示各分支都要满足。`)]},...advancements]});
  const skinSections = [];
  for (const [family, label] of Object.entries({sword:'剑',axe:'斧',pickaxe:'镐',hoe:'锄',shovel:'铲',spear:'矛'})) {
    const definition = JSON.parse(await fs.readFile(path.join(root, `code/src/main/resources/assets/contribution/items/weapon_skin/${family}.json`), 'utf8'));
    const cases = definition.model.cases;
    skinSections.push({title:label,blocks:[{type:'gallery',compact:true,items:media.skins[family].map(item=>({...item,label:displayName(item.label)}))},{type:'details',title:'模型定义',blocks:[table(['item_name','模型'],cases.flatMap(entry => (Array.isArray(entry.when) ? entry.when : [entry.when]).map(label => [label,`\`${JSON.stringify(entry.model)}\``])))]}]});
  }
  pages.push({id:'weapon-skins',title:'武器皮肤',summary:'武器皮肤为剑、斧、镐、锄、铲和矛提供外观，通过对应的原初物品选择并定型。',sections:[{id:'获取与定型',title:'获取与使用',blocks:[paragraph('在 /shop 购买对应原初物品，放在主手使用，选择外观并预览。确认后获得金质武器或工具，所选外观固定。操作见[特殊道具](page:special-items)。'),paragraph('皮肤通过资源包显示。名称、手持模型与物品栏模型按各外观定义，具体组件可在下方模型定义中查看。')]},...skinSections]});
  return {pages, recipeIds:recipes.map(recipe=>recipe.id), displayedAdvancements:advancements.length, hiddenAdvancements:hiddenCount};
}
