/* XMLTV program. Parsuje se regexem po blocich, aby se na TV neslozila pamet
   z DOMParseru nad desitkami MB. Drzi se jen okno kolem aktualniho casu. */
var EPG = (function () {
  'use strict';

  var WINDOW_BACK_MS = 3 * 3600 * 1000;
  var WINDOW_FWD_MS = 14 * 3600 * 1000;

  var byChannel = {};   /* xmltv channel id -> [programy] */
  var nameToId = {};    /* folded display-name -> xmltv channel id */
  var loadedAt = 0;
  var loading = false;

  /* "20261128190000 +0100" -> Date */
  function parseTime(s) {
    if (!s) { return null; }
    var m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/.exec(s.trim());
    if (!m) { return null; }
    var utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0);
    if (m[7]) {
      var sign = m[7].charAt(0) === '-' ? -1 : 1;
      var offMin = parseInt(m[7].substr(1, 2), 10) * 60 + parseInt(m[7].substr(3, 2), 10);
      utc -= sign * offMin * 60000;
    }
    return new Date(utc);
  }

  function attr(tag, name) {
    var m = new RegExp(name + '="([^"]*)"').exec(tag);
    return m ? m[1] : '';
  }

  function unescapeXml(s) {
    return String(s)
      .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
      .replace(/&#(\d+);/g, function (_, d) { return String.fromCharCode(+d); })
      .replace(/&amp;/g, '&')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function inner(block, tag) {
    var m = new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)<\\/' + tag + '>').exec(block);
    return m ? unescapeXml(m[1]) : '';
  }

  function parse(text, wantedIds) {
    var now = Date.now();
    var from = now - WINDOW_BACK_MS;
    var to = now + WINDOW_FWD_MS;
    var index = {};
    var names = {};
    var want = null;
    if (wantedIds && wantedIds.length) {
      want = {};
      wantedIds.forEach(function (id) { if (id) { want[id] = true; } });
    }

    /* mapa xmltv kanalu na jmena */
    var chRe = /<channel\b([^>]*)>([\s\S]*?)<\/channel>/g;
    var m;
    while ((m = chRe.exec(text)) !== null) {
      var cid = attr(m[1], 'id');
      if (!cid) { continue; }
      var dn = inner(m[2], 'display-name');
      if (dn) { names[U.fold(dn)] = cid; }
      names[U.fold(cid)] = cid;
    }

    var prRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
    var kept = 0;
    var total = 0;
    while ((m = prRe.exec(text)) !== null) {
      total += 1;
      var head = m[1];
      var cid2 = attr(head, 'channel');
      if (!cid2) { continue; }
      if (want && !want[cid2] && !want[U.fold(cid2)]) { continue; }
      var start = parseTime(attr(head, 'start'));
      var stop = parseTime(attr(head, 'stop'));
      if (!start) { continue; }
      var startMs = start.getTime();
      var stopMs = stop ? stop.getTime() : startMs + 3600000;
      if (stopMs < from || startMs > to) { continue; }
      if (!index[cid2]) { index[cid2] = []; }
      index[cid2].push({
        start: startMs, stop: stopMs,
        title: inner(m[2], 'title') || 'Bez názvu',
        desc: inner(m[2], 'desc')
      });
      kept += 1;
    }

    Object.keys(index).forEach(function (k) {
      index[k].sort(function (a, b) { return a.start - b.start; });
    });

    U.log('EPG: ' + kept + ' z ' + total + ' porad, ' + Object.keys(index).length + ' kanalu');
    return { index: index, names: names };
  }

  function load(url, channels) {
    if (!url || loading) { return Promise.resolve(false); }
    loading = true;
    var wanted = (channels || []).map(function (c) { return c.tvgId; }).filter(Boolean);
    return U.fetchText(url, { timeout: 60000 }).then(function (text) {
      if (text.indexOf('<programme') === -1) {
        throw new Error('Odpoved neobsahuje XMLTV data (gzip soubory .gz nejsou podporovane)');
      }
      var res = parse(text, wanted.length ? wanted : null);
      byChannel = res.index;
      nameToId = res.names;
      loadedAt = Date.now();
      loading = false;
      return true;
    }, function (e) {
      loading = false;
      U.warn('EPG se nepodarilo nacist:', e.message);
      throw e;
    });
  }

  function listFor(ch) {
    if (!ch) { return []; }
    if (ch.tvgId && byChannel[ch.tvgId]) { return byChannel[ch.tvgId]; }
    var viaName = nameToId[U.fold(ch.name)];
    if (viaName && byChannel[viaName]) { return byChannel[viaName]; }
    return [];
  }

  function nowNext(ch) {
    var list = listFor(ch);
    var now = Date.now();
    var out = { now: null, next: null };
    for (var i = 0; i < list.length; i++) {
      if (list[i].start <= now && list[i].stop > now) {
        out.now = list[i];
        out.next = list[i + 1] || null;
        return out;
      }
      if (list[i].start > now) { out.next = list[i]; return out; }
    }
    return out;
  }

  function upcoming(ch, count) {
    var list = listFor(ch);
    var now = Date.now();
    var out = [];
    for (var i = 0; i < list.length && out.length < (count || 5); i++) {
      if (list[i].stop > now) { out.push(list[i]); }
    }
    return out;
  }

  function ready() { return Object.keys(byChannel).length > 0; }
  function age() { return loadedAt ? Date.now() - loadedAt : Infinity; }

  return { load: load, nowNext: nowNext, upcoming: upcoming, ready: ready, age: age,
    parseTime: parseTime };
}());
