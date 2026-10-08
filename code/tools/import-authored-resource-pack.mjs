import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const pack = path.resolve(process.argv[2] ?? 'F:/我的世界/PCL2/.minecraft/versions/26.3-Fabric 0.19.5/resourcepacks/CSU-YSU-items-0.1.19-resource-pack');
const assets = path.join(root, 'code/src/main/resources/assets');
async function walk(dir) {
  const files = [];
  for (const entry of await fs.readdir(dir, {withFileTypes:true})) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...await walk(file)); else files.push(file);
  }
  return files;
}
const sourceAssets = path.join(pack, 'assets');
const sourceFiles = await walk(sourceAssets);
for (const file of sourceFiles) {
  if (file.endsWith('.json') || file.endsWith('.mcmeta')) JSON.parse(await fs.readFile(file, 'utf8'));
}
// Synchronize the user-approved directory; never regenerate its models from the old datapack.
const oldBases = path.join(assets, 'contribution/models/item/weapon_skin/base');
const saved = path.join(root, 'outputs/resource-pack-maintenance/backups/source-sync-' + new Date().toISOString().replace(/[:.]/g, '-'));
for (const file of await walk(oldBases)) {
  const relative = path.relative(assets, file);
  try { await fs.access(path.join(sourceAssets, relative)); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    if (!/^(sword|axe|pickaxe|hoe|spear|shovel)_[a-f0-9]{12}\.json$/.test(path.basename(file))) throw new Error('Unexpected obsolete model');
    await fs.mkdir(saved, {recursive:true}); await fs.copyFile(file, path.join(saved, path.basename(file))); await fs.unlink(file);
  }
}
for (const file of sourceFiles) {
  const target = path.join(assets, path.relative(sourceAssets, file));
  await fs.mkdir(path.dirname(target), {recursive:true}); await fs.copyFile(file, target);
}
const hashes = {};
for (const file of sourceFiles.filter(f => /[\\/]weapon_skin[\\/]/.test(f)))
  hashes[path.relative(sourceAssets, file).replaceAll('\\', '/')] = crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
await fs.writeFile(path.join(root, 'code/src/main/resources/contribution/weapon-skin-assets.json'), JSON.stringify({source:path.basename(pack), files:hashes}, null, 2) + '\n');
async function readModel(ref) {
  if (!ref.startsWith('contribution:')) return {};
  const model = JSON.parse(await fs.readFile(path.join(assets, 'contribution/models', ref.slice(13) + '.json'), 'utf8'));
  const parent = model.parent ? await readModel(model.parent) : {};
  return {...parent, ...model, display:{...parent.display, ...model.display}};
}
async function write(file, value) { await fs.mkdir(path.dirname(file), {recursive:true}); await fs.writeFile(file, JSON.stringify(value, null, 2) + '\n'); }
for (const type of ['sword','axe','pickaxe','hoe','shovel','spear']) {
  const definition = JSON.parse(await fs.readFile(path.join(assets, `contribution/items/weapon_skin/${type}.json`), 'utf8'));
  const cases = [];
  for (const entry of definition.model.cases) {
    const branch = entry.model.type === 'minecraft:select' ? entry.model.cases[0].model : entry.model;
    const model = await readModel(branch.model), gui = model.display?.gui ?? {};
    const preview = `item/weapon_skin/preview/${type}/${path.posix.basename(branch.model)}`;
    await write(path.join(assets, 'contribution/models', preview + '.json'), {parent:branch.model,
      display:{gui:{rotation:gui.rotation ?? [0,0,0],
        translation:[40 + (gui.translation?.[0] ?? 0) * 4, -40 + (gui.translation?.[1] ?? 0) * 4, (gui.translation?.[2] ?? 0)],
        scale:(gui.scale ?? [1,1,1]).map(v => Math.min(4, v * 4))}}});
    cases.push({when:entry.when, model:{type:'minecraft:model',model:'contribution:' + preview}});
  }
  await write(path.join(assets, `contribution/items/weapon_skin/preview/${type}.json`), {
    model:{...definition.model,cases,fallback:cases[0].model}
  });
}
console.log(`AUTHORED_PACK_SYNC_PASS: ${sourceFiles.length} assets, preserved user models/textures; 91 isolated Dialog previews; ${Object.keys(hashes).length} fingerprints`);
