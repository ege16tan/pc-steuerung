'use strict';

// PC Steuerung – Dashboard (Phase 1)
// Alle schreibenden Aufrufe tragen den CSRF-Header: localhost verlangt keinen
// Login, deshalb muss der Agent fremde POSTs ablehnen können.

const CSRF_HEADER = 'X-PC-Steuerung';
const POLL_MS = 2000;

const $ = (id) => document.getElementById(id);
const PLANS = { high: 'Höchstleistung', balanced: 'Ausbalanciert', saver: 'Energiesparen' };

const state = {
  status: null,
  events: [],
  games: [],
  scannedAt: null,
  plugin: null,
  pairing: null,
  simulate: true,
  connected: false,
  pendingTotal: null,
  selectedGame: '',
  gameSignature: '',
  timerSignature: '',
  busy: false,
  ticks: 0,
  metrics: null,
  cpuHistory: [],
  ramHistory: [],
};

/* ---------------- Darstellung: Hell/Dunkel/System ---------------- */

const THEME_KEY = 'pcTheme';
const THEME_ORDER = ['system', 'light', 'dark'];
const THEME_LABEL = { system: '◐ System', light: '☀ Hell', dark: '☾ Dunkel' };

function currentTheme() {
  const stored = localStorage.getItem(THEME_KEY);
  return THEME_LABEL[stored] ? stored : 'system';
}

function applyTheme() {
  const choice = currentTheme();
  const prefersLight = window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: light)').matches
    : false;
  const light = choice === 'light' || (choice === 'system' && prefersLight);
  document.documentElement.dataset.theme = light ? 'light' : 'dark';
  const pill = $('themePill');
  if (pill) pill.textContent = THEME_LABEL[choice];
}

function cycleTheme() {
  const next = THEME_ORDER[(THEME_ORDER.indexOf(currentTheme()) + 1) % THEME_ORDER.length];
  localStorage.setItem(THEME_KEY, next);
  applyTheme();
}

/* ---------------- Hilfen ---------------- */

