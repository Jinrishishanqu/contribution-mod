import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const filename = id => id === 'home' ? 'index.html' : `${id}.html`;

/** Generate regular HTML links: no router, fetch, server or network required. */
export async function writeSite(root, wiki, rendered, navigation, version) {
  const folder = path.join(root, 'wiki');
  const anchors = new Map();
  for (const [id, html] of rendered) {
    for (const match of html.matchAll(/\bid="([^"]+)"/g)) {
      if (anchors.has(match[1])) throw new Error(`Duplicate site anchor: ${match[1]}`);
      anchors.set(match[1], id);
    }
  }
  const rewrite = (html, current) => html.replace(/href="([^"]+)"/g, (_, href) => {
    if (!href.startsWith('#')) return `href="${href}"`;
    const target = href.slice(1);
    const owner = anchors.get(target);
    if (!owner) throw new Error(`Unresolved site anchor: ${target}`);
    return `href="${owner === current ? '' : filename(owner)}#${target}"`;
  });
  const outputs = new Map();
  const index = [];
  for (const page of wiki.pages) {
    const content = rendered.get(page.id).replace(/ hidden(?=>)/, '');
    const header = `<header class="topbar"><nav class="topbar-crumbs" aria-label="面包屑"><a href="index.html">首页</a><span> / ${escape(page.title)}</span></nav><span class="topbar-spacer"></span><button id="mobile-nav" type="button" aria-expanded="false" aria-controls="site-navigation">导航</button><button id="theme-toggle" class="theme-toggle" type="button" aria-pressed="false">切换主题</button></header>`;
    outputs.set(filename(page.id), `<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="description" content="${escape(page.summary ?? wiki.brand.description)}"><title>${escape(page.title)} · ${escape(wiki.brand.title)} ${escape(version)}</title><link rel="stylesheet" href="assets/wiki.css"><script defer src="assets/search-index.js"></script><script defer src="assets/site.js"></script></head><body data-current-page="${page.id}"><a class="skip-link" href="#${page.id}">跳到正文</a><div class="shell">${rewrite(navigation, page.id).replace('<aside class="sidebar"', '<aside id="site-navigation" class="sidebar"')}<main class="main">${header}${rewrite(content, page.id)}</main></div></body></html>\n`);
    const plain = content.replace(/<details\b[\s\S]*?<\/details>/g, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
    index.push({title: page.title, url: filename(page.id), text: plain, summary: page.summary ?? ''});
  }
  outputs.set('assets/search-index.js', `window.WIKI_INDEX = ${JSON.stringify(index).replace(/</g, '\\u003c')};\n`);
  async function assets(directory) {
    for (const entry of await fs.readdir(directory, {withFileTypes:true})) {
      const file = path.join(directory,entry.name);
      if (entry.isDirectory()) await assets(file);
      else if (entry.name !== 'search-index.js') outputs.set(path.relative(folder,file).replaceAll('\\','/'),await fs.readFile(file));
    }
  }
  await assets(path.join(folder,'assets'));
  outputs.set('commands.xlsx',await fs.readFile(path.join(root,'documentation/commands.xlsx')));
  // Validate every local link, cross-page fragment and asset before publishing.
  for (const [file, html] of outputs) {
    if (!file.endsWith('.html')) continue;
    if ((html.match(/<h1\b/g) ?? []).length !== 1) throw new Error(`${file}: expected one H1`);
    for (const match of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
      const href = match[1];
      if (/^(https?:|mailto:)/.test(href)) continue;
      const [target, fragment] = href.split('#');
      const resolved = target || file;
      if (!outputs.has(resolved)) throw new Error(`${file}: missing local target ${href}`);
      if (fragment && !outputs.get(resolved).includes(`id="${fragment}"`)) throw new Error(`${file}: missing fragment ${href}`);
    }
  }
  const hashes = {};
  for (const [file, text] of outputs) {
    if (!file.startsWith('assets/') || file === 'assets/search-index.js') await fs.writeFile(path.join(folder, file), text);
    hashes[file] = crypto.createHash('sha256').update(await fs.readFile(path.join(folder, file))).digest('hex');
  }
  console.log(`WIKI_SITE_PASS: ${wiki.pages.length} pages, local links and assets verified`);
  return hashes;
}
