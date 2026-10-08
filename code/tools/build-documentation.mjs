import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';
import { gameContent } from './wiki-game-content.mjs';
import { writeSite } from './wiki-site.mjs';
import { loadMedia, mediaRenderer } from './wiki-media.mjs';
import { loadEditorial, verifyEditorial } from './wiki-editorial.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.join(root, 'documentation');
const exported = JSON.parse(await fs.readFile(path.join(root, 'code/build/documentation/commands.json'), 'utf8'));
const catalog = JSON.parse(await fs.readFile(path.join(output, 'source/catalog.json'), 'utf8'));
const wiki = JSON.parse(await fs.readFile(path.join(root, 'wiki/content/site.json'), 'utf8'));
wiki.pages = await Promise.all(wiki.pages.map(id => fs.readFile(path.join(root, `wiki/content/pages/${id}.json`), 'utf8').then(JSON.parse)));
const media = await loadMedia(root);
const editorial = await loadEditorial(root);
const art = mediaRenderer(media);
const game = await gameContent(root);
wiki.pages.push(...game.pages);
verifyEditorial(wiki, editorial);
if (new Set(wiki.pages.map(page => page.id)).size !== wiki.pages.length || wiki.pages.some(page => !/^[a-z0-9-]+$/.test(page.id))) {
  throw new Error('Wiki page IDs must be unique lowercase names without paths');
}

/* ------------------------------------------------------------------ *
 * Text helpers
 * ------------------------------------------------------------------ */

const escape = (value) =>
  String(value).replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);

const slug = (value) =>
  String(value)
    .trim()
    .toLowerCase()
    .replace(/[\s、，,。.：:·（）()【】\[\]「」/\\]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '') || 'section';

/**
 * Minimal, offline TeX reader covering the notation this wiki actually uses.
 * Each command's arity is explicit, so "\max\left(" is not read as "\max"
 * swallowing "(", and bare "_"/"^" always consume the next token as a script.
 */
const math = (tex) => {
  const SYMBOLS = {
    alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', theta: 'θ', sigma: 'σ', mu: 'μ',
    pi: 'π', rho: 'ρ', lambda: 'λ', Delta: 'Δ', Sigma: 'Σ', Omega: 'Ω',
    times: '×', cdot: '·', pm: '±', le: '≤', ge: '≥', ne: '≠', approx: '≈', sim: '∼',
    to: '→', rightarrow: '→', infty: '∞', sum: '∑', lfloor: '⌊', rfloor: '⌋',
    lceil: '⌈', rceil: '⌉', lvert: '|', rvert: '|', left: '', right: '',
    quad: '\u2003', qquad: '\u2003\u2003', ',': '\u2009', ';': '\u2009', '!': '',
    min: 'min', max: 'max', log: 'log', exp: 'exp',
  };
  const STYLED = { operatorname: 'cs', mathcal: 'cs', mathrm: 'cs', text: 'mtx' };
  /** command -> number of braced/tight arguments it consumes */
  const ARITY = { frac: 2, sqrt: 1, text: 1, operatorname: 1, mathcal: 1, mathrm: 1, '^': 1, _: 1 };

  const groups = (input) => {
    const nodes = [];
    let index = 0;
    const braced = (from) => {
      if (input[from] !== '{') return null;
      let depth = 1;
      let end = from + 1;
      while (end < input.length && depth > 0) {
        if (input[end] === '{') depth++;
        else if (input[end] === '}') depth--;
        end++;
      }
      return { nodes: groups(input.slice(from + 1, end - 1)), end };
    };
    while (index < input.length) {
      const character = input[index];
      if (character === '{') {
        const group = braced(index);
        nodes.push(...(group ? group.nodes : []));
        index = group ? group.end : index + 1;
      } else if (character === '\\' || character === '_' || character === '^') {
        const name = character === '\\' ? /^\\([A-Za-z]+)/.exec(input.slice(index)) : null;
        const command = { command: name ? name[1] : character === '\\' ? input[index + 1] ?? '' : character };
        index += name ? name[0].length : character === '\\' ? 2 : 1;
        const arity = ARITY[command.command] ?? 0;
        const collected = [];
        while (collected.length < arity) {
          const group = braced(index) ?? (index < input.length && input[index] !== ' '
            ? { nodes: groups(input.slice(index, index + 1)), end: index + 1 }
            : null);
          if (!group) break;
          collected.push({ nodes: group.nodes });
          index = group.end;
        }
        if (arity === 1 && collected.length === 1) command.argument = { nodes: collected[0].nodes };
        if (arity === 2 && collected.length === 2) {
          command.argument = { nodes: collected[0].nodes };
          command.denominator = { nodes: collected[1].nodes };
        }
        nodes.push(command);
      } else {
        nodes.push({ char: character });
        index++;
      }
    }
    return nodes;
  };

  const plain = (list) =>
    (list ?? [])
      .map((item) => {
        if (!item) return '';
        if (item.char !== undefined) return item.char;
        if (item.command) {
          if (STYLED[item.command]) return plain(item.argument?.nodes);
          return SYMBOLS[item.command] ?? item.command;
        }
        return plain(item.nodes);
      })
      .join('');

  const emit = (nodes) => {
    if (!nodes) return '';
    let out = '';
    for (const node of nodes) {
      if (!node) continue;
      if (node.command) {
        const head = node.command;
        const argument = node.argument;
        if (head === '^' || head === '_') {
          const tag = head === '^' ? 'sup' : 'sub';
          out += `<${tag}>${argument ? emit(argument.nodes) : ''}</${tag}>`;
        } else if (head === 'frac') {
          out += `<span class="frac"><span class="num">${argument ? emit(argument.nodes) : ''}</span><span class="den">${node.denominator ? emit(node.denominator.nodes) : ''}</span></span>`;
        } else if (head === 'sqrt') {
          out += `<span class="sqrt">√<span class="radicand">${argument ? emit(argument.nodes) : ''}</span></span>`;
        } else if (STYLED[head]) {
          const content = argument ? (head === 'text' ? plain(argument.nodes) : emit(argument.nodes)) : '';
          out += `<span class="${STYLED[head]}">${content}</span>`;
        } else {
          out += SYMBOLS[head] ?? head;
        }
      } else if (node.char !== undefined) {
        out += node.char.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      } else if (node.nodes) {
        out += emit(node.nodes);
      }
    }
    return out;
  };

  return `<span class="math">${emit(groups(String(tex).replace(/\s+/g, ' ').trim()))}</span>`;
};