function esc(value) {
  return String(value).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function fmtTime(seconds) {
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min ' + (s % 60) + ' s';
  return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
}

function fmtUptime(seconds) {
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min';
  return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
}

/* ---------------- API ---------------- */

async function api(path, options) {
  const opts = options || {};
  const method = opts.method || 'GET';
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') headers[CSRF_HEADER] = '1';
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(path, {
    method,
    headers,
    body: opts.body,
    cache: 'no-store',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    let detail = 'HTTP ' + response.status;
    try {
      const data = await response.json();
      if (data && data.detail) detail = data.detail;
    } catch (ignored) {
      // kein JSON (z. B. 404-Text) – Statuscode reicht
    }
    const error = new Error(detail);
    error.status = response.status;
    throw error;
  }

  const type = response.headers.get('content-type') || '';
  return type.indexOf('application/json') >= 0 ? response.json() : response.text();
}

/* ---------------- Hinweis- und Fehlerleiste ---------------- */

function showBanner(message, kind) {
  const banner = $('banner');
  banner.textContent = message;
  banner.className = 'banner' + (kind === 'warn' ? ' warn' : '');
  banner.hidden = false;
}

function clearBanner() {
  const banner = $('banner');
  banner.hidden = true;
  banner.textContent = '';
}

function connectionError(error) {
  if (error && error.status === 401) {
    return 'Keine Verbindung: Anmeldung fehlt (401). Bitte Benutzername und Passwort eingeben.';
  }
  if (error && error.status === 403) {
    return 'Zugriff verweigert (403): ' + (error.message || 'unbekannt');
  }
  return 'Keine Verbindung zum Agenten. Läuft er noch? (' +
    (error && error.message ? error.message : 'Netzwerkfehler') + ')';
}

/* ---------------- Einrichtung ---------------- */

let setupStep = 1;

function showSetup(step) {
  setupStep = step;
  for (let i = 1; i <= 3; i++) {
    $('wiz' + i).hidden = i !== step;
    $('step' + i).className = i <= step ? 'on' : '';
  }
  $('btnSetupBack').hidden = step <= 1;
  $('btnSetupNext').textContent = step === 3 ? 'Fertig' : (step === 2 ? 'Einrichten' : 'Weiter');
  $('setupTitle').textContent = step === 3
    ? 'Mit dem Handy verbinden'
    : 'PC einrichten · Schritt ' + step + ' von 2';
  $('setupMsg').textContent = '';
}

function failSetup(message) {
  $('setupMsg').textContent = message;
}

async function setupNext() {
  if (setupStep === 1) {
    const name = $('setupName').value.trim();
    if (!name) return failSetup('Bitte einen PC-Namen angeben.');
    if ($('setupWol').checked) {
      const mac = $('setupMac').value.trim();
      if (!/^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$/.test(mac)) {
        return failSetup('MAC-Adresse ungültig (Format AA:BB:CC:DD:EE:FF).');
      }
    }
    showSetup(2);
    return;
  }

  if (setupStep === 2) {
    const user = $('setupUser').value.trim();
    const pass = $('setupPass').value;
    const pass2 = $('setupPass2').value;
    if (!user) return failSetup('Bitte einen Benutzernamen angeben.');
    if (pass.length < 8) return failSetup('Das Passwort braucht mindestens 8 Zeichen.');
    if (pass !== pass2) return failSetup('Die Passwörter stimmen nicht überein.');

    $('btnSetupNext').disabled = true;
    try {
      await api('/device-setup', {
        method: 'POST',
        body: JSON.stringify({
          name: $('setupName').value.trim(),
          wol_enabled: $('setupWol').checked,
          mac: $('setupWol').checked ? $('setupMac').value.trim() : '',
        }),
      });
      await api('/setup', {
        method: 'POST',
        body: JSON.stringify({ auth_mode: 'basic', user: user, password: pass }),
      });
      await showQrStep();
    } catch (error) {
      failSetup(connectionError(error));
    } finally {
      $('btnSetupNext').disabled = false;
    }
    return;
  }

  window.location.reload();
}

async function showQrStep() {
  showSetup(3);
  try {
    const info = await api('/pairing-info');
    if (!info.ready) {
      $('setupAddress').textContent = info.message || 'Keine LAN-Adresse erkannt.';
      $('setupQr').hidden = true;
      return;
    }
    $('setupAddress').textContent = info.name + ' · http://' + info.host + ':' + info.port;
    $('setupQr').src = '/pairing-qr.svg?ts=' + Date.now();
    $('setupQr').hidden = false;
  } catch (error) {
    $('setupAddress').textContent = 'QR-Code konnte nicht geladen werden.';
  }
}

async function findAdapters() {
  const field = $('adapterField');
  const select = $('setupAdapter');
  select.innerHTML = '<option value="">Suche …</option>';
  field.hidden = false;
  try {
    const list = await api('/network-adapters');
    if (!Array.isArray(list) || !list.length) {
      select.innerHTML = '<option value="">Keine Adapter gefunden</option>';
      return;
    }
    select.innerHTML = '<option value="">Adapter wählen …</option>' + list.map((item) =>
      '<option value="' + esc(item.mac) + '">' + esc(item.name) + ' · ' + esc(item.ip) +
      (item.is_default ? ' (Standard)' : '') + '</option>'
    ).join('');
  } catch (error) {
    select.innerHTML = '<option value="">Suche fehlgeschlagen</option>';
  }
}

/* ---------------- Darstellung ---------------- */

function mount() {
  const plans = $('plans');
  plans.innerHTML = Object.keys(PLANS).map((key) =>
    '<button type="button" class="plan" data-plan="' + key + '">' + PLANS[key] + '</button>'
  ).join('');
  plans.querySelectorAll('button[data-plan]').forEach((button) => {
    button.onclick = () => runAction(() => setPlan(button.dataset.plan));
  });

  $('modePill').onclick = toggleMode;
  $('themePill').onclick = cycleTheme;
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').addEventListener) {
    window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', applyTheme);
  }
  $('btnOptimize').onclick = () => runAction(optimizeSelected);
  $('btnAuto').onclick = () => runAction(autoOptimize);
  $('btnStopGame').onclick = () => runAction(stopOptimize);
  $('btnScan').onclick = () => runAction(scanGames);
  $('gameSelect').onchange = () => {
    state.selectedGame = $('gameSelect').value;
    renderPlugin();
  };

  $('btnSetupNext').onclick = setupNext;
  $('btnSetupBack').onclick = () => showSetup(setupStep - 1);
  $('btnAdapters').onclick = findAdapters;
  $('setupWol').onchange = () => { $('wolFields').hidden = !$('setupWol').checked; };
  $('setupAdapter').onchange = () => {
    const mac = $('setupAdapter').value;
    if (mac) $('setupMac').value = mac;
  };

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopPolling();
    else { tick(); startPolling(); }
  });
}

