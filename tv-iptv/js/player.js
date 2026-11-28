/* Prehravani streamu. Na TV pres webapis.avplay (HLS, MPEG-TS, DASH, progresivni MP4),
   v prohlizeci pres <video> (jen pro ladeni vzhledu; volitelne hls.js z vendor/). */
var Player = (function () {
  'use strict';

  var MODE = {
    auto: 'PLAYER_DISPLAY_MODE_LETTER_BOX',
    fill: 'PLAYER_DISPLAY_MODE_FULL_SCREEN',
    zoom: 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO'
  };

  var av = null;
  var video = null;
  var hls = null;
  var current = null;
  var retries = 0;
  var retryTimer = 0;
  var listeners = {};
  var lastState = 'idle';

  function emit(name, data) {
    if (listeners[name]) { listeners[name].forEach(function (fn) { fn(data); }); }
  }

  function on(name, fn) {
    if (!listeners[name]) { listeners[name] = []; }
    listeners[name].push(fn);
  }

  function setState(s, detail) {
    lastState = s;
    emit('state', { state: s, detail: detail, channel: current });
  }

  function init() {
    video = U.one('#html5video');
    if (U.isTizen && window.webapis && webapis.avplay) {
      av = webapis.avplay;
      document.body.classList.add('tizen');
      U.log('prehravac: avplay');
    } else {
      U.log('prehravac: HTML5 video');
      video.addEventListener('waiting', function () { setState('buffering'); });
      video.addEventListener('playing', function () { retries = 0; setState('playing'); });
      video.addEventListener('error', function () { onFailure('Prohlížeč tento stream nepřehraje'); });
    }
    applyAspect(Store.get('aspect'));
  }

  function clearRetry() {
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = 0; }
  }

  function stop() {
    clearRetry();
    if (av) {
      try {
        var st = av.getState();
        if (st === 'PLAYING' || st === 'PAUSED' || st === 'READY') { av.stop(); }
        if (st !== 'NONE') { av.close(); }
      } catch (e) { U.warn('avplay stop:', e.message); }
    } else if (video) {
      if (hls) { try { hls.destroy(); } catch (e2) { /* nic */ } hls = null; }
      video.pause();
      video.removeAttribute('src');
      try { video.load(); } catch (e3) { /* nic */ }
    }
    setState('idle');
  }

  function applyStreamProps(ch) {
    if (!av) { return; }
    var ua = ch.ua || Store.get('userAgent');
    /* Nazvy vlastnosti se mezi firmwary lisi, proto kazdy pokus zvlast. */
    if (ua) {
      try { av.setStreamingProperty('USER_AGENT', ua); } catch (e) { U.warn('USER_AGENT: ' + e.message); }
      try { av.setStreamingProperty('ADAPTIVE_INFO', 'USER_AGENT=' + ua); } catch (e2) { /* nic */ }
    }
    if (ch.referer) {
      try { av.setStreamingProperty('COOKIE', 'Referer=' + ch.referer); } catch (e3) { /* nic */ }
    }
    try { av.setStreamingProperty('PREBUFFER_MODE', 'auto'); } catch (e4) { /* nic */ }
  }

  function avListener() {
    return {
      onbufferingstart: function () { setState('buffering'); },
      onbufferingprogress: function () { /* nic */ },
      onbufferingcomplete: function () { retries = 0; setState('playing'); },
      oncurrentplaytime: function () { /* nic */ },
      onstreamcompleted: function () {
        U.log('stream skoncil');
        onFailure('Stream skončil');
      },
      onevent: function (type, data) { U.log('avplay event', type, data); },
      onerror: function (type) { onFailure('Chyba přehrávače: ' + type); },
      onsubtitlechange: function () { /* nic */ },
      ondrmevent: function () { /* nic */ }
    };
  }

  function onFailure(msg) {
    U.warn('prehravani selhalo:', msg);
    setState('error', msg);
    if (!Store.get('autoReconnect') || !current) { return; }
    if (retries >= 5) {
      setState('dead', msg);
      return;
    }
    retries += 1;
    var wait = U.clamp(retries * 2000, 2000, 10000);
    setState('reconnecting', 'Pokus ' + retries + ' z 5');
    clearRetry();
    retryTimer = setTimeout(function () { play(current, true); }, wait);
  }

  function play(ch, isRetry) {
    if (!ch) { return; }
    if (!isRetry) { retries = 0; }
    clearRetry();
    current = ch;
    setState('loading');

    if (ch.drm) {
      setState('dead', 'Kanál je chráněný DRM (Widevine/PlayReady), aplikace ho nepřehraje');
      return;
    }

    /* Rezim pro ladeni vzhledu na PC (dev/screenshot.js): nic se nepripojuje. */
    if (window.IPTV_DEMO) { setState('playing'); return; }

    if (av) {
      try {
        try { stopQuiet(); } catch (e0) { /* nic */ }
        av.open(ch.url);
        av.setListener(avListener());
        av.setDisplayRect(0, 0, 1920, 1080);
        applyStreamProps(ch);
        try { av.setDisplayMethod(MODE[Store.get('aspect')] || MODE.auto); } catch (e1) { /* nic */ }
        av.prepareAsync(function () {
          try { av.play(); setState('playing'); }
          catch (e2) { onFailure(e2.message); }
        }, function (err) {
          onFailure(err && err.message ? err.message : 'prepareAsync selhalo');
        });
      } catch (e) {
        onFailure(e.message || String(e));
      }
      return;
    }

    /* Prohlizec */
    if (hls) { try { hls.destroy(); } catch (e3) { /* nic */ } hls = null; }
    var isHls = /\.m3u8(\?|$)/i.test(ch.url);
    if (isHls && window.Hls && window.Hls.isSupported()) {
      hls = new window.Hls({ enableWorker: false });
      hls.loadSource(ch.url);
      hls.attachMedia(video);
    } else {
      video.src = ch.url;
    }
    var p = video.play();
    if (p && p.catch) { p.catch(function (e4) { U.warn('video.play:', e4.message); }); }
  }

  function stopQuiet() {
    if (!av) { return; }
    var st = av.getState();
    if (st === 'PLAYING' || st === 'PAUSED') { av.stop(); }
    if (st !== 'NONE') { av.close(); }
  }

  function pauseToggle() {
    if (av) {
      try {
        var st = av.getState();
        if (st === 'PLAYING') { av.pause(); setState('paused'); return 'paused'; }
        if (st === 'PAUSED') { av.play(); setState('playing'); return 'playing'; }
      } catch (e) { U.warn('pause:', e.message); }
      return lastState;
    }
    if (video.paused) { video.play(); return 'playing'; }
    video.pause();
    return 'paused';
  }

  function applyAspect(mode) {
    if (!MODE[mode]) { mode = 'auto'; }
    if (av) {
      try { av.setDisplayMethod(MODE[mode]); } catch (e) { U.warn('setDisplayMethod:', e.message); }
    }
    document.body.classList.remove('fit-fill', 'fit-cover');
    if (mode === 'fill') { document.body.classList.add('fit-fill'); }
    if (mode === 'zoom') { document.body.classList.add('fit-cover'); }
  }

  function info() {
    if (!av) {
      return { width: video.videoWidth, height: video.videoHeight };
    }
    try {
      var d = av.getCurrentStreamInfo();
      for (var i = 0; i < d.length; i++) {
        if (d[i].type === 'VIDEO') {
          var extra = JSON.parse(d[i].extra_info);
          return { width: extra.Width, height: extra.Height, codec: extra.fourCC };
        }
      }
    } catch (e) { /* nic */ }
    return {};
  }

  function state() { return lastState; }
  function channel() { return current; }

  return { init: init, play: play, stop: stop, pauseToggle: pauseToggle,
    applyAspect: applyAspect, on: on, info: info, state: state, channel: channel };
}());