const pageIds = new Set(wiki.pages.map((page) => page.id));
const pageTitles = new Map(wiki.pages.map((page) => [page.id, page.title]));
const pageById = new Map(wiki.pages.map((page) => [page.id, page]));

/** Inline markup: code spans, $math$, **bold**, [label](page:id) and [label](file:name). */
const inline = (value) => {
  const escaped = escape(value);
  const codeSpans = [];
  let text = escaped.replace(/`([^`]+)`/g, (_, code) => {
    codeSpans.push(`<code>${code}</code>`);
    return `\u0000${codeSpans.length - 1}\u0000`;
  });
  text = text.replace(/\$([^$]+)\$/g, (_, tex) => math(tex));
  text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  text = text.replace(/\[([^\]]+)\]\((page|file):([^)]+)\)/g, (match, label, kind, target) => {
    if (kind === 'page') {
      if (!pageIds.has(target)) throw new Error(`Wiki link points at unknown page: ${target}`);
      return `<a class="wikilink" href="#${target}">${label}</a>`;
    }
    if (target !== 'commands.xlsx') throw new Error(`Wiki file link must be commands.xlsx: ${target}`);
    return `<a class="wikilink" href="${target}">${label}</a>`;
  });
  return text.replace(/\u0000(\d+)\u0000/g, (_, index) => codeSpans[Number(index)]);
};

const ATTENTION = {
  info: { icon: 'ℹ', label: '说明' },
  note: { icon: '✎', label: '注意' },
  tip: { icon: '☀', label: '技巧' },
  warn: { icon: '⚠', label: '警告' },
  danger: { icon: '✖', label: '危险' },
};

const renderBlock = (block) => {
  switch (block.type) {
    case 'subsection': return `<section class="entry-section"><h3>${inline(block.title)}</h3>${block.blocks.map(renderBlock).join('\n')}</section>`;
    case 'details':
      return `<details class="source-details"${block.anchor ? ` id="${escape(block.anchor)}"` : ''}><summary>${inline(block.title)}</summary>${block.blocks.map(renderBlock).join('\n')}</details>`;
    case 'gallery': return art.gallery(block);
    case 'flow': return art.flow(block);
    case 'illustration': return `<figure class="entry-illustration">${art.image(block.key, block.label)}<figcaption>${inline(block.text)}</figcaption></figure>`;
    case 'recipe': return art.recipe(block);
    case 'p':
      return `<p>${inline(block.text)}</p>`;
    case 'code':
      return `<pre class="code"${block.language ? ` data-language="${escape(block.language)}"` : ''}><code>${escape(block.text)}</code></pre>`;
    case 'formula':
      return `<div class="formula">${math(block.text)}</div>`;
    case 'figure':
      return `<pre class="figure">${escape(block.text)}</pre>`;
    case 'list':
      return `<${block.ordered ? 'ol' : 'ul'}>${block.items.map((item) => `<li>${inline(item)}</li>`).join('')}</${block.ordered ? 'ol' : 'ul'}>`;
    case 'table': {
      const head = block.columns.map((column) => `<th scope="col">${inline(column)}</th>`).join('');
      const body = block.rows.map((row) => `<tr>${row.map((cell) => `<td>${inline(cell)}</td>`).join('')}</tr>`).join('\n');
      return `<div class="table-wrap"><table class="wikitable">\n<thead><tr>${head}</tr></thead>\n<tbody>\n${body}\n</tbody>\n</table></div>`;
    }
    case 'callout': {
      const attention = ATTENTION[block.style] ?? ATTENTION.info;
      const title = block.title ? `<span class="msgbox-title">${inline(block.title)}</span>` : '';
      return `<div class="msgbox msgbox-${block.style}"><span class="msgbox-icon" aria-hidden="true">${attention.icon}</span><div class="msgbox-body">${title}<span class="msgbox-text">${inline(block.text)}</span></div></div>`;
    }
    case 'cards': {
      const items = block.items
        .map((item) => {
          const heading = item.page ? `<a class="wikilink" href="#${item.page}">${inline(item.title)}</a>` : inline(item.title);
          return `<div class="card${item.image ? ' illustrated-card' : ''}">${item.image ? art.image(item.image,item.title) : ''}<div class="card-body"><div class="card-title">${heading}</div><p>${inline(item.text)}</p></div></div>`;
        })
        .join('\n');
      return `<div class="cards" data-columns="${block.columns ?? 3}">\n${items}\n</div>`;
    }
    default:
      throw new Error(`Unknown wiki block type: ${block.type}`);
  }
};

/* ------------------------------------------------------------------ *
 * Command reference data
 * ------------------------------------------------------------------ */

const SOURCE_PREFIX = /^(\/[a-z_]+(?: [a-z_]+)?)/;

const rows = exported.commands.map((route) => {
  const family = route.syntax.split(' ').slice(0, route.syntax.includes(' ') ? 2 : 1).join(' ');
  const entry = catalog.groups[family];
  if (!entry) throw new Error(`Missing command documentation: ${family}`);
  const [category, basePurpose, basePermission, baseExample, baseNotes] = entry;
  const [purpose, permission, example, notes] = catalog.overrides?.[route.syntax] ?? [basePurpose, basePermission, baseExample, baseNotes];
  const actualPermission = route.syntax.startsWith('/contribution checkin event ') ? '管理员；只允许主服务器创建' : permission;
  const source = SOURCE_PREFIX.exec(route.syntax)?.[1] ?? route.syntax.split(' ')[0];
  return { category, syntax: route.syntax, purpose, permission: actualPermission, example, notes, arguments: route.argumentTypes || '无参数', source };
});

const familyOrder = [];
for (const row of rows) if (!familyOrder.includes(row.category)) familyOrder.push(row.category);
const familyIndex = new Map(familyOrder.map((name, index) => [name, index]));
rows.sort((a, b) => familyIndex.get(a.category) - familyIndex.get(b.category) || a.syntax.localeCompare(b.syntax));

/**
 * One command rendered as a definition block rather than a seven-column row.
 * A wide table forces a horizontal scrollbar on every page; this reads better
 * and stays inside the content column at any width.
 */
const commandEntry = (row, showModule) => {
  const facts = [
    ['功能', row.purpose],
    ['权限', row.permission],
    ['示例', row.example],
    ['参数与限制', row.notes],
  ];

  return `<article class="cmd" data-search="${escape([row.category, row.syntax, row.purpose, row.permission, row.example, row.notes, row.arguments].join(' ').toLowerCase())}">