let statusSignature = '';

function render() {
  const status = state.status;
  const connected = state.connected;

  $('connPill').className = 'pill ' + (connected ? 'ok' : 'off');
  $('connText').textContent = connected ? '● Online' : '○ Keine Verbindung';

  const name = (status && status.device_name) || (state.pairing && state.pairing.name) ||
    (status && status.host) || 'PC';
  $('deviceName').textContent = name;

  const modePill = $('modePill');
  modePill.textContent = state.simulate ? 'SIMULATION' : 'ECHT';
  modePill.className = 'pill sim' + (state.simulate ? '' : ' echt');

  $('statusBig').textContent = connected ? 'Online' : 'Unbekannt';
  $('statusSub').textContent = connected
    ? 'Agent läuft · Plan: ' + (PLANS[status.power_plan] || status.power_plan || 'unbekannt')
    : 'Kein Kontakt zum Agenten';
  $('statusGlow').style.background = connected ? 'var(--grn)' : 'var(--red)';

  $('planName').textContent =
    (status && (PLANS[status.power_plan] || status.power_plan)) || '–';
  document.querySelectorAll('#plans button[data-plan]').forEach((button) => {
    button.classList.toggle('on', !!status && button.dataset.plan === status.power_plan);
  });

  renderStatusActions();
  renderEvents();
  renderMetrics();
  renderTimer();
  renderPlugin();
}

function renderStatusActions() {
  const pending = state.status ? (state.status.pending_shutdown || 0) : 0;
  const wol = !!(state.status && state.status.wol_enabled);
  const signature = (state.connected ? 'on' : 'off') + '|' + (pending > 0 ? 'pending' : 'idle') + '|' + (wol ? 'wol' : '-');
  if (signature === statusSignature) return;
  statusSignature = signature;

  const box = $('statusActions');
  if (!state.connected) {
    box.innerHTML = '<button type="button" class="ghost" data-act="retry">Erneut versuchen</button>';
  } else {
    box.innerHTML =
      '<button type="button" class="stop" data-act="shutdown">Herunterfahren</button>' +
      '<button type="button" class="ghost" data-act="hibernate">Ruhezustand</button>' +
      (pending > 0 ? '<button type="button" class="ghost" data-act="abort">Abbrechen</button>' : '') +
      '<button type="button" class="ghost" data-act="wake"' +
      (wol ? '' : ' disabled title="Wake-on-LAN ist nicht eingerichtet"') +
      '>Einschalten</button>';
  }

  box.querySelectorAll('button[data-act]').forEach((button) => {
    button.onclick = () => {
      const act = button.dataset.act;
      if (act === 'retry') { tick(); return; }
      if (act === 'shutdown') runAction(() => shutdownNow(60));
      if (act === 'hibernate') runAction(hibernate);
      if (act === 'abort') runAction(abortShutdown);
      if (act === 'wake') runAction(wake);
    };
  });
}

let eventsSignature = '';

function renderEvents() {
  const signature = state.events.map((event) => event.time + event.action).join('|');
  if (signature === eventsSignature) return;
  eventsSignature = signature;

  const box = $('events');
  if (!state.events.length) {
    box.textContent = 'Noch keine Ereignisse.';
    return;
  }
  box.innerHTML = state.events.slice(0, 12).map((event) =>
    '<div>' + esc(event.time.slice(11)) + ' · <strong>' + esc(event.action) + '</strong> · ' +
    esc(event.detail) + (event.ok ? '' : ' <span style="color:var(--red)">FEHLER</span>') + '</div>'
  ).join('');
}

function sparkPoints(values) {
  if (values.length < 2) return '';
  const step = 200 / (values.length - 1);
  return values.map((value, index) => {
    const x = (index * step).toFixed(1);
    const y = (60 - Math.max(0, Math.min(100, value)) * 0.56).toFixed(1);
    return x + ',' + y;
  }).join(' ');
}

