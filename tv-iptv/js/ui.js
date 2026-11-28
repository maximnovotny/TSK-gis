/* Obrazovky, ovladani ovladacem a vykreslovani seznamu. */
var UI = (function () {
  'use strict';

  var ROWS = 13;            /* kolik radku se vejde do seznamu */
  var GROUP_ALL = '\u0001all';
  var GROUP_FAV = '\u0001fav';

  var hooks = {};           /* nastavi app.js */
  var channels = [];
  var groups = [];          /* [{key, name, count}] */

  var st = {
    screen: 'player',       /* player | panel | keyboard | settings | error */
    col: 'channels',        /* aktivni sloupec v panelu */
    groupIdx: 0,
    chanIdx: 0,
    chanOff: 0,
    groupOff: 0,
    query: '',
    setIdx: 0,
    setOff: 0,
    kb: null,               /* { value, charset, idx, label, onDone } */
    osdTimer: 0,
    exitArmed: 0
  };

  /* ---------- pomocne ---------- */

  function groupKey() { return groups.length ? groups[st.groupIdx].key : GROUP_ALL; }

  function visibleChannels() {
    var key = groupKey();
    var q = U.fold(st.query);
    var out = [];
    for (var i = 0; i < channels.length; i++) {
      var c = channels[i];
      if (key === GROUP_FAV && !Store.isFav(c.id)) { continue; }
      if (key !== GROUP_ALL && key !== GROUP_FAV && c.group !== key) { continue; }
      if (q && U.fold(c.name).indexOf(q) === -1) { continue; }
      out.push(c);
    }
    if (Store.get('sortBy') === 'name') {
      out.sort(function (a, b) { return U.fold(a.name) < U.fold(b.name) ? -1 : 1; });
    }
    return out;
  }

  function focusedChannel() {
    var list = visibleChannels();
    return list[st.chanIdx] || null;
  }

  function epgLabel(ch) {
    var nn = EPG.nowNext(ch);
    return nn.now ? nn.now.title : '';
  }

  /* ---------- OSD ---------- */

  function osdClock() {
    var now = new Date();
    var t = U.hhmm(now);
    var a = U.one('#osd-clock');
    var b = U.one('#panel-clock');
    if (a) { a.textContent = t; }
    if (b) { b.textContent = t; }
  }

  function showOsd(ch, keepOpen) {
    if (!ch) { return; }
    U.one('#osd-number').textContent = ch.number;
    U.one('#osd-name').textContent = ch.name;
    U.show('#osd-fav', Store.isFav(ch.id));

    var img = U.one('#osd-logo-img');
    var fb = U.one('#osd-logo-fallback');
    if (ch.logo) {
      img.src = ch.logo;
      U.show(img, true); U.show(fb, false);
      img.onerror = function () { U.show(img, false); U.show(fb, true); fb.textContent = ch.name.substr(0, 2).toUpperCase(); };
    } else {
      U.show(img, false); U.show(fb, true);
      fb.textContent = ch.name.substr(0, 2).toUpperCase();
    }

    var nn = EPG.nowNext(ch);
    var nowT = U.one('#osd-now-title');
    var nowTime = U.one('#osd-now-time');
    var nextT = U.one('#osd-next-title');
    var nextTime = U.one('#osd-next-time');
    var bar = U.one('#osd-progress');

    if (nn.now) {
      nowTime.textContent = U.hhmm(new Date(nn.now.start)) + '–' + U.hhmm(new Date(nn.now.stop));
      nowT.textContent = nn.now.title;
      var pct = (Date.now() - nn.now.start) / (nn.now.stop - nn.now.start) * 100;
      bar.style.width = U.clamp(pct, 0, 100) + '%';
    } else {
      nowTime.textContent = '';
      nowT.textContent = EPG.ready() ? 'Program není k dispozici' : 'EPG není nastavené';
      bar.style.width = '0';
    }
    if (nn.next) {
      nextTime.textContent = U.hhmm(new Date(nn.next.start));
      nextT.textContent = nn.next.title;
    } else {
      nextTime.textContent = '';
      nextT.textContent = '';
    }

    var vi = Player.info();
    U.one('#osd-badges').textContent = vi && vi.height ? vi.width + 'x' + vi.height : '';

    osdClock();
    U.show('#osd', true);
    if (st.osdTimer) { clearTimeout(st.osdTimer); st.osdTimer = 0; }
    var secs = keepOpen ? 0 : Number(Store.get('osdSeconds'));
    if (secs > 0) {
      st.osdTimer = setTimeout(function () { U.show('#osd', false); }, secs * 1000);
    }
  }

  function hideOsd() {
    if (st.osdTimer) { clearTimeout(st.osdTimer); st.osdTimer = 0; }
    U.show('#osd', false);
  }

  /* ---------- panel kanalu ---------- */

  function renderGroups() {
    var ul = U.one('#group-list');
    ul.innerHTML = '';
    for (var g = 0; g < groups.length; g++) {
      if (groups[g].key === GROUP_FAV) {
        groups[g].count = channels.filter(function (c) { return Store.isFav(c.id); }).length;
      }
    }
    ul.setAttribute('data-active', st.col === 'groups' ? '1' : '0');
    st.groupOff = U.clamp(st.groupOff, Math.max(0, st.groupIdx - ROWS + 1), st.groupIdx);
    var slice = groups.slice(st.groupOff, st.groupOff + ROWS);
    slice.forEach(function (g, i) {
      var idx = st.groupOff + i;
      var li = U.el('li', idx === st.groupIdx ? 'focus' : '');
      li.appendChild(U.el('span', 'nm', g.name));
      li.appendChild(U.el('span', 'count', g.count));
      ul.appendChild(li);
    });
  }

  function renderChannels() {
    var ul = U.one('#channel-list');
    var list = visibleChannels();
    ul.innerHTML = '';
    ul.setAttribute('data-active', st.col === 'channels' ? '1' : '0');

    if (!list.length) {
      var li0 = U.el('li', '');
      li0.appendChild(U.el('span', 'nm', st.query ? 'Nic nenalezeno' : 'Skupina je prázdná'));
      ul.appendChild(li0);
      renderDetail(null);
      return;
    }

    st.chanIdx = U.clamp(st.chanIdx, 0, list.length - 1);
    st.chanOff = U.clamp(st.chanOff, Math.max(0, st.chanIdx - ROWS + 1), st.chanIdx);
    var playing = Player.channel();

    list.slice(st.chanOff, st.chanOff + ROWS).forEach(function (c, i) {
      var idx = st.chanOff + i;
      var cls = [];
      if (idx === st.chanIdx) { cls.push('focus'); }
      if (playing && playing.id === c.id) { cls.push('playing'); }
      var li = U.el('li', cls.join(' '));
      li.appendChild(U.el('span', 'num', c.number));
      var logo = U.el('span', 'logo');
      if (c.logo) {
        var im = U.el('img');
        im.src = c.logo;
        im.onerror = function () { im.style.display = 'none'; };
        logo.appendChild(im);
      }
      li.appendChild(logo);
      li.appendChild(U.el('span', 'nm', c.name));
      if (Store.isFav(c.id)) { li.appendChild(U.el('span', 'star', '★')); }
      var e = epgLabel(c);
      if (e) { li.appendChild(U.el('span', 'ep', e)); }
      ul.appendChild(li);
    });

    renderDetail(list[st.chanIdx]);
  }

  function renderDetail(ch) {
    var img = U.one('#detail-logo-img');
    if (ch && ch.logo) {
      img.src = ch.logo;
      img.style.display = '';
      img.onerror = function () { img.style.display = 'none'; };
    } else {
      img.removeAttribute('src');
      img.style.display = 'none';
    }
    U.one('#detail-name').textContent = ch ? ch.name : '';
    U.one('#detail-group').textContent = ch ? ch.group + (ch.drm ? '  •  DRM' : '') : '';

    var box = U.one('#detail-epg');
    box.innerHTML = '';
    if (!ch) { return; }
    var list = EPG.upcoming(ch, 4);
    if (!list.length) {
      box.appendChild(U.el('div', 'row', EPG.ready() ? 'Pro tento kanál není program.' : 'EPG není nastavené.'));
      return;
    }
    list.forEach(function (p, i) {
      var row = U.el('div', 'row');
      row.appendChild(U.el('b', '', U.hhmm(new Date(p.start)) + '–' + U.hhmm(new Date(p.stop))));
      row.appendChild(U.el('span', '', p.title));
      if (i === 0 && p.desc) { row.appendChild(U.el('p', '', p.desc)); }
      box.appendChild(row);
    });
  }

  function renderPanel() {
    U.one('#panel-query').textContent = st.query || '—';
    renderGroups();
    renderChannels();
  }

  function openPanel() {
    st.screen = 'panel';
    document.body.classList.add('dimmed');
    hideOsd();
    /* fokus na prave hrajici kanal */
    var playing = Player.channel();
    if (playing) {
      var list = visibleChannels();
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === playing.id) { st.chanIdx = i; break; }
      }
    }
    U.show('#panel', true);
    renderPanel();
  }

  function closePanel() {
    U.show('#panel', false);
    document.body.classList.remove('dimmed');
    st.screen = 'player';
  }

  /* ---------- klavesnice ---------- */

  var CHARS_TEXT = ('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789').split('');
  var CHARS_URL = ('abcdefghijklmnopqrstuvwxyz0123456789:/.-_?=&%@+~,;()*' ).split('');

  function openKeyboard(label, value, charset, onDone) {
    st.kb = { label: label, value: value || '', charset: charset, idx: 0, onDone: onDone };
    var target = st.screen === 'settings' ? 'settings' : 'keyboard';
    st.kbTarget = target;
    if (target === 'settings') {
      U.one('#settings-edit-label').textContent = label;
      U.show('#settings-edit', true);
    } else {
      U.one('#keyboard h2').textContent = label;
      U.show('#keyboard', true);
      st.screen = 'keyboard';
    }
    renderKeyboard();
  }

  function kbKeys() {
    var keys = st.kb.charset.slice();
    keys.push({ label: 'MEZERA', value: ' ', wide: true });
    keys.push({ label: 'MAZAT', value: '\b', wide: true });
    if (st.kb.charset === CHARS_URL) {
      keys.push({ label: 'http://', value: 'http://', wide: true });
      keys.push({ label: 'https://', value: 'https://', wide: true });
    }
    keys.push({ label: 'HOTOVO', value: '\n', wide: true });
    return keys.map(function (k) {
      return typeof k === 'string' ? { label: k, value: k, wide: false } : k;
    });
  }

  function renderKeyboard() {
    var grid = st.kbTarget === 'settings' ? U.one('#settings-kb') : U.one('#kb-grid');
    var valueBox = st.kbTarget === 'settings' ? U.one('#settings-edit-value') : U.one('#kb-value');
    grid.innerHTML = '';
    valueBox.textContent = st.kb.value;
    kbKeys().forEach(function (k, i) {
      var b = U.el('b', (k.wide ? 'wide' : '') + (i === st.kb.idx ? ' focus' : ''), k.label);
      grid.appendChild(b);
    });
  }

  /* Pohyb po klavesnici podle skutecneho rozlozeni - siroke klavesy zabiraji
     dva sloupce, takze pocitani indexu po radcich by sedelo jen nahodou. */
  function kbMove(dir) {
    var grid = st.kbTarget === 'settings' ? U.one('#settings-kb') : U.one('#kb-grid');
    var nodes = Array.prototype.slice.call(grid.children);
    if (!nodes.length) { return; }
    var cur = nodes[st.kb.idx].getBoundingClientRect();
    var cx = cur.left + cur.width / 2;
    var cy = cur.top + cur.height / 2;
    var best = -1;
    var bestScore = Infinity;

    for (var i = 0; i < nodes.length; i++) {
      if (i === st.kb.idx) { continue; }
      var r = nodes[i].getBoundingClientRect();
      var rx = r.left + r.width / 2;
      var ry = r.top + r.height / 2;
      var dx = rx - cx;
      var dy = ry - cy;
      var ok = (dir === 'left' && r.right <= cur.left + 1 && Math.abs(dy) < cur.height / 2) ||
        (dir === 'right' && r.left >= cur.right - 1 && Math.abs(dy) < cur.height / 2) ||
        (dir === 'up' && r.bottom <= cur.top + 1) ||
        (dir === 'down' && r.top >= cur.bottom - 1);
      if (!ok) { continue; }
      var score = (dir === 'up' || dir === 'down')
        ? Math.abs(dy) + Math.abs(dx) * 2
        : Math.abs(dx) + Math.abs(dy) * 4;
      if (score < bestScore) { bestScore = score; best = i; }
    }
    if (best !== -1) { st.kb.idx = best; renderKeyboard(); }
  }

  function kbCommit() {
    var k = kbKeys()[st.kb.idx];
    if (k.value === '\n') { kbFinish(true); return; }
    if (k.value === '\b') { st.kb.value = st.kb.value.slice(0, -1); renderKeyboard(); return; }
    st.kb.value += k.value;
    renderKeyboard();
  }

  function kbFinish(accept) {
    var kb = st.kb;
    st.kb = null;
    if (st.kbTarget === 'settings') {
      U.show('#settings-edit', false);
      st.screen = 'settings';
    } else {
      U.show('#keyboard', false);
      st.screen = 'panel';
    }
    if (accept && kb.onDone) { kb.onDone(kb.value); }
    if (st.screen === 'panel') { renderPanel(); }
    if (st.screen === 'settings') { renderSettings(); }
  }

  /* ---------- nastaveni ---------- */

  var SETTINGS = [
    { key: 'playlistUrl', label: 'Adresa playlistu', type: 'url',
      help: 'URL na m3u/m3u8 seznam kanálů' },
    { key: 'epgUrl', label: 'Adresa EPG (XMLTV)', type: 'url',
      help: 'Nepovinné. Nekomprimovaný .xml' },
    { key: 'userAgent', label: 'User-Agent', type: 'url',
      help: 'Nepovinné. Některé servery ho vyžadují' },
    { key: 'startChannel', label: 'Po spuštění', type: 'enum',
      options: [['last', 'Poslední kanál'], ['first', 'První kanál']] },
    { key: 'aspect', label: 'Poměr stran', type: 'enum',
      options: [['auto', 'Automaticky'], ['fill', 'Na celou obrazovku'], ['zoom', 'Přiblížit']] },
    { key: 'sortBy', label: 'Řazení kanálů', type: 'enum',
      options: [['playlist', 'Podle playlistu'], ['name', 'Podle názvu']] },
    { key: 'osdSeconds', label: 'Informační lišta', type: 'enum',
      options: [[3, '3 sekundy'], [6, '6 sekund'], [10, '10 sekund'], [0, 'Nezhasínat']] },
    { key: 'autoReconnect', label: 'Automatické obnovení', type: 'enum',
      options: [[true, 'Zapnuto'], [false, 'Vypnuto']], help: 'Při přerušení streamu' },
    { key: '@reloadPlaylist', label: 'Načíst playlist znovu', type: 'action' },
    { key: '@reloadEpg', label: 'Načíst EPG znovu', type: 'action' },
    { key: '@clearFavs', label: 'Smazat oblíbené', type: 'action' },
    { key: '@log', label: 'Zobrazit poslední chyby', type: 'action' }
  ];

  function settingValueText(item) {
    if (item.type === 'action') { return 'OK'; }
    var v = Store.get(item.key);
    if (item.type === 'enum') {
      for (var i = 0; i < item.options.length; i++) {
        if (String(item.options[i][0]) === String(v)) { return item.options[i][1]; }
      }
      return String(v);
    }
    if (!v) { return '— nenastaveno —'; }
    return String(v).length > 70 ? String(v).substr(0, 68) + '…' : String(v);
  }

  var SET_ROWS = 10;

  function renderSettings() {
    var ul = U.one('#settings-list');
    ul.innerHTML = '';
    st.setOff = U.clamp(st.setOff || 0, Math.max(0, st.setIdx - SET_ROWS + 1), st.setIdx);
    SETTINGS.slice(st.setOff, st.setOff + SET_ROWS).forEach(function (item, i) {
      var idx = st.setOff + i;
      var li = U.el('li', idx === st.setIdx ? 'focus' : '');
      li.appendChild(U.el('span', 'lbl', item.label));
      li.appendChild(U.el('span', 'val', settingValueText(item)));
      li.appendChild(U.el('span', 'hlp', item.help || ''));
      ul.appendChild(li);
    });
    var more = SETTINGS.length - (st.setOff + SET_ROWS);
    U.one('#settings-version').textContent = (more > 0 ? '▼ ještě ' + more + '  •  ' : '') +
      channels.length + ' kanálů  •  v1.0.0';
  }

  function openSettings() {
    st.prevScreen = st.screen;
    st.screen = 'settings';
    hideOsd();
    U.show('#panel', false);
    document.body.classList.add('dimmed');
    U.show('#settings', true);
    renderSettings();
  }

  function closeSettings() {
    U.show('#settings', false);
    document.body.classList.remove('dimmed');
    st.screen = 'player';
  }

  function cycleEnum(item, dir) {
    var v = Store.get(item.key);
    var idx = 0;
    for (var i = 0; i < item.options.length; i++) {
      if (String(item.options[i][0]) === String(v)) { idx = i; break; }
    }
    idx = (idx + dir + item.options.length) % item.options.length;
    Store.set(item.key, item.options[idx][0]);
    if (item.key === 'aspect') { Player.applyAspect(item.options[idx][0]); }
    renderSettings();
  }

  function activateSetting(item) {
    if (item.type === 'enum') { cycleEnum(item, 1); return; }
    if (item.type === 'url') {
      openKeyboard(item.label, Store.get(item.key), CHARS_URL, function (val) {
        Store.set(item.key, val.trim());
        if (item.key === 'playlistUrl' && hooks.onReloadPlaylist) { hooks.onReloadPlaylist(); }
        if (item.key === 'epgUrl' && hooks.onReloadEpg) { hooks.onReloadEpg(); }
      });
      return;
    }
    if (item.key === '@reloadPlaylist' && hooks.onReloadPlaylist) { hooks.onReloadPlaylist(); toast('Načítám playlist…'); }
    if (item.key === '@reloadEpg' && hooks.onReloadEpg) { hooks.onReloadEpg(); toast('Načítám EPG…'); }
    if (item.key === '@clearFavs') {
      Store.favs().forEach(function (id) { Store.toggleFav(id); });
      toast('Oblíbené smazány');
      renderSettings();
    }
    if (item.key === '@log') {
      error('Poslední hlášení', U.dumpLog().split('\n').slice(-12).join('\n') || 'Žádná hlášení.');
    }
  }

  /* ---------- hlasky ---------- */

  var toastTimer = 0;
  function toast(msg, ms) {
    var n = U.one('#toast');
    n.textContent = msg;
    U.show(n, true);
    if (toastTimer) { clearTimeout(toastTimer); }
    toastTimer = setTimeout(function () { U.show(n, false); }, ms || 3000);
  }

  function spinner(on, text) {
    U.show('#spinner', on);
    if (text) { U.one('#spinner-text').textContent = text; }
  }

  function error(title, text) {
    st.screen = 'error';
    U.one('#error-title').textContent = title;
    U.one('#error-text').textContent = text;
    U.show('#panel', false);
    U.show('#settings', false);
    U.show('#error', true);
    document.body.classList.add('dimmed');
    spinner(false);
  }

  function clearError() {
    U.show('#error', false);
    document.body.classList.remove('dimmed');
    st.screen = 'player';
  }

  function numpad(digits) {
    var n = U.one('#numpad');
    if (!digits) { U.show(n, false); return; }
    n.textContent = digits;
    U.show(n, true);
  }

  /* ---------- ovladani ---------- */

  function playFocused() {
    var ch = focusedChannel();
    if (!ch) { return; }
    closePanel();
    if (hooks.onPlay) { hooks.onPlay(ch); }
  }

  function moveChannels(delta) {
    var list = visibleChannels();
    if (!list.length) { return; }
    st.chanIdx = U.clamp(st.chanIdx + delta, 0, list.length - 1);
    renderChannels();
  }

  function moveGroups(delta) {
    if (!groups.length) { return; }
    st.groupIdx = U.clamp(st.groupIdx + delta, 0, groups.length - 1);
    st.chanIdx = 0;
    st.chanOff = 0;
    renderPanel();
  }

  function onKey(name, extra) {
    /* obrazovka s chybou */
    if (st.screen === 'error') {
      if (name === 'back' || name === 'ok') {
        clearError();
        if (name === 'ok' && hooks.onRetry) { hooks.onRetry(); }
        return true;
      }
      if (name === 'red') { clearError(); openSettings(); return true; }
      return true;
    }

    /* klavesnice */
    if (st.kb) {
      if (name === 'left' || name === 'right' || name === 'up' || name === 'down') { kbMove(name); return true; }
      if (name === 'ok') { kbCommit(); return true; }
      if (name === 'back') { kbFinish(false); return true; }
      if (name === 'playpause' || name === 'play') { st.kb.value = ''; renderKeyboard(); return true; }
      if (name === 'digit') { st.kb.value += extra; renderKeyboard(); return true; }
      if (name === 'char') { st.kb.value += extra; renderKeyboard(); return true; }
      if (name === 'green') { kbFinish(true); return true; }
      return true;
    }

    /* nastaveni */
    if (st.screen === 'settings') {
      if (name === 'up') { st.setIdx = U.clamp(st.setIdx - 1, 0, SETTINGS.length - 1); renderSettings(); return true; }
      if (name === 'down') { st.setIdx = U.clamp(st.setIdx + 1, 0, SETTINGS.length - 1); renderSettings(); return true; }
      if (name === 'left') { if (SETTINGS[st.setIdx].type === 'enum') { cycleEnum(SETTINGS[st.setIdx], -1); } return true; }
      if (name === 'right') { if (SETTINGS[st.setIdx].type === 'enum') { cycleEnum(SETTINGS[st.setIdx], 1); } return true; }
      if (name === 'ok') { activateSetting(SETTINGS[st.setIdx]); return true; }
      if (name === 'back' || name === 'red') { closeSettings(); return true; }
      return true;
    }

    /* seznam kanalu */
    if (st.screen === 'panel') {
      if (name === 'up') { st.col === 'groups' ? moveGroups(-1) : moveChannels(-1); return true; }
      if (name === 'down') { st.col === 'groups' ? moveGroups(1) : moveChannels(1); return true; }
      if (name === 'chup') { st.col === 'groups' ? moveGroups(-1) : moveChannels(-ROWS); return true; }
      if (name === 'chdown') { st.col === 'groups' ? moveGroups(1) : moveChannels(ROWS); return true; }
      if (name === 'left') { st.col = 'groups'; renderPanel(); return true; }
      if (name === 'right') { st.col = 'channels'; renderPanel(); return true; }
      if (name === 'ok') {
        if (st.col === 'groups') { st.col = 'channels'; renderPanel(); } else { playFocused(); }
        return true;
      }
      if (name === 'back' || name === 'list') { closePanel(); return true; }
      if (name === 'yellow') {
        var ch = focusedChannel();
        if (ch) { toast(Store.toggleFav(ch.id) ? 'Přidáno do oblíbených' : 'Odebráno z oblíbených', 1500); renderPanel(); }
        return true;
      }
      if (name === 'green') {
        openKeyboard('Hledat kanál', st.query, CHARS_TEXT, function (v) {
          st.query = v.trim();
          st.chanIdx = 0; st.chanOff = 0;
          renderPanel();
        });
        return true;
      }
      if (name === 'red') { openSettings(); return true; }
      if (name === 'blue') { nextAspect(); return true; }
      if (name === 'info') {
        var c2 = focusedChannel();
        if (c2) { closePanel(); showOsd(c2, true); }
        return true;
      }
      return false; /* cislice resi app.js */
    }

    /* prehravac */
    if (name === 'ok' || name === 'list' || name === 'right') { openPanel(); return true; }
    if (name === 'info' || name === 'left') {
      var cur = Player.channel();
      if (!cur) { return true; }
      if (U.one('#osd').classList.contains('hidden')) { showOsd(cur, true); } else { hideOsd(); }
      return true;
    }
    if (name === 'red') { openSettings(); return true; }
    if (name === 'green') { openPanel(); onKey('green'); return true; }
    if (name === 'yellow') {
      var c3 = Player.channel();
      if (c3) { toast(Store.toggleFav(c3.id) ? 'Přidáno do oblíbených' : 'Odebráno z oblíbených', 1500); showOsd(c3); }
      return true;
    }
    if (name === 'blue') { nextAspect(); return true; }
    if (name === 'playpause' || name === 'play' || name === 'pause') {
      toast(Player.pauseToggle() === 'paused' ? 'Pozastaveno' : 'Pokračuje', 1500);
      return true;
    }
    if (name === 'back') {
      if (Date.now() - st.exitArmed < 3000) { exitApp(); return true; }
      st.exitArmed = Date.now();
      toast('Stiskněte RETURN znovu pro ukončení', 3000);
      return true;
    }
    if (name === 'exit') { exitApp(); return true; }
    return false;
  }

  function nextAspect() {
    var order = ['auto', 'fill', 'zoom'];
    var labels = { auto: 'Automaticky', fill: 'Na celou obrazovku', zoom: 'Přiblížit' };
    var i = order.indexOf(Store.get('aspect'));
    var next = order[(i + 1 + order.length) % order.length];
    Store.set('aspect', next);
    Player.applyAspect(next);
    toast('Poměr stran: ' + labels[next], 2000);
  }

  function exitApp() {
    Player.stop();
    if (U.isTizen && window.tizen && tizen.application) {
      try { tizen.application.getCurrentApplication().exit(); return; } catch (e) { U.warn('exit:', e.message); }
    }
    toast('Aplikaci ukončí RETURN na televizi');
  }

  /* ---------- verejne ---------- */

  function setChannels(list) {
    channels = list || [];
    var gs = M3U.groupsOf(channels);
    groups = [{ key: GROUP_ALL, name: 'Vše', count: channels.length },
      { key: GROUP_FAV, name: 'Oblíbené', count: Store.favs().length }];
    gs.forEach(function (g) { groups.push({ key: g.name, name: g.name, count: g.count }); });
    st.groupIdx = U.clamp(st.groupIdx, 0, groups.length - 1);
    if (st.screen === 'panel') { renderPanel(); }
  }

  function refresh() {
    if (st.screen === 'panel') { renderPanel(); }
    if (!U.one('#osd').classList.contains('hidden') && Player.channel()) { showOsd(Player.channel(), true); }
  }

  function init(h) {
    hooks = h || {};
    Keys.on(onKey);
    setInterval(osdClock, 10000);
    osdClock();
  }

  return { init: init, setChannels: setChannels, refresh: refresh,
    showOsd: showOsd, hideOsd: hideOsd, openPanel: openPanel, closePanel: closePanel,
    openSettings: openSettings, toast: toast, spinner: spinner, error: error,
    numpad: numpad, screen: function () { return st.screen; } };
}());