<h3 class="cmd-head"><code class="cmd-syntax">${escape(row.syntax)}</code>${showModule ? `<span class="cmd-module">${escape(row.category)}</span>` : ''}</h3>
<dl class="cmd-facts">
${facts.map(([label, value]) => `<dt>${escape(label)}</dt><dd>${label === '示例' ? `<code>${escape(value)}</code>` : inline(value)}</dd>`).join('\n')}
</dl>
${row.arguments && row.arguments !== '无参数' ? `<details class="source-details"><summary>原版参数类型</summary><code>${escape(row.arguments)}</code></details>` : ''}
</article>`;
};

const commandList = (members, showModule) =>
  `<div class="cmd-list">\n${members.map((row) => commandEntry(row, showModule)).join('\n')}\n</div>`;

const commandTable = () => {
  const toolbar =
    '<div class="toolbar"><label for="search">筛选命令</label><input id="search" placeholder="例如 shop、管理员、签到、retry" type="search" autocomplete="off"><span id="count" aria-live="polite"></span></div>';
  const categories = familyOrder
    .map((category) => {
      const members = rows.filter((row) => row.category === category);
      return `<h3 class="cmd-group">${escape(category)}<span class="cmd-group-count">${members.length} 条</span></h3>\n${commandList(members, false)}`;
    })
    .join('\n');
  return `${toolbar}\n${categories}`;
};

const familySummaryTable = () => {
  const families = familyOrder.map((category) => {
    const members = rows.filter((row) => row.category === category);
    const roots = [...new Set(members.map((row) => row.source))].sort();
    return [category, roots.map((root) => `\`${root}\``).join('、'), String(members.length)];
  });
  return renderBlock({ type: 'table', columns: ['模块', '命令根', '路径数'], rows: families });
};

/** Commands belonging to one wiki module page, matched through catalog.groups. */
const rowsForModule = (pageId) => {
  const page = pageById.get(pageId);
  if (!page) throw new Error(`Unknown command module page: ${pageId}`);
  const category = editorial.commandCategories[pageId];
  if (!familyOrder.includes(category)) throw new Error(`Command module page ${pageId} has no matching catalog category: ${category}`);
  return rows.filter((row) => row.category === category);
};

const moduleTable = (pageId) => {
  const members = rowsForModule(pageId);
  if (members.length === 0) return '';
  return `<section class="wiki-section" id="${pageId}-命令路径"><h2>本模块命令路径</h2>\n<p>共 ${members.length} 条命令路径，权限、示例与参数要求列在各条目中。</p>\n${commandList(members, false)}\n</section>`;
};

const moduleCards = () =>
  renderBlock({
    type: 'cards',
    columns: 3,
    items: wiki.pages
      .filter((page) => page.id.startsWith('commands-'))
      .map((page) => ({ page: page.id, title: page.title, text: page.summary ?? '' })),
  });

const commandPage = () => {
  const page = pageById.get('commands');
  const generated = [
    `<h2 id="commands-modules">模块一览</h2>\n${moduleCards()}\n${familySummaryTable()}`,
    `<h2 id="commands-all">全部命令路径</h2>\n${commandTable()}`,
  ];
  const authored = (page.sections ?? []).map(
    (section) => `<h2 id="commands-${section.id ?? slug(section.title)}">${inline(section.title)}</h2>\n${section.blocks.map(renderBlock).join('\n')}`
  );
  return [
    ...authored.slice(0, 1),
    ...generated,
    ...authored.slice(1),
  ];
};

