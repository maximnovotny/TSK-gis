/* Funkcni test ovladani v headless Chromiu. Pouziti: node dev/test.js */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const PORT = 8778;
const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8', '.m3u': 'audio/x-mpegurl; charset=utf-8',
  '.xml': 'text/xml; charset=utf-8', '.png': 'image/png' };

function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
      const file = path.join(ROOT, rel);
      if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404); res.end('404'); return;
      }
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    });
    srv.listen(PORT, () => resolve(srv));
  });
}

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); }
  else { failed += 1; console.log('  FAIL ' + name + (detail !== undefined ? '  -> ' + JSON.stringify(detail) : '')); }
}

async function main() {
  fs.writeFileSync(path.join(ROOT, 'dev', 'epg.demo.xml'),
    require('child_process').execFileSync(process.execPath, [path.join(ROOT, 'dev', 'make-demo-epg.js')], { encoding: 'utf8' }));

  const srv = await serve();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push('console: ' + m.text()); } });

  await page.addInitScript(() => {
    window.IPTV_DEMO = true;
    localStorage.setItem('iptvhrac.v1', JSON.stringify({
      settings: { playlistUrl: '', epgUrl: 'dev/epg.demo.xml', userAgent: '', startChannel: 'first',
        aspect: 'auto', osdSeconds: 0, sortBy: 'playlist', autoReconnect: true, favOnly: false },
      favs: [], lastId: '', lastNumber: 0
    }));
  });
  await page.goto(`http://127.0.0.1:${PORT}/index.html`);
  await page.waitForFunction(() => window.IPTV && window.IPTV.channels().length > 0, null, { timeout: 15000 });
  await page.waitForTimeout(1500);

  const key = async (k, wait = 250) => { await page.keyboard.press(k); await page.waitForTimeout(wait); };
  const text = (sel) => page.$eval(sel, (n) => n.textContent.trim()).catch(() => null);
  const count = (sel) => page.$$eval(sel, (n) => n.length);

  console.log('\nparser a start');
  check('playlist nacten (5 kanalu)', await page.evaluate(() => window.IPTV.channels().length) === 5);
  check('hraje kanal 1', (await text('#osd-number')) === '1', await text('#osd-name'));
  check('EPG prirazeno k poradu', (await text('#osd-now-title')) !== 'Program není k dispozici',
    await text('#osd-now-title'));

  console.log('\nseznam kanalu');
  await key('Enter');
  check('panel otevren', await page.isVisible('#panel'));
  check('5 radku v seznamu', (await count('#channel-list li')) === 5);
  await key('ArrowDown');
  check('fokus na 2. kanalu', (await text('#detail-name')) === 'Mux HLS test', await text('#detail-name'));

  console.log('\noblibene');
  await key('F3');  /* zluta */
  check('hvezdicka u kanalu', (await count('#channel-list li .star')) === 1);
  check('skupina Oblibene ma 1', (await page.$$eval('#group-list li', (ns) =>
    ns.map((n) => n.textContent))).some((t) => t.indexOf('Oblíbené1') !== -1));

  console.log('\nhledani');
  await key('F2');  /* zelena = klavesnice */
  check('klavesnice otevrena', await page.isVisible('#keyboard'));
  const kbFocus = () => page.$eval('#kb-grid b.focus', (n) => n.textContent);
  check('fokus na A', (await kbFocus()) === 'A');
  await key('ArrowDown'); await key('ArrowDown');
  check('po dvou dolu je fokus na 2', (await kbFocus()) === '2', await kbFocus());
  await key('ArrowDown');
  check('z posledni rady se fokus neztrati', (await kbFocus()) !== null, await kbFocus());
  await page.keyboard.press('KeyS'); await page.keyboard.press('KeyI'); await page.keyboard.press('KeyN');
  await page.waitForTimeout(200);
  check('do pole se psalo z klavesnice', (await text('#kb-value')) === 'sin', await text('#kb-value'));
  await key('F2'); /* zelena = hotovo */
  check('klavesnice zavrena', !(await page.isVisible('#keyboard')));
  check('filtr nechal 1 kanal', (await page.$$eval('#channel-list li .num', (n) => n.length)) === 1,
    await text('#channel-list'));
  check('je to Sintel', (await text('#detail-name')) === 'Sintel (MP4)', await text('#detail-name'));

  console.log('\nprepnuti a prehravac');
  await key('Enter');
  check('panel zavren po vyberu', !(await page.isVisible('#panel')));
  check('hraje Sintel', (await text('#osd-name')) === 'Sintel (MP4)', await text('#osd-name'));
  await page.keyboard.press('Digit3');
  await page.waitForTimeout(2600);
  check('cislem 3 se prepnulo', (await text('#osd-number')) === '3', await text('#osd-name'));
  await key('PageUp');
  check('CH+ prepne na 4', (await text('#osd-number')) === '4', await text('#osd-number'));
  await key('PageDown'); await key('PageDown');
  check('CH- prepne zpet na 2', (await text('#osd-number')) === '2', await text('#osd-number'));

  console.log('\npomer stran a nastaveni');
  await key('F4');
  check('pomer prepnut na fill', await page.evaluate(() => document.body.classList.contains('fit-fill')));
  await key('F1');
  check('nastaveni otevrena', await page.isVisible('#settings'));
  check('vidi se 10 polozek', (await count('#settings-list li')) === 10, await count('#settings-list li'));
  for (let i = 0; i < 11; i++) { await key('ArrowDown', 60); }
  check('posledni polozka je dosazitelna',
    (await page.$eval('#settings-list li.focus .lbl', (n) => n.textContent)) === 'Zobrazit poslední chyby',
    await page.$eval('#settings-list li.focus .lbl', (n) => n.textContent));
  await key('Escape');
  check('nastaveni zavrena', !(await page.isVisible('#settings')));

  console.log('\nulozeni stavu');
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('iptvhrac.v1')));
  check('oblibeny se ulozil', saved.favs.length === 1, saved.favs);
  check('posledni kanal se ulozil', !!saved.lastId, saved.lastId);
  check('pomer stran se ulozil', saved.settings.aspect === 'fill', saved.settings.aspect);

  await browser.close();
  srv.close();

  if (errors.length) { failed += 1; console.log('\nCHYBY V KONZOLI:\n' + errors.join('\n')); }
  console.log(failed ? '\n' + failed + ' selhani' : '\nvse proslo');
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
