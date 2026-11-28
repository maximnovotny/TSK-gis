/* Nahled vzhledu na PC: spusti staticky server, nacte aplikaci v Chromiu
   a ulozi snimky obrazovek. Pouziti: node dev/screenshot.js [vystupni-adresar] */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.resolve(process.argv[2] || path.join(ROOT, 'dev', 'shots'));
const PORT = 8777;

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.m3u': 'audio/x-mpegurl; charset=utf-8',
  '.xml': 'text/xml; charset=utf-8',
  '.png': 'image/png'
};

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

async function main() {
  fs.mkdirSync(OUT, { recursive: true });

  // Program pro ukazku vyrobime na aktualni cas.
  const epgPath = path.join(ROOT, 'dev', 'epg.demo.xml');
  const gen = require('child_process').execFileSync(process.execPath,
    [path.join(ROOT, 'dev', 'make-demo-epg.js')], { encoding: 'utf8' });
  fs.writeFileSync(epgPath, gen);

  const srv = await serve();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });

  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') { errors.push('console: ' + m.text()); } });

  await page.addInitScript(() => {
    window.IPTV_DEMO = true;   // nepripojovat se k zadnemu streamu
    localStorage.setItem('iptvhrac.v1', JSON.stringify({
      settings: { playlistUrl: '', epgUrl: 'dev/epg.demo.xml', userAgent: '',
        startChannel: 'first', aspect: 'auto', osdSeconds: 0, sortBy: 'playlist',
        autoReconnect: true, favOnly: false },
      favs: ['i:demo.mux'], lastId: '', lastNumber: 0
    }));
  });

  await page.goto(`http://127.0.0.1:${PORT}/index.html`);
  await page.waitForFunction(() => window.IPTV && window.IPTV.channels().length > 0, null, { timeout: 15000 });
  await page.waitForTimeout(1200);

  const key = async (k, wait = 500) => { await page.keyboard.press(k); await page.waitForTimeout(wait); };
  const shot = async (name) => {
    await page.screenshot({ path: path.join(OUT, name) });
    console.log('ulozeno', name);
  };

  await shot('1-prehravac-osd.png');

  await key('Enter');                      // otevrit seznam kanalu
  await shot('2-seznam-kanalu.png');

  await key('ArrowLeft');                  // sloupec skupin
  await key('ArrowDown');                  // Oblibene
  await shot('3-oblibene.png');

  await key('ArrowDown');                  // skupina Ukazky
  await key('ArrowRight');
  await key('ArrowDown');
  await shot('4-detail-kanalu.png');

  await key('F2');                         // zelena = hledat
  await shot('5-klavesnice.png');
  await key('Escape');

  await key('F1');                         // cervena = nastaveni
  await shot('6-nastaveni.png');
  await key('ArrowDown');
  await key('Enter');                      // editace EPG adresy
  await shot('7-nastaveni-editace.png');
  await key('Escape');
  await key('Escape');

  await page.keyboard.press('Digit4');     // prepnuti cislem
  await page.waitForTimeout(300);
  await shot('8-prepnuti-cislem.png');

  const log = await page.evaluate(() => window.IPTV.log());
  fs.writeFileSync(path.join(OUT, 'log.txt'), log + '\n\n' + errors.join('\n'));

  await browser.close();
  srv.close();

  if (errors.length) {
    console.error('\nCHYBY V KONZOLI:\n' + errors.join('\n'));
    process.exit(1);
  }
  console.log('\nbez chyb, snimky v ' + OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