/* ------------------------------------------------------------------ *
 * Page rendering
 * ------------------------------------------------------------------ */

const escapeJson = (value) => JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');

const sections = (page) => {
  const toc = [];
  const html = (page.sections ?? [])
    .map((section) => {
      const id = `${page.id}-${section.id ?? slug(section.title)}`;
      if (section.title) toc.push({ id, title: section.title });
      const blocks = section.blocks.map(renderBlock).join('\n');
      return `<section class="wiki-section"${section.title ? ` id="${id}"` : ''}>\n${section.title ? `<h2>${inline(section.title)}</h2>\n` : ''}${blocks}\n</section>`;
    })
    .join('\n');
  return { html, toc };
};

const articleFor = (page) => {
  if (page.id === 'commands') {
    const parts = commandPage();
    const authoredToc = (page.sections ?? []).map(section => ({ id: `commands-${section.id ?? slug(section.title)}`, title: section.title }));
    const toc = [
      ...authoredToc.slice(0, 1),
      { id: 'commands-modules', title: '模块一览' },
      { id: 'commands-all', title: '全部命令路径' },
      ...authoredToc.slice(1),
    ];
    return { html: parts.join('\n'), toc };
  }
  if (page.id.startsWith('commands-')) {
    const { html, toc } = sections(page);
    const table = moduleTable(page.id);
    if (table) toc.push({ id: `${page.id}-命令路径`, title: '本模块命令路径' });
    return { html: table ? `${html}\n${table}` : html, toc };
  }
  return sections(page);
};

const navGroup = (title, id, items, accessKey) =>
  `<div class="nav-group" data-nav-group>
<h3 class="nav-group-heading"><button type="button" class="nav-toggle" aria-expanded="true" aria-controls="${id}"${accessKey ? ` accesskey="${accessKey}"` : ''}><span class="nav-chevron" aria-hidden="true"></span><span class="nav-toggle-label">${escape(title)}</span></button></h3>
<ul id="${id}">
${items}
</ul>
</div>`;

const navSidebar = () => {
  const groups = wiki.nav
    .map((group, index) => {
      const items = group.items
        .map((item) => {
          const title = item.label ?? pageTitles.get(item.page);
          if (!title) throw new Error(`Navigation points at unknown page: ${item.page}`);
          return `<li><a href="#${item.page}" data-nav="${item.page}">${escape(title)}</a></li>`;
        })
        .join('\n');
      return navGroup(group.title, `nav-list-${index}`, items);
    })
    .join('\n');
  const tools = [
    '<li><a href="#commands" data-nav="commands">命令速查</a></li>',
    '<li><a href="commands.xlsx">下载命令工作簿</a></li>',
    '<li><a href="#documentation" data-nav="documentation">维基维护</a></li>',
  ].join('\n');
  return `<aside class="sidebar" aria-label="站点导航">
<a class="brand" href="#home">
<span class="brand-mark" aria-hidden="true"></span>
<span class="brand-text"><span class="brand-title">${escape(wiki.brand.shortTitle)}</span><span class="brand-tagline">${escape(wiki.brand.tagline)}</span></span>
</a>
<div class="sidebar-search"><label class="visually-hidden" for="site-search">搜索本维基</label><input id="site-search" type="search" placeholder="搜索页面与章节…" autocomplete="off"><div id="site-search-results" class="search-results" hidden></div></div>
<div class="nav">
${groups}
${navGroup('工具', 'nav-list-tools', tools)}
</div>
<div class="sidebar-footer">版本 ${escape(exported.version)} · Minecraft 26.3<br>Fabric Loader 0.19.5 · Java 25</div>
</aside>`;
};

const pageInfo = (page) => {
  const groups = wiki.nav.filter((group) => group.items.some((item) => item.page === page.id));
  const siblings = groups.length ? groups[0].items.filter((item) => item.page !== page.id).slice(0,4) : [];
  const navbox = siblings.length
    ? `<table class="navbox"><tbody>
<tr><th class="navbox-top" colspan="2"><span class="navbox-title">${escape(groups[0].title)}</span></th></tr>
<tr><th>相关页面</th><td><ul class="hlist">${siblings.map((item) => `<li><a href="#${item.page}">${escape(item.label ?? pageTitles.get(item.page))}</a></li>`).join('')}</ul></td></tr>
</tbody></table>`
    : '';
  const categories = groups.flatMap((group) => group.items.filter((item) => item.page === page.id).map(() => group.title));
  return `<aside class="page-info">
<div class="page-info-inner">
<div class="page-info-head">条目概要</div>
<table class="infobox">
${editorial.facts[page.id].map(([label,value])=>`<tr><th>${escape(label)}</th><td>${inline(value)}</td></tr>`).join('\n')}
${page.id === 'commands' || page.id.startsWith('commands-') ? `<tr><th>命令路径</th><td>${page.id === 'commands' ? rows.length : rowsForModule(page.id).length} 条</td></tr>` : ''}
</table>
</div>
${navbox}
<div class="catlinks"><span>分类：</span><ul>${categories.map((name) => `<li>${escape(name)}</li>`).join('')}</ul></div>
</aside>`;
};

/* ------------------------------------------------------------------ *
 * Styles — Minecraft Wiki (MediaWiki Vector) visual language
 * ------------------------------------------------------------------ */

