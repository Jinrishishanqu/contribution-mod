"""
Audit every generated wiki page for layout defects.

Two points that matter:
  * each page is shown in isolation before measuring, because hiding/showing
    pages changes the layout and would corrupt the numbers;
  * the two-column area is the article plus the page-info column, so a block
    ending at the article's right edge is CORRECT, not "short".

Run:  python code/tools/audit-wiki-pages.py
"""
import io
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
WIKI = os.path.join(ROOT, 'documentation', 'wiki.html')
PREVIEW = os.path.join(ROOT, 'code', 'build', 'documentation', 'previews')
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'

PROBE = r"""
<style>#audit{position:fixed;left:0;top:0;z-index:99999;background:#fff;color:#000;
  font:12px/1.45 monospace;padding:10px;margin:0;white-space:pre}</style>
<script>
window.addEventListener('load', function(){
  setTimeout(function(){
    var pages = [].slice.call(document.querySelectorAll('.wiki-page'));
    var out = [];
    var worst = {};

    pages.forEach(function(pg){
      pages.forEach(function(q){ q.hidden = q !== pg; });
      var r = function(n){ return n.getBoundingClientRect(); };
      var issues = [];

      var panel = pg.querySelector('.content-panel');
      var article = pg.querySelector('.article');
      var info = pg.querySelector('.page-info-inner');
      var contentR = Math.round(r(panel).right) - 24;   // panel padding
      var articleR = Math.round(r(article).right);
      var articleL = Math.round(r(article).left);
      var infoL = Math.round(r(info).left);

      // 1. no content block may overflow horizontally
      var overflow = [];
      pg.querySelectorAll('.table-wrap,.cmd,.cards,.msgbox,pre.code,pre.figure,.formula,.wikitable').forEach(function(n){
        if (n.scrollWidth > n.clientWidth + 2) overflow.push(n.className.split(' ')[0]);
      });
      if (overflow.length) issues.push('OVERFLOW[' + overflow.join(',') + ']');

      // 2. blocks must span the whole article column (not a fraction of it)
      var narrow = [];
      pg.querySelectorAll('.wiki-section > .table-wrap,.wiki-section > .msgbox,'
        + '.wiki-section > .cards,.wiki-section > pre.figure,.wiki-section > .formula').forEach(function(n){
        var w = Math.round(r(n).width), colW = articleR - articleL;
        if (colW > 0 && w < colW - 4) narrow.push(n.className.split(' ')[0] + ':' + w + '/' + colW);
      });
      if (narrow.length) issues.push('NARROW[' + narrow.join(',') + ']');

      // 3. tables
      pg.querySelectorAll('.wikitable').forEach(function(t, ti){
        var rows = [].slice.call(t.querySelectorAll('tbody > tr'));
        if (!rows.length) { issues.push('T' + ti + '[no-rows]'); return; }
        var hs = rows.map(function(x){ return Math.round(r(x).height); });
        var spread = Math.max.apply(null, hs) - Math.min.apply(null, hs);
        if (rows.length > 1 && spread > 2) {
          if (!worst['rowSpread'] || spread > worst['rowSpread'].v) worst['rowSpread'] = { page: pg.id, v: spread };
        }
        var head = t.querySelector('thead th');
        if (head && Math.round(r(head).height) > 52) issues.push('T' + ti + '[header ' + Math.round(r(head).height) + 'px]');
        var first = rows[0].querySelector('th,td');
        if (first && Math.round(r(first).width) < 70) issues.push('T' + ti + '[col1 ' + Math.round(r(first).width) + 'px]');
        if (t.scrollWidth > t.clientWidth + 2) issues.push('T' + ti + '[scrolls]');
      });

      // 4. card grids: uniform heights, no orphan of one
      pg.querySelectorAll('.cards').forEach(function(g, gi){
        var cards = [].slice.call(g.querySelectorAll('.card'));
        if (!cards.length) { issues.push('G' + gi + '[empty]'); return; }
        var hs = cards.map(function(c){ return Math.round(r(c).height); });
        var spread = Math.max.apply(null, hs) - Math.min.apply(null, hs);
        if (spread > 2) issues.push('G' + gi + '[cardH spread ' + spread + ']');
        var cols = getComputedStyle(g).gridTemplateColumns.split(' ').length;
        if (cards.length % cols === 1) issues.push('G' + gi + '[' + cards.length + ' cards/' + cols + ' cols orphan]');
      });

      // 5. command definition blocks: label column must be uniform
      var labels = [];
      pg.querySelectorAll('.cmd').forEach(function(c){
        var dt = c.querySelector('dt');
        if (dt) labels.push(Math.round(r(dt).width));
      });
      if (labels.length) {
        var lo = Math.min.apply(null, labels), hi = Math.max.apply(null, labels);
        if (hi - lo > 2) issues.push('CMDLABEL[' + lo + '..' + hi + ']');
      }

      // 6. all paragraphs share one measure
      var pws = [];
      pg.querySelectorAll('.wiki-section > p').forEach(function(p){ pws.push(Math.round(r(p).width)); });
      if (pws.length > 1) {
        var lo2 = Math.min.apply(null, pws), hi2 = Math.max.apply(null, pws);
        if (hi2 - lo2 > 3) issues.push('MEASURE[p ' + lo2 + '..' + hi2 + ']');
      }

      // 7. toc must match the article column
      var toc = pg.querySelector('.toc');
      if (!toc) issues.push('NO-TOC');
      else if (Math.round(r(toc).right) !== articleR) issues.push('TOC[right ' + Math.round(r(toc).right) + '!=' + articleR + ']');

      // 8. no empty headings
      pg.querySelectorAll('h2,h3,h4').forEach(function(h){ if (!h.textContent.trim()) issues.push('EMPTY-H'); });

      out.push(pg.id.padEnd(19)
        + '| art ' + String(articleR - articleL).padStart(4)
        + ' gap ' + String(infoL - articleR).padStart(3)
        + ' | T' + pg.querySelectorAll('.wikitable').length
        + ' G' + pg.querySelectorAll('.cards').length
        + ' M' + pg.querySelectorAll('.cmd').length
        + ' | ' + (issues.length ? issues.join(' ') : 'OK'));
    });

    pages.forEach(function(q){ q.hidden = q.id !== 'home'; });
    var head = 'page               | widths          | blocks | issues\n';
    var tail = '\nworst table row-height spread: '
      + (worst['rowSpread'] ? worst['rowSpread'].page + ' = ' + worst['rowSpread'].v + 'px' : 'none');
    var pre = document.createElement('pre');
    pre.id = 'audit';
    pre.textContent = head + out.join('\n') + tail;
    document.body.appendChild(pre);
  }, 500);
});
</script>
</body>
"""


def main() -> int:
    html = io.open(WIKI, encoding='utf-8').read()
    os.makedirs(PREVIEW, exist_ok=True)
    probe_path = os.path.join(PREVIEW, '_audit.html')
    io.open(probe_path, 'w', encoding='utf-8', newline='\n').write(html.replace('</body>', PROBE))
    subprocess.run([
        CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars',
        '--virtual-time-budget=15000', '--window-size=1400,2200',
        '--screenshot=' + os.path.join(PREVIEW, 'audit.png'),
        'file:///' + probe_path.replace('\\', '/'),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print('report written to', os.path.join(PREVIEW, 'audit.png'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
