/* Kody tlacitek samsungskeho ovladace a jejich registrace.
   Sipky, OK a RETURN jsou dostupne vzdy, ostatni se musi registrovat. */
var Keys = (function () {
  'use strict';

  var MAP = {
    37: 'left', 38: 'up', 39: 'right', 40: 'down',
    13: 'ok',
    10009: 'back', 10182: 'exit',
    427: 'chup', 428: 'chdown', 10073: 'list',
    457: 'info', 458: 'guide',
    415: 'play', 19: 'pause', 10252: 'playpause', 413: 'stop',
    417: 'ff', 412: 'rw',
    403: 'red', 404: 'green', 405: 'yellow', 406: 'blue',
    10135: 'tools', 10228: 'extra'
  };

  /* Nazvy pro tizen.tvinputdevice.registerKey - co TV nezna, se preskoci. */
  var REGISTER = ['ChannelUp', 'ChannelDown', 'ChannelList', 'Info', 'Guide',
    'MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaStop',
    'MediaFastForward', 'MediaRewind',
    'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue',
    'Tools', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];

  /* Nahradni klavesy pro ladeni v prohlizeci na PC. */
  var DEV = {
    Escape: 'back', Backspace: 'back', Enter: 'ok',
    ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
    PageUp: 'chup', PageDown: 'chdown',
    F1: 'red', F2: 'green', F3: 'yellow', F4: 'blue',
    F5: 'info', F6: 'playpause', F7: 'list'
  };

  var handlers = [];
  var available = {};

  function register() {
    if (!U.isTizen || !window.tizen || !tizen.tvinputdevice) { return; }
    var supported = {};
    try {
      tizen.tvinputdevice.getSupportedKeys().forEach(function (k) { supported[k.name] = true; });
    } catch (e) {
      U.warn('getSupportedKeys selhalo:', e.message);
    }
    REGISTER.forEach(function (name) {
      if (Object.keys(supported).length && !supported[name]) { return; }
      try {
        tizen.tvinputdevice.registerKey(name);
        available[name] = true;
      } catch (e) {
        U.warn('registerKey ' + name + ': ' + e.message);
      }
    });
    U.log('registrovano tlacitek:', Object.keys(available).length);
  }

  function emit(name, ev, extra) {
    for (var i = 0; i < handlers.length; i++) {
      if (handlers[i](name, extra, ev) === true) { return; }
    }
  }

  function onKeyDown(ev) {
    var code = ev.keyCode;
    var name = MAP[code];

    if (!name && code >= 48 && code <= 57) {
      ev.preventDefault();
      emit('digit', ev, String(code - 48));
      return;
    }
    if (!name && code >= 96 && code <= 105) { /* numericka klavesnice na PC */
      ev.preventDefault();
      emit('digit', ev, String(code - 96));
      return;
    }
    if (!name && !U.isTizen && DEV[ev.key]) { name = DEV[ev.key]; }

    if (name) {
      ev.preventDefault();
      emit(name, ev);
      return;
    }
    /* Pismena z USB klavesnice - vyuzije hledani. */
    if (ev.key && ev.key.length === 1 && /[a-zA-Z0-9 +]/.test(ev.key)) {
      emit('char', ev, ev.key);
    }
  }

  function init() {
    register();
    document.addEventListener('keydown', onKeyDown, false);
  }

  function on(fn) { handlers.unshift(fn); }
  function off(fn) {
    var i = handlers.indexOf(fn);
    if (i !== -1) { handlers.splice(i, 1); }
  }

  return { init: init, on: on, off: off, codes: MAP };
}());
