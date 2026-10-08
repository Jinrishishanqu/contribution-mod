import fs from 'node:fs/promises';
import path from 'node:path';

export async function loadMedia(root) {
  const catalog = JSON.parse(await fs.readFile(path.join(root, 'wiki/content/media.json'), 'utf8'));
  for (const [key, icon] of Object.entries(catalog.icons)) {
    if (!/^assets\/images\/[a-zA-Z0-9_\-/\u0080-\uffff.]+\.png$/.test(icon.src) || icon.src.includes('..')) throw new Error(`Unsafe wiki image: ${key}`);
    await fs.access(path.join(root, 'wiki', icon.src));
  }
  if (Object.keys(catalog.unresolved).length) throw new Error(`Unresolved wiki media: ${JSON.stringify(catalog.unresolved)}`);
  return catalog;
}

export function mediaKey(stack) {
  if (typeof stack === 'string') return stack === '#minecraft:logs' ? 'minecraft:oak_log' : stack;
  if (Array.isArray(stack)) return mediaKey(stack[0]);
  const model = stack?.components?.['minecraft:item_model'];
  return model ? `model:${model}` : mediaKey(stack?.id ?? stack?.item ?? stack?.items);
}

const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

export function mediaRenderer(catalog) {
  const image = (key, alt, className = 'item-icon') => {
    const icon = catalog.icons[key];
    if (!icon) throw new Error(`Missing wiki illustration: ${key}`);
    return `<img class="${className}" src="${escape(icon.src)}" alt="${escape(alt)}" width="128" height="128" loading="lazy" decoding="async">`;
  };
  const slot = (item, label = '') => `<div class="slot-group"><div class="item-slot" title="${escape(item.label)}">${image(item.key, item.label)}${item.count > 1 ? `<span class="item-count">${item.count}</span>` : ''}</div>${label ? `<span class="slot-label">${escape(label)}</span>` : ''}</div>`;
  const gallery = block => `<figure class="media-figure${block.items.length === 1 ? ' single-figure' : ''}"><div class="item-gallery${block.compact ? ' compact' : ''}">${block.items.map(item => `<figure class="gallery-item">${image(item.key, item.label)}<figcaption>${escape(item.label)}${item.text ? `<small>${escape(item.text)}</small>` : ''}</figcaption></figure>`).join('')}</div>${block.caption ? `<figcaption class="media-caption">${escape(block.caption)}</figcaption>` : ''}</figure>`;
  const flow = block => `<figure class="media-figure"><ol class="visual-flow">${block.items.map(item => `<li>${image(item.key, item.label)}<strong>${escape(item.label)}</strong>${item.text ? `<span>${escape(item.text)}</span>` : ''}</li>`).join('')}</ol>${block.caption ? `<figcaption class="media-caption">${escape(block.caption)}</figcaption>` : ''}</figure>`;
  const recipe = block => {
    const result = slot({key:block.resultIcon,label:block.result,count:block.count ?? 1}, '产物');
    if (block.pattern) {
      return `<figure class="recipe-figure"><div class="recipe-layout"><div class="recipe-grid" aria-label="工作台三乘三配方">${block.pattern.join('').split('').map(symbol => symbol === ' ' ? '<div class="item-slot empty" aria-label="空槽"></div>' : slot({key:block.keyImages[symbol],label:block.key[symbol]})).join('')}</div><span class="recipe-arrow" aria-hidden="true">→</span>${result}</div><figcaption>${escape(block.result)}</figcaption></figure>`;
    }
    return `<figure class="recipe-figure"><div class="recipe-layout ${block.mode === 'smithing' ? 'smithing-layout' : 'transmute-layout'}">${block.inputs.map((item, i) => slot(item, block.mode === 'smithing' ? ['模板','基底','附加'][i] : '材料')).join('')}<span class="recipe-arrow" aria-hidden="true">→</span>${result}</div><figcaption>${block.mode === 'smithing' ? '锻造台' : '无序合成'} · ${escape(block.result)}</figcaption></figure>`;
  };
  return {image, gallery, flow, recipe};
}
