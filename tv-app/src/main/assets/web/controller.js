/* Control web para el emulador en la TV. Protocolo: docs/protocol.md */
(function () {
  'use strict';

  // ---------- protocolo ----------
  var BIT = { UP: 0, DOWN: 1, LEFT: 2, RIGHT: 3, A: 4, B: 5, X: 6, Y: 7, L: 8, R: 9, START: 10, SELECT: 11 };
  var M = {};
  Object.keys(BIT).forEach(function (k) { M[k] = 1 << BIT[k]; });
  var DIR_MASK = M.UP | M.DOWN | M.LEFT | M.RIGHT;
  // J1 azul, J2 rojo, J3 verde, J4 amarillo (paleta retro)
  var PLAYER_COLORS = { 1: '#4a78ff', 2: '#ff4b45', 3: '#3fd05c', 4: '#ffc61a' };

  var KEEPALIVE_MS = 1000;
  var PING_MS = 2000;
  var DPAD_DEADZONE = 0.22;   // fracción del radio de la cruceta
  var FACE_HIT = 1.4;         // radio de acierto de A/B/X/Y (x radio visual) -> permite presionar dos a la vez entre botones
  var RECT_PAD = 10;          // px extra alrededor de L/R/START/SELECT

  // ---------- DOM ----------
  var $ = function (id) { return document.getElementById(id); };
  var pad = $('pad'), dpadEl = $('dpad'), faceEl = $('face');
  var connEl = $('conn'), connText = $('conn-text'), playerEl = $('player'), pingEl = $('ping');
  var fullEl = $('full');
  var arms = {
    UP: dpadEl.querySelector('.up'), DOWN: dpadEl.querySelector('.down'),
    LEFT: dpadEl.querySelector('.left'), RIGHT: dpadEl.querySelector('.right')
  };
  var btnEls = {};
  Array.prototype.forEach.call(document.querySelectorAll('[data-btn]'), function (el) {
    btnEls[el.dataset.btn] = el;
  });
  var FACE = ['A', 'B', 'X', 'Y'];
  var RECTS = ['L', 'R', 'START', 'SELECT'];

  // ---------- geometría (cacheada, se recalcula al cambiar tamaño) ----------
  var geo = null;
  function measure() {
    var d = dpadEl.getBoundingClientRect();
    var f = faceEl.getBoundingClientRect();
    geo = {
      dpad: { cx: d.left + d.width / 2, cy: d.top + d.height / 2, r: d.width / 2 },
      face: { left: f.left, top: f.top, right: f.right, bottom: f.bottom },
      faceBtns: FACE.map(function (k) {
        var r = btnEls[k].getBoundingClientRect();
        return { k: k, cx: r.left + r.width / 2, cy: r.top + r.height / 2, r: r.width / 2 };
      }),
      rects: RECTS.map(function (k) {
        var r = btnEls[k].getBoundingClientRect();
        return { k: k, l: r.left - RECT_PAD, t: r.top - RECT_PAD, r: r.right + RECT_PAD, b: r.bottom + RECT_PAD };
      })
    };
  }
  window.addEventListener('resize', measure);
  window.addEventListener('orientationchange', function () { setTimeout(measure, 250); });

  // ---------- entrada (multitouch por pointerId) ----------
  var pointers = new Map(); // pointerId -> { kind: 'dpad'|'btns', mask }
  var mask = 0;

  function dpadMask(x, y) {
    var g = geo.dpad;
    var dx = x - g.cx, dy = y - g.cy;
    if (Math.hypot(dx, dy) < g.r * DPAD_DEADZONE) return 0;
    // 8 sectores de 45°, 0 = derecha, sentido horario (y crece hacia abajo)
    var s = Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
    s = (s + 8) % 8;
    return [M.RIGHT, M.RIGHT | M.DOWN, M.DOWN, M.DOWN | M.LEFT,
            M.LEFT, M.LEFT | M.UP, M.UP, M.UP | M.RIGHT][s];
  }

  function btnsMask(x, y) {
    var m = 0;
    geo.faceBtns.forEach(function (b) {
      if (Math.hypot(x - b.cx, y - b.cy) <= b.r * FACE_HIT) m |= M[b.k];
    });
    geo.rects.forEach(function (b) {
      if (x >= b.l && x <= b.r && y >= b.t && y <= b.b) m |= M[b.k];
    });
    return m;
  }

  function inDpadZone(x, y) {
    var g = geo.dpad;
    return Math.hypot(x - g.cx, y - g.cy) <= g.r * 1.15;
  }

  function recompute() {
    var m = 0;
    pointers.forEach(function (p) { m |= p.mask; });
    setMask(m);
  }

  function setMask(m) {
    if (m === mask) return;
    var pressed = m & ~mask;
    var changed = m ^ mask;
    mask = m;
    Object.keys(BIT).forEach(function (k) {
      if (!(changed & M[k])) return;
      var on = !!(m & M[k]);
      if (M[k] & DIR_MASK) arms[k].classList.toggle('on', on);
      else btnEls[k].classList.toggle('on', on);
    });
    if (pressed) buzz(10);
    sendState();
  }

  function releaseAll() {
    pointers.clear();
    if (mask !== 0) setMask(0); else sendState();
  }

  function onDown(e) {
    var action = e.target.closest && e.target.closest('[data-action]');
    if (action) return; // botones utilitarios (menú, pantalla completa, juego) se manejan aparte
    if (uploadOpen) return; // con la ventana de subida abierta el control no captura toques
    e.preventDefault();
    // Se mide en cada toque nuevo: si la página cargó en vertical y luego se giró, una
    // geometría guardada deja los botones desfasados (el evento resize no siempre llega a tiempo).
    measure();
    var p = inDpadZone(e.clientX, e.clientY)
      ? { kind: 'dpad', mask: dpadMask(e.clientX, e.clientY) }
      : { kind: 'btns', mask: btnsMask(e.clientX, e.clientY) };
    pointers.set(e.pointerId, p);
    recompute();
  }

  function onMove(e) {
    var p = pointers.get(e.pointerId);
    if (!p) return;
    e.preventDefault();
    // en Chrome Android, getCoalescedEvents no hace falta: solo importa la última posición
    p.mask = p.kind === 'dpad' ? dpadMask(e.clientX, e.clientY) : btnsMask(e.clientX, e.clientY);
    recompute();
  }

  function onUp(e) {
    if (!pointers.delete(e.pointerId)) return;
    recompute();
  }

  pad.addEventListener('pointerdown', onDown, { passive: false });
  window.addEventListener('pointermove', onMove, { passive: false });
  window.addEventListener('pointerup', onUp);
  window.addEventListener('pointercancel', releaseAll);
  window.addEventListener('blur', releaseAll);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') releaseAll();
    else if (!isFull && (!ws || ws.readyState > 1)) connectNow();
  });

  // Bloquear gestos del navegador
  ['contextmenu', 'selectstart', 'dragstart', 'gesturestart'].forEach(function (t) {
    document.addEventListener(t, function (e) { e.preventDefault(); });
  });
  document.addEventListener('touchmove', function (e) {
    if (e.target.closest && e.target.closest('.scroll')) return; // listas con scroll (subida)
    e.preventDefault();
  }, { passive: false });
  document.addEventListener('dblclick', function (e) { e.preventDefault(); });

  function buzz(ms) {
    try { if (navigator.vibrate) navigator.vibrate(ms); } catch (_) { /* sin vibración */ }
  }

  // ---------- botones utilitarios ----------
  $('menu').addEventListener('pointerdown', function (e) {
    e.preventDefault();
    buzz(10);
    send({ t: 'menu' });
    var el = e.currentTarget;
    el.classList.add('on');
    setTimeout(function () { el.classList.remove('on'); }, 150);
  });

  async function goFullscreen() {
    try {
      var el = document.documentElement;
      if (!document.fullscreenElement && el.requestFullscreen) await el.requestFullscreen({ navigationUI: 'hide' });
    } catch (_) { /* no soportado / denegado */ }
    try {
      if (screen.orientation && screen.orientation.lock) await screen.orientation.lock('landscape');
    } catch (_) { /* requiere pantalla completa o no soportado */ }
    setTimeout(measure, 300);
  }
  Array.prototype.forEach.call(document.querySelectorAll('[data-action="fullscreen"]'), function (el) {
    el.addEventListener('click', goFullscreen);
  });
  document.addEventListener('fullscreenchange', function () {
    document.documentElement.classList.toggle('is-fullscreen', !!document.fullscreenElement);
    setTimeout(measure, 300);
  });
  document.querySelector('[data-action="retry"]').addEventListener('click', function () {
    fullEl.hidden = true;
    connectNow();
  });

  // ---------- subir juegos (POST /api/upload, ver docs/protocol.md) ----------
  var DEFAULT_EXTS = ['nes', 'sfc', 'smc', 'gb', 'gbc', 'gba', 'md', 'gen', 'smd', 'sms', 'gg', 'zip'];
  var MAX_UPLOAD = 64 * 1024 * 1024;
  var upEl = $('upload'), upInput = $('up-input'), upList = $('up-list'), upExts = $('up-exts');
  var addLabel = $('add-game-label');
  var uploadOpen = false;
  var exts = [];
  var upQueue = [];   // { file, el }
  var upBusy = false;

  function applyExts(list) {
    exts = list;
    upInput.accept = list.map(function (x) { return '.' + x; }).join(',');
    upExts.textContent = list.map(function (x) { return x.toUpperCase(); }).join(' ');
  }
  applyExts(DEFAULT_EXTS);

  function loadSystems() {
    var x = new XMLHttpRequest();
    x.open('GET', '/api/systems');
    x.timeout = 4000;
    x.onload = function () {
      if (x.status !== 200) return;
      try {
        var list = JSON.parse(x.responseText).extensions;
        if (Array.isArray(list)) {
          list = list.map(function (s) { return String(s).replace(/^\./, '').toLowerCase(); }).filter(Boolean);
          if (list.length) applyExts(list);
        }
      } catch (_) { /* nos quedamos con la lista por defecto */ }
    };
    x.send();
  }

  function openUpload() {
    uploadOpen = true;
    releaseAll();               // b=0: nada queda apretado mientras se elige el archivo
    upEl.hidden = false;
    pad.setAttribute('aria-hidden', 'true');
    loadSystems();
    if (curTab === 'games') loadGames();
  }
  function closeUpload() {
    uploadOpen = false;
    upEl.hidden = true;
    pad.removeAttribute('aria-hidden');
    releaseAll();
    setTimeout(measure, 50);
  }
  $('add-game').addEventListener('click', openUpload);
  $('up-close').addEventListener('click', closeUpload);
  $('up-back').addEventListener('click', closeUpload);
  document.addEventListener('keydown', function (e) {
    if (uploadOpen && e.key === 'Escape') closeUpload();
  });

  function fmtSize(n) {
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  upInput.addEventListener('change', function () {
    var files = Array.prototype.slice.call(upInput.files || []);
    upInput.value = ''; // permite volver a elegir el mismo archivo
    files.forEach(function (f) {
      var li = el('li', 'up-item');
      li.dataset.state = 'wait';
      var row = el('div', 'up-row');
      row.appendChild(el('span', 'up-name', f.name));
      row.appendChild(el('span', 'up-size', fmtSize(f.size)));
      var bar = el('div', 'up-bar');
      var fill = el('i');
      bar.appendChild(fill);
      li.appendChild(row);
      li.appendChild(bar);
      li.appendChild(el('div', 'up-msg', 'En cola'));
      upList.appendChild(li);
      upQueue.push({ file: f, el: li });
    });
    pumpUploads();
  });

  function setItem(item, state, pct, msg) {
    item.el.dataset.state = state;
    if (pct != null) item.el.querySelector('.up-bar i').style.width = pct + '%';
    if (msg != null) item.el.querySelector('.up-msg').textContent = msg;
  }

  function setAddLabel(pct) {
    addLabel.textContent = pct == null ? '+ JUEGO' : '↑ ' + pct + '%';
  }

  function pumpUploads() {
    if (upBusy) return;
    var item = upQueue.shift();
    if (!item) { setAddLabel(null); return; }
    var f = item.file;
    var ext = (/\.([^.]+)$/.exec(f.name) || [])[1];
    ext = ext ? ext.toLowerCase() : '';
    if (exts.indexOf(ext) < 0) { setItem(item, 'err', 100, 'Formato no soportado'); pumpUploads(); return; }
    if (f.size > MAX_UPLOAD) { setItem(item, 'err', 100, 'El archivo supera 64 MB'); pumpUploads(); return; }

    upBusy = true;
    setItem(item, 'up', 0, 'Subiendo 0%');
    setAddLabel(0);
    item.el.scrollIntoView({ block: 'nearest' });
    var done = function (state, msg) {
      upBusy = false;
      setItem(item, state, 100, msg);
      if (state === 'ok') { buzz(30); gamesStale = true; }
      pumpUploads();
      // al terminar la cola, si "Mis juegos" está a la vista, se refresca
      if (!upBusy && gamesStale && uploadOpen && curTab === 'games') loadGames();
    };
    var x = new XMLHttpRequest();
    x.open('POST', '/api/upload?name=' + encodeURIComponent(f.name));
    x.setRequestHeader('Content-Type', 'application/octet-stream');
    x.upload.onprogress = function (e) {
      if (!e.lengthComputable) return;
      var pct = Math.min(99, Math.floor(e.loaded * 100 / e.total));
      setItem(item, 'up', pct, 'Subiendo ' + pct + '%');
      setAddLabel(pct);
    };
    x.onload = function () {
      var r = null;
      try { r = JSON.parse(x.responseText); } catch (_) { /* respuesta no JSON */ }
      if (x.status === 200 && r && r.ok) {
        done('ok', '¡LISTO! ' + (r.title || f.name) + (r.system ? ' (' + r.system + ')' : '') + ' ya está en la TV');
      } else {
        done('err', (r && r.error) || (x.status === 413 ? 'El archivo supera 64 MB' : 'Error del servidor (' + x.status + ')'));
      }
    };
    x.onerror = function () { done('err', 'No se pudo conectar con la TV'); };
    x.onabort = function () { done('err', 'Subida cancelada'); };
    x.send(f);
  }

  // ---------- pestañas de la ventana: AGREGAR | MIS JUEGOS ----------
  var tabs = Array.prototype.slice.call(upEl.querySelectorAll('[role="tab"]'));
  var curTab = 'add';
  function showTab(name, focus) {
    curTab = name;
    tabs.forEach(function (t) {
      var on = t.dataset.tab === name;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      $(t.getAttribute('aria-controls')).hidden = !on;
      if (on && focus) t.focus();
    });
    if (name === 'games') loadGames();
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { if (curTab !== t.dataset.tab) { buzz(8); showTab(t.dataset.tab); } });
    t.addEventListener('keydown', function (e) {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
      e.preventDefault();
      var n = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
      showTab(n.dataset.tab, true);
    });
  });

  // ---------- mis juegos (GET /api/games, POST /api/delete, ver docs/protocol.md) ----------
  var gamesList = $('games-list'), gamesInfo = $('games-info'), gamesCount = $('games-count');
  var gamesStale = true;
  var gamesReq = null;
  var games = [];     // última lista recibida (sin los borrados)
  var SYS_COLORS = {  // mismos acentos que la biblioteca de la TV (GameSystem.kt)
    NES: '#e4404a', SNES: '#8c6be8', GB: '#7f9c0c', GBC: '#2fb8c4', GBA: '#5b5bd6',
    MD: '#3a7bf2', SMS: '#e8524a', GG: '#3fae5d'
  };
  var TRASH = '<svg class="ico" viewBox="0 0 7 7" aria-hidden="true"><path d="M2 0h3v1H2zM0 1h7v1H0zM1 2h5v1H1zM1 3h1v3H1zM3 3h1v3H3zM5 3h1v3H5zM1 6h5v1H1z"/></svg>';

  function apiError(x, r, fallback) {
    if (r && r.error) return String(r.error);
    if (x.status === 0) return 'No se pudo conectar con la TV';
    return fallback + ' (' + x.status + ')';
  }

  function gamesMsg(cls, title, text, retry) {
    gamesList.textContent = '';
    var li = el('li', 'games-msg ' + cls);
    li.appendChild(el('b', null, title));
    if (text) li.appendChild(document.createTextNode(text));
    if (retry) {
      li.appendChild(document.createElement('br'));
      var b = el('button', 'mini', 'REINTENTAR');
      b.type = 'button';
      b.addEventListener('click', loadGames);
      li.appendChild(b);
    }
    gamesList.appendChild(li);
  }

  function updateGamesInfo() {
    var n = games.length, bytes = 0;
    games.forEach(function (g) { bytes += g.size || 0; });
    gamesCount.textContent = String(n);
    gamesInfo.textContent = n ? n + (n === 1 ? ' JUEGO · ' : ' JUEGOS · ') + fmtSize(bytes) : 'SIN JUEGOS';
  }

  function loadGames() {
    if (gamesReq) gamesReq.abort();
    gamesStale = false;
    if (!gamesList.querySelector('.game')) gamesMsg('loading', 'CARGANDO', '');
    gamesInfo.textContent = 'Cargando…';
    var x = new XMLHttpRequest();
    gamesReq = x;
    x.open('GET', '/api/games');
    x.timeout = 6000;
    x.onload = function () {
      if (gamesReq !== x) return;
      gamesReq = null;
      var r = null;
      try { r = JSON.parse(x.responseText); } catch (_) { /* respuesta no JSON */ }
      if (x.status !== 200 || !r || !Array.isArray(r.games)) {
        gamesStale = true;
        gamesInfo.textContent = '';
        gamesMsg('err', 'ERROR', apiError(x, r, 'No se pudo leer la lista'), true);
        return;
      }
      renderGames(r.games);
    };
    x.onerror = x.ontimeout = function () {
      if (gamesReq !== x) return;
      gamesReq = null;
      gamesStale = true;
      gamesInfo.textContent = '';
      gamesMsg('err', 'ERROR', 'No se pudo conectar con la TV', true);
    };
    x.send();
  }

  function renderGames(list) {
    games = list.filter(function (g) { return g && g.id != null; });
    gamesList.textContent = '';
    updateGamesInfo();
    if (!games.length) {
      gamesMsg('empty', 'NO HAY JUEGOS', 'Toca AGREGAR para subir tu primera ROM desde el celular.');
      return;
    }
    games.forEach(function (g) { gamesList.appendChild(gameRow(g)); });
  }

  function gameRow(g) {
    var sys = String(g.system || '?').toUpperCase();
    var saves = Math.max(0, g.saves | 0);
    var li = el('li', 'game');
    li.dataset.state = 'normal';
    li.style.setProperty('--sc', SYS_COLORS[sys] || '#6c687b');

    var tag = el('span', 'g-tag', sys);
    if (g.systemName) tag.title = g.systemName;
    var main = el('div', 'g-main');
    main.appendChild(el('span', 'g-title', g.title || g.id));
    var meta = el('span', 'g-meta', fmtSize(g.size || 0));
    if (saves > 0) {
      meta.appendChild(document.createTextNode(' · '));
      meta.appendChild(el('span', 'g-saves', saves + (saves === 1 ? ' PARTIDA' : ' PARTIDAS')));
    }
    main.appendChild(meta);
    var ask = el('span', 'g-ask');
    main.appendChild(ask);

    var acts = el('div', 'g-acts');
    var del = el('button', 'g-btn g-del');
    del.type = 'button';
    del.innerHTML = TRASH;
    del.setAttribute('aria-label', 'Borrar ' + (g.title || g.id));
    var yes = el('button', 'g-btn g-yes', 'SÍ');
    yes.type = 'button';
    var no = el('button', 'g-btn g-no', 'NO');
    no.type = 'button';
    acts.appendChild(del);
    acts.appendChild(yes);
    acts.appendChild(no);

    li.appendChild(tag);
    li.appendChild(main);
    li.appendChild(acts);

    function setAsk(state, nodes) {
      li.dataset.state = state;
      ask.textContent = '';
      nodes.forEach(function (n) { ask.appendChild(typeof n === 'string' ? document.createTextNode(n) : n); });
    }

    del.addEventListener('click', function () {
      // una sola confirmación abierta a la vez
      Array.prototype.forEach.call(gamesList.querySelectorAll('.game[data-state="confirm"]'), function (o) {
        if (o !== li && o._cancel) o._cancel();
      });
      buzz(10);
      setAsk('confirm', saves > 0
        ? ['¿BORRAR? ', el('span', 'g-warn', 'También se borran ' + saves + (saves === 1 ? ' partida guardada' : ' partidas guardadas'))]
        : ['¿BORRAR ESTE JUEGO?']);
      no.focus();
    });
    li._cancel = function () { setAsk('normal', []); };
    no.addEventListener('click', function () { li._cancel(); del.focus(); });
    yes.addEventListener('click', function () {
      setAsk('busy', ['BORRANDO']);
      yes.disabled = no.disabled = true;
      var x = new XMLHttpRequest();
      x.open('POST', '/api/delete?id=' + encodeURIComponent(g.id));
      x.timeout = 8000;
      var fail = function (msg) {
        yes.disabled = no.disabled = false;
        setAsk('err', [msg]);
        buzz(60);
      };
      x.onload = function () {
        var r = null;
        try { r = JSON.parse(x.responseText); } catch (_) { /* respuesta no JSON */ }
        if (x.status === 200 && r && r.ok) {
          buzz(30);
          setAsk('gone', ['¡BORRADO!']);
          games = games.filter(function (o) { return o.id !== g.id; });
          updateGamesInfo();
          setTimeout(function () {
            li.classList.add('out');
            setTimeout(function () {
              li.remove();
              if (!games.length && !gamesList.querySelector('.game')) renderGames([]);
            }, 340);
          }, 700);
        } else {
          fail(apiError(x, r, 'No se pudo borrar'));
        }
      };
      x.onerror = function () { fail('No se pudo conectar con la TV'); };
      x.ontimeout = function () { fail('La TV no respondió'); };
      x.send();
    });
    return li;
  }
  $('games-reload').addEventListener('click', function () { buzz(8); loadGames(); });

  // ---------- red ----------
  var ws = null;
  var isFull = false;
  var backoff = 500;
  var reconnectTimer = 0, countdownTimer = 0;
  var deviceName = guessName();
  // Id estable por celular: al reconectar, la TV le devuelve el mismo número de jugador.
  var clientId = (function () {
    try {
      var v = localStorage.getItem('retrotv-id');
      if (!v) {
        v = Math.random().toString(36).slice(2, 12);
        localStorage.setItem('retrotv-id', v);
      }
      return v;
    } catch (_) {
      return Math.random().toString(36).slice(2, 12);
    }
  })();

  function guessName() {
    var m = /Android[^;)]*;\s*([^;)]+?)(?:\s+Build\/[^;)]*)?[;)]/.exec(navigator.userAgent);
    var n = m && m[1].trim();
    if (!n || n === 'K' || /^wv$/i.test(n)) n = /Android/.test(navigator.userAgent) ? 'Android' : 'Navegador';
    return n.slice(0, 32);
  }

  function send(obj) {
    if (ws && ws.readyState === 1) {
      try { ws.send(JSON.stringify(obj)); } catch (_) { /* se reconecta en onclose */ }
    }
  }
  function sendState() { send({ t: 's', b: mask }); }

  function setConn(state, text) {
    connEl.dataset.state = state;
    connText.textContent = text;
  }
  function setPlayer(n) {
    if (n) {
      playerEl.hidden = false;
      playerEl.textContent = 'J' + n;
      document.documentElement.style.setProperty('--pc', PLAYER_COLORS[n] || '#8a92a6');
    } else {
      playerEl.hidden = true;
      document.documentElement.style.removeProperty('--pc');
    }
  }
  function setPing(ms) {
    pingEl.className = 'ping';
    if (ms == null) { pingEl.textContent = '— ms'; return; }
    pingEl.textContent = Math.round(ms) + ' ms';
    pingEl.classList.add(ms < 40 ? 'good' : ms < 100 ? 'mid' : 'bad');
  }

  function connectNow() {
    clearTimeout(reconnectTimer);
    clearInterval(countdownTimer);
    backoff = 500;
    isFull = false;
    connect();
  }

  function connect() {
    if (ws && ws.readyState <= 1) return;
    setConn('connecting', 'Conectando…');
    var sock;
    try {
      sock = new WebSocket('ws://' + location.host + '/ws');
    } catch (_) {
      scheduleReconnect();
      return;
    }
    ws = sock;
    sock.onopen = function () {
      if (ws !== sock) return;
      send({ t: 'hello', name: deviceName, id: clientId });
      sendState();
      sendPing();
    };
    sock.onmessage = function (ev) {
      if (ws !== sock) return;
      var msg;
      try { msg = JSON.parse(ev.data); } catch (_) { return; }
      switch (msg.t) {
        case 'welcome':
          backoff = 500;
          setPlayer(msg.player);
          setConn('ok', 'Conectado');
          break;
        case 'full':
          isFull = true;
          setPlayer(0);
          setConn('full', 'Partida llena');
          fullEl.hidden = false;
          try { sock.close(); } catch (_) {}
          break;
        case 'pong':
          if (typeof msg.ts === 'number') setPing(performance.now() - msg.ts);
          break;
        case 'rumble':
          buzz(Math.max(0, Math.min(1000, msg.ms | 0)));
          break;
      }
    };
    sock.onclose = function () {
      if (ws !== sock) return;
      ws = null;
      setPlayer(0);
      setPing(null);
      if (isFull) { setConn('full', 'Partida llena'); return; }
      scheduleReconnect();
    };
    sock.onerror = function () { /* onclose se encarga */ };
  }

  function scheduleReconnect() {
    var wait = backoff;
    backoff = Math.min(backoff * 2, 8000);
    var until = Date.now() + wait;
    var tick = function () {
      var s = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      setConn('off', s > 0 ? 'Sin conexión · reintento en ' + s + ' s' : 'Reconectando…');
    };
    tick();
    clearInterval(countdownTimer);
    countdownTimer = setInterval(tick, 500);
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function () {
      clearInterval(countdownTimer);
      connect();
    }, wait);
  }

  function sendPing() { send({ t: 'ping', ts: performance.now() }); }

  setInterval(sendState, KEEPALIVE_MS);
  setInterval(sendPing, PING_MS);
  window.addEventListener('online', function () { if (!isFull && (!ws || ws.readyState > 1)) connectNow(); });

  measure();
  connect();
})();