function renderSpark(id, values, color) {
  const svg = $(id);
  if (!svg) return;
  if (values.length < 2) {
    svg.innerHTML = '';
    return;
  }
  svg.innerHTML = '<polyline points="' + sparkPoints(values) + '" style="stroke:' + color + '"/>';
}

function renderMetrics() {
  const metrics = state.metrics;
  const cpu = metrics && typeof metrics.cpu_percent === 'number' ? metrics.cpu_percent : null;
  if (cpu === null) {
    $('cpuVal').textContent = '–';
    $('cpuSub').textContent = metrics && state.connected
      ? 'erste Messung läuft …'
      : 'keine Messwerte';
  } else {
    $('cpuVal').textContent = cpu.toFixed(1).replace('.', ',') + ' %';
    $('cpuSub').textContent = metrics.cpu_cores
      ? metrics.cpu_cores + ' logische Prozessoren'
      : 'Auslastung';
  }
  renderSpark('cpuSpark', state.cpuHistory, 'var(--teal)');

  const ram = metrics && typeof metrics.ram_percent === 'number' ? metrics.ram_percent : null;
  if (ram === null) {
    $('ramVal').textContent = '–';
    $('ramSub').textContent = 'keine Messwerte';
  } else {
    $('ramVal').textContent = ram.toFixed(1).replace('.', ',') + ' %';
    $('ramSub').textContent = metrics.ram_used_mb + ' von ' + metrics.ram_total_mb + ' MB';
  }
  renderSpark('ramSpark', state.ramHistory, 'var(--blue)');
}

function ringSvg(total, remaining) {
  const circumference = 2 * Math.PI * 40;
  const ratio = total > 0 ? Math.max(0, Math.min(1, remaining / total)) : 0;
  return '<svg class="ring" viewBox="0 0 100 100" role="img" aria-label="Verbleibende Zeit">' +
    '<circle class="track" cx="50" cy="50" r="40" fill="none" stroke-width="9"/>' +
    '<circle class="progress" cx="50" cy="50" r="40" fill="none" stroke-width="9" ' +
    'stroke-linecap="round" stroke-dasharray="' + circumference.toFixed(1) + '" stroke-dashoffset="' +
    (circumference * (1 - ratio)).toFixed(1) + '"/></svg>';
}

function renderTimer() {
  const pending = state.status ? (state.status.pending_shutdown || 0) : 0;
  if (pending === 0) state.pendingTotal = null;
  // Gesamtdauer: der Agent kennt sie (`pending_seconds`), sonst die im Browser gemerkte.
  const serverTotal = state.status && state.status.pending_seconds > 0
    ? state.status.pending_seconds
    : null;
  const total = pending > 0 ? (serverTotal || state.pendingTotal) : null;
  const signature = pending > 0 ? 'run:' + (total === null ? 'unknown' : 'known') : 'idle';
  if (signature === state.timerSignature) {
    updateTimerText();
    return;
  }
  state.timerSignature = signature;

  const box = $('timerBody');
  if (pending > 0) {
    box.innerHTML =
      (total ? ringSvg(total, pending) : '') +
      '<div class="mut" id="timerText" style="text-align:center;margin-top:8px"></div>' +
      '<div class="btns" style="margin-top:10px">' +
      '<button type="button" class="ghost" data-act="abort">Abbrechen</button></div>';
    box.querySelector('[data-act="abort"]').onclick = () => runAction(abortShutdown);
  } else {
    box.innerHTML =
      '<div class="mut">Kein Herunterfahren geplant</div>' +
      '<div class="row" style="margin-top:10px">' +
      '<input id="timerInput" type="number" min="0" max="86400" step="60" value="3600" ' +
      'aria-label="Sekunden bis zum Herunterfahren">' +
      '<button type="button" data-act="schedule" style="flex:0 0 auto">Planen</button>' +
      '</div>' +
      '<div class="btns" style="margin-top:8px">' +
      '<button type="button" class="ghost" data-act="3600">1 h</button>' +
      '<button type="button" class="ghost" data-act="7200">2 h</button></div>';

    box.querySelector('[data-act="schedule"]').onclick = () => {
      const raw = parseInt($('timerInput').value || '0', 10);
      const seconds = Math.max(0, Math.min(86400, isNaN(raw) ? 0 : raw));
      if (!seconds) return;
      runAction(() => scheduleShutdown(seconds));
    };
    box.querySelectorAll('button[data-act]').forEach((button) => {
      const value = Number(button.dataset.act);
      if (isNaN(value)) return;
      button.onclick = () => runAction(() => scheduleShutdown(value));
    });
  }
  updateTimerText();
}

