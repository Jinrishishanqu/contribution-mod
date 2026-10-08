import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

// Historical prototype importer. For approved edited packs use import-authored-resource-pack.mjs;
// rerunning this old importer intentionally regenerates prototype geometry, not user edits.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const source = path.join(root, 'datapack/assets/minecraft');
const output = path.join(root, 'code/src/main/resources');
const assets = path.join(output, 'assets/contribution');
const generatedModels = new Set();
const read = async file => JSON.parse(await fs.readFile(file, 'utf8'));
const write = async (file, value) => {
  await fs.mkdir(path.dirname(file), {recursive: true});
  await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n');
  if (file.startsWith(path.join(assets, 'models') + path.sep)) generatedModels.add(file);
};
const copied = new Set();
const importing = new Set();
const parents = new Map();
async function model(ref, family) {
  const original = ref.replace(/^minecraft:/, '');
  const target = original.replace('item/trident/', 'item/weapon_skin/spear/')
    .replace(/^item\/(sword|axe|hoe|pickaxe)\//, 'item/weapon_skin/$1/');
  if (importing.has(target)) throw new Error(`Cyclic model parent: ${ref}`);
  if (copied.has(target)) return `contribution:${target}`;
  importing.add(target);
  const data = await read(path.join(source, 'models', original + '.json'));
  if (family === 'spear') {
    data.parent = 'minecraft:item/spear_in_hand';
    // Inherit the native spear hand transforms, preserving any authored non-hand views.
    for (const view of ['firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand'])
      if (data.display) delete data.display[view];
    if (data.display && !Object.keys(data.display).length) delete data.display;
  }
  if (data.parent) {
    const parent = data.parent.replace(/^minecraft:/, '');
    let local = false;
    try { await fs.access(path.join(source, 'models', parent + '.json')); local = true; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    // Recursively migrate authored parents; retain genuine vanilla references.
    data.parent = local ? await model(`minecraft:${parent}`, family) : `minecraft:${parent}`;
  }
  for (const [key, value] of Object.entries(data.textures ?? {})) {
    if (value.startsWith('#')) continue;
    const texture = value.replace(/^minecraft:/, '');
    const destination = texture.replace('item/trident/', 'item/weapon_skin/spear/')
      .replace(/^item\/(sword|axe|hoe|pickaxe)\//, 'item/weapon_skin/$1/');
    const input = path.join(source, 'textures', texture + '.png');
    const out = path.join(assets, 'textures', destination + '.png');
    await fs.mkdir(path.dirname(out), {recursive: true});
    await fs.copyFile(input, out);
    try { await fs.copyFile(input + '.mcmeta', out + '.mcmeta'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    data.textures[key] = `contribution:${destination}`;
  }
  // Element models do not inherit generated-model particle aliases.
  if (!data.parent && data.textures && !data.textures.particle)
    data.textures.particle = `#${Object.keys(data.textures)[0]}`;
  // Identical geometry/display blocks are shared parents; leaf files only bind textures.
  const {textures, ...structure} = data;
  if (structure.parent && !structure.parent.includes(':')) structure.parent = `minecraft:${structure.parent}`;
  const signature = JSON.stringify(structure);
  let parent = parents.get(signature);
  if (!parent) {
    parent = `contribution:item/weapon_skin/base/${family}_${crypto.createHash('sha256').update(signature).digest('hex').slice(0, 12)}`;
    parents.set(signature, parent);
    await write(path.join(assets, 'models', parent.split(':')[1] + '.json'), structure);
  }
  await write(path.join(assets, 'models', target + '.json'), {parent, textures});
  importing.delete(target);
  copied.add(target);
  return `contribution:${target}`;
}
const sources = {sword:'diamond_sword', axe:'diamond_axe', pickaxe:'diamond_pickaxe', hoe:'diamond_hoe', spear:'trident', shovel:null};
const labels = {sword:'剑', axe:'斧', pickaxe:'镐', hoe:'锄', spear:'矛', shovel:'铲'};
const catalog = [];
for (const [family, file] of Object.entries(sources)) {
  const entries = file ? (await read(path.join(source, 'items', file + '.json'))).model.cases : [];
  const names = [];
  const cases = [];
  for (const entry of entries) {
    // The old trident's charging branch is inapplicable to the wooden-sword carrier.
    const branch = entry.model.on_false ?? entry.model;
    if (!branch.model.startsWith(`minecraft:item/${family === 'spear' ? 'trident' : family}/`)) continue;
    const ref = await model(branch.model, family);
    names.push(entry.when);
    let selected = {type:'minecraft:model', model:ref};
    if (family === 'spear') {
      const leaf = await read(path.join(assets, 'models', ref.split(':')[1] + '.json'));
      const texture = leaf.textures.layer0.split(':')[1];
      const name = path.posix.basename(ref);
      const gui = `item/weapon_skin/spear/gui/${name}`;
      const input = path.join(assets, 'textures', texture + '.png');
      const out = path.join(assets, 'textures', gui + '.png');
      await fs.mkdir(path.dirname(out), {recursive:true});
      // Seed only missing GUI assets; later user replacements survive reimports.
      try {
        await fs.copyFile(input, out, (await import('node:fs')).constants.COPYFILE_EXCL);
        try { await fs.copyFile(input + '.mcmeta', out + '.mcmeta'); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
      } catch (error) { if (error.code !== 'EEXIST') throw error; }
      await write(path.join(assets, 'models', gui + '.json'), {
        parent:'minecraft:item/generated', textures:{layer0:`contribution:${gui}`}
      });
      selected = {type:'minecraft:select',property:'minecraft:display_context',
        cases:[{when:['gui','ground','fixed','on_shelf'],model:{type:'minecraft:model',model:`contribution:${gui}`}}],
        fallback:selected};
    }
    cases.push({when:entry.when, model:selected});
  }
  const blankName = `原初${labels[family]}`;
  const blank = `contribution:item/weapon_skin/${family}/static`;
  await fs.mkdir(path.join(assets, 'textures/item/weapon_skin', family), {recursive:true});
  await fs.copyFile(path.join(root, `design/static_${family}.png`), path.join(assets, `textures/item/weapon_skin/${family}/static.png`));
  await write(path.join(assets, `models/item/weapon_skin/${family}/static.json`), {
    parent:family === 'spear' ? 'minecraft:item/generated' : 'minecraft:item/handheld', textures:{layer0:`contribution:item/weapon_skin/${family}/static`}
  });
  let fallback = {type:'minecraft:model',model:blank};
  if (family === 'spear') {
    await fs.copyFile(path.join(root, 'design/static_spear_in_hand.png'), path.join(assets, 'textures/item/weapon_skin/spear/static_in_hand.png'));
    await write(path.join(assets, 'models/item/weapon_skin/spear/static_in_hand.json'), {
      parent:'minecraft:item/spear_in_hand', textures:{layer0:'contribution:item/weapon_skin/spear/static_in_hand'}
    });
    fallback = {type:'minecraft:select',property:'minecraft:display_context',
      cases:[{when:['gui','ground','fixed','on_shelf'],model:fallback}],
      fallback:{type:'minecraft:model',model:'contribution:item/weapon_skin/spear/static_in_hand'}};
  }
  // No legacy shovel skins exist; provide an explicit vanilla-looking choice.
  if (!file) { names.push('铁铲'); cases.push({when:'铁铲',model:{type:'minecraft:model',model:'minecraft:item/iron_shovel'}}); }
  await write(path.join(assets, `items/weapon_skin/${family}.json`), { ...(family === 'spear' ? {swap_animation_scale:1.95} : {}), model:{
    type:'minecraft:select',property:'minecraft:component',component:'minecraft:item_name',cases,
    fallback
  }});
  catalog.push({type:family, label:labels[family], initialName:blankName, names});
}
await write(path.join(output, 'contribution/weapon-skins.json'), catalog);
// Remove only obsolete, content-hashed parents owned by this generator.
const base = path.join(assets, 'models/item/weapon_skin/base');
for (const name of await fs.readdir(base)) {
  const file = path.join(base, name);
  if (/^(sword|axe|pickaxe|hoe|spear|shovel)_[a-f0-9]{12}\.json$/.test(name) && !generatedModels.has(file))
    await fs.unlink(file);
}
console.log(`WEAPON_SKINS_IMPORTED: ${catalog.reduce((sum,c)=>sum+c.names.length,0)} choices, ${copied.size} leaves, ${parents.size} shared parents`);