const styles = await fs.readFile(path.join(root, 'wiki/assets/wiki.css'), 'utf8');

/* ------------------------------------------------------------------ *
 * Client script
 * ------------------------------------------------------------------ */

const searchIndex = wiki.pages.flatMap((page) => {
  const entries = [{ page: page.id, title: page.title, section: null, summary: page.summary ?? '' }];
  for (const section of page.sections ?? []) {
    if (section.title) entries.push({ page: page.id, title: page.title, section: section.title, summary: '' });
  }
  if (page.id === 'commands') {
    entries.push({ page: 'commands', title: page.title, section: '模块一览', summary: '' });
    entries.push({ page: 'commands', title: page.title, section: '全部命令路径', summary: '' });
    for (const section of page.sections ?? []) entries.push({ page: 'commands', title: page.title, section: section.title, summary: '' });
  }
  return entries;
});

const clientScript = `
(function(){
  var PAGES = ${escapeJson(wiki.pages.map((page) => ({ id: page.id, title: page.title })))};
  var INDEX = ${escapeJson(searchIndex)};
  var NAV = ${escapeJson(wiki.nav)};
  var BRAND = ${escapeJson(wiki.brand.title)};
  var pageIds = PAGES.map(function(page){ return page.id; });

  function byId(id){ return document.getElementById(id); }
  function normalize(value){ return String(value).toLowerCase(); }

  /* command filter: works over the definition blocks and their group headings */
  var search = byId('search');
  var entries = [].slice.call(document.querySelectorAll('.cmd'));
  var groups = [].slice.call(document.querySelectorAll('.cmd-group'));
  var count = byId('count');
  function filterCommands(){
    if(!search) return;
    var value = search.value.trim().toLowerCase();
    var visible = 0;
    entries.forEach(function(entry){
      var hit = value === '' || (entry.getAttribute('data-search') || '').indexOf(value) !== -1;
      entry.hidden = !hit;
      if(hit) visible++;
    });
    groups.forEach(function(heading){
      var list = heading.nextElementSibling;
      var any = false;
      while(list && list.classList.contains('cmd')){
        if(!list.hidden){ any = true; break; }
        list = list.nextElementSibling;
      }
      heading.hidden = !any;
    });
    if(count) count.textContent = visible + ' / ' + entries.length + ' 条';
  }
  if(search) search.addEventListener('input', filterCommands);
  filterCommands();

  /* collapsible sidebar groups, like the Vector skin's portal panels */
  var NAV_KEY = 'contribution-wiki-nav-collapsed';
  var collapsed = [];
  try { collapsed = JSON.parse(window.localStorage.getItem(NAV_KEY) || '[]') || []; } catch (error) { collapsed = []; }
  function saveCollapsed(){
    try { window.localStorage.setItem(NAV_KEY, JSON.stringify(collapsed)); } catch (error) {}
  }
  function setGroup(group, isCollapsed){
    var toggle = group.querySelector('.nav-toggle');
    group.classList.toggle('collapsed', isCollapsed);
    if(toggle) toggle.setAttribute('aria-expanded', isCollapsed ? 'false' : 'true');
  }
  var groups = [].slice.call(document.querySelectorAll('[data-nav-group]'));
  groups.forEach(function(group){
    var toggle = group.querySelector('.nav-toggle');
    if(!toggle) return;
    var id = toggle.getAttribute('aria-controls');
    setGroup(group, collapsed.indexOf(id) !== -1);
    toggle.addEventListener('click', function(){
      var next = !group.classList.contains('collapsed');
      setGroup(group, next);
      var at = collapsed.indexOf(id);
      if(next && at === -1) collapsed.push(id);
      if(!next && at !== -1) collapsed.splice(at, 1);
      saveCollapsed();
    });
  });

  /* keep the group holding the current page expanded */
  var touched = false;
  function revealGroup(id){
    var link = document.querySelector('[data-nav="' + id + '"]');
    if(!link) return;
    var group = link.closest ? link.closest('[data-nav-group]') : null;
    if(!group || !group.classList.contains('collapsed')) return;
    var toggle = group.querySelector('.nav-toggle');
    var key = toggle ? toggle.getAttribute('aria-controls') : null;
    setGroup(group, false);
    if(key){
      var at = collapsed.indexOf(key);
      if(at !== -1) collapsed.splice(at, 1);
      touched = true;
      saveCollapsed();
    }
  }

  /* site search */
  var siteSearch = byId('site-search');
  var results = byId('site-search-results');
  function runSiteSearch(){
    if(!siteSearch || !results) return;
    var value = normalize(siteSearch.value.trim());
    if(value.length === 0){ results.hidden = true; results.innerHTML = ''; return; }
    var hits = INDEX.filter(function(entry){
      return normalize(entry.title).indexOf(value) !== -1
        || normalize(entry.section || '').indexOf(value) !== -1
        || normalize(entry.summary || '').indexOf(value) !== -1;
    }).slice(0, 40);
    if(hits.length === 0){ results.innerHTML = '<div class="empty">没有匹配的页面或章节</div>'; results.hidden = false; return; }
    results.innerHTML = hits.map(function(hit){
      var section = hit.section ? '<span class="sr-section">' + hit.section + '</span>' : '';
      return '<a href="#' + hit.page + '"><span class="sr-page">' + hit.title + '</span> ' + section + '</a>';
    }).join('');
    results.hidden = false;
  }
  if(siteSearch){
    siteSearch.addEventListener('input', runSiteSearch);
    siteSearch.addEventListener('focus', runSiteSearch);
    document.addEventListener('click', function(event){
      if(results && !results.contains(event.target) && event.target !== siteSearch) results.hidden = true;
    });
    siteSearch.addEventListener('keydown', function(event){
      if(event.key === 'Escape'){ siteSearch.value = ''; results.hidden = true; }
      if(event.key === 'Enter' && results){
        var first = results.querySelector('a');
        if(first){ location.hash = first.getAttribute('href'); results.hidden = true; }
      }
    });
  }

  /* hash router */
  function currentPage(){
    var id = location.hash.replace(/^#/, '').split('#')[0];
    return pageIds.indexOf(id) === -1 ? 'home' : id;
  }
  function render(){
    var id = currentPage();
    [].forEach.call(document.querySelectorAll('.wiki-page'), function(node){
      node.hidden = node.id !== id;
    });
    var activeLink = null;
    [].forEach.call(document.querySelectorAll('[data-nav]'), function(link){
      var on = link.getAttribute('data-nav') === id;
      link.classList.toggle('active', on);
      if(on) activeLink = link;
    });
    var page = PAGES.filter(function(item){ return item.id === id; })[0];
    var crumbs = byId('breadcrumb');
    if(crumbs && page){
      crumbs.innerHTML = page.id === 'home'
        ? '<a href="#home">首页</a>'
        : '<a href="#home">首页</a> <span aria-hidden="true">›</span> ' + page.title;
    }
    if(page) document.title = page.title + ' — ' + BRAND;
    revealGroup(id);
    if(activeLink && activeLink.scrollIntoView) activeLink.scrollIntoView({ block: 'nearest' });
    /* Only follow a hash that points inside the visible page: a plain page hash
       must not scroll, and a hidden page's section must not be targeted. */
    var target = location.hash.split('#')[1];
    var anchor = null;
    while(target){
      var candidate = document.getElementById(target);
      if(candidate && candidate.offsetParent !== null){ anchor = candidate; break; }
      var next = target.indexOf('-');
      if(next === -1) break;
      target = target.slice(next + 1);
    }
    if(anchor) window.scrollTo(0, Math.max(0, anchor.getBoundingClientRect().top + window.pageYOffset - 20));
    else window.scrollTo(0, 0);
  }
  window.addEventListener('hashchange', render);
  render();
  /* Re-assert the scroll position after the browser has settled its own layout
     and anchor handling, otherwise a deep link can leave the viewport past the
     end of the newly shown page. */
  window.addEventListener('load', function(){ setTimeout(render, 0); });
  window.addEventListener('pageshow', function(){ setTimeout(render, 0); });

  /* theme */
  var THEME_KEY = 'contribution-wiki-theme';
  function applyTheme(theme){
    document.body.classList.toggle('dark', theme === 'dark');
    var button = byId('theme-toggle');
    if(button){
      button.textContent = theme === 'dark' ? '☀ 浅色' : '☾ 深色';
      button.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
    }
  }
  var stored = null;
  try { stored = window.localStorage.getItem(THEME_KEY); } catch (error) { stored = null; }
  applyTheme(stored === 'dark' ? 'dark' : 'light');
  var toggle = byId('theme-toggle');
  if(toggle) toggle.addEventListener('click', function(){
    var next = document.body.classList.contains('dark') ? 'light' : 'dark';
    applyTheme(next);
    try { window.localStorage.setItem(THEME_KEY, next); } catch (error) {}
  });
})();
`;

