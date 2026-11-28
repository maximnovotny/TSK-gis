/* Pomocne funkce. Tizen 5.0 = Chromium 63, takze zadne ?. ani ??. */
var U = (function () {
  'use strict';

  var LOG_MAX = 200;
  var log = [];

  function logLine(level, args) {
    var msg = Array.prototype.slice.call(args).map(function (a) {
      if (typeof a === 'string') { return a; }
      try { return JSON.stringify(a); } catch (e) { return String(a); }
    }).join(' ');
    log.push(new Date().toISOString().substr(11, 8) + ' [' + level + '] ' + msg);
    if (log.length > LOG_MAX) { log.shift(); }
    if (window.console && console.log) { console.log('[iptv]', msg); }
  }

  var isTizen = typeof window.tizen !== 'undefined';

  function one(sel) { return document.querySelector(sel); }
  function all(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text !== undefined && text !== null) { n.textContent = String(text); }
    return n;
  }

  function show(node, on) {
    if (typeof node === 'string') { node = one(node); }
    if (!node) { return; }
    if (on) { node.classList.remove('hidden'); } else { node.classList.add('hidden'); }
  }

  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function hhmm(d) {
    if (!d) { return ''; }
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  /* Diakritiku na TV nejde spolehlive psat, takze hledani porovnava bez ni. */
  var FOLD = { 'á': 'a', 'č': 'c', 'ď': 'd', 'é': 'e', 'ě': 'e', 'í': 'i', 'ň': 'n', 'ó': 'o',
    'ř': 'r', 'š': 's', 'ť': 't', 'ú': 'u', 'ů': 'u', 'ý': 'y', 'ž': 'z', 'ä': 'a', 'ö': 'o',
    'ü': 'u', 'ß': 's', 'ł': 'l', 'ę': 'e', 'ą': 'a', 'ś': 's', 'ć': 'c', 'ź': 'z', 'ż': 'z',
    'ô': 'o', 'ê': 'e', 'î': 'i', 'â': 'a', 'à': 'a', 'è': 'e', 'ù': 'u', 'ç': 'c', 'ñ': 'n' };

  function fold(s) {
    s = String(s === undefined || s === null ? '' : s).toLowerCase();
    var out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      out += FOLD[c] !== undefined ? FOLD[c] : c;
    }
    return out;
  }

  /* XHR misto fetch: na Tizenu 5.0 je spolehlivejsi a umi timeout i pro file:// origin. */
  function fetchText(url, opts) {
    opts = opts || {};
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      var done = false;
      xhr.open('GET', url, true);
      xhr.timeout = opts.timeout || 20000;
      if (opts.userAgent) {
        try { xhr.setRequestHeader('User-Agent', opts.userAgent); } catch (e) { /* prohlizec zakazuje */ }
      }
      xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4 || done) { return; }
        done = true;
        var ok = (xhr.status >= 200 && xhr.status < 300) || (xhr.status === 0 && xhr.responseText);
        if (ok) { resolve(xhr.responseText); }
        else { reject(new Error('HTTP ' + xhr.status)); }
      };
      xhr.ontimeout = function () { if (!done) { done = true; reject(new Error('Vyprsel cas pripojeni')); } };
      xhr.onerror = function () { if (!done) { done = true; reject(new Error('Sit nedostupna')); } };
      try { xhr.send(null); } catch (e) { done = true; reject(e); }
    });
  }

  function online() {
    if (isTizen && window.webapis && webapis.network) {
      try {
        return webapis.network.isConnectedToGateway();
      } catch (e) { /* padnout na navigator */ }
    }
    return navigator.onLine !== false;
  }

  return {
    isTizen: isTizen,
    one: one, all: all, el: el, show: show, clamp: clamp, pad2: pad2, hhmm: hhmm,
    fold: fold, fetchText: fetchText, online: online,
    log: function () { logLine('I', arguments); },
    warn: function () { logLine('W', arguments); },
    err: function () { logLine('E', arguments); },
    dumpLog: function () { return log.join('\n'); }
  };
}());
