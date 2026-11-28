/* Vyrobi XMLTV se programem kolem aktualniho casu pro prilozeny vzorek playlistu.
   Pouziti: node dev/make-demo-epg.js > dev/epg.demo.xml */
'use strict';

var CHANNELS = [
  ['demo.apple', 'Apple HLS test'],
  ['demo.mux', 'Mux HLS test'],
  ['demo.bbb', 'Big Buck Bunny'],
  ['demo.sintel', 'Sintel'],
  ['demo.tears', 'Tears of Steel']
];

var TITLES = ['Zprávy', 'Dokument: Ledovce', 'Fotbal: liga', 'Film: Sintel', 'Seriál: 3. díl',
  'Počasí', 'Hudební klipy', 'Večerníček', 'Debata', 'Reportáž z hor'];

function stamp(d) {
  function p(n) { return (n < 10 ? '0' : '') + n; }
  var off = -d.getTimezoneOffset();
  var sign = off >= 0 ? '+' : '-';
  off = Math.abs(off);
  return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) +
    p(d.getHours()) + p(d.getMinutes()) + '00 ' +
    sign + p(Math.floor(off / 60)) + p(off % 60);
}

var out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="dev/make-demo-epg.js">'];
CHANNELS.forEach(function (c) {
  out.push('  <channel id="' + c[0] + '"><display-name>' + c[1] + '</display-name></channel>');
});

var now = new Date();
var base = new Date(now.getTime() - 90 * 60000);
base.setMinutes(base.getMinutes() - (base.getMinutes() % 30), 0, 0);

CHANNELS.forEach(function (c, ci) {
  for (var i = 0; i < 12; i++) {
    var start = new Date(base.getTime() + i * 45 * 60000);
    var stop = new Date(start.getTime() + 45 * 60000);
    var title = TITLES[(ci * 3 + i) % TITLES.length];
    out.push('  <programme start="' + stamp(start) + '" stop="' + stamp(stop) + '" channel="' + c[0] + '">');
    out.push('    <title lang="cs">' + title + '</title>');
    out.push('    <desc lang="cs">Ukázkový popis pořadu pro ověření, že se program správně parsuje a zobrazuje v detailu kanálu.</desc>');
    out.push('  </programme>');
  }
});
out.push('</tv>');
process.stdout.write(out.join('\n') + '\n');
