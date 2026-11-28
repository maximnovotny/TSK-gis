/* Spojeni vsech casti: nacteni playlistu, prepinani kanalu, EPG, stav prehravace. */
(function () {
  'use strict';

  var LOCAL_PLAYLIST = 'assets/playlist.m3u';   /* zaloha prilozena v balicku */
  var EPG_REFRESH_MS = 30 * 60 * 1000;

  var channels = [];
  var byNumber = {};
  var zap = { digits: '', timer: 0 };
  var epgTimer = 0;
  var playlistEpgHint = '';

  /* ---------- playlist ---------- */

  function loadPlaylist() {
    var url = String(Store.get('playlistUrl') || '').trim();
    UI.spinner(true, 'Načítám playlist…');

    var src = url ? U.fetchText(url, { timeout: 25000, userAgent: Store.get('userAgent') })
      : U.fetchText(LOCAL_PLAYLIST, { timeout: 5000 });

    return src.then(function (text) {
      var res = M3U.parse(text);
      if (!res.channels.length) { throw new Error('Playlist neobsahuje žádné kanály'); }
      channels = res.channels;
      playlistEpgHint = res.epgUrl;
      byNumber = {};
      channels.forEach(function (c) { byNumber[c.number] = c; });
      UI.setChannels(channels);
      UI.spinner(false);
      U.log('playlist: ' + channels.length + ' kanalu' + (url ? '' : ' (prilozeny vzorek)'));
      if (!url) {
        UI.toast('Není nastavená adresa playlistu – hraje přiložený vzorek. Nastavení: červené tlačítko.', 7000);
      }
      startPlayback();
      scheduleEpg();
      return true;
    }, function (e) {
      UI.spinner(false);
      UI.error('Playlist se nepodařilo načíst',
        (url ? url : LOCAL_PLAYLIST) + '\n\n' + e.message +
        '\n\nZkontrolujte adresu v nastavení (červené tlačítko) a připojení televize k síti.');
      return false;
    });
  }

  /* ---------- EPG ---------- */

  function epgUrl() {
    var u = String(Store.get('epgUrl') || '').trim();
    return u || playlistEpgHint;
  }

  function loadEpg() {
    var url = epgUrl();
    if (!url) { return; }
    U.log('nacitam EPG:', url);
    EPG.load(url, channels).then(function () {
      UI.refresh();
      UI.toast('Program (EPG) načten', 2000);
    }, function (e) {
      UI.toast('EPG se nepodařilo načíst: ' + e.message, 5000);
    });
  }

  function scheduleEpg() {
    if (epgTimer) { clearInterval(epgTimer); }
    if (!epgUrl()) { return; }
    loadEpg();
    epgTimer = setInterval(loadEpg, EPG_REFRESH_MS);
  }

  /* ---------- prehravani ---------- */

  function sortedByNumber() {
    return channels.slice().sort(function (a, b) { return a.number - b.number; });
  }

  function playChannel(ch) {
    if (!ch) { return; }
    Store.remember(ch);
    Player.play(ch);
    UI.showOsd(ch);
    UI.refresh();
  }

  function startPlayback() {
    if (!channels.length) { return; }
    var want = null;
    if (Store.get('startChannel') === 'last' && Store.lastId()) {
      for (var i = 0; i < channels.length; i++) {
        if (channels[i].id === Store.lastId()) { want = channels[i]; break; }
      }
    }
    if (!want) { want = sortedByNumber()[0]; }
    playChannel(want);
  }

  function step(delta) {
    if (!channels.length) { return; }
    var list = sortedByNumber();
    var cur = Player.channel();
    var idx = 0;
    if (cur) {
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === cur.id) { idx = i; break; }
      }
    }
    idx = (idx + delta + list.length) % list.length;
    playChannel(list[idx]);
  }

  function zapDigit(d) {
    zap.digits += d;
    if (zap.digits.length > 4) { zap.digits = d; }
    UI.numpad(zap.digits);
    if (zap.timer) { clearTimeout(zap.timer); }
    zap.timer = setTimeout(function () {
      var num = parseInt(zap.digits, 10);
      zap.digits = '';
      UI.numpad('');
      var ch = byNumber[num];
      if (ch) { playChannel(ch); }
      else { UI.toast('Kanál ' + num + ' neexistuje', 2000); }
    }, 2200);
  }

  /* ---------- stav prehravace ---------- */

  function onPlayerState(ev) {
    if (ev.state === 'loading') { UI.spinner(true, 'Připojuji ke kanálu…'); return; }
    if (ev.state === 'buffering') { UI.spinner(true, 'Naplňuji buffer…'); return; }
    if (ev.state === 'playing') { UI.spinner(false); return; }
    if (ev.state === 'paused') { UI.spinner(false); return; }
    if (ev.state === 'reconnecting') {
      UI.spinner(true, 'Stream se přerušil, zkouším znovu… (' + (ev.detail || '') + ')');
      return;
    }
    if (ev.state === 'dead') {
      UI.spinner(false);
      UI.error('Kanál nelze přehrát',
        (ev.channel ? ev.channel.name + '\n' + ev.channel.url + '\n\n' : '') + (ev.detail || '') +
        '\n\nStisknutím OK to zkusíte znovu, seznam kanálů otevřete tlačítkem RETURN.');
      return;
    }
    if (ev.state === 'idle') { UI.spinner(false); }
  }

  /* ---------- globalni tlacitka ---------- */

  function onKey(name, extra) {
    if (name === 'digit') { zapDigit(extra); return true; }
    if (name === 'chup') { step(1); return true; }
    if (name === 'chdown') { step(-1); return true; }
    if (name === 'up' && UI.screen() === 'player') { step(1); return true; }
    if (name === 'down' && UI.screen() === 'player') { step(-1); return true; }
    return false;
  }

  /* ---------- obrazovka a zivotni cyklus ---------- */

  function keepScreenOn() {
    if (!U.isTizen || !window.webapis || !webapis.appcommon) { return; }
    try {
      webapis.appcommon.setScreenSaver(webapis.appcommon.AppCommonScreenSaverState.SCREEN_SAVER_OFF);
      U.log('sporic obrazovky vypnut');
    } catch (e) { U.warn('setScreenSaver:', e.message); }
  }

  function onVisibility() {
    if (document.hidden) {
      U.log('aplikace na pozadi, zastavuji prehravani');
      Player.stop();
    } else if (Player.channel()) {
      U.log('aplikace zpet v popredi');
      Player.play(Player.channel());
    }
  }

  function boot() {
    U.log('start, Tizen =', U.isTizen);
    Keys.init();
    Player.init();
    Player.on('state', onPlayerState);

    UI.init({
      onPlay: playChannel,
      onReloadPlaylist: loadPlaylist,
      onReloadEpg: function () { playlistEpgHint = playlistEpgHint; scheduleEpg(); },
      onRetry: function () {
        if (!channels.length) { loadPlaylist(); }
        else if (Player.channel()) { playChannel(Player.channel()); }
      }
    });
    Keys.on(onKey);

    keepScreenOn();
    document.addEventListener('visibilitychange', onVisibility, false);

    if (!U.online()) {
      UI.error('Televize není v síti', 'Zkontrolujte připojení k Wi-Fi nebo kabelu a zkuste to znovu tlačítkem OK.');
      return;
    }
    loadPlaylist();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, false);
  } else {
    boot();
  }

  /* pro ladeni z konzole */
  window.IPTV = {
    channels: function () { return channels; },
    play: playChannel,
    log: U.dumpLog
  };
}());
