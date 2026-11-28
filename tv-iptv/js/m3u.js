/* Parser M3U / M3U8 playlistu (format rozsireny o tvg- atributy). */
var M3U = (function () {
  'use strict';

  var ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;

  function attrs(line) {
    var out = {};
    var m;
    ATTR.lastIndex = 0;
    while ((m = ATTR.exec(line)) !== null) {
      out[m[1].toLowerCase()] = m[2];
    }
    return out;
  }

  function parse(text) {
    var lines = String(text).replace(/\r/g, '').split('\n');
    var channels = [];
    var epgHint = '';
    var pending = null;
    var groupTag = '';
    var seen = {};
    var number = 0;

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) { continue; }

      if (line.indexOf('#EXTM3U') === 0) {
        var head = attrs(line);
        epgHint = head['url-tvg'] || head['x-tvg-url'] || '';
        if (epgHint.indexOf(',') !== -1) { epgHint = epgHint.split(',')[0]; }
        continue;
      }

      if (line.indexOf('#EXTINF:') === 0) {
        var a = attrs(line);
        var comma = line.lastIndexOf(',');
        var title = comma !== -1 ? line.substr(comma + 1).trim() : '';
        pending = {
          name: title || a['tvg-name'] || 'Bez nazvu',
          tvgId: a['tvg-id'] || '',
          logo: a['tvg-logo'] || a['logo'] || '',
          group: a['group-title'] || groupTag || 'Ostatní',
          chno: a['tvg-chno'] ? parseInt(a['tvg-chno'], 10) : 0,
          catchup: a['catchup'] || '',
          ua: '', referer: '', drm: false
        };
        continue;
      }

      if (line.indexOf('#EXTGRP:') === 0) {
        groupTag = line.substr(8).trim();
        if (pending) { pending.group = groupTag; }
        continue;
      }

      if (line.indexOf('#EXTVLCOPT:') === 0 && pending) {
        var opt = line.substr(11);
        var eq = opt.indexOf('=');
        if (eq !== -1) {
          var k = opt.substr(0, eq).toLowerCase().trim();
          var v = opt.substr(eq + 1).trim();
          if (k === 'http-user-agent') { pending.ua = v; }
          if (k === 'http-referrer' || k === 'http-referer') { pending.referer = v; }
        }
        continue;
      }

      if (line.indexOf('#KODIPROP:') === 0 && pending) {
        var kp = line.substr(10).toLowerCase();
        if (kp.indexOf('license') !== -1 || kp.indexOf('drm') !== -1 ||
            kp.indexOf('widevine') !== -1 || kp.indexOf('playready') !== -1) {
          pending.drm = true;
        }
        continue;
      }

      if (line.charAt(0) === '#') { continue; }

      /* Radek s adresou uzavira polozku. */
      var url = line;
      var item = pending || { name: url.split('/').pop(), tvgId: '', logo: '',
        group: groupTag || 'Ostatní', chno: 0, catchup: '', ua: '', referer: '', drm: false };
      pending = null;

      /* Pripona |User-Agent=... , kterou pouzivaji nektere playlisty. */
      var bar = url.indexOf('|');
      if (bar !== -1) {
        var tail = url.substr(bar + 1);
        url = url.substr(0, bar);
        tail.split('&').forEach(function (kv) {
          var p = kv.split('=');
          if (p.length < 2) { return; }
          var key = p[0].toLowerCase().replace(/[-_]/g, '');
          var val = p.slice(1).join('=');
          if (key === 'useragent') { item.ua = val; }
          if (key === 'referer' || key === 'referrer') { item.referer = val; }
        });
      }

      number += 1;
      var id = (item.tvgId ? 'i:' + item.tvgId : 'u:' + url).toLowerCase();
      if (seen[id]) { id = id + '#' + number; }
      seen[id] = true;

      item.id = id;
      item.url = url;
      item.number = item.chno > 0 ? item.chno : number;
      channels.push(item);
    }

    /* Vlastni cisla z playlistu mohou kolidovat - duplikaty prepiseme poradim. */
    var usedNumbers = {};
    channels.forEach(function (c, idx) {
      if (usedNumbers[c.number]) { c.number = idx + 1; }
      usedNumbers[c.number] = true;
    });

    return { channels: channels, epgUrl: epgHint };
  }

  /* Skupiny v poradi, v jakem se objevily v playlistu. */
  function groupsOf(channels) {
    var order = [];
    var map = {};
    channels.forEach(function (c) {
      if (!map[c.group]) { map[c.group] = 0; order.push(c.group); }
      map[c.group] += 1;
    });
    return order.map(function (g) { return { name: g, count: map[g] }; });
  }

  return { parse: parse, groupsOf: groupsOf };
}());