function updateTimerText() {
  const pending = state.status ? (state.status.pending_shutdown || 0) : 0;
  const text = $('timerText');
  if (text) {
    text.textContent = pending > 0 ? 'Herunterfahren in ' + fmtTime(pending) : '';
  }
  const total = (state.status && state.status.pending_seconds > 0)
    ? state.status.pending_seconds
    : state.pendingTotal;
  const circle = document.querySelector('#timerBody circle.progress');
  if (circle && total) {
    const circumference = 2 * Math.PI * 40;
    const ratio = Math.max(0, Math.min(1, pending / total));
    circle.setAttribute('stroke-dashoffset', (circumference * (1 - ratio)).toFixed(1));
  }
}

function renderPlugin() {
  const plugin = state.plugin;
  const select = $('gameSelect');
  const signature = state.games.map((game) => game.exe + '|' + (game.running ? '1' : '0')).join(',') +
    '#' + state.scannedAt + '#' + state.selectedGame;

  if (signature !== state.gameSignature) {
    state.gameSignature = signature;
    const options = ['<option value="">Spiel wählen …</option>'];
    if (!state.games.length) {
      options.push('<option value="" disabled>Keine Spiele gefunden – erst scannen</option>');
    }
    state.games.forEach((game) => {
      options.push(
        '<option value="' + esc(game.exe) + '">' + esc(game.name) +
        (game.running ? ' · läuft' : '') + ' · ' + esc(game.source) + '</option>'
      );
    });
    select.innerHTML = options.join('');
    select.value = state.selectedGame;
  }

  const running = !!(plugin && plugin.running);
  $('btnOptimize').disabled = state.busy || running || !state.selectedGame;
  $('btnAuto').disabled = state.busy || running;
  $('btnStopGame').disabled = state.busy || !running;
  $('btnScan').disabled = state.busy;

  const line = $('pluginState');
  if (!plugin) {
    line.textContent = 'Plugin wird geladen …';
  } else if (!plugin.available) {
    line.textContent = 'Plugin-Datei fehlt (game-optimizer.exe neben dem Agenten).';
  } else if (running) {
    const game = plugin.game ? plugin.game.split('\\').pop() : 'ohne Spiel';
    line.innerHTML = '<span class="chip">aktiv</span> ' + esc(game) + ' · seit ' +
      fmtUptime(plugin.uptime) + (plugin.auto ? ' · automatisch' : '');
  } else {
    line.textContent = state.games.length
      ? 'Plugin ist nicht aktiv. ' + state.games.length + ' Spiele gefunden.'
      : 'Plugin ist nicht aktiv. Noch keine Spiele gescannt.';
  }
  $('pluginLog').textContent = plugin && plugin.tail ? plugin.tail.join('\n') : '';
}

/* ---------------- Aktionen ---------------- */

async function runAction(action) {
  if (state.busy) return;
  state.busy = true;
  renderPlugin();
  try {
    await action();
    clearBanner();
  } catch (error) {
    if (error && error.status && error.status !== 401) {
      showBanner('Aktion fehlgeschlagen: ' + error.message, 'warn');
    } else {
      showBanner(connectionError(error));
    }
  } finally {
    state.busy = false;
    await tick();
  }
}

async function shutdownNow(seconds) {
  if (!window.confirm('PC in ' + seconds + ' Sekunden herunterfahren?')) return;
  await api('/shutdown?delay=' + seconds, { method: 'POST' });
  state.pendingTotal = seconds;
  state.timerSignature = '';
}

async function hibernate() {
  if (!window.confirm('PC jetzt in den Ruhezustand versetzen?')) return;
  await api('/hibernate', { method: 'POST' });
}

async function abortShutdown() {
  await api('/abort', { method: 'POST' });
  state.pendingTotal = null;
  state.timerSignature = '';
}

async function scheduleShutdown(seconds) {
  if (!seconds) return;
  await api('/shutdown?delay=' + seconds, { method: 'POST' });
  state.pendingTotal = seconds;
  state.timerSignature = '';
}