/* ------------------------------------------------------------------ *
 * Assemble the HTML
 * ------------------------------------------------------------------ */

const renderedPages = new Map();
const pagesHtml = wiki.pages
  .map((page) => {
    const { html, toc } = articleFor(page);
    const tocHtml = toc.length && editorial.showContents?.[page.id] !== false
      ? `<div class="toc"><input class="toc-toggle" type="checkbox" id="toc-${page.id}" checked><label class="toc-head" for="toc-${page.id}">目录 <span class="toc-toggle-label"><span class="toc-show">[显示]</span><span class="toc-hide">[隐藏]</span></span></label><ol>${toc.map((item) => `<li><a href="#${item.id}">${escape(item.title)}</a></li>`).join('')}</ol></div>`
      : '';
    /* The initial page is baked in statically so the very first paint is correct
       even before the router runs; JavaScript only handles later navigation. */
    const rendered = `<div class="wiki-page" id="${page.id}" data-page-title="${escape(page.title)}"${page.id === 'home' ? '' : ' hidden'}>
<div class="content-panel">
<div class="page-head">
<h1 class="page-title">${escape(page.title)}</h1>
${page.summary ? `<p class="page-summary">${inline(page.summary)}</p>` : ''}
</div>
<div class="page-columns">
<div class="article">
${tocHtml}
${html}
<div class="page-footer">${escape(wiki.brand.title)} · ${escape(exported.version)} · <a href="#documentation">维基维护</a></div>
</div>
${pageInfo(page)}
</div>
</div>
</div>`;
    renderedPages.set(page.id, rendered);
    return rendered;
  })
  .join('\n');

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="${escape(wiki.brand.description)}">
<meta name="generator" content="build-documentation.mjs">
<title>${escape(wiki.brand.title)} ${escape(exported.version)}</title>
<style>${styles}</style>
</head>
<body>
<div class="shell">
${navSidebar()}
<main class="main">
<header class="topbar">
<nav class="topbar-crumbs" id="breadcrumb" aria-label="面包屑"><a href="#home">首页</a></nav>
<span class="topbar-spacer"></span>
<div class="topbar-search">
<label class="visually-hidden" for="topbar-search">搜索本维基</label>
<input id="topbar-search" type="search" placeholder="搜索页面与章节…" autocomplete="off">
</div>
<button id="theme-toggle" class="theme-toggle" type="button" aria-pressed="false">☾ 深色</button>
</header>
${pagesHtml}
</main>
</div>
<noscript><p class="noscript">本维基 使用少量内联脚本在页面之间切换、提供搜索、侧栏分组折叠与主题切换。脚本被禁用时仍可阅读当前页面并展开全部分组，但命令表筛选不可用；可改用 <a href="commands.xlsx">命令工作簿</a> 离线检索命令。</p></noscript>
<script>${clientScript}
(function(){var box=document.getElementById('topbar-search'),side=document.getElementById('site-search');if(box&&side){box.addEventListener('input',function(){side.value=box.value;side.dispatchEvent(new Event('input'));});}})();</script>
</body>
</html>
`;

await fs.mkdir(output, { recursive: true });

/**
 * Guard the generated document before it is written: balanced tags, resolvable
 * anchors, one heading per page and a strict offline guarantee. A failure here
 * must abort generation instead of shipping a broken or half-updated artifact.
 */
const validateHtml = (document) => {
  const problems = [];
  const markup = document.replace(/<script>[\s\S]*?<\/script>/g, '');
  const voidTags = new Set(['meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'area', 'base', 'col', 'embed', 'param', 'track', 'wbr']);
  const rawText = new Set(['script', 'style', 'textarea', 'title']);
  const stack = [];
  let index = 0;
  while (index < document.length) {
    const open = document.indexOf('<', index);
    if (open === -1) break;
    if (document.startsWith('<!--', open)) {
      const end = document.indexOf('-->', open);
      index = end === -1 ? document.length : end + 3;
      continue;
    }
    const match = /^<(\/?)([a-zA-Z][a-zA-Z0-9]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/.exec(document.slice(open));
    if (!match) {
      index = open + 1;
      continue;
    }
    const [full, closing, rawName, , selfClosing] = match;
    const tag = rawName.toLowerCase();
    index = open + full.length;
    if (voidTags.has(tag) || selfClosing) continue;
    if (closing) {
      const top = stack.pop();
      if (top !== tag) problems.push(`tag mismatch at index ${open}: closed </${tag}> but <${top ?? 'nothing'}> was open`);
    } else {
      stack.push(tag);
      if (rawText.has(tag)) {
        const end = document.indexOf(`</${tag}`, index);
        if (end === -1) {
          problems.push(`unterminated <${tag}> at index ${open}`);
          index = document.length;
        } else {
          stack.pop();
          index = document.indexOf('>', end) + 1;
        }
      }
    }
  }
  if (stack.length !== 0) problems.push(`unclosed tags: ${stack.join(', ')}`);

  const ids = new Set([...markup.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]));
  const missing = [...new Set([...markup.matchAll(/href="#([^"]+)"/g)].map((match) => match[1]).filter((anchor) => !ids.has(anchor)))];
  if (missing.length > 0) problems.push(`links to missing anchors: ${missing.join(', ')}`);

  const pageIds = [...markup.matchAll(/<div class="wiki-page" id="([^"]+)"/g)].map((match) => match[1]);
  if (pageIds.length !== wiki.pages.length) problems.push(`rendered ${pageIds.length} pages, expected ${wiki.pages.length}`);
  for (const id of pageIds) {
    const start = markup.indexOf(`<div class="wiki-page" id="${id}"`);
    const next = markup.indexOf('<div class="wiki-page"', start + 1);
    const chunk = markup.slice(start, next === -1 ? markup.length : next);
    if (!/<h1[\s>]/.test(chunk)) problems.push(`page ${id} has no <h1>`);
    if (/<h2[^>]*>\s*<\/h2>/.test(chunk)) problems.push(`page ${id} has an empty heading`);
    if (chunk.length < 900) problems.push(`page ${id} looks empty (${chunk.length} characters)`);
  }

  for (const [, name, json] of document.matchAll(/var (PAGES|INDEX|NAV|BRAND) = (.*?);\n/g)) {
    try {
      JSON.parse(json.replace(/\\u003c/g, '<').replace(/\\u003e/g, '>').replace(/\\u0026/g, '&'));
    } catch (error) {
      problems.push(`${name} payload is not valid JSON: ${error.message}`);
    }
  }

  const external = [...markup.matchAll(/(?:src|href)="(?:https?:)?\/\//g)];
  if (external.length > 0) problems.push(`found ${external.length} external resource reference(s)`);
  if (problems.length > 0) throw new Error(`Generated wiki.html failed validation:\n- ${problems.join('\n- ')}`);
};

const compatibilityHtml = html.replaceAll('assets/images/', '../wiki/assets/images/');
validateHtml(compatibilityHtml);
await fs.writeFile(path.join(output, 'wiki.html'), compatibilityHtml);

/* ------------------------------------------------------------------ *
 * XLSX
 * ------------------------------------------------------------------ */

const workbook = Workbook.create();
const sheet = workbook.worksheets.add('命令汇总');
sheet.showGridLines = false;
sheet.getRange('A2').values = [[`贡献值模组 ${exported.version} — 全部命令`]];
sheet.getRange('A3').values = [[`共 ${rows.length} 条实际可执行路径；<> 为必填，多个尾部变体逐行列出。`]];
const headers = ['模块', '完整用法', '功能', '权限', '示例', '参数与限制', '原版参数类型'];
const workbookText = value => String(value).replace(/`([^`]+)`/g, '$1');
const sheetRows = rows.map((row) => [row.category, row.syntax, row.purpose, row.permission, row.example, row.notes, row.arguments].map(workbookText));
sheet.getRange(`A5:G${rows.length + 5}`).values = [headers, ...sheetRows];
sheet.getRange(`A1:G${rows.length + 5}`).format.font = { name: 'Arial', size: 10 };
sheet.getRange('A2:G2').format.font = { name: 'Arial', size: 15, bold: true, color: '#153F55' };
sheet.getRange('A2:G2').format.rowHeight = 26;
sheet.getRange('A3:G3').format.rowHeight = 24;
sheet.getRange('A5:G5').format = { fill: '#153F55', font: { name: 'Arial', bold: true, color: '#FFFFFF' }, rowHeight: 25 };
sheet.getRange(`A6:G${rows.length + 5}`).format.wrapText = true;
sheet.getRange(`A6:G${rows.length + 5}`).format.verticalAlignment = 'top';
for (const [index, width] of [110, 420, 260, 220, 360, 430, 330].entries()) {
  const column = String.fromCharCode(65 + index);
  sheet.getRange(`${column}:${column}`).format.columnWidthPx = width;
}
for (let index = 0; index < rows.length; index++) {
  const longest = Math.max(Math.ceil(sheetRows[index][1].length / 55), Math.ceil(sheetRows[index][2].length / 24), Math.ceil(sheetRows[index][5].length / 38));
  sheet.getRange(`A${index + 6}:G${index + 6}`).format.rowHeight = Math.max(42, 16 * longest + 10);
}
sheet.tables.add(`A5:G${rows.length + 5}`, true, 'CommandReference');
sheet.freezePanes.freezeRows(5);
sheet.freezePanes.freezeColumns(2);
const guide = workbook.worksheets.add('使用指南');
guide.showGridLines = false;
guide.getRange('A2').values = [[`贡献值模组 ${exported.version} — 使用指南`]];
guide.getRange(`A5:B${catalog.guides.length + 5}`).values = [['主题', '说明'], ...catalog.guides.map(row => row.map(workbookText))];
guide.getRange('A:A').format.columnWidthPx = 190;
guide.getRange('B:B').format.columnWidthPx = 870;
guide.getRange(`A1:B${catalog.guides.length + 5}`).format.font = { name: 'Arial', size: 10 };
guide.getRange('A2:B2').format.font = { name: 'Arial', size: 15, bold: true, color: '#153F55' };
guide.getRange('A5:B5').format = { fill: '#153F55', font: { bold: true, color: '#FFFFFF' }, rowHeight: 25 };
guide.getRange(`A6:B${catalog.guides.length + 5}`).format.wrapText = true;
guide.getRange(`A6:B${catalog.guides.length + 5}`).format.verticalAlignment = 'top';
guide.getRange(`A6:B${catalog.guides.length + 5}`).format.rowHeight = 76;
guide.freezePanes.freezeRows(5);
workbook.recalculate();
console.log((await workbook.inspect({ kind: 'table', range: '命令汇总!A5:D8', include: 'values,formulas' })).ndjson);
const errors = await workbook.inspect({ kind: 'match', searchTerm: '#REF!|#DIV/0!|#VALUE!|#NAME\\?|#NUM!', options: { useRegex: true, maxResults: 20 } });
console.log(errors.ndjson);
const previews = path.join(root, 'code/build/documentation/previews');
await fs.mkdir(previews, { recursive: true });
for (const [name, range] of [['命令汇总', 'A1:F10'], ['使用指南', 'A1:B9'], ['使用指南', 'A15:B15'], ['使用指南', 'A19:B19'], ['使用指南', 'A22:B22']]) {
  const png = await workbook.render({ sheetName: name, range, scale: 1, format: 'png' });
  await fs.writeFile(path.join(previews, `${range === 'A22:B22' ? '维基维护' : range === 'A19:B19' ? '武器皮肤' : range === 'A15:B15' ? '装备升级' : name}.png`), new Uint8Array(await png.arrayBuffer()));
}
const artifact = await SpreadsheetFile.exportXlsx(workbook);
await artifact.save(path.join(output, 'commands.xlsx'));
try { await fs.rename(path.join(output, 'commands.xlsx.inspect.ndjson'), path.join(previews, 'xlsx-inspect.ndjson')); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const hashes = {};
for (const file of ['wiki.html', 'commands.xlsx']) hashes[file] = crypto.createHash('sha256').update(await fs.readFile(path.join(output, file))).digest('hex');
const websiteFiles = await writeSite(root, wiki, renderedPages, navSidebar(), exported.version);
await fs.writeFile(path.join(output, 'manifest.json'), JSON.stringify({ version: exported.version, sourceDigest: exported.sourceDigest, commandCount: rows.length, files: hashes, websiteFiles }, null, 2) + '\n');
console.log(`DOCUMENTATION_GENERATED: ${rows.length} routes, ${wiki.pages.length} wiki pages, two sheets, HTML/XLSX and checksummed manifest`);
