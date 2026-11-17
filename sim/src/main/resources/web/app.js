/* FTCSim browser UI */
(() => {
'use strict';
const $ = (id) => document.getElementById(id);
const state = {
  ws: null, hello: null, opmodes: [], build: null, last: null, field: null, map: null, game: null, autos: null,
  trail: [], trailEpoch: -1, viewRot: 0, telemetryTab: 'ds', rightTab: 'hardware',
  logLevel: 2, logFilter: '', logEntries: [], configurables: [], graphs: {}, drag: null, orbit: null,
  gamepads: { 1: null, 2: null }, physical: { 1: null, 2: null }, keyboardPad: 1,
  view: '2d', cam: { yaw: 0, pitch: 0.62, dist: 205, tx: 0, ty: 0, tz: 0, yawOff: 0, preset: 'audience' },
  seq: null, summaryOff: false, dbg: { intake: false, launch: false },
};

// ------------------------------------------------------------------ websocket
function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws`);
  state.ws = ws;
  ws.onopen = () => { $('connPill').textContent = 'connected'; $('connPill').className = 'pill ok'; send({ type: 'hello' }); };
  ws.onclose = () => { $('connPill').textContent = 'offline'; $('connPill').className = 'pill bad'; setTimeout(connect, 1000); };
  ws.onmessage = (ev) => { try { handle(JSON.parse(ev.data)); } catch (e) { console.error(e); } };
}
function send(obj) { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify(obj)); }

function handle(m) {
  switch (m.type) {
    case 'hello': state.hello = m; setField(m.field); $('repoMeta').textContent = `${m.repoName || 'no repo'} · SDK ${m.sdkVersion} · config ${m.configName}`; $('configFile').textContent = m.configFile; state.trail = []; updateAutos(m, true); renderFolder(); resetGameUi(); break;
    case 'field': setField(m.field); break;
    case 'opmodes': state.opmodes = m.list; state.build = m.build; updateAutos(m, false); renderOpModes(); renderBuild(); renderFolder(); break;
    case 'buildStatus': state.build = m.build; renderBuild(); break;
    case 'state': state.last = m; onState(m); break;
    case 'log': for (const e of m.entries) state.logEntries.push(e); if (state.logEntries.length > 5000) state.logEntries.splice(0, state.logEntries.length - 5000); renderLog(m.entries); break;
    case 'configurables': state.configurables = m.classes; renderConfigurables(); break;
    case 'graphs': state.graphs = m.series; if (state.rightTab === 'graph') renderGraph(); break;
    case 'config': $('configText').value = m.json; $('configFile').textContent = m.file; break;
    case 'toast': toast(m.level, m.text); break;
  }
}

function toast(level, text) {
  const d = document.createElement('div'); d.className = 'toast ' + level; d.textContent = text; $('toasts').appendChild(d);
  setTimeout(() => d.remove(), 6000);
}

// ------------------------------------------------------------------ driver station
function renderOpModes() {
  const sel = $('opmodeSelect'); const prev = sel.value; sel.innerHTML = '';
  const groups = { TELEOP: {}, AUTONOMOUS: {} };
  for (const o of state.opmodes) { if (o.disabled) continue; (groups[o.flavor][o.group || ''] ||= []).push(o); }
  for (const flavor of ['TELEOP', 'AUTONOMOUS']) {
    for (const g of Object.keys(groups[flavor]).sort()) {
      const og = document.createElement('optgroup'); og.label = (flavor === 'TELEOP' ? 'TeleOp' : 'Autonomous') + (g ? ' · ' + g : '');
      for (const o of groups[flavor][g].sort((a, b) => a.name.localeCompare(b.name))) { const opt = document.createElement('option'); opt.value = o.flavor + '|' + o.name; opt.textContent = o.name + (o.linear ? '' : ' ⟳'); og.appendChild(opt); }
      sel.appendChild(og);
    }
  }
  if (!sel.options.length) { const opt = document.createElement('option'); opt.textContent = state.build && state.build.success === false ? '(build failed — see Log)' : '(no OpModes found yet)'; sel.appendChild(opt); }
  if (prev) sel.value = prev;
  renderMatchSelects();
}
function renderBuild() {
  const b = state.build || {}; const pill = $('buildPill');
  if (b.building) { pill.textContent = 'compiling…'; pill.className = 'pill busy'; }
  else if (b.success) { pill.textContent = 'build OK' + (b.sourcesChanged ? ' · sources changed' : ''); pill.className = 'pill ' + (b.sourcesChanged ? 'busy' : 'ok'); }
  else { pill.textContent = 'build FAILED'; pill.className = 'pill bad'; }
  pill.title = b.summary || '';
  const diag = $('buildDiag'); let txt = (b.summary || '') + '\n';
  for (const d of (b.diagnostics || [])) txt += `${d.kind} ${d.file}:${d.line}: ${d.message}\n`;
  for (const e of (b.loadErrors || [])) txt += `LOAD ${e}\n`;
  diag.textContent = txt;
}
$('reloadBtn').onclick = () => send({ type: 'code.reload' });
$('initBtn').onclick = () => { const v = $('opmodeSelect').value; if (!v.includes('|')) return; const [flavor, name] = v.split('|'); send({ type: 'opmode.init', name, flavor }); };
$('startBtn').onclick = () => send({ type: 'opmode.start' });
$('stopBtn').onclick = () => send({ type: 'opmode.stop' });
$('restartBtn').onclick = () => send({ type: 'robot.restart' });
$('timerChk').onchange = (e) => send({ type: 'sim.timer', enabled: e.target.checked });
$('pauseBtn').onclick = () => { const paused = !(state.last && state.last.sim.paused); send({ type: 'sim.pause', paused }); };
$('stepBtn').onclick = () => { if (!(state.last && state.last.sim.paused)) send({ type: 'sim.pause', paused: true }); send({ type: 'sim.step', ms: parseFloat($('stepSize').value) }); };
$('opmodeSelect').ondblclick = () => $('initBtn').click();

function onState(m) {
  const op = m.opmode;
  const pill = $('statePill'); pill.textContent = op.state; pill.className = 'state-pill ' + op.state;
  $('runtime').textContent = op.runtime.toFixed(1) + ' s';
  $('initBtn').disabled = op.state !== 'IDLE'; $('startBtn').disabled = op.state !== 'INIT'; $('stopBtn').disabled = !(op.state === 'INIT' || op.state === 'RUNNING');
  $('restartBtn').classList.toggle('hidden', op.state !== 'ESTOP');
  $('errorBox').classList.toggle('hidden', !op.error); $('errorBox').textContent = op.error || '';
  const warn = [op.warning, op.globalError && op.state !== 'ESTOP' ? op.globalError : ''].filter(Boolean).join(' · ');
  $('warnBox').classList.toggle('hidden', !warn); $('warnBox').textContent = warn;
  $('timerRemaining').textContent = op.timerRemaining != null ? Math.ceil(op.timerRemaining) + 's' : '';
  const src = state.telemetryTab === 'ds' ? m.telemetry : state.telemetryTab === 'panels' ? m.panels : m.dashboard;
  const tel = $('telemetry'); const txt = (src.lines || []).join('\n'); if (tel.textContent !== txt) tel.textContent = txt;
  $('batteryPill').textContent = m.battery.volts.toFixed(2) + ' V · ' + m.battery.current.toFixed(1) + ' A';
  $('simPill').textContent = (m.sim.paused ? (m.sim.stepping ? 'stepping' : 'paused') : Math.round(m.sim.stepRate) + ' Hz');
  $('pauseBtn').textContent = m.sim.paused ? '▶ Resume' : '⏸ Pause';
  const io = m.sim.io || {};
  $('ioPill').textContent = io.enabled === false ? 'I/O instant'
    : `I/O ${(io.totalMs || 0).toFixed(0)} ms · ${io.bulkReads || 0} bulk · ${io.writes || 0} w · ${io.i2c || 0} i2c`;
  $('ioPill').classList.toggle('muted', io.enabled === false);
  const r = m.robot;
  if (r.epoch !== state.trailEpoch) { state.trail = []; state.trailEpoch = r.epoch; }
  const lastT = state.trail[state.trail.length - 1];
  if (!lastT || Math.hypot(lastT[0] - r.x, lastT[1] - r.y) > 0.25) { state.trail.push([r.x, r.y]); if (state.trail.length > 1500) state.trail.shift(); }
  const pd = toPedro(r.x, r.y, r.headingDeg);
  $('poseFtc').textContent = `FTC x ${r.x.toFixed(1)}  y ${r.y.toFixed(1)}  θ ${r.headingDeg.toFixed(0)}°`;
  $('posePedro').textContent = `Pedro x ${pd.x.toFixed(1)}  y ${pd.y.toFixed(1)}  θ ${pd.h.toFixed(0)}°`;
  if (state.rightTab === 'hardware') renderDevices(m.devices);
  if ($('obeliskSelect')) $('obeliskSelect').value = String(m.sim.obelisk);
  onGame(m.game || null);
}
document.querySelectorAll('#telemetryTabs button').forEach(b => b.onclick = () => { document.querySelectorAll('#telemetryTabs button').forEach(x => x.classList.remove('active')); b.classList.add('active'); state.telemetryTab = b.dataset.tab; });
document.querySelectorAll('#rightTabs button').forEach(b => b.onclick = () => {
  document.querySelectorAll('#rightTabs button').forEach(x => x.classList.remove('active')); b.classList.add('active'); state.rightTab = b.dataset.tab;
  document.querySelectorAll('#right .tab').forEach(t => t.classList.toggle('active', t.id === 'tab-' + state.rightTab));
  if (state.rightTab === 'robot' && !$('configText').value) send({ type: 'config.get' });
  if (state.rightTab === 'graph') renderGraph();
});

// ------------------------------------------------------------------ coordinates
function toPedro(x, y, h) { return { x: y + 72, y: 72 - x, h: norm(h - 90) }; }
function fromPedro(px, py, ph) { return { x: 72 - py, y: px - 72, h: norm(ph + 90) }; }
function norm(d) { d = ((d + 180) % 360 + 360) % 360 - 180; return d; }

// ------------------------------------------------------------------ field
const canvas = $('field'); const ctx = canvas.getContext('2d');
const RED = '#d93b2b', BLUE = '#2b5fd9', POLLEN = '#e8c547';
const geo = { H: 72, T: 24, n: 6 };
const view = { w: 800, h: 800, dpr: 1, scale: 800 / 150 };
/** Field scale comes from the server's field description; fields without halfSizeIn/tileIn behave like the classic 144 in field. */
function setField(f) {
  state.field = f || null; state.map = (f && f.map) || null;
  geo.H = (f && (f.halfSizeIn || (f.sizeIn && f.sizeIn / 2))) || 72;
  geo.T = (f && f.tileIn) || 24;
  geo.n = Math.max(1, Math.round(2 * geo.H / geo.T));
  setCamPreset(state.cam.preset);
  renderFieldOptions(f);
}
function fieldToScreen(x, y) {
  // FTC frame: +x right, +y up (rotated by the view setting)
  const rot = state.viewRot * Math.PI / 180;
  const c = Math.cos(rot), s = Math.sin(rot);
  const rx = c * x - s * y, ry = s * x + c * y;
  return [view.w / 2 + rx * view.scale, view.h / 2 - ry * view.scale];
}
function screenToField(sx, sy) {
  const rx = (sx - view.w / 2) / view.scale, ry = -(sy - view.h / 2) / view.scale;
  const rot = -state.viewRot * Math.PI / 180; const c = Math.cos(rot), s = Math.sin(rot);
  return [c * rx - s * ry, s * rx + c * ry];
}

const mixCache = new Map();
/** k < 1 darkens, k > 1 lightens towards white. */
function mixHex(hex, k) {
  k = Math.round(k * 20) / 20; const key = hex + k; let v = mixCache.get(key);
  if (!v) { const [r, g, b] = rgbOf(hex); const m = (c) => Math.max(0, Math.min(255, Math.round(k <= 1 ? c * k : c + (255 - c) * (k - 1)))); v = `rgb(${m(r)},${m(g)},${m(b)})`; mixCache.set(key, v); }
  return v;
}
const rgbCache = new Map();
function rgbOf(hex) {
  let v = rgbCache.get(hex);
  if (!v) {
    const m = /^#([0-9a-f]{3}|[0-9a-f]{6})/i.exec(hex || ''); let h = m ? m[1] : '808080';
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    v = [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]; rgbCache.set(hex, v);
  }
  return v;
}
const allianceColor = (a) => a === 'RED' ? RED : a === 'BLUE' ? BLUE : '#8b94a7';
const elemColor = (e) => e.t === 'P' ? POLLEN : e.c === 'R' ? RED : BLUE;
const elemRadius = (e) => e.t === 'P' ? 1.4 : 1.81;

function poly(pts, fill, stroke, lw) { ctx.beginPath(); pts.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.closePath(); if (fill) { ctx.fillStyle = fill; ctx.fill(); } if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = lw || 1; ctx.stroke(); } }
function line(x1, y1, x2, y2) { const a = fieldToScreen(x1, y1), b = fieldToScreen(x2, y2); ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke(); }
function label(x, y, text, color) { const [sx, sy] = fieldToScreen(x, y); ctx.fillStyle = color; ctx.font = 'bold 12px system-ui'; ctx.textAlign = 'center'; ctx.fillText(text, sx, sy + 4); ctx.textAlign = 'left'; }
const rectPts = (b) => [[b.minX, b.minY], [b.maxX, b.minY], [b.maxX, b.maxY], [b.minX, b.maxY]];
function drawShape(sh) {
  const c = sh.coords, lw = sh.width || 1;
  switch (sh.type) {
    case 'rect': poly([[c[0], c[1]], [c[2], c[1]], [c[2], c[3]], [c[0], c[3]]], sh.fill || null, sh.stroke || null, lw);
      if (sh.label && $('labelsChk').checked) label((c[0] + c[2]) / 2, (c[1] + c[3]) / 2, sh.label, '#f5f5f5'); break;
    case 'circle': { const [sx, sy] = fieldToScreen(c[0], c[1]); ctx.beginPath(); ctx.arc(sx, sy, c[2] * view.scale, 0, Math.PI * 2); if (sh.fill) { ctx.fillStyle = sh.fill; ctx.fill(); } if (sh.stroke) { ctx.strokeStyle = sh.stroke; ctx.lineWidth = lw; ctx.stroke(); }
      if (sh.label && $('labelsChk').checked) label(c[0], c[1] - c[2] - 3, sh.label, '#f5f5f5'); break; }
    case 'line': ctx.strokeStyle = sh.stroke || '#ffffff'; ctx.lineWidth = lw; line(c[0], c[1], c[2], c[3]); break;
    case 'polygon': { const pts = []; for (let i = 0; i + 1 < c.length; i += 2) pts.push([c[i], c[i + 1]]); poly(pts, sh.fill || null, sh.stroke || null, lw); break; }
    case 'label': if ($('labelsChk').checked) label(c[0], c[1], sh.label || '', sh.stroke || '#f5f5f5'); break;
  }
}

function drawField() {
  ctx.setTransform(view.dpr, 0, 0, view.dpr, 0, 0);
  ctx.clearRect(0, 0, view.w, view.h);
  if (state.view === '3d') draw3D(); else draw2D();
}

function draw2D() {
  const H = geo.H, T = geo.T, n = geo.n, map = state.map, g = state.game, f = state.field;
  ctx.save();
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    const x0 = -H + i * T, y0 = -H + j * T; const q = [[x0, y0], [x0 + T, y0], [x0 + T, y0 + T], [x0, y0 + T]];
    poly(q, (i + j) % 2 ? '#1a1f28' : '#161b23', null);
    if (map && (j === 0 || j === n - 1)) poly(q, j === 0 ? RED + '16' : BLUE + '16', null);
  }
  ctx.strokeStyle = '#2a3140'; ctx.lineWidth = 1;
  for (let i = 0; i <= n; i++) { line(-H + i * T, -H, -H + i * T, H); line(-H, -H + i * T, H, -H + i * T); }
  const wh = (map && map.wallInnerHalfIn) || H;
  poly([[-wh, -wh], [wh, -wh], [wh, wh], [-wh, wh]], null, '#6c7a95', 3);
  if (f) {
    const showLabels = $('labelsChk').checked;
    for (const z of (f.zones || [])) {
      poly(rectPts(z), z.color + '44', z.color + 'aa', 1);
      if (map && showLabels && z.name) { const [sx, sy] = fieldToScreen((z.minX + z.maxX) / 2, (z.minY + z.maxY) / 2); ctx.fillStyle = '#c9d1e3aa'; ctx.font = '10px system-ui'; ctx.textAlign = 'center'; ctx.fillText(z.name, sx, sy + 3); ctx.textAlign = 'left'; }
      if (g && /garden/i.test(z.name || '')) {
        let cnt = 0; for (const e of g.elements) if (e.s === 'GARDEN' && e.x >= z.minX && e.x <= z.maxX && e.y >= z.minY && e.y <= z.maxY) cnt++;
        if (cnt) { const [sx, sy] = fieldToScreen((z.minX + z.maxX) / 2, (z.minY + z.maxY) / 2); ctx.fillStyle = '#ffffffdd'; ctx.font = 'bold 14px system-ui'; ctx.textAlign = 'center'; ctx.fillText('×' + cnt, sx, sy - 8); ctx.textAlign = 'left'; }
      }
    }
    for (const o of (f.obstacles || [])) poly(rectPts(o), o.color + '55', o.color, 2);
    for (const sh of (f.shapes || [])) drawShape(sh);
    const ob = state.last ? state.last.sim.obelisk : 21;
    for (const t of (f.tags || [])) {
      if (t.id >= 21 && t.id <= 23 && t.id !== ob && f.name === 'DECODE') continue;
      const a = t.yawDeg * Math.PI / 180; const hx = Math.cos(a + Math.PI / 2) * t.size / 2, hy = Math.sin(a + Math.PI / 2) * t.size / 2;
      ctx.lineWidth = 4; ctx.strokeStyle = '#f5f5f5'; line(t.x - hx, t.y - hy, t.x + hx, t.y + hy);
      ctx.lineWidth = 1.5; ctx.strokeStyle = '#f5f5f5'; line(t.x, t.y, t.x + Math.cos(a) * 5, t.y + Math.sin(a) * 5);
      if (showLabels) label(t.x + Math.cos(a) * 9, t.y + Math.sin(a) * 9, String(t.id), '#f5f5f5');
    }
  }
  if (map) { drawHives2D(); drawFlowers2D(); }
  const els = g ? g.elements : null;
  if (els) { const ground = els.filter(e => e.s === 'FIELD' || e.s === 'GARDEN' || e.s === 'FLOWER' || e.s === 'CELL').sort((a, b) => a.z - b.z); for (const e of ground) drawElem2D(e); }
  if ($('labelsChk').checked) { ctx.fillStyle = '#5c6a85'; const [ax, ay] = fieldToScreen(H - 6, -H + 3); ctx.font = '11px system-ui'; ctx.fillText('+X →', ax - 12, ay); const [bx, by] = fieldToScreen(-H + 3, H - 6); ctx.fillText('+Y ↑', bx, by); }
  if (state.last) for (const d of (state.last.drawings || [])) drawOverlay(d);
  if ($('trailChk').checked && state.trail.length > 1) {
    ctx.strokeStyle = '#4f8cff88'; ctx.lineWidth = 2; ctx.beginPath();
    state.trail.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.stroke();
  }
  if (state.last && state.hello) drawRobot2D(g);
  if (els) { for (const e of els) if (e.s === 'HELD') drawElem2D(e); for (const e of els) if (e.s === 'FLIGHT') drawElem2D(e); }
  ctx.restore();
}

function drawRobot2D(g) {
  const r = state.last.robot; const L = state.hello.robot.lengthIn, Wd = state.hello.robot.widthIn;
  const h = r.headingDeg * Math.PI / 180; const c = Math.cos(h), s = Math.sin(h);
  const corner = (fx, fy) => [r.x + c * fx - s * fy, r.y + s * fx + c * fy];
  poly([corner(L / 2, Wd / 2), corner(L / 2, -Wd / 2), corner(-L / 2, -Wd / 2), corner(-L / 2, Wd / 2)], '#4f8cff66', '#8fb8ff', 2);
  ctx.strokeStyle = '#ffd166'; ctx.lineWidth = 3; const tip = corner(L / 2 - 1, 0); line(r.x, r.y, tip[0], tip[1]);
  const w = r.wheels || [];
  const wp = [[L / 2 - 2.5, Wd / 2 - 1.2], [L / 2 - 2.5, -Wd / 2 + 1.2], [-L / 2 + 2.5, Wd / 2 - 1.2], [-L / 2 + 2.5, -Wd / 2 + 1.2]];
  w.forEach((p, i) => { if (!wp[i]) return; const [wx, wy] = wp[i]; const a = corner(wx - 2, wy), b = corner(wx + 2 * Math.max(-1, Math.min(1, p)), wy); ctx.strokeStyle = p >= 0 ? '#2ecc71' : '#e74c3c'; ctx.lineWidth = 4; line(a[0], a[1], b[0], b[1]); });
  const gr = g && g.robot; if (!gr) return;
  const a = corner(L / 2 + 1.3, -Wd / 2 + 1.5), b = corner(L / 2 + 1.3, Wd / 2 - 1.5);
  ctx.lineCap = 'round'; ctx.lineWidth = 5; ctx.strokeStyle = gr.intake ? '#2ecc71' : '#3a4560'; line(a[0], a[1], b[0], b[1]); ctx.lineCap = 'butt';
  const sp = Math.max(0, Math.min(1, gr.launcherSpeed || 0)); const [cx, cy] = fieldToScreen(r.x, r.y);
  ctx.strokeStyle = '#3a4560'; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(cx, cy, 3.4 * view.scale, 0, Math.PI * 2); ctx.stroke();
  if (sp > 0.01) { ctx.strokeStyle = '#ff9f43'; ctx.beginPath(); ctx.arc(cx, cy, 3.4 * view.scale, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * sp); ctx.stroke(); ctx.fillStyle = `rgba(255,159,67,${(0.15 + 0.5 * sp).toFixed(2)})`; ctx.beginPath(); ctx.arc(cx, cy, 2.4 * view.scale, 0, Math.PI * 2); ctx.fill(); }
}

function drawElem2D(e) {
  const small = e.s === 'FLOWER' || e.s === 'CELL'; const z = e.z || 0; const base = elemRadius(e) * (small ? 0.6 : 1);
  const r = base * (1 + Math.min(z, 40) * 0.012) * view.scale; const [sx, sy] = fieldToScreen(e.x, e.y);
  if (z > 1.5 && !small) { const off = z * 0.12 * view.scale; ctx.fillStyle = 'rgba(0,0,0,.4)'; ctx.beginPath(); ctx.arc(sx + off, sy + off, base * view.scale * 0.9, 0, Math.PI * 2); ctx.fill(); }
  ctx.fillStyle = mixHex(elemColor(e), 0.9 + Math.min(z, 40) * 0.012); ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2); ctx.fill();
  ctx.strokeStyle = 'rgba(0,0,0,.5)'; ctx.lineWidth = 1; ctx.stroke();
  if (r > 3) { ctx.fillStyle = 'rgba(255,255,255,.35)'; ctx.beginPath(); ctx.arc(sx - r * 0.3, sy - r * 0.3, r * 0.3, 0, Math.PI * 2); ctx.fill(); }
}

// ---- HIVE / FLOWER geometry shared by the 2D and 3D renderers
const gameHive = (alliance) => { const g = state.game; return g && g.hives ? g.hives.find(x => x.alliance === alliance) || null : null; };
/** Rocker rotation relative to the CAD pose, radians; positive raises the +X (audience) end. */
function hiveDelta(h) {
  const gs = gameHive(h.alliance); if (!gs || gs.angle == null) return 0;
  const a0 = h.cells[0] && h.cells[0].up ? h.tiltDeg : -h.tiltDeg;
  return (gs.angle - a0) * Math.PI / 180;
}
function rotXZ(h, d, x, z) {
  const dx = x - h.x, dz = z - h.pivotZ, c = Math.cos(d), s = Math.sin(d);
  return [h.x + dx * c - dz * s, h.pivotZ + dx * s + dz * c];
}
function cellCorners(h, cell, d) {
  const b = cell.shell, out = [];
  for (let k = 0; k < 8; k++) { const [rx, rz] = rotXZ(h, d, k & 1 ? b.maxX : b.minX, k & 4 ? b.maxZ : b.minZ); out.push([rx, k & 2 ? b.maxY : b.minY, rz]); }
  return out;
}

function drawHives2D() {
  const g = state.game;
  for (const h of (state.map.hives || [])) {
    const hc = allianceColor(h.alliance), gs = gameHive(h.alliance), d = hiveDelta(h), fr = h.frame;
    poly(rectPts(fr), hc + '14', hc + 'cc', 2);
    const cells = h.cells.map(c => { const cs = cellCorners(h, c, d); return { c, cs, zm: cs.reduce((a, p) => a + p[2], 0) / 8 }; }).sort((a, b) => a.zm - b.zm);
    for (const e of cells) {
      let x0 = Infinity, x1 = -Infinity; for (const p of e.cs) { x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); }
      const y0 = e.c.shell.minY, y1 = e.c.shell.maxY, sc = 1 + 0.004 * e.zm;
      const X = (x) => h.x + (x - h.x) * sc, Y = (y) => h.y + (y - h.y) * sc;
      const k = 0.55 + e.zm * 0.03;
      poly([[X(x0), Y(y0)], [X(x1), Y(y0)], [X(x1), Y(y1)], [X(x0), Y(y1)]], mixHex(hc, k), mixHex(hc, k + 0.4), 1.5);
      const mx = e.c.mouthX * Math.cos(d) - e.c.mouthZ * Math.sin(d); const len = x1 - x0;
      let ix0 = x0 + len * 0.2, ix1 = x1 - len * 0.2;
      if (Math.abs(mx) > 0.25) { if (mx > 0) ix1 = x1 - len * 0.04; else ix0 = x0 + len * 0.04; }
      const iy0 = y0 + (y1 - y0) * 0.18, iy1 = y1 - (y1 - y0) * 0.18;
      poly([[X(ix0), Y(iy0)], [X(ix1), Y(iy0)], [X(ix1), Y(iy1)], [X(ix0), Y(iy1)]], '#0b0e13b0', null);
      if (g) {
        let cnt = 0; for (const el of g.elements) if (el.s === 'CELL' && el.x >= x0 && el.x <= x1 && el.y >= y0 && el.y <= y1) cnt++;
        if (cnt) label((x0 + x1) / 2, (y0 + y1) / 2, String(cnt), '#ffffff');
      }
    }
    if (gs) {
      const bx0 = fr.minX, bx1 = fr.maxX, by0 = h.alliance === 'RED' ? fr.minY - 3.6 : fr.maxY + 1.4, by1 = by0 + 2.2;
      poly([[bx0, by0], [bx1, by0], [bx1, by1], [bx0, by1]], '#0b0e13cc', '#3a4560', 1);
      const p = Math.max(0, Math.min(1, gs.tipLoad > 0 ? gs.load / gs.tipLoad : 0));
      if (p > 0) poly([[bx0, by0], [bx0 + (bx1 - bx0) * p, by0], [bx0 + (bx1 - bx0) * p, by1], [bx0, by1]], hc, null);
      label((bx0 + bx1) / 2, h.alliance === 'RED' ? by0 - 2.4 : by1 + 2.4, `${gs.tips} tip${gs.tips === 1 ? '' : 's'} · ${gs.load}/${gs.tipLoad}`, '#e6e9f0');
    }
  }
}

function drawFlowers2D() {
  const g = state.game, now = performance.now();
  for (const fl of (state.map.flowers || [])) {
    const gs = g && g.flowers ? g.flowers.find(x => x.index === fl.index) : null;
    const col = gs && gs.owner ? allianceColor(gs.owner) : '#8b94a7';
    poly(rectPts(fl.footprint), '#222b38', col + '99', 1.5);
    const [sx, sy] = fieldToScreen(fl.x, fl.y); const r = fl.openingR * view.scale;
    if (gs && gs.scoring) { const k = 0.5 + 0.5 * Math.sin(now / 120); ctx.save(); ctx.shadowColor = '#ffe58a'; ctx.shadowBlur = 8 + 14 * k; ctx.strokeStyle = '#ffe58a'; ctx.lineWidth = 5; ctx.beginPath(); ctx.arc(sx, sy, r + 3, 0, Math.PI * 2); ctx.stroke(); ctx.restore(); }
    ctx.fillStyle = '#0b0e13'; ctx.strokeStyle = col; ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    ctx.strokeStyle = col + '66'; ctx.lineWidth = 1; ctx.beginPath(); ctx.arc(sx, sy, r * 0.55, 0, Math.PI * 2); ctx.stroke();
    if (gs && gs.count > 0) { ctx.fillStyle = '#fff'; ctx.font = `bold ${Math.round(r * 0.9)}px system-ui`; ctx.textAlign = 'center'; ctx.fillText(String(gs.count), sx, sy + r * 0.32); ctx.textAlign = 'left'; }
    if (g && g.flowerUnlockIn > 0) {
      const ly = sy - r - 9; ctx.fillStyle = '#ffe58a'; ctx.strokeStyle = '#ffe58a'; ctx.lineWidth = 1.6;
      ctx.fillRect(sx - 4, ly, 8, 6); ctx.beginPath(); ctx.arc(sx, ly, 3, Math.PI, 0); ctx.stroke();
    }
  }
}
/** Field-specific controls (obelisk face, HIVE tips...) come from the server's field description. */
function renderFieldOptions(field) {
  const host = $('fieldOptions'); if (!host) return;
  const opts = (field && field.options) || [];
  const key = JSON.stringify(opts.map(o => o.key)) + (field ? field.name : '');
  if (host.dataset.key !== key) {
    host.dataset.key = key;
    host.innerHTML = opts.map(o => `<label>${esc(o.label)} <select data-key="${esc(o.key)}">${o.choices.map((c, i) => `<option value="${esc(c)}">${esc(o.choiceLabels[i] || c)}</option>`).join('')}</select></label>`).join('');
    for (const sel of host.querySelectorAll('select')) sel.onchange = (e) => {
      const k = e.target.dataset.key;
      if (k === 'obelisk') send({ type: 'sim.obelisk', id: parseInt(e.target.value, 10) });
      send({ type: 'sim.field', key: k, value: e.target.value });
    };
  }
  const st = (field && field.state) || {};
  for (const sel of host.querySelectorAll('select')) if (st[sel.dataset.key] !== undefined && document.activeElement !== sel) sel.value = String(st[sel.dataset.key]);
}
function drawOverlay(d) {
  const conv = d.frame === 'pedro' ? (x, y) => { const p = fromPedro(x, y, 0); return [p.x, p.y]; } : (x, y) => [x, y];
  const scale = view.scale;
  let stroke = '#ffffff', fill = '#ffffff', lw = 1, alpha = 1;
  for (const op of d.ops) {
    ctx.globalAlpha = alpha;
    switch (op.op) {
      case 'stroke': stroke = op.c; break; case 'fill': fill = op.c; break; case 'strokeWidth': lw = op.w; break; case 'alpha': alpha = op.a; break;
      case 'circle': { const [sx, sy] = fieldToScreen(...conv(op.x, op.y)); ctx.beginPath(); ctx.arc(sx, sy, op.r * scale, 0, Math.PI * 2);
        if (op.stroke === false || (d.source === 'panels' && op.fill && op.fill !== 'transparent')) { ctx.fillStyle = d.source === 'panels' ? op.fill : fill; ctx.fill(); }
        if (op.stroke !== false) { ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : (op.fill || stroke)) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); } break; }
      case 'line': { const a = fieldToScreen(...conv(op.x1, op.y1)), b = fieldToScreen(...conv(op.x2, op.y2)); ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : (op.fill || stroke)) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); break; }
      case 'rect': { const pts = [conv(op.x, op.y), conv(op.x + op.w, op.y), conv(op.x + op.w, op.y + op.h), conv(op.x, op.y + op.h)]; ctx.beginPath(); pts.forEach((p, i) => { const [sx, sy] = fieldToScreen(p[0], p[1]); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); ctx.closePath();
        if (op.stroke === false || (d.source === 'panels' && op.fill && op.fill !== 'transparent')) { ctx.fillStyle = d.source === 'panels' ? op.fill : fill; ctx.fill(); }
        if (op.stroke !== false) { ctx.strokeStyle = d.source === 'panels' ? (op.stroke && op.stroke !== 'transparent' ? op.stroke : stroke) : stroke; ctx.lineWidth = d.source === 'panels' ? Math.max(1, (op.width || 0.1) * scale) : lw; ctx.stroke(); } break; }
      case 'polygon': case 'polyline': { ctx.beginPath(); op.xs.forEach((x, i) => { const [sx, sy] = fieldToScreen(...conv(x, op.ys[i])); if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy); }); if (op.op === 'polygon') ctx.closePath();
        if (op.stroke === false) { ctx.fillStyle = fill; ctx.fill(); } else { ctx.strokeStyle = stroke; ctx.lineWidth = lw; ctx.stroke(); } break; }
      case 'text': { const [sx, sy] = fieldToScreen(...conv(op.x, op.y)); ctx.fillStyle = fill; ctx.font = op.font || '12px system-ui'; ctx.fillText(op.t, sx, sy); break; }
      case 'grid': break;
    }
  }
  ctx.globalAlpha = 1;
}
/** 2D keeps a square field; 3D fills the whole area. HiDPI backing store, all drawing in CSS px. */
function sizeCanvas() {
  const wrap = $('fieldWrap'), stage = $('stage');
  const aw = Math.max(200, wrap.clientWidth - 4), ah = Math.max(200, wrap.clientHeight - 4);
  const w = state.view === '3d' ? aw : Math.min(aw, ah), h = state.view === '3d' ? ah : w;
  const dpr = window.devicePixelRatio || 1;
  if (stage.dataset.w !== String(w) || stage.dataset.h !== String(h)) { stage.dataset.w = w; stage.dataset.h = h; stage.style.width = w + 'px'; stage.style.height = h + 'px'; }
  const pw = Math.round(w * dpr), ph = Math.round(h * dpr);
  if (canvas.width !== pw || canvas.height !== ph) { canvas.width = pw; canvas.height = ph; }
  view.w = w; view.h = h; view.dpr = dpr; view.scale = Math.min(w, h) / (2 * geo.H + 6);
}
window.addEventListener('resize', sizeCanvas);
function frame() { sizeCanvas(); drawField(); drawPadIndicator(); requestAnimationFrame(frame); }
requestAnimationFrame(frame);

// drag robot / rotate (2D) or orbit the camera (3D)
const canvasXY = (e) => { const rect = canvas.getBoundingClientRect(); return [(e.clientX - rect.left) * view.w / rect.width, (e.clientY - rect.top) * view.h / rect.height]; };
let orbitDrag = null;
canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  if (state.view === '3d') { orbitDrag = { x: e.clientX, y: e.clientY }; return; }
  if (!state.last) return;
  const [sx, sy] = canvasXY(e);
  const [fx, fy] = screenToField(sx, sy); const r = state.last.robot;
  state.drag = { rotate: e.shiftKey || e.button === 2, x: r.x, y: r.y, h: r.headingDeg, ox: fx - r.x, oy: fy - r.y };
});
canvas.addEventListener('pointermove', (e) => {
  if (orbitDrag) {
    const dx = e.clientX - orbitDrag.x, dy = e.clientY - orbitDrag.y; orbitDrag.x = e.clientX; orbitDrag.y = e.clientY;
    const c = state.cam; c.yaw -= dx * 0.008; c.yawOff -= dx * 0.008; c.pitch = Math.max(0.05, Math.min(1.55, c.pitch + dy * 0.006));
    return;
  }
  if (!state.drag) return;
  const [sx, sy] = canvasXY(e);
  const [fx, fy] = screenToField(sx, sy);
  if (state.drag.rotate) { state.drag.h = Math.atan2(fy - state.drag.y, fx - state.drag.x) * 180 / Math.PI; }
  else { state.drag.x = Math.max(-geo.H, Math.min(geo.H, fx - state.drag.ox)); state.drag.y = Math.max(-geo.H, Math.min(geo.H, fy - state.drag.oy)); }
  send({ type: 'sim.setPose', x: state.drag.x, y: state.drag.y, headingDeg: state.drag.h });
});
canvas.addEventListener('pointerup', () => { state.drag = null; orbitDrag = null; });
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  if (state.view === '3d') { const c = state.cam; c.dist = Math.max(30, Math.min(6 * geo.H, c.dist * (e.deltaY > 0 ? 1.1 : 0.9))); return; }
  if (!state.last) return; const r = state.last.robot; send({ type: 'sim.setPose', x: r.x, y: r.y, headingDeg: r.headingDeg + (e.deltaY > 0 ? -5 : 5) });
}, { passive: false });
$('setPoseBtn').onclick = () => {
  let x = parseFloat($('poseX').value) || 0, y = parseFloat($('poseY').value) || 0, h = parseFloat($('poseH').value) || 0;
  if ($('frameSelect').value === 'pedro') { const p = fromPedro(x, y, h); x = p.x; y = p.y; h = p.h; }
  send({ type: 'sim.setPose', x, y, headingDeg: h });
};
$('resetPoseBtn').onclick = () => send({ type: 'sim.reset' });
$('saveStartBtn').onclick = () => { const r = state.last.robot; send({ type: 'sim.setStartPose', x: r.x, y: r.y, headingDeg: r.headingDeg }); toast('info', 'Start pose saved'); };
$('rotateSelect').onchange = (e) => { state.viewRot = parseInt(e.target.value, 10); };

$('frameSelect').onchange = () => { if (!state.last) return; const r = state.last.robot; const p = $('frameSelect').value === 'pedro' ? toPedro(r.x, r.y, r.headingDeg) : { x: r.x, y: r.y, h: r.headingDeg }; $('poseX').value = p.x.toFixed(1); $('poseY').value = p.y.toFixed(1); $('poseH').value = p.h.toFixed(0); };

// ------------------------------------------------------------------ hardware panel
const devInputs = {}; // name -> {key -> element} to avoid re-rendering while editing
let devicesSignature = '';
function renderDevices(devices) {
  const sig = devices.map(d => d.name + '|' + d.type + '|' + d.hub).join(';');
  const root = $('devices');
  if (sig !== devicesSignature) {
    devicesSignature = sig; root.innerHTML = '';
    const byHub = {};
    for (const d of devices) (byHub[d.hub] ||= []).push(d);
    for (const hub of Object.keys(byHub)) {
      const h = document.createElement('div'); h.className = 'hubhead'; h.textContent = hub; root.appendChild(h);
      for (const d of byHub[hub]) root.appendChild(deviceCard(d));
    }
  }
  for (const d of devices) {
    const card = document.querySelector(`.dev[data-name="${CSS.escape(d.name)}"][data-type="${CSS.escape(d.type)}"]`); if (!card) continue;
    const vals = card.querySelector('.vals'); const parts = [];
    for (const [k, v] of Object.entries(d.values)) { if (k === 'input') continue; parts.push(`<span><b>${k}</b> ${fmt(v)}</span>`); }
    const html = parts.join(''); if (vals.innerHTML !== html) vals.innerHTML = html;
    const bar = card.querySelector('.bar i');
    if (bar) { const p = d.values.power != null ? d.values.power : d.values.commanded != null ? (d.values.commanded * 2 - 1) : 0; const w = Math.abs(p) * 50; bar.style.left = (p < 0 ? 50 - w : 50) + '%'; bar.style.width = w + '%'; }
    const inputs = devInputs[d.name] || {};
    for (const [k, el] of Object.entries(inputs)) { if (document.activeElement === el) continue; const v = d.values[k]; if (v == null) continue; if (el.type === 'checkbox') el.checked = !!v; else if (el.type === 'range' || el.type === 'number') { if (Math.abs(parseFloat(el.value) - v) > 1e-6) el.value = v; } }
  }
}
function fmt(v) { if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(Math.abs(v) < 10 ? 3 : 1); if (Array.isArray(v)) return '[' + v.join(',') + ']'; return String(v); }
function deviceCard(d) {
  const card = document.createElement('div'); card.className = 'dev'; card.dataset.name = d.name; card.dataset.type = d.type;
  card.innerHTML = `<div class="head"><span>${esc(d.name)}</span><span class="type">${esc(d.type)}${d.port != null ? ' · port ' + d.port : ''}</span>${d.autoCreated ? '<span class="badge" title="Not in the robot configuration; created when the code asked for it">auto</span>' : ''}</div><div class="vals"></div>`;
  if (d.type === 'DcMotorEx' || d.type === 'Servo' || d.type === 'CRServo') { const bar = document.createElement('div'); bar.className = 'bar'; bar.innerHTML = '<i></i>'; card.appendChild(bar); }
  const inputs = document.createElement('div'); inputs.className = 'inputs'; const reg = devInputs[d.name] = {};
  const addToggle = (key, labelText) => { const l = document.createElement('label'); const c = document.createElement('input'); c.type = 'checkbox'; c.onchange = () => send({ type: 'device.set', name: d.name, key, value: c.checked }); l.appendChild(c); l.appendChild(document.createTextNode(labelText)); inputs.appendChild(l); reg[key] = c; };
  const addRange = (key, labelText, min, max, step) => { const l = document.createElement('label'); const r = document.createElement('input'); r.type = 'range'; r.min = min; r.max = max; r.step = step; r.oninput = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(r.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(r); inputs.appendChild(l); reg[key] = r; };
  const addNumber = (key, labelText, step) => { const l = document.createElement('label'); const n = document.createElement('input'); n.type = 'number'; n.step = step; n.onchange = () => send({ type: 'device.set', name: d.name, key, value: parseFloat(n.value) }); l.appendChild(document.createTextNode(labelText)); l.appendChild(n); inputs.appendChild(l); reg[key] = n; };
  switch (d.type) {
    case 'DigitalChannel': addToggle('state', 'input high'); break;
    case 'TouchSensor': addToggle('pressed', 'pressed'); break;
    case 'AnalogInput': addRange('voltage', 'V', 0, 3.3, 0.01); break;
    case 'Rev2mDistanceSensor': addNumber('distanceMm', 'mm', 1); addToggle('raycast', 'ray-cast'); break;
    case 'RevColorSensorV3': addRange('red', 'R', 0, 1, 0.01); addRange('green', 'G', 0, 1, 0.01); addRange('blue', 'B', 0, 1, 0.01); addRange('alpha', 'A', 0, 1, 0.01); addNumber('distanceMm', 'mm', 1); break;
    case 'Limelight3A': addToggle('forceNoTargets', 'hide targets'); break;
    case 'Servo': addRange('position', 'move', 0, 1, 0.01); break;
  }
  if (inputs.children.length) card.appendChild(inputs);
  return card;
}
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

// ------------------------------------------------------------------ configurables
let configSig = '';
function renderConfigurables() {
  const root = $('configurables');
  const sig = state.configurables.map(c => c.className + ':' + c.fields.map(f => f.path + '/' + f.type + '/' + f.editable).join(',')).join(';');
  if (sig !== configSig) {
    configSig = sig; root.innerHTML = '';
    if (!state.configurables.length) { root.innerHTML = '<p class="hint">No @Configurable / @Config classes found in the team code.</p>'; return; }
    for (const c of state.configurables) {
      const div = document.createElement('div'); div.className = 'cls'; const h = document.createElement('h3'); h.textContent = c.simpleName; h.title = c.className; div.appendChild(h);
      for (const f of c.fields) {
        const row = document.createElement('div'); row.className = 'row' + (f.editable ? '' : ' ro'); row.dataset.path = f.path; row.dataset.cls = c.className;
        const name = document.createElement('span'); name.textContent = f.path; name.title = f.type; row.appendChild(name);
        let input;
        if (!f.editable) { input = document.createElement('span'); input.className = 'val'; }
        else if (f.type === 'boolean') { input = document.createElement('input'); input.type = 'checkbox'; input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.checked }); }
        else if (f.type === 'enum') { input = document.createElement('select'); for (const o of f.options) { const op = document.createElement('option'); op.value = o; op.textContent = o; input.appendChild(op); } input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.value }); }
        else { input = document.createElement('input'); input.type = f.type === 'String' ? 'text' : 'number'; if (input.type === 'number') input.step = 'any'; input.onchange = () => send({ type: 'configurable.set', className: c.className, path: f.path, value: input.value }); input.onkeydown = (e) => { if (e.key === 'Enter') input.blur(); }; }
        row.appendChild(input); div.appendChild(row);
      }
      root.appendChild(div);
    }
  }
  for (const c of state.configurables) for (const f of c.fields) {
    const row = root.querySelector(`.row[data-cls="${CSS.escape(c.className)}"][data-path="${CSS.escape(f.path)}"]`); if (!row) continue;
    const el = row.children[1]; if (document.activeElement === el) continue;
    if (el.tagName === 'SPAN') el.textContent = fmt(f.value); else if (el.type === 'checkbox') el.checked = !!f.value; else el.value = f.value == null ? '' : f.value;
  }
}

// ------------------------------------------------------------------ graph
const COLORS = ['#4f8cff', '#2ecc71', '#f1c40f', '#e74c3c', '#9b59b6', '#1abc9c', '#e67e22', '#ff7ab6'];
function renderGraph() {
  const g = $('graph'); const c = g.getContext('2d'); g.width = g.clientWidth || 360; const W = g.width, H = g.height; c.clearRect(0, 0, W, H);
  const names = Object.keys(state.graphs); if (!names.length) { c.fillStyle = '#8b94a7'; c.font = '12px system-ui'; c.fillText('No graph data (Panels graph / Dashboard packets).', 10, 20); $('graphLegend').innerHTML = ''; return; }
  const now = Date.now(); const span = 20000; let min = Infinity, max = -Infinity;
  for (const n of names) for (const [t, v] of state.graphs[n]) if (now - t < span) { min = Math.min(min, v); max = Math.max(max, v); }
  if (!isFinite(min)) { min = 0; max = 1; } if (max - min < 1e-9) { max = min + 1; }
  const pad = (max - min) * 0.05; min -= pad; max += pad;
  c.strokeStyle = '#2a3140'; c.beginPath(); for (let i = 0; i <= 4; i++) { const y = i * H / 4; c.moveTo(0, y); c.lineTo(W, y); } c.stroke();
  c.fillStyle = '#8b94a7'; c.font = '10px monospace'; c.fillText(max.toFixed(2), 2, 10); c.fillText(min.toFixed(2), 2, H - 3);
  names.forEach((n, i) => { c.strokeStyle = COLORS[i % COLORS.length]; c.lineWidth = 1.5; c.beginPath(); let first = true; for (const [t, v] of state.graphs[n]) { if (now - t > span) continue; const x = W - (now - t) / span * W, y = H - (v - min) / (max - min) * H; if (first) { c.moveTo(x, y); first = false; } else c.lineTo(x, y); } c.stroke(); });
  $('graphLegend').innerHTML = names.map((n, i) => `<span style="color:${COLORS[i % COLORS.length]}">■ ${esc(n)} ${fmt(state.graphs[n].length ? state.graphs[n][state.graphs[n].length - 1][1] : 0)}</span>`).join('');
}

// ------------------------------------------------------------------ log
const LEVELS = { VERBOSE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 };
function renderLog(newEntries) {
  const pre = $('log'); const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
  if (!newEntries) pre.innerHTML = '';
  const entries = newEntries || state.logEntries; const frag = document.createDocumentFragment();
  for (const e of entries) {
    if (LEVELS[e.level] < state.logLevel) continue;
    if (state.logFilter && !(e.tag + ' ' + e.message).toLowerCase().includes(state.logFilter)) continue;
    const span = document.createElement('span'); span.className = e.level[0];
    const t = new Date(e.timeMs); span.textContent = `${t.toLocaleTimeString([], { hour12: false })}.${String(t.getMilliseconds()).padStart(3, '0')} ${e.level[0]}/${e.tag}: ${e.message}\n`;
    frag.appendChild(span);
  }
  pre.appendChild(frag);
  while (pre.childNodes.length > 3000) pre.removeChild(pre.firstChild);
  if (atBottom) pre.scrollTop = pre.scrollHeight;
}
$('logLevel').onchange = (e) => { state.logLevel = parseInt(e.target.value, 10); renderLog(null); };
$('logFilter').oninput = (e) => { state.logFilter = e.target.value.toLowerCase(); renderLog(null); };
$('logClear').onclick = () => { state.logEntries = []; $('log').innerHTML = ''; send({ type: 'log.clear' }); };
$('configLoadBtn').onclick = () => send({ type: 'config.get' });
$('configSaveBtn').onclick = () => { try { JSON.parse($('configText').value); } catch (e) { toast('error', 'Invalid JSON: ' + e.message); return; } send({ type: 'config.save', json: $('configText').value }); };
$('configSaveCurrentBtn').onclick = () => { send({ type: 'config.saveCurrent' }); setTimeout(() => send({ type: 'config.get' }), 300); };

// ------------------------------------------------------------------ gamepads
const BUTTONS = [['a', 'A'], ['b', 'B'], ['x', 'X'], ['y', 'Y'], ['lb', 'LB'], ['rb', 'RB'], ['du', '▲'], ['dd', '▼'], ['dl', '◀'], ['dr', '▶'], ['back', 'back'], ['start', 'start'], ['guide', 'guide'], ['ls', 'L3'], ['rs', 'R3'], ['touchpad', 'pad']];
const KEYMAP = { Space: 'a', KeyB: 'b', KeyX: 'x', KeyY: 'y', KeyQ: 'lb', KeyE: 'rb', KeyT: 'du', KeyG: 'dd', KeyF: 'dl', KeyH: 'dr', Enter: 'start', Backspace: 'back' };
const KEY_TRIGGERS = { KeyZ: 'lt', KeyC: 'rt' };
const KEY_CODES = new Set([...Object.keys(KEYMAP), ...Object.keys(KEY_TRIGGERS), 'KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']);
const KB_PAD = 1;
class VirtualPad {
  constructor(el, id) {
    this.el = el; this.id = id; this.keys = new Set();
    this.s = { lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0, type: 'xbox' }; for (const [k] of BUTTONS) this.s[k] = false;
    this.lastSent = ''; this.source = 'virtual';
    el.innerHTML = `<div class="title">Gamepad ${id} <span class="src">${id === 1 ? 'virtual · keyboard when no controller' : 'physical controller: press Start+B'}</span><span class="spacer"></span>${id === 1 ? '<label class="kbdopt" title="Drive gamepad 1 from the keyboard even when a controller is connected"><input type="checkbox" id="kbdChk"> Keyboard</label><button id="legendBtn" title="Show keyboard bindings">Keys</button>' : ''}<select class="physSel"><option value="">no controller</option></select></div>
      <div class="stick" data-stick="l"><div class="knob"></div><div class="lab">left stick</div></div>
      <div class="mid"></div>
      <div class="stick" data-stick="r"><div class="knob"></div><div class="lab">right stick</div></div>`;
    const mid = el.querySelector('.mid');
    for (const [k, label] of BUTTONS) { const b = document.createElement('div'); b.className = 'btn'; b.dataset.key = k; b.textContent = label; b.onpointerdown = (e) => { e.preventDefault(); b.setPointerCapture(e.pointerId); this.s[k] = true; this.render(); }; b.onpointerup = () => { this.s[k] = false; this.render(); }; b.onpointercancel = b.onpointerup; mid.appendChild(b); }
    for (const t of ['lt', 'rt']) { const l = document.createElement('label'); l.className = 'trig'; l.innerHTML = `${t.toUpperCase()} <input type="range" min="0" max="1" step="0.01" value="0">`; const inp = l.querySelector('input'); inp.oninput = () => { this.s[t] = parseFloat(inp.value); }; inp.onpointerup = () => { inp.value = 0; this.s[t] = 0; }; mid.appendChild(l); }
    el.querySelectorAll('.stick').forEach(st => {
      const which = st.dataset.stick; const knob = st.querySelector('.knob');
      const move = (e) => { const r = st.getBoundingClientRect(); let x = (e.clientX - r.left - r.width / 2) / (r.width / 2 - 14), y = (e.clientY - r.top - r.height / 2) / (r.height / 2 - 14); const m = Math.hypot(x, y); if (m > 1) { x /= m; y /= m; } this.s[which + 'x'] = x; this.s[which + 'y'] = y; this.render(); };
      st.onpointerdown = (e) => { st.setPointerCapture(e.pointerId); st.classList.add('active'); move(e); };
      st.onpointermove = (e) => { if (st.classList.contains('active')) move(e); };
      st.onpointerup = st.onpointercancel = () => { st.classList.remove('active'); this.s[which + 'x'] = 0; this.s[which + 'y'] = 0; this.render(); };
    });
    this.physSel = el.querySelector('.physSel');
    this.physSel.onchange = () => { state.physical[id] = this.physSel.value === '' ? null : parseInt(this.physSel.value, 10); };
    this.render();
  }
  render() {
    const el = this.el;
    el.querySelectorAll('.btn').forEach(b => b.classList.toggle('on', !!this.s[b.dataset.key]));
    el.querySelectorAll('.stick').forEach(st => { const w = st.dataset.stick; const k = st.querySelector('.knob'); const r = st.clientWidth / 2 - 14; k.style.left = (st.clientWidth / 2 - 14 + this.s[w + 'x'] * r) + 'px'; k.style.top = (st.clientHeight / 2 - 14 + this.s[w + 'y'] * r) + 'px'; });
    el.querySelectorAll('.trig input').forEach((inp, i) => { const v = this.s[i === 0 ? 'lt' : 'rt']; if (document.activeElement !== inp) inp.value = v; });
  }
  applyPhysical(gp) {
    const dz = (v) => Math.abs(v) < 0.08 ? 0 : v;
    const b = (i) => !!(gp.buttons[i] && gp.buttons[i].pressed);
    this.s.lx = dz(gp.axes[0] || 0); this.s.ly = dz(gp.axes[1] || 0); this.s.rx = dz(gp.axes[2] || 0); this.s.ry = dz(gp.axes[3] || 0);
    this.s.a = b(0); this.s.b = b(1); this.s.x = b(2); this.s.y = b(3); this.s.lb = b(4); this.s.rb = b(5);
    this.s.lt = gp.buttons[6] ? gp.buttons[6].value : 0; this.s.rt = gp.buttons[7] ? gp.buttons[7].value : 0;
    this.s.back = b(8); this.s.start = b(9); this.s.ls = b(10); this.s.rs = b(11); this.s.du = b(12); this.s.dd = b(13); this.s.dl = b(14); this.s.dr = b(15); this.s.guide = b(16);
    this.s.type = /playstation|dualshock|dualsense|054c/i.test(gp.id) ? 'ps4' : 'xbox';
    this.render();
  }
  /** overlay=true layers pressed keys on top of a physical controller's state instead of replacing it. */
  applyKeyboard(overlay) {
    const k = this.keys; const ax = (neg, pos) => (k.has(pos) ? 1 : 0) - (k.has(neg) ? 1 : 0);
    const set = (name, v) => { if (!overlay || v) this.s[name] = v; };
    set('lx', ax('KeyA', 'KeyD')); set('ly', ax('KeyW', 'KeyS')); set('rx', ax('ArrowLeft', 'ArrowRight')); set('ry', ax('ArrowUp', 'ArrowDown'));
    for (const [code, t] of Object.entries(KEY_TRIGGERS)) set(t, k.has(code) ? 1 : 0);
    for (const [code, btn] of Object.entries(KEYMAP)) set(btn, k.has(code));
    this.render();
  }
  sendIfNeeded(force) {
    const json = JSON.stringify(this.s);
    if (force || json !== this.lastSent) { this.lastSent = json; send({ type: 'gamepad', id: this.id, state: this.s }); }
  }
}
const pads = { 1: new VirtualPad($('gp1'), 1), 2: new VirtualPad($('gp2'), 2) };
let keepalive = 0;
function gamepadLoop() {
  const gps = navigator.getGamepads ? Array.from(navigator.getGamepads()).filter(Boolean) : [];
  // keep selectors in sync
  for (const id of [1, 2]) {
    const sel = pads[id].physSel; const opts = ['<option value="">no controller</option>'].concat(gps.map(g => `<option value="${g.index}">${esc(g.id.slice(0, 32))}</option>`)).join('');
    if (sel.dataset.opts !== opts) { sel.dataset.opts = opts; sel.innerHTML = opts; sel.value = state.physical[id] == null ? '' : String(state.physical[id]); }
  }
  // Driver Station style assignment: Start+A -> gamepad 1, Start+B -> gamepad 2
  for (const g of gps) {
    const start = g.buttons[9] && g.buttons[9].pressed;
    if (start && g.buttons[0] && g.buttons[0].pressed && state.physical[1] !== g.index) { state.physical[1] = g.index; if (state.physical[2] === g.index) state.physical[2] = null; toast('info', `${g.id.slice(0, 40)} → gamepad 1`); }
    if (start && g.buttons[1] && g.buttons[1].pressed && state.physical[2] !== g.index) { state.physical[2] = g.index; if (state.physical[1] === g.index) state.physical[1] = null; toast('info', `${g.id.slice(0, 40)} → gamepad 2`); }
  }
  for (const id of [1, 2]) {
    const p = pads[id]; const phys = state.physical[id] != null ? gps.find(g => g.index === state.physical[id]) : null;
    if (phys) { p.applyPhysical(phys); p.el.querySelector('.src').textContent = 'physical: ' + phys.id.slice(0, 40); if (id === KB_PAD && p.keys.size) p.applyKeyboard(true); }
    else if (id === KB_PAD && p.keys.size) { p.applyKeyboard(false); }
    // rumble
    p.sendIfNeeded(keepalive % 15 === 0);
  }
  keepalive++;
  setTimeout(gamepadLoop, 33);
}
gamepadLoop();
/** Keyboard drives gamepad 1 when asked to, or whenever no physical controller is assigned to it. */
const kbdActive = () => $('kbdChk').checked || state.physical[KB_PAD] == null;
window.addEventListener('keydown', (e) => {
  const tag = e.target.tagName; if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || e.ctrlKey || e.metaKey || e.altKey) return;
  if (!KEY_CODES.has(e.code) || !kbdActive()) return;
  e.preventDefault();
  const p = pads[KB_PAD]; if (!p.keys.has(e.code)) { p.keys.add(e.code); p.applyKeyboard(state.physical[KB_PAD] != null); }
});
window.addEventListener('keyup', (e) => { const p = pads[KB_PAD]; if (p.keys.delete(e.code)) { e.preventDefault(); p.applyKeyboard(state.physical[KB_PAD] != null); } });
const releaseKeys = () => { const p = pads[KB_PAD]; if (p.keys.size) { p.keys.clear(); p.applyKeyboard(state.physical[KB_PAD] != null); } };
window.addEventListener('blur', releaseKeys);
// Buttons keep focus after a click; without this Space/Enter would re-trigger them while driving.
document.addEventListener('click', (e) => { const b = e.target.closest && e.target.closest('button'); if (b) b.blur(); });
$('kbdChk').onchange = () => { if (!$('kbdChk').checked) releaseKeys(); };
$('legendBtn').onclick = () => { $('kbdLegend').classList.toggle('hidden'); };
$('kbdLegend').innerHTML = [['W A S D', 'left stick'], ['← ↑ ↓ →', 'right stick'], ['Space', 'A'], ['B / X / Y', 'B / X / Y'], ['Q / E', 'left / right bumper'], ['Z / C', 'left / right trigger'], ['T G F H', 'dpad ↑ ↓ ← →'], ['Enter', 'start'], ['Backspace', 'back']].map(([k, v]) => `<b>${k}</b><span>${v}</span>`).join('');

/** Small on-field indicator of what the keyboard (or the active pad 1 state) sends. */
const padCtx = $('padInd').getContext('2d');
let padDpr = 0;
function drawPadIndicator() {
  const card = $('kbdCard'); const show = kbdActive();
  card.classList.toggle('hidden', !show); if (!show) return;
  const cv = $('padInd'), dpr = window.devicePixelRatio || 1;
  if (padDpr !== dpr) { padDpr = dpr; cv.width = Math.round(176 * dpr); cv.height = Math.round(64 * dpr); }
  const c = padCtx, s = pads[KB_PAD].s;
  c.setTransform(dpr, 0, 0, dpr, 0, 0); c.clearRect(0, 0, 176, 64);
  const stick = (cx, cy, x, y) => {
    c.strokeStyle = '#3a4560'; c.lineWidth = 1.5; c.beginPath(); c.arc(cx, cy, 15, 0, Math.PI * 2); c.stroke();
    c.fillStyle = (x || y) ? '#4f8cff' : '#5c6a85'; c.beginPath(); c.arc(cx + x * 9, cy + y * 9, 5, 0, Math.PI * 2); c.fill();
  };
  const btn = (cx, cy, on, text, col) => {
    c.fillStyle = on ? (col || '#4f8cff') : '#232a37'; c.beginPath(); c.arc(cx, cy, 7, 0, Math.PI * 2); c.fill();
    c.fillStyle = on ? '#fff' : '#7d889e'; c.font = 'bold 9px system-ui'; c.textAlign = 'center'; c.fillText(text, cx, cy + 3);
  };
  const bar = (x, w, on, text) => { c.fillStyle = on ? '#4f8cff' : '#232a37'; c.fillRect(x, 4, w, 8); c.fillStyle = on ? '#fff' : '#7d889e'; c.font = '8px system-ui'; c.textAlign = 'center'; c.fillText(text, x + w / 2, 11); };
  stick(30, 38, s.lx, s.ly); stick(106, 48, s.rx, s.ry);
  bar(6, 22, s.lb, 'LB'); bar(30, 14, s.lt > 0.1, 'LT'); bar(132, 14, s.rt > 0.1, 'RT'); bar(148, 22, s.rb, 'RB');
  btn(66, 40, s.du, '▲'); btn(66, 56, s.dd, '▼'); btn(58, 48, s.dl, '◀'); btn(74, 48, s.dr, '▶');
  btn(148, 38, s.y, 'Y', '#d4a017'); btn(148, 54, s.a, 'A', '#2e9e57'); btn(140, 46, s.x, 'X', '#2b5fd9'); btn(156, 46, s.b, 'B', '#d93b2b');
  c.font = '8px system-ui'; c.fillStyle = s.back ? '#4f8cff' : '#5c6a85'; c.textAlign = 'center'; c.fillText('back', 84, 25); c.fillStyle = s.start ? '#4f8cff' : '#5c6a85'; c.fillText('start', 108, 25);
  c.textAlign = 'left';
}

// ===== folder (autos) UI =====
function bind(id, ev, fn) { const el = $(id); if (el) el[ev] = fn; }
function updateAutos(m, isHello) {
  if (!state.autos) state.autos = { folder: null, mode: 'none', recent: [], desktop: false };
  const a = state.autos;
  if (isHello) a.desktop = !!m.desktop;
  if ('autosFolder' in m) a.folder = m.autosFolder || null;
  if ('mode' in m) a.mode = m.mode || 'none';
  if ('recentFolders' in m) a.recent = m.recentFolders || [];
}
function renderFolder() {
  const a = state.autos;
  const sec = $('folderSec');
  if (!a || !sec) return;
  sec.classList.remove('hidden');
  const p = $('autosPath');
  p.textContent = a.folder || 'no folder';
  p.title = a.folder || 'Choose a folder containing @Autonomous / @TeleOp OpMode sources';
  const names = { none: 'no OpModes', folder: 'folder', project: 'project', 'project+folder': 'project + folder' };
  $('autosMode').textContent = names[a.mode] || a.mode;
  const r = $('autosRecent');
  r.innerHTML = '';
  const first = document.createElement('option');
  first.value = ''; first.textContent = 'Recent folders…';
  r.appendChild(first);
  (a.recent || []).forEach((f) => { const o = document.createElement('option'); o.value = f; o.textContent = f; r.appendChild(o); });
  r.classList.toggle('hidden', !(a.recent && a.recent.length));
}
bind('autosRecent', 'onchange', () => { const v = $('autosRecent').value; if (v) send({ type: 'autos.set', path: v }); $('autosRecent').value = ''; });
bind('autosChooseBtn', 'onclick', (e) => {
  if (state.hello && state.hello.desktop && !e.shiftKey) { send({ type: 'autos.browse' }); return; }
  $('autosPrompt').classList.remove('hidden');
  const i = $('autosPathInput'); if (state.autos && state.autos.folder && !i.value) i.value = state.autos.folder; i.focus();
});
bind('autosLoadBtn', 'onclick', () => {
  const v = $('autosPathInput').value.trim();
  if (v) send({ type: 'autos.set', path: v });
  $('autosPrompt').classList.add('hidden');
});
bind('autosCancelBtn', 'onclick', () => $('autosPrompt').classList.add('hidden'));
bind('autosPathInput', 'onkeydown', (e) => { if (e.key === 'Enter') $('autosLoadBtn').click(); });

function resetGameUi() {
  const t = $('ticker'); if (t) t.innerHTML = '';
  state.seq = null;
  const s = $('summary'); if (s) { s.classList.add('hidden'); s.innerHTML = ''; }
  state.summaryOff = false;
  state.dbg = { intake: false, launch: false };
  const b = $('dbgIntakeBtn'); if (b) { b.textContent = 'Debug intake: off'; b.classList.remove('on'); }
}

// ===== match controls =====
function renderMatchSelects() {
  const fill = (id, flavor) => {
    const sel = $(id); if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = '';
    const none = document.createElement('option');
    none.value = ''; none.textContent = flavor === 'AUTONOMOUS' ? '(none — robot idle)' : '(none — no TeleOp)';
    sel.appendChild(none);
    (state.opmodes || []).filter((o) => o.flavor === flavor && !o.disabled).forEach((o) => {
      const op = document.createElement('option'); op.value = o.name; op.textContent = o.name; sel.appendChild(op);
    });
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  };
  fill('matchAuto', 'AUTONOMOUS');
  fill('matchTele', 'TELEOP');
}
bind('matchAuto', 'onchange', () => {
  const e = (state.opmodes || []).find((o) => o.flavor === 'AUTONOMOUS' && o.name === $('matchAuto').value);
  if (e && e.preselectTeleOp && [...$('matchTele').options].some((o) => o.value === e.preselectTeleOp)) $('matchTele').value = e.preselectTeleOp;
});
bind('matchRunBtn', 'onclick', () => {
  state.summaryOff = false;
  send({ type: 'match.run', auto: $('matchAuto').value || null, teleop: $('matchTele').value || null });
});
bind('matchAbortBtn', 'onclick', () => send({ type: 'match.abort' }));
bind('matchResetBtn', 'onclick', () => { state.summaryOff = false; send({ type: 'game.reset' }); });
bind('gsAlliance', 'onchange', () => send({ type: 'game.settings', allianceMode: $('gsAlliance').value }));
bind('gsTipLoad', 'onchange', () => { const v = parseFloat($('gsTipLoad').value); if (isFinite(v)) send({ type: 'game.settings', tipLoad: v }); });
bind('gsHuman', 'onchange', () => send({ type: 'game.settings', scriptedHuman: $('gsHuman').checked }));
bind('humanBtn', 'onclick', () => send({ type: 'game.human', action: 'nectar' }));
bind('dbgIntakeBtn', 'onclick', () => {
  state.dbg.intake = !state.dbg.intake;
  $('dbgIntakeBtn').textContent = 'Debug intake: ' + (state.dbg.intake ? 'on' : 'off');
  $('dbgIntakeBtn').classList.toggle('on', state.dbg.intake);
  send({ type: 'game.manual', intake: state.dbg.intake });
});
{
  const f = $('dbgFireBtn');
  if (f) {
    f.onpointerdown = () => { state.dbg.launch = true; send({ type: 'game.manual', launch: true }); };
    const up = () => { if (state.dbg.launch) { state.dbg.launch = false; send({ type: 'game.manual', launch: false }); } };
    f.onpointerup = up; f.onpointerleave = up; f.onpointercancel = up;
  }
}

function clockText(s) {
  if (s == null) return '–:––';
  const t = Math.max(0, Math.ceil(s));
  return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0');
}
function setText(id, t) { const el = $(id); if (el && el.textContent !== t) el.textContent = t; }

const SCORE_ROWS = [
  ['AUTO', 'auto'], ['TELEOP', 'teleop'], ['PENALTIES', 'penalty'], ['TOTAL', 'total'],
  ['LEAVE', 'leave'], ['PARK', 'park'], ['HIVE TIPS', 'hiveTips'], ['CELL', 'cell'],
  ['FLOWER', 'flower'], ['NECTAR bonus', 'bottomNectar'], ['GARDEN', 'garden'], ['RP', 'rp'],
];
function scoreTableHtml(sb) {
  let h = '<span class="k"></span><span class="h red">RED</span><span class="h blue">BLUE</span>';
  SCORE_ROWS.forEach(([lab, key]) => {
    const t = key === 'total' ? ' t' : '';
    const kv = (a) => {
      let v = a ? a[key] : 0;
      if (key === 'hiveTips' && a) v = v + ' (' + a.tips + ')';
      return v;
    };
    h += `<span class="k">${lab}</span><span class="v${t}">${kv(sb.red)}</span><span class="v${t}">${kv(sb.blue)}</span>`;
  });
  return h;
}
function hudScore(id, a, name, fin) {
  const el = $(id); if (!el || !a) return;
  el.querySelector('.n').textContent = a.total;
  el.querySelector('.rp').textContent = fin ? 'RP ' + a.rp : '';
  const b = [];
  if (a.leave > 0) b.push('<span class="badge">LEAVE</span>');
  if (a.park > 0) b.push('<span class="badge">PARK</span>');
  if (a.swarm) b.push('<span class="badge">SWARM</span>');
  if (a.pollinator1) b.push('<span class="badge pol">POLLINATOR 1</span>');
  if (a.pollinator2) b.push('<span class="badge pol">POLLINATOR 2</span>');
  const html = b.join('');
  const bd = el.querySelector('.badges');
  if (bd.dataset.sig !== html) { bd.dataset.sig = html; bd.innerHTML = html; }
  el.title = `${name}: auto ${a.auto} · teleop ${a.teleop} · penalties ${a.penalty} · tips ${a.tips}`;
}

function onGame(g) {
  state.game = g;
  const has = !!g;
  ['matchSec', 'hudBar'].forEach((id) => { const el = $(id); if (el) el.classList.toggle('hidden', !has); });
  const vc = $('viewCtl');
  if (vc) vc.classList.toggle('hidden', !state.map);
  if (!state.map && state.view !== '2d') setView('2d');
  if (!has) return;

  const m = g.match || { active: false, step: 'IDLE' };
  // HUD
  const ph = $('hudPhase');
  const phName = g.phase === 'PRE' ? 'PRE-MATCH' : g.phase === 'ENDED' ? 'FINAL' : g.phase;
  if (ph) { ph.textContent = phName; ph.className = 'phase-pill ' + g.phase; }
  const clk = $('hudClock');
  if (clk) {
    const rem = g.clock ? g.clock.periodRemaining : null;
    clk.textContent = clockText(rem);
    clk.classList.toggle('low', rem != null && rem <= 10 && (g.phase === 'AUTO' || g.phase === 'TELEOP'));
  }
  const un = $('hudUnlock');
  if (un) {
    if (g.flowerUnlockIn == null) un.classList.add('hidden');
    else {
      un.classList.remove('hidden');
      const locked = g.flowerUnlockIn > 0;
      un.classList.toggle('locked', locked);
      un.textContent = locked ? '🔒 FLOWERs in ' + clockText(g.flowerUnlockIn) : 'FLOWERs open';
    }
  }
  hudScore('hudRed', g.scoreboard.red, 'RED', g.finalized);
  hudScore('hudBlue', g.scoreboard.blue, 'BLUE', g.finalized);
  const r = g.robot || { held: 0, capacity: 0 };
  setText('hudHeld', `held ${r.held}/${r.capacity}` + (r.intake ? ' ⟳' : ''));
  const nv = (g.violations || []).length;
  const hv = $('hudViol');
  if (hv) { hv.classList.toggle('hidden', nv === 0); hv.textContent = nv + (nv === 1 ? ' violation' : ' violations'); }

  // match panel
  const st = $('matchStep');
  if (st) { st.textContent = m.step; st.className = 'state-pill ' + m.step; }
  setText('matchNames', m.active ? [m.auto, m.teleop].filter(Boolean).join(' → ') : (g.practice ? 'practice' : ''));
  $('matchRunBtn').disabled = !!m.active;
  $('matchAbortBtn').disabled = !m.active;
  if (m.active) { $('initBtn').disabled = true; $('startBtn').disabled = true; }

  const ae = document.activeElement;
  const st2 = g.settings || {};
  if ($('gsAlliance') !== ae && st2.allianceMode) $('gsAlliance').value = st2.allianceMode;
  if ($('gsTipLoad') !== ae && st2.tipLoad != null) $('gsTipLoad').value = st2.tipLoad;
  if ($('gsHuman') !== ae && st2.scriptedHuman != null) $('gsHuman').checked = !!st2.scriptedHuman;
  const hb = $('humanBtn');
  if (hb) hb.textContent = 'Human: stage NECTAR' + (g.human && g.human.nectar ? ` (${g.human.nectar})` : '');

  // score table
  const tbl = $('scoreTable');
  if (tbl) {
    const html = scoreTableHtml(g.scoreboard);
    if (tbl.dataset.sig !== html) { tbl.dataset.sig = html; tbl.innerHTML = html; }
  }
  // violations
  setText('violCount', '(' + nv + ')');
  const vl = $('violations');
  if (vl) {
    const rows = (g.violations || []).slice(-50).reverse().map((v) =>
      `<div class="viol-row ${v.level}"><b>${esc(v.rule)}</b> <span class="who">${esc(v.against + ' · ' + v.phase + ' ' + clockText(v.t))}</span> ${esc(v.note || '')}</div>`).join('');
    const html = rows || '<span class="hint">none</span>';
    if (vl.dataset.sig !== html) { vl.dataset.sig = html; vl.innerHTML = html; }
  }
  // ticker
  const ev = g.events || [];
  const maxSeq = ev.length ? Math.max(...ev.map((e) => e.seq)) : 0;
  const tk = $('ticker');
  if (state.seq == null) state.seq = maxSeq;
  else if (maxSeq < state.seq) state.seq = 0;
  if (tk) {
    ev.filter((e) => e.seq > state.seq).forEach((e) => {
      const d = document.createElement('div');
      d.className = 'fade';
      d.textContent = clockText(e.t) + '  ' + e.text;
      tk.appendChild(d);
      setTimeout(() => d.remove(), 6000);
    });
    while (tk.children.length > 6) tk.removeChild(tk.firstChild);
  }
  state.seq = Math.max(state.seq, maxSeq);
  // summary
  const sm = $('summary');
  if (sm) {
    if (g.phase === 'ENDED' && g.finalized) {
      if (!state.summaryOff && sm.classList.contains('hidden')) {
        const sb = g.scoreboard, a = sb.red.total, b = sb.blue.total;
        const title = a === b ? `TIE ${a}–${b}` : a > b ? `<span class="red">RED</span> wins ${a}–${b}` : `<span class="blue">BLUE</span> wins ${b}–${a}`;
        sm.innerHTML = `<div class="card"><h3>${title}</h3><div class="score-table">${scoreTableHtml(sb)}</div>` +
          (g.practice ? '<p class="hint">Single robot; opponent alliance is passive.</p>' : '') +
          '<div class="row"><button id="sumClose">Close</button><button id="sumNew" class="primary">New match</button></div></div>';
        sm.classList.remove('hidden');
        $('sumClose').onclick = () => { state.summaryOff = true; sm.classList.add('hidden'); };
        $('sumNew').onclick = () => { state.summaryOff = false; sm.classList.add('hidden'); send({ type: 'game.reset' }); };
      }
    } else {
      state.summaryOff = false;
      if (!sm.classList.contains('hidden')) { sm.classList.add('hidden'); sm.innerHTML = ''; }
    }
  }
}

// ===== view controls / camera =====
function setView(v) {
  state.view = v;
  $('view2d').classList.toggle('active', v === '2d');
  $('view3d').classList.toggle('active', v === '3d');
  $('camPreset').classList.toggle('hidden', v !== '3d');
  canvas.classList.toggle('orbit', v === '3d');
  sizeCanvas();
}
bind('view2d', 'onclick', () => setView('2d'));
bind('view3d', 'onclick', () => { if (state.map) setView('3d'); });
bind('camPreset', 'onchange', () => setCamPreset($('camPreset').value));
function setCamPreset(name) {
  const c = state.cam, k = (geo.H || 72) / 72;
  c.preset = name; c.yawOff = 0; c.tx = 0; c.ty = 0; c.tz = 0;
  c.dist = 205 * k; c.pitch = 0.62;
  if (name === 'audience') c.yaw = 0;
  else if (name === 'red') c.yaw = -Math.PI / 2;
  else if (name === 'blue') c.yaw = Math.PI / 2;
  else if (name === 'top') { c.pitch = 1.55; c.dist = 235 * k; c.yaw = 0; }
  else if (name === 'follow') { c.dist = 95 * k; c.pitch = 0.5; c.yaw = 0; }
  const sel = $('camPreset'); if (sel && sel.value !== name) sel.value = name;
}

// ===== pseudo-3D renderer (painter's algorithm) =====
const FACES = [[0, 1, 3, 2, 0.7], [4, 5, 7, 6, 1.18], [0, 2, 6, 4, 0.82], [1, 3, 7, 5, 0.95], [0, 1, 5, 4, 0.88], [2, 3, 7, 6, 0.76]];
function boxCorners(x0, y0, z0, x1, y1, z1) {
  const o = [];
  for (let i = 0; i < 8; i++) o.push([i & 1 ? x1 : x0, i & 2 ? y1 : y0, i & 4 ? z1 : z0]);
  return o;
}
function draw3D() {
  const W = view.w, Hh = view.h, c = state.cam, g = state.game, map = state.map;
  if (!map) return;
  const rb = state.last && state.last.robot;
  let yaw = c.yaw, tx = c.tx, ty = c.ty;
  if (c.preset === 'follow' && rb) {
    yaw = rb.headingDeg * Math.PI / 180 + Math.PI + c.yawOff;
    tx = rb.x; ty = rb.y;
  }
  const cp = Math.cos(c.pitch), sp = Math.sin(c.pitch);
  const cam = [tx + c.dist * cp * Math.cos(yaw), ty + c.dist * cp * Math.sin(yaw), c.tz + c.dist * sp];
  let f = [tx - cam[0], ty - cam[1], c.tz - cam[2]];
  const fl = Math.hypot(f[0], f[1], f[2]); f = f.map((v) => v / fl);
  let r = [f[1], -f[0], 0]; const rl = Math.hypot(r[0], r[1]) || 1; r = r.map((v) => v / rl);
  const u = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]];
  const F = Hh * 1.05;
  const proj = (p) => {
    const dx = p[0] - cam[0], dy = p[1] - cam[1], dz = p[2] - cam[2];
    const z = dx * f[0] + dy * f[1] + dz * f[2];
    if (z < 1) return null;
    return [W / 2 + (dx * r[0] + dy * r[1] + dz * r[2]) / z * F, Hh / 2 - (dx * u[0] + dy * u[1] + dz * u[2]) / z * F, z];
  };
  const depthOf = (p) => (p[0] - cam[0]) * f[0] + (p[1] - cam[1]) * f[1] + (p[2] - cam[2]) * f[2];
  const pathPts = (pts, fill, stroke, lw) => {
    const q = pts.map(proj); if (q.some((v) => !v)) return;
    ctx.beginPath(); q.forEach((v, i) => (i ? ctx.lineTo(v[0], v[1]) : ctx.moveTo(v[0], v[1]))); ctx.closePath();
    if (fill) { ctx.fillStyle = fill; ctx.fill(); }
    if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = lw || 1; ctx.stroke(); }
  };
  const prims = [];
  const addBox = (cs, base, stroke, alpha) => {
    const cen = [0, 0, 0]; cs.forEach((p) => { cen[0] += p[0] / 8; cen[1] += p[1] / 8; cen[2] += p[2] / 8; });
    prims.push({ d: depthOf(cen), f: () => {
      const fs = FACES.map((fc) => {
        const pts = fc.slice(0, 4).map((i) => cs[i]);
        const cc = [0, 0, 0]; pts.forEach((p) => { cc[0] += p[0] / 4; cc[1] += p[1] / 4; cc[2] += p[2] / 4; });
        return { pts, k: fc[4], d: depthOf(cc) };
      }).sort((a, b) => b.d - a.d);
      ctx.globalAlpha = alpha == null ? 1 : alpha;
      fs.forEach((q) => pathPts(q.pts, mixHex(base, q.k), stroke, 1));
      ctx.globalAlpha = 1;
    } });
  };
  const txt = (p, text, color) => prims.push({ d: depthOf(p) - 0.5, f: () => {
    const q = proj(p); if (!q) return;
    ctx.font = '11px system-ui'; ctx.textAlign = 'center'; ctx.fillStyle = color || '#cfd8ea'; ctx.fillText(text, q[0], q[1]);
  } });
  const H = geo.H, T = geo.T, n = geo.n;

  // floor
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    const x0 = -H + i * T, y0 = -H + j * T;
    const pts = [[x0, y0, 0], [x0 + T, y0, 0], [x0 + T, y0 + T, 0], [x0, y0 + T, 0]];
    pathPts(pts, (i + j) % 2 ? '#1a1f28' : '#161b23', '#2a3140', 1);
    if (j === 0) pathPts(pts, RED + '16');
    if (j === n - 1) pathPts(pts, BLUE + '16');
  }
  const fld = state.field;
  if (fld && fld.zones) fld.zones.forEach((z) => {
    pathPts([[z.minX, z.minY, 0.05], [z.maxX, z.minY, 0.05], [z.maxX, z.maxY, 0.05], [z.minX, z.maxY, 0.05]], z.color + '44', z.color + 'aa', 1.5);
    if (labelsOn()) { const q = proj([(z.minX + z.maxX) / 2, (z.minY + z.maxY) / 2, 0.1]); if (q) { ctx.font = '10px system-ui'; ctx.textAlign = 'center'; ctx.fillStyle = z.color; ctx.fillText(z.name, q[0], q[1]); } }
  });
  if ($('trailChk') && $('trailChk').checked && state.trail.length > 1) {
    ctx.beginPath(); let st = false;
    state.trail.forEach((p) => { const q = proj([p[0], p[1], 0.1]); if (!q) { st = false; return; } if (st) ctx.lineTo(q[0], q[1]); else { ctx.moveTo(q[0], q[1]); st = true; } });
    ctx.strokeStyle = '#4f8cff88'; ctx.lineWidth = 2; ctx.stroke();
  }

  // walls
  const w = map.wallInnerHalfIn, wh = map.wallHeightIn, th = 2;
  [[-w - th, -w - th, w + th, -w], [-w - th, w, w + th, w + th], [-w - th, -w, -w, w], [w, -w, w + th, w]].forEach((b) => {
    const cen = [(b[0] + b[2]) / 2, (b[1] + b[3]) / 2, wh / 2];
    const near = depthOf(cen) < c.dist;
    addBox(boxCorners(b[0], b[1], 0, b[2], b[3], wh), '#6c7a95', '#8593ad', near ? 0.3 : 1);
  });
  (map.solids || []).forEach((s) => addBox(boxCorners(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ), '#56627c', '#7b88a6', 1));

  // hives
  (map.hives || []).forEach((h) => {
    const col = allianceColor(h.alliance), fr = h.frame;
    addBox(boxCorners(fr.minX, fr.minY, fr.minZ, fr.maxX, fr.maxY, fr.maxZ), col, mixHex(col, 1.3), 0.55);
    const d = hiveDelta(h);
    h.cells.forEach((cell) => addBox(cellCorners(h, cell, d), cell.up ? '#c9a23a' : col, '#e8e8e8', 0.9));
    const gs = gameHive(h.alliance);
    if (gs && g) txt([(fr.minX + fr.maxX) / 2, (fr.minY + fr.maxY) / 2, fr.maxZ + 26], `${gs.tips} tip${gs.tips === 1 ? '' : 's'} · ${(+gs.load).toFixed(1)}/${gs.tipLoad}`, col);
  });
  // flowers
  (map.flowers || []).forEach((fw) => {
    const fp = fw.footprint, gf = g && g.flowers ? g.flowers.find((q) => q.index === fw.index) : null;
    addBox(boxCorners(fp.minX, fp.minY, fp.minZ, fp.maxX, fp.maxY, fp.topZ == null ? fp.maxZ : fp.maxZ), '#4a5570', '#6c7a95', 1);
    const ring = gf && gf.scoring ? '#ffe58a' : gf && gf.owner ? allianceColor(gf.owner) : '#8b94a7';
    const pts = []; for (let a = 0; a < 20; a++) pts.push([fw.x + fw.openingR * Math.cos(a * Math.PI / 10), fw.y + fw.openingR * Math.sin(a * Math.PI / 10), fw.topZ + 0.1]);
    prims.push({ d: depthOf([fw.x, fw.y, fw.topZ]) - 1, f: () => pathPts(pts, '#0b0e14', ring, 2.5) });
    if (gf) txt([fw.x, fw.y, fw.topZ + 5], '' + gf.count, ring);
  });
  // elements
  if (g && g.elements) g.elements.forEach((e) => {
    const col = elemColor(e), rad = elemRadius(e) * (e.s === 'FLOWER' || e.s === 'CELL' ? 0.6 : 1);
    const q = proj([e.x, e.y, e.z]); if (!q) return;
    const sh = proj([e.x, e.y, 0.05]);
    prims.push({ d: q[2], f: () => {
      if (sh && e.z > 0.5) { ctx.fillStyle = '#00000055'; ctx.beginPath(); ctx.ellipse(sh[0], sh[1], rad * F / sh[2], rad * F / sh[2] * 0.4, 0, 0, 7); ctx.fill(); }
      ctx.fillStyle = col; ctx.strokeStyle = '#0008'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.arc(q[0], q[1], Math.max(1.5, rad * F / q[2]), 0, 7); ctx.fill(); ctx.stroke();
    } });
  });
  // robot
  if (rb && state.hello && state.hello.robot) {
    const L = state.hello.robot.lengthIn / 2, Wd = state.hello.robot.widthIn / 2, hd = rb.headingDeg * Math.PI / 180;
    const ch = Math.cos(hd), sn = Math.sin(hd);
    const cs = [];
    for (let i = 0; i < 8; i++) {
      const lx = i & 1 ? L : -L, ly = i & 2 ? Wd : -Wd;
      cs.push([rb.x + lx * ch - ly * sn, rb.y + lx * sn + ly * ch, i & 4 ? 18 : 1]);
    }
    addBox(cs, '#4f8cff', '#8fb8ff', 0.92);
    const mk = (lx, z) => [rb.x + lx * ch, rb.y + lx * sn, z];
    prims.push({ d: depthOf(mk(0, 18)) - 2, f: () => {
      const a = proj(mk(0, 18.2)), b = proj(mk(L, 18.2)); if (!a || !b) return;
      ctx.strokeStyle = '#ffd166'; ctx.lineWidth = 3; ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
    } });
  }
  if (labelsOn()) {
    txt([0, -H - 6, 0], 'RED wall', RED); txt([0, H + 6, 0], 'BLUE wall', BLUE);
  }
  prims.sort((a, b) => b.d - a.d).forEach((p) => p.f());
}
function labelsOn() { const l = $('labelsChk'); return !l || l.checked; }

connect();
})();