async function setPlan(plan) {
  await api('/power-plan?name=' + encodeURIComponent(plan), { method: 'POST' });
}

async function toggleMode() {
  if (state.simulate) {
    if (!window.confirm('Echtzeitmodus aktivieren? Befehle werden wirklich ausgeführt!')) return;
    await api('/mode?simulate=false', { method: 'POST' });
  } else {
    await api('/mode?simulate=true', { method: 'POST' });
  }
}

async function wake() {
  // Ohne MAC-Parameter: der Agent nimmt die eingerichtete Adresse aus device.json.
  await api('/wake', { method: 'POST' });
}

async function optimizeSelected() {
  const exe = state.selectedGame;
  if (!exe) return;
  if (!window.confirm('PC für dieses Spiel optimieren?\n' + exe +
    '\n\nApps aus der Kill-Liste werden geschlossen.')) return;
  await api('/plugins/game-optimizer/start?game=' + encodeURIComponent(exe), { method: 'POST' });
}

async function autoOptimize() {
  await api('/plugins/game-optimizer/start?auto=1', { method: 'POST' });
}

async function stopOptimize() {
  await api('/plugins/game-optimizer/stop', { method: 'POST' });
}

async function scanGames() {
  await api('/games/scan', { method: 'POST' });
  await refreshGames();
}

/* ---------------- Daten holen ---------------- */

async function refreshMetrics() {
  if (!state.connected) {
    state.metrics = null;
    return;
  }
  try {
    const data = await api('/metrics');
    state.metrics = data;
    if (typeof data.cpu_percent === 'number') {
      state.cpuHistory.push(data.cpu_percent);
      if (state.cpuHistory.length > 30) state.cpuHistory.shift();
    }
    if (typeof data.ram_percent === 'number') {
      state.ramHistory.push(data.ram_percent);
      if (state.ramHistory.length > 30) state.ramHistory.shift();
    }
  } catch (error) {
    state.metrics = null;
  }
}

async function refreshPlugin() {
  if (!state.connected) {
    state.plugin = null;
    return;
  }
  try {
    const data = await api('/plugins');
    state.plugin = (data.plugins || []).find((item) => item.id === 'game-optimizer') || null;
  } catch (error) {
    // Anzeige bleibt stehen; der nächste Durchlauf versucht es erneut.
  }
}

async function refreshGames() {
  if (!state.connected) return;
  try {
    const data = await api('/games');
    state.games = data.games || [];
    state.scannedAt = data.scanned_at || null;
  } catch (error) {
    // egal – Liste bleibt
  }
}

async function refreshLog() {
  if (!state.connected) return;
  try {
    const text = await api('/log?lines=200');
    $('fileLog').textContent = (text || '').trim() || 'noch keine Einträge';
  } catch (error) {
    $('fileLog').textContent = 'Log konnte nicht geladen werden.';
  }
}

/* ---------------- Polling (pausiert im Hintergrund) ---------------- */

let pollTimer = null;

function startPolling() {
  if (!pollTimer) pollTimer = window.setInterval(tick, POLL_MS);
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function tick() {
  if (document.hidden) return;

  try {
    const status = await api('/status');
    state.status = status;
    state.simulate = !!status.simulate;
    state.connected = true;
  } catch (error) {
    state.connected = false;
    state.status = null;
    state.metrics = null;
    showBanner(connectionError(error));
  }

  if (state.connected) {
    await refreshMetrics();
    try {
      state.events = (await api('/events')).events || [];
    } catch (error) {
      // Ereignisse sind nicht kritisch
    }
    state.ticks += 1;
    if (state.ticks % 2 === 0) await refreshPlugin();
    if (state.ticks % 5 === 0) {
      await refreshGames();
      await refreshLog();
    }
  }

  render();
}

/* ---------------- Start ---------------- */

async function boot() {
  applyTheme();
  mount();

  try {
    const setup = await api('/setup-status');
    if (setup && setup.required) {
      $('setup').hidden = false;
      showSetup(1);
      return;
    }
  } catch (error) {
    // Agent nicht erreichbar: Dashboard zeigen, Banner erklärt es
  }

  $('app').hidden = false;
  try {
    state.pairing = await api('/pairing-info');
  } catch (error) {
    state.pairing = null;
  }
  await tick();
  startPolling();
}

document.addEventListener('DOMContentLoaded', boot);
