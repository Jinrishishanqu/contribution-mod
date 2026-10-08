'use strict';
(() => {
  document.body.classList.add('js-enabled');
  const current = document.body.dataset.currentPage;
  document.querySelectorAll('[data-nav]').forEach(link => {
    if (link.dataset.nav === current) { link.classList.add('active'); link.setAttribute('aria-current', 'page'); }
  });
  document.querySelectorAll('.nav-toggle').forEach(button => button.addEventListener('click', () => {
    const panel = document.getElementById(button.getAttribute('aria-controls'));
    const expanded = button.getAttribute('aria-expanded') === 'true';
    button.setAttribute('aria-expanded', String(!expanded));
    panel.hidden = expanded;
  }));
  const mobile = document.getElementById('mobile-nav');
  mobile.addEventListener('click', () => {
    const open = document.body.classList.toggle('nav-open');
    mobile.setAttribute('aria-expanded', String(open));
  });
  const theme = document.getElementById('theme-toggle');
  const applyTheme = dark => { document.body.classList.toggle('dark', dark); theme.setAttribute('aria-pressed', String(dark)); };
  try { applyTheme(localStorage.getItem('contribution-wiki-theme') === 'dark'); } catch {}
  theme.addEventListener('click', () => { const dark = !document.body.classList.contains('dark'); applyTheme(dark); try { localStorage.setItem('contribution-wiki-theme', dark ? 'dark' : 'light'); } catch {} });
  const box = document.getElementById('site-search');
  const results = document.getElementById('site-search-results');
  box.addEventListener('input', () => {
    const terms = box.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
    results.replaceChildren();
    results.hidden = !terms.length;
    if (!terms.length) return;
    const found = (window.WIKI_INDEX ?? []).filter(page => terms.every(term => (page.title + ' ' + page.text).toLocaleLowerCase().includes(term))).sort((a, b) => Number(terms.some(t => b.title.includes(t))) - Number(terms.some(t => a.title.includes(t)))).slice(0, 20);
    if (!found.length) results.textContent = '没有找到相关内容。可尝试物品名称、命令或配方 ID。';
    for (const page of found) { const link = document.createElement('a'); link.href = page.url; link.textContent = page.title; results.append(link); }
  });
  box.addEventListener('keydown', event => { if (event.key === 'Escape') { results.hidden = true; box.blur(); } });
  const filter = document.getElementById('search');
  if (filter) filter.addEventListener('input', () => {
    let visible = 0;
    document.querySelectorAll('.cmd').forEach(command => { command.hidden = !command.dataset.search.includes(filter.value.trim().toLocaleLowerCase()); if (!command.hidden) visible++; });
    document.getElementById('count').textContent = `${visible} 条`;
  });
})();
