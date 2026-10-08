import fs from 'node:fs/promises';
import path from 'node:path';

export const displayName = value => String(value).replace(/Mark 6/g, '马克六型').replace(/TNT/g, '炸药');

const fields = {
  player:'玩家',entity:'目标实体',item:'使用物品',items:'匹配物品',child:'繁殖后代',damage:'伤害条件',taken:'实际受到的伤害',killing_blow:'致命伤害',durability:'剩余耐久',delta:'耐久变化',position:'位置',recipes:'完成配方',mainhand:'主手',offhand:'副手',head:'头部装备',min:'至少',max:'至多',is_fall_flying:'正在滑翔',is_on_ground:'在地面',source_entity:'伤害来源',direct_entity:'直接伤害实体',is_direct:'直接伤害',absolute:'直线距离',speed:'移动速度',dimension:'维度',blocks:'方块种类',block:'方块条件',structures:'结构',components:'数据组件',expected:'要求值',type:'条件类型',terms:'子条件',predicate:'实体条件',equipment:'装备',movement:'移动',flags:'状态',distance:'距离',nbt:'精确数据',offsetX:'相邻 X 偏移',offsetY:'相邻 Y 偏移',offsetZ:'相邻 Z 偏移',x:'X 坐标',y:'Y 坐标',z:'Z 坐标',entity_type:'实体种类',id:'实体种类',lightning:'雷击条件',location:'位置条件',tags:'结构标签',glider:'滑翔能力',custom_name:'自定义名称','sheep/color':'羊的颜色','axolotl/variant':'美西螈变种',experience:'经验',loot:'战利品',function:'奖励函数'
};
const literals = {
  true:'是',false:'否',this:'当前判定实体',black:'黑色',blue:'蓝色',brown:'棕色',cyan:'青色',gray:'灰色',green:'绿色',light_blue:'淡蓝色',light_gray:'淡灰色',lime:'黄绿色',magenta:'品红色',orange:'橙色',pink:'粉红色',purple:'紫色',red:'红色',white:'白色',yellow:'黄色',
  'minecraft:any_of':'满足任一分支','minecraft:all_of':'满足全部分支','minecraft:entity_properties':'实体条件','minecraft:location_check':'位置检查','minecraft:overworld':'主世界','minecraft:the_nether':'下界','minecraft:the_end':'末地','minecraft:fly_into_wall':'撞墙伤害','minecraft:out_of_world':'虚空伤害','minecraft:sonic_boom':'音爆伤害','minecraft:lava':'熔岩',
  '#minecraft:all_signs':'任意告示牌','#minecraft:arrows':'任意箭','#minecraft:swords':'任意剑','#minecraft:village':'任意村庄结构','{Tame:1b}':'已驯服','jeb_':'命名为 `jeb_`'
};

/** Player-facing labels are distinct from the unmodified resource JSON. */
export function conditionDescriptions(names) {
  const value = raw => {
    if (raw === null) return '未设置';
    if (Object.hasOwn(literals, String(raw))) return literals[String(raw)];
    if (typeof raw !== 'string') return String(raw);
    if (names[raw]) return displayName(names[raw]);
    const sign = /^\{(front|back)_text:\{messages:\['(.+)'\]\}\}$/.exec(raw);
    if (sign) return `${sign[1] === 'front' ? '正面' : '背面'}文字包含「${sign[2]}」`;
    // Unknown exact literals remain explicit code rather than masquerading as prose.
    return `\`${raw}\``;
  };
  const label = key => {
    const normalized = key.replace(/^minecraft:/, '');
    if (!fields[normalized]) throw new Error(`Missing Chinese condition label: ${key}`);
    return fields[normalized];
  };
  const rows = (raw, prefix = '') => {
    if (Array.isArray(raw)) return raw.flatMap((child, i) => rows(child, `${prefix} / 第 ${i+1} 项`));
    if (raw && typeof raw === 'object') {
      if (!Object.keys(raw).length) return [[prefix, '已设置']];
      return Object.entries(raw).flatMap(([key, child]) => rows(child, `${prefix}${prefix ? ' / ' : ''}${label(key)}`));
    }
    return [[prefix, value(raw)]];
  };
  return {value, rows};
}

export async function loadEditorial(root) {
  return JSON.parse(await fs.readFile(path.join(root, 'wiki/content/editorial.json'), 'utf8'));
}

/** Reject obsolete prose terminology while allowing exact commands, formulas and names. */
export function verifyEditorial(wiki, editorial) {
  const obsolete = /\b(?:Dialog|dialog|UI|GUI|modifier|tick|EMA|true|false)\b|商城|指令/;
  const inspect = (raw, where) => {
    if (typeof raw === 'string') {
      const prose = raw.replace(/`[^`]*`|\$[^$]*\$|\]\([^)]*\)/g, '');
      if (obsolete.test(prose)) throw new Error(`Unedited terminology in ${where}: ${raw}`);
    } else if (Array.isArray(raw)) raw.forEach(child => inspect(child, where));
    else if (raw && typeof raw === 'object' && raw.type !== 'code') {
      for (const [key, child] of Object.entries(raw)) if (!['id','key','page','anchor','language','src','resultIcon','keyImages'].includes(key)) inspect(child, where);
    }
  };
  for (const page of wiki.pages) {
    if (!editorial.facts[page.id]?.length) throw new Error(`Missing topic facts: ${page.id}`);
    inspect(page, page.id);
  }
  const navigation = wiki.nav.flatMap(group => group.items.map(item => item.page));
  if (new Set(navigation).size !== wiki.pages.length || navigation.length !== wiki.pages.length) throw new Error('Every page must have exactly one subject navigation entry');
  console.log(`WIKI_EDITORIAL_PASS: ${wiki.pages.length} topic plans, unique navigation, unified prose terminology`);
}
