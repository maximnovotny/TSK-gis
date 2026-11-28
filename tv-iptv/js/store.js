/* Trvale ulozena nastaveni, oblibene kanaly a posledni kanal. */
var Store = (function () {
  'use strict';

  var KEY = 'iptvhrac.v1';

  var DEFAULTS = {
    playlistUrl: '',
    epgUrl: '',
    userAgent: '',
    startChannel: 'last',   /* last | first */
    aspect: 'auto',         /* auto | fill | zoom */
    osdSeconds: 6,
    sortBy: 'playlist',     /* playlist | name */
    autoReconnect: true,
    favOnly: false
  };

  var state = { settings: {}, favs: [], lastId: '', lastNumber: 0 };

  function readRaw() {
    try {
      var raw = window.localStorage.getItem(KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      U.warn('localStorage nelze cist:', e.message);
      return null;
    }
  }

  function persist() {
    try {
      window.localStorage.setItem(KEY, JSON.stringify(state));
    } catch (e) {
      U.warn('localStorage nelze zapsat:', e.message);
    }
  }

  function load() {
    var saved = readRaw();
    state.settings = {};
    Object.keys(DEFAULTS).forEach(function (k) {
      var v = saved && saved.settings && saved.settings[k] !== undefined ? saved.settings[k] : DEFAULTS[k];
      state.settings[k] = v;
    });
    state.favs = saved && Array.isArray(saved.favs) ? saved.favs.slice() : [];
    state.lastId = saved && saved.lastId ? String(saved.lastId) : '';
    state.lastNumber = saved && saved.lastNumber ? Number(saved.lastNumber) : 0;
  }

  function get(key) { return state.settings[key]; }

  function set(key, val) {
    state.settings[key] = val;
    persist();
  }

  function defaults() { return DEFAULTS; }

  function isFav(id) { return state.favs.indexOf(id) !== -1; }

  function toggleFav(id) {
    var i = state.favs.indexOf(id);
    if (i === -1) { state.favs.push(id); } else { state.favs.splice(i, 1); }
    persist();
    return i === -1;
  }

  function favs() { return state.favs.slice(); }

  function remember(ch) {
    if (!ch) { return; }
    state.lastId = ch.id;
    state.lastNumber = ch.number;
    persist();
  }

  function lastId() { return state.lastId; }

  function reset() {
    state = { settings: {}, favs: [], lastId: '', lastNumber: 0 };
    load();
    persist();
  }

  load();

  return { get: get, set: set, defaults: defaults, isFav: isFav, toggleFav: toggleFav,
    favs: favs, remember: remember, lastId: lastId, reset: reset };
}());
