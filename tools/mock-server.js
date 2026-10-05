#!/usr/bin/env node
/*
 * Servidor de prueba que imita el lado TV del protocolo (docs/protocol.md).
 * Sin dependencias npm: el WebSocket (RFC 6455) está implementado a mano abajo.
 *
 *   node tools/mock-server.js [--port 8080] [--max 4]
 *
 * --max 1 sirve para probar el mensaje {"t":"full"} abriendo dos pestañas.
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const os = require('os');

const args = process.argv.slice(2);
const arg = (name, def) => { const i = args.indexOf(name); return i >= 0 ? Number(args[i + 1]) : def; };
const PORT = arg('--port', 8080);
const MAX_PLAYERS = arg('--max', 4);
const TIMEOUT_MS = 3000;
const WEB_ROOT = path.resolve(__dirname, '..', 'tv-app', 'src', 'main', 'assets', 'web');

const NAMES = ['UP', 'DOWN', 'LEFT', 'RIGHT', 'A', 'B', 'X', 'Y', 'L', 'R', 'START', 'SELECT'];
const decode = (b) => NAMES.filter((_, i) => b & (1 << i)).join('+') || '(nada)';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
  '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
};

const ts = () => new Date().toISOString().slice(11, 23);
const log = (...a) => console.log(ts(), ...a);

// ---------- subida de juegos (POST /api/upload, GET /api/systems) ----------
const UPLOAD_DIR = path.resolve(__dirname, 'mock-uploads');
const MAX_UPLOAD = 64 * 1024 * 1024;
const SYSTEMS = {
  nes: 'NES', sfc: 'SNES', smc: 'SNES', gb: 'GB', gbc: 'GBC', gba: 'GBA',
  md: 'Mega Drive', gen: 'Mega Drive', smd: 'Mega Drive', sms: 'Master System', gg: 'Game Gear', zip: 'ZIP',
};
const EXTENSIONS = Object.keys(SYSTEMS);

const sendJsonRes = (res, status, obj) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
};

function handleUpload(req, res, url) {
  const fail = (status, error) => {
    log(`SUBIDA rechazada (${status}): ${error}`);
    sendJsonRes(res, status, { ok: false, error });
    req.resume(); // drenar el cuerpo para no cortar la conexión a la mitad
  };
  const raw = url.searchParams.get('name') || '';
  const name = path.basename(raw.replace(/\\/g, '/')).replace(/[\x00-\x1f<>:"|?*]/g, '_').trim();
  if (!name) return fail(400, 'Falta el nombre del archivo');
  const ext = path.extname(name).slice(1).toLowerCase();
  if (!SYSTEMS[ext]) return fail(400, 'Formato no soportado');
  const declared = Number(req.headers['content-length'] || 0);
  if (declared > MAX_UPLOAD) return fail(413, 'El archivo supera 64 MB');

  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  const dest = path.join(UPLOAD_DIR, name);
  const tmp = dest + '.part';
  const out = fs.createWriteStream(tmp);
  let size = 0, aborted = false;
  req.on('data', (chunk) => {
    if (aborted) return;
    size += chunk.length;
    if (size > MAX_UPLOAD) {
      aborted = true;
      out.destroy();
      fs.rm(tmp, { force: true }, () => {});
      fail(413, 'El archivo supera 64 MB');
      return;
    }
    if (!out.write(chunk)) { req.pause(); out.once('drain', () => req.resume()); }
  });
  req.on('end', () => {
    if (aborted) return;
    out.end(() => {
      if (size === 0) { fs.rm(tmp, { force: true }, () => {}); return fail(400, 'El archivo está vacío'); }
      fs.rename(tmp, dest, (err) => {
        if (err) return fail(500, 'No se pudo guardar el archivo');
        const title = path.basename(name, path.extname(name)).replace(/[_.]+/g, ' ').replace(/\s+/g, ' ').trim() || name;
        log(`SUBIDA ok: "${name}" ${size} bytes -> ${SYSTEMS[ext]} (${dest})`);
        sendJsonRes(res, 200, { ok: true, title, system: SYSTEMS[ext] });
      });
    });
  });
  req.on('aborted', () => { aborted = true; out.destroy(); fs.rm(tmp, { force: true }, () => {}); log('SUBIDA cortada por el cliente'); });
}

// ---------- juegos instalados (GET /api/games, POST /api/delete) ----------
// En la TV el id es "<SISTEMA>-<archivo>"; acá el sistema sale de la extensión.
const GAME_SYSTEMS = {
  nes: ['NES', 'Nintendo'], sfc: ['SNES', 'Super Nintendo'], smc: ['SNES', 'Super Nintendo'],
  gb: ['GB', 'Game Boy'], gbc: ['GBC', 'Game Boy Color'], gba: ['GBA', 'Game Boy Advance'],
  md: ['MD', 'Mega Drive'], gen: ['MD', 'Mega Drive'], smd: ['MD', 'Mega Drive'],
  sms: ['SMS', 'Master System'], gg: ['GG', 'Game Gear'], zip: ['ZIP', 'ZIP'],
};

function listGames() {
  let names = [];
  try { names = fs.readdirSync(UPLOAD_DIR); } catch (_) { return []; }
  const games = [];
  for (const file of names) {
    const sys = GAME_SYSTEMS[path.extname(file).slice(1).toLowerCase()];
    if (!sys) continue; // .part y otros
    let st;
    try { st = fs.statSync(path.join(UPLOAD_DIR, file)); } catch (_) { continue; }
    if (!st.isFile()) continue;
    const title = path.basename(file, path.extname(file)).replace(/[_.]+/g, ' ').replace(/\s+/g, ' ').trim() || file;
    games.push({ id: `${sys[0]}-${file}`, title, system: sys[0], systemName: sys[1],
      size: st.size, addedAt: Math.round(st.mtimeMs), saves: 0 });
  }
  return games.sort((a, b) => a.title.localeCompare(b.title, 'es', { sensitivity: 'base' }));
}

function handleDelete(req, res, url) {
  req.resume();
  const id = url.searchParams.get('id') || '';
  const m = /^([A-Z]+)-(.+)$/.exec(id);
  const file = m && path.basename(m[2]);
  const game = file && listGames().find((g) => g.id === id);
  if (!game) { log(`BORRAR rechazado: "${id}" no existe`); sendJsonRes(res, 404, { ok: false, error: 'Juego no encontrado' }); return; }
  fs.rm(path.join(UPLOAD_DIR, file), (err) => {
    if (err) { log(`BORRAR falló: ${err.message}`); sendJsonRes(res, 500, { ok: false, error: 'No se pudo borrar el juego' }); return; }
    log(`BORRAR ok: "${file}" (${id})`);
    sendJsonRes(res, 200, { ok: true });
  });
}

// ---------- HTTP ----------
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/systems' && req.method === 'GET') { sendJsonRes(res, 200, { extensions: EXTENSIONS }); return; }
  if (url.pathname === '/api/games' && req.method === 'GET') { sendJsonRes(res, 200, { games: listGames() }); return; }
  if (url.pathname === '/api/delete') {
    if (req.method !== 'POST') { sendJsonRes(res, 405, { ok: false, error: 'Usa POST' }); return; }
    handleDelete(req, res, url);
    return;
  }
  if (url.pathname === '/api/upload') {
    if (req.method !== 'POST') { sendJsonRes(res, 405, { ok: false, error: 'Usa POST' }); return; }
    handleUpload(req, res, url);
    return;
  }
  let p;
  try { p = decodeURIComponent(url.pathname); } catch (_) { res.writeHead(400).end(); return; }
  if (p === '/') p = '/index.html';
  const file = path.normalize(path.join(WEB_ROOT, p));
  if (!file.startsWith(WEB_ROOT)) { res.writeHead(403).end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }).end('404'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  });
});

// ---------- WebSocket mínimo ----------
const slots = new Array(MAX_PLAYERS + 1).fill(null); // índice = número de jugador

function frame(opcode, payload) {
  const len = payload.length;
  let head;
  if (len < 126) head = Buffer.from([0x80 | opcode, len]);
  else if (len < 65536) { head = Buffer.alloc(4); head[0] = 0x80 | opcode; head[1] = 126; head.writeUInt16BE(len, 2); }
  else { head = Buffer.alloc(10); head[0] = 0x80 | opcode; head[1] = 127; head.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([head, payload]);
}

server.on('upgrade', (req, socket) => {
  if (new URL(req.url, 'http://x').pathname !== '/ws' || !req.headers['sec-websocket-key']) {
    socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
    return;
  }
  const accept = crypto.createHash('sha1')
    .update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
  socket.setNoDelay(true);

  const peer = `${req.socket.remoteAddress}:${req.socket.remotePort}`;
  const sendJson = (o) => { if (!socket.destroyed) socket.write(frame(0x1, Buffer.from(JSON.stringify(o)))); };
  let closed = false;
  const close = (why) => {
    if (closed) return;
    closed = true;
    clearTimeout(timer);
    if (player) {
      if (state) log(`J${player} soltar todo (b=0)`);
      slots[player] = null;
      log(`J${player} desconectado (${why})`);
    }
    try { socket.write(frame(0x8, Buffer.alloc(0))); } catch (_) {}
    socket.end();
  };

  const player = slots.findIndex((s, i) => i > 0 && s === null);
  if (player < 1) {
    log(`${peer} rechazado: partida llena`);
    sendJson({ t: 'full' });
    socket.write(frame(0x8, Buffer.alloc(0)));
    socket.end();
    return;
  }
  slots[player] = socket;
  let name = '?';
  let state = 0;
  let timer = setTimeout(() => close('timeout'), TIMEOUT_MS);
  sendJson({ t: 'welcome', player });
  log(`J${player} conectado desde ${peer}`);

  let buf = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      const opcode = buf[0] & 0x0f;
      const masked = buf[1] & 0x80;
      let len = buf[1] & 0x7f, off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      const maskOff = off; if (masked) off += 4;
      if (buf.length < off + len) return;
      const data = Buffer.from(buf.subarray(off, off + len));
      if (masked) for (let i = 0; i < len; i++) data[i] ^= buf[maskOff + (i & 3)];
      buf = buf.subarray(off + len);

      if (opcode === 0x8) { close('cerró el cliente'); return; }
      if (opcode === 0x9) { socket.write(frame(0xA, data)); continue; }
      if (opcode !== 0x1) continue; // ignoramos binario/fragmentos

      clearTimeout(timer);
      timer = setTimeout(() => close('timeout 3 s'), TIMEOUT_MS);
      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch (_) { log(`J${player} JSON inválido`); continue; }
      switch (msg.t) {
        case 'hello': name = String(msg.name || '?'); log(`J${player} hello: "${name}"`); break;
        case 's':
          if ((msg.b | 0) !== state) { state = msg.b | 0; log(`J${player} b=${state} ${decode(state)}`); }
          break;
        case 'ping': sendJson({ t: 'pong', ts: msg.ts }); break;
        case 'menu': log(`J${player} MENÚ`); break;
        default: log(`J${player} mensaje desconocido`, msg);
      }
    }
  });
  socket.on('close', () => close('socket cerrado'));
  socket.on('error', () => close('error'));
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((n) => n && n.family === 'IPv4' && !n.internal).map((n) => n.address);
  log(`Mock TV escuchando en :${PORT} (máx ${MAX_PLAYERS} jugadores) sirviendo ${WEB_ROOT}`);
  for (const ip of ['localhost', ...ips]) log(`  http://${ip}:${PORT}/`);
});
