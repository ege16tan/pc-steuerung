'use strict';

// PC Steuerung – Dashboard (Phase 4)
// Alle schreibenden Aufrufe tragen den CSRF-Header: localhost verlangt keinen
// Login, deshalb muss der Agent fremde POSTs ablehnen können.
//
// Phase 3: Live-Updates per SSE (`/events/stream`), Geräteliste im Browser
// (localStorage, fremde Agenten brauchen `PC_AGENT_CORS`) und Rollen
// (`admin` darf schalten, `viewer` sieht nur zu).
// Phase 4: Routinen (`/routines`, Uhrzeit oder Knopf) und ein eigener
// Bestätigungsdialog statt `window.confirm()`.

const CSRF_HEADER = 'X-PC-Steuerung';
const POLL_MS = 2000;
const SLOW_MS = 5000;
const DEVICE_PING_MS = 15000;
const LIVE_TIMEOUT_MS = 8000;
const DEVICES_KEY = 'pcDevices';
const ACTIVE_DEVICE_KEY = 'pcActiveDevice';

const $ = (id) => document.getElementById(id);
const PLANS = { high: 'Höchstleistung', balanced: 'Ausbalanciert', saver: 'Energiesparen' };

const state = {
  base: location.origin,
  role: 'admin',
  live: false,
  status: null,
  events: [],
  games: [],
  scannedAt: null,
  plugin: null,
  plugins: [],
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
  devices: [],
  deviceStatus: {},
  users: [],
  usersPrimary: null,
  corsOrigins: [],
  slowTicks: 0,
  routines: [],
  routineSignature: '',
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

// Basis-Adresse: eigener Agent oder ein Eintrag aus der Geräteliste.
function apiUrl(path) {
  return state.base.replace(/\/+$/, '') + path;
}

function normalizeBase(value) {
  let text = String(value || '').trim().replace(/\/+$/, '');
  if (!text) return '';
  if (!/^https?:\/\//i.test(text)) text = 'http://' + text;
  return text;
}

function isOwnOrigin() {
  return state.base === location.origin;
}

async function api(path, options) {
  const opts = options || {};
  const method = opts.method || 'GET';
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') headers[CSRF_HEADER] = '1';
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(apiUrl(path), {
    method,
    headers,
    body: opts.body,
    cache: 'no-store',
    // Fremde Agenten: Basic-Auth-Anmeldung des Browsers mitschicken (CORS).
    credentials: isOwnOrigin() ? 'same-origin' : 'include',
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

/* ---------------- Rolle ---------------- */

function canWrite() {
  return state.role !== 'viewer';
}

function guardWrite() {
  if (canWrite()) return true;
  showBanner('Nur Lesezugriff: die Rolle „viewer“ darf nichts schalten.', 'warn');
  return false;
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

/* ---------------- Bestätigungsdialog ---------------- */

/// Ersetzt `window.confirm()`: eigenes `<dialog>` mit Fokus, ESC und klaren
/// Knöpfen. Liefert ein Promise<boolean> (true = bestätigt).
function askConfirm(options) {
  const opts = options || {};
  const text = opts.text || 'Fortfahren?';
  const dialog = $('dialog');
  if (!dialog || typeof dialog.showModal !== 'function') {
    return Promise.resolve(window.confirm(text));
  }
  if (dialog.open) dialog.close();
  $('dlgTitle').textContent = opts.title || 'Bestätigen';
  $('dlgText').textContent = text;
  const ok = $('dlgOk');
  ok.textContent = opts.ok || 'OK';
  ok.className = opts.danger ? 'stop' : 'go';
  $('dlgCancel').textContent = opts.cancel || 'Abbrechen';
  dialog.returnValue = '';
  return new Promise((resolve) => {
    dialog.onclose = () => resolve(dialog.returnValue === 'ok');
    dialog.showModal();
  });
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
  $('pluginList').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button || !guardWrite()) return;
    const start = button.dataset.pluginStart;
    const stop = button.dataset.pluginStop;
    if (start) {
      runAction(() => api('/plugins/' + encodeURIComponent(start) + '/start', { method: 'POST' }));
    } else if (stop) {
      runAction(() => api('/plugins/' + encodeURIComponent(stop) + '/stop', { method: 'POST' }));
    }
  };
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

  $('btnAddDevice').onclick = addDevice;
  $('deviceUrl').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') { event.preventDefault(); addDevice(); }
  });
  $('btnAddUser').onclick = addUser;

  const planSelect = $('routinePlan');
  planSelect.innerHTML = Object.keys(PLANS).map((key) =>
    '<option value="' + key + '">' + PLANS[key] + '</option>').join('');
  $('routineAction').onchange = updateRoutineAction;
  document.querySelectorAll('#routineDays button').forEach((button) => {
    button.onclick = () => {
      const on = button.classList.toggle('on');
      button.setAttribute('aria-pressed', on ? 'true' : 'false');
    };
  });
  $('btnAddRoutine').onclick = addRoutine;
  updateRoutineAction();

  $('dlgOk').onclick = () => {
    const dlg = $('dialog');
    dlg.returnValue = 'ok';
    dlg.close();
  };
  $('dlgCancel').onclick = () => {
    const dlg = $('dialog');
    dlg.returnValue = 'cancel';
    dlg.close();
  };

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      stopLive();
      stopPolling();
    } else {
      startLive();
      if (!state.live) startPolling();
      tick();
    }
  });
}

let statusSignature = '';

function render() {
  const status = state.status;
  const connected = state.connected;

  $('connPill').className = 'pill ' + (connected ? 'ok' : 'off');
  $('connText').textContent = connected ? '● Online' : '○ Keine Verbindung';
  const livePill = $('livePill');
  if (livePill) livePill.hidden = !state.live;

  const name = (status && status.device_name) || (state.pairing && state.pairing.name) ||
    (status && status.host) || 'PC';
  $('deviceName').textContent = name + (isOwnOrigin() ? '' : ' · ' + state.base.replace(/^https?:\/\//, ''));

  const modePill = $('modePill');
  modePill.textContent = state.simulate ? 'SIMULATION' : 'ECHT';
  modePill.className = 'pill sim' + (state.simulate ? '' : ' echt');
  modePill.disabled = !canWrite();

  $('statusBig').textContent = connected ? 'Online' : 'Unbekannt';
  $('statusSub').textContent = connected
    ? 'Agent läuft · Plan: ' + (PLANS[status.power_plan] || status.power_plan || 'unbekannt') +
      (canWrite() ? '' : ' · nur Lesen')
    : 'Kein Kontakt zum Agenten';
  $('statusGlow').style.background = connected ? 'var(--grn)' : 'var(--red)';

  $('planName').textContent =
    (status && (PLANS[status.power_plan] || status.power_plan)) || '–';
  document.querySelectorAll('#plans button[data-plan]').forEach((button) => {
    button.classList.toggle('on', !!status && button.dataset.plan === status.power_plan);
    button.disabled = !canWrite();
  });

  renderStatusActions();
  renderEvents();
  renderMetrics();
  renderTimer();
  renderPlugin();
  renderDevices();
  renderUsers();
  renderRoutines();
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
    if (button.dataset.act !== 'retry') button.disabled = !canWrite();
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
    box.querySelector('[data-act="abort"]').disabled = !canWrite();
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

    box.querySelector('[data-act="schedule"]').disabled = !canWrite();
    box.querySelector('[data-act="schedule"]').onclick = () => {
      const raw = parseInt($('timerInput').value || '0', 10);
      const seconds = Math.max(0, Math.min(86400, isNaN(raw) ? 0 : raw));
      if (!seconds) return;
      runAction(() => scheduleShutdown(seconds));
    };
    box.querySelectorAll('button[data-act]').forEach((button) => {
      const value = Number(button.dataset.act);
      if (isNaN(value)) return;
      button.disabled = !canWrite();
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
  select.disabled = state.busy || !canWrite();
  $('btnOptimize').disabled = state.busy || running || !state.selectedGame || !canWrite();
  $('btnAuto').disabled = state.busy || running || !canWrite();
  $('btnStopGame').disabled = state.busy || !running || !canWrite();
  $('btnScan').disabled = state.busy || !canWrite();

  const line = $('pluginState');
  if (!plugin) {
    line.textContent = 'Plugin wird geladen …';
  } else if (!plugin.available) {
    line.textContent = 'Plugin ist nicht installiert.';
  } else if (running) {
    const game = plugin.game ? plugin.game.split('\\').pop() : 'ohne Spiel';
    line.innerHTML = '<span class="chip">aktiv</span> ' + esc(game) + ' · seit ' +
      fmtUptime(plugin.uptime) + (plugin.auto ? ' · automatisch' : '') +
      (plugin.status ? ' · ' + esc(plugin.status) : '');
  } else {
    line.textContent = state.games.length
      ? 'Plugin ist nicht aktiv. ' + state.games.length + ' Spiele gefunden.'
      : 'Plugin ist nicht aktiv. Noch keine Spiele gescannt.';
  }
  $('pluginLog').textContent = plugin && plugin.tail ? plugin.tail.join('\n') : '';
  renderPluginList();
}

function pluginStateText(item) {
  if (!item.available) return 'fehlt';
  return item.running ? 'aktiv' : 'bereit';
}

/// Liste aller Plugins (Game Optimization hat oben eine eigene Kachel).
function renderPluginList() {
  const host = $('pluginList');
  if (!host) return;
  const list = state.plugins || [];
  if (!list.length) {
    host.innerHTML = '<div class="mut">Keine Plugins installiert.</div>';
    return;
  }
  host.innerHTML = list.map((item) => {
    const running = !!item.running;
    const kind = item.kind === 'javascript' ? 'JS' : 'EXE';
    const tail = (item.tail || []).slice(-3);
    return '<div class="pluginRow">' +
      '<div class="row" style="gap:8px">' +
      '<b>' + esc(item.name || item.id) + '</b>' +
      '<span class="chip">' + pluginStateText(item) + '</span>' +
      '<span class="mut">' + kind + ' · v' + esc(item.version || '1') + '</span>' +
      (running && item.status ? '<span class="mut">' + esc(item.status) + '</span>' : '') +
      '</div>' +
      (item.description ? '<div class="mut">' + esc(item.description) + '</div>' : '') +
      '<div class="btns" style="margin-top:6px">' +
      (running
        ? '<button type="button" class="ghost" data-plugin-stop="' + esc(item.id) + '">Beenden</button>'
        : '<button type="button" class="go" data-plugin-start="' + esc(item.id) + '"' +
        (canWrite() && item.available ? '' : ' disabled') + '>Starten</button>') +
      '</div>' +
      (tail.length ? '<div class="gamestate">' + tail.map(esc).join('<br>') + '</div>' : '') +
      '</div>';
  }).join('');
}

/* ---------------- Aktionen ---------------- */

async function runAction(action) {
  if (state.busy) return;
  if (!guardWrite()) return;
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
  const ok = await askConfirm({
    title: 'Herunterfahren',
    text: 'PC in ' + seconds + ' Sekunden herunterfahren?',
    ok: 'Herunterfahren',
    danger: true,
  });
  if (!ok) return;
  await api('/shutdown?delay=' + seconds, { method: 'POST' });
  state.pendingTotal = seconds;
  state.timerSignature = '';
}

async function hibernate() {
  const ok = await askConfirm({
    title: 'Ruhezustand',
    text: 'PC jetzt in den Ruhezustand versetzen?',
    ok: 'Ruhezustand',
    danger: true,
  });
  if (!ok) return;
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
    const ok = await askConfirm({
      title: 'Echtzeitmodus',
      text: 'Echtzeitmodus aktivieren? Befehle werden wirklich ausgeführt!',
      ok: 'Echt aktivieren',
      danger: true,
    });
    if (!ok) return;
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
  const ok = await askConfirm({
    title: 'Spiel optimieren',
    text: 'PC für dieses Spiel optimieren?\n' + exe +
      '\n\nApps aus der Kill-Liste werden geschlossen.',
    ok: 'Optimieren',
    danger: true,
  });
  if (!ok) return;
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

/// CPU-/RAM-Verlauf fortschreiben (Polling und SSE-Rundruf nutzen dieselbe Liste).
function recordMetrics(data) {
  if (!data) return;
  if (typeof data.cpu_percent === 'number') {
    state.cpuHistory.push(data.cpu_percent);
    if (state.cpuHistory.length > 30) state.cpuHistory.shift();
  }
  if (typeof data.ram_percent === 'number') {
    state.ramHistory.push(data.ram_percent);
    if (state.ramHistory.length > 30) state.ramHistory.shift();
  }
}

async function refreshMetrics() {
  if (!state.connected) {
    state.metrics = null;
    return;
  }
  try {
    const data = await api('/metrics');
    state.metrics = data;
    recordMetrics(data);
  } catch (error) {
    state.metrics = null;
  }
}

async function refreshPlugin() {
  if (!state.connected) {
    state.plugin = null;
    state.plugins = [];
    return;
  }
  try {
    const data = await api('/plugins');
    state.plugins = data.plugins || [];
    state.plugin = state.plugins.find((item) => item.id === 'game-optimizer') || null;
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

/* ---------------- Polling (Fallback, pausiert im Hintergrund) ---------------- */

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
    // Rolle kommt nur bei /status mit (SSE-Rundrufe sind rollen-neutral).
    state.role = status.role === 'viewer' ? 'viewer' : 'admin';
    if (Array.isArray(status.cors_origins)) state.corsOrigins = status.cors_origins;
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
  }

  render();
}

/* ---------------- Langsame Abfragen (Spiele, Log, Benutzer) ---------------- */

let slowTimer = null;

function startSlow() {
  if (!slowTimer) slowTimer = window.setInterval(slowTick, SLOW_MS);
}

function stopSlow() {
  if (slowTimer) {
    window.clearInterval(slowTimer);
    slowTimer = null;
  }
}

async function slowTick() {
  if (document.hidden || !state.connected) return;
  state.slowTicks += 1;
  await refreshPlugin();
  if (state.slowTicks % 2 === 0) {
    await refreshGames();
    await refreshUsers();
    await refreshRoutines();
  }
  if (state.slowTicks % 3 === 0) {
    await refreshLog();
    await refreshDeviceStatus();
  }
  render();
}

/* ---------------- Live-Updates (SSE) ---------------- */

let eventSource = null;
let liveWatchdog = null;
let lastLiveAt = 0;

function stopLive() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  if (liveWatchdog) {
    window.clearInterval(liveWatchdog);
    liveWatchdog = null;
  }
  state.live = false;
}

function applyState(payload) {
  if (!payload) return;
  const wasOffline = !state.connected;
  if (payload.status) {
    state.status = payload.status;
    state.simulate = !!payload.status.simulate;
    if (Array.isArray(payload.status.cors_origins)) state.corsOrigins = payload.status.cors_origins;
    state.connected = true;
  }
  if (payload.metrics) {
    state.metrics = payload.metrics;
    recordMetrics(payload.metrics);
  }
  if (Array.isArray(payload.events)) state.events = payload.events;
  // Nur Verbindungsfehler verschwinden automatisch; Warnungen bleiben stehen.
  if (wasOffline && state.connected) clearBanner();
  render();
}

function pushEvent(entry) {
  if (!entry || !entry.time) return;
  const exists = state.events.some((event) => event.time === entry.time &&
    event.action === entry.action && event.detail === entry.detail);
  if (!exists) state.events = [entry].concat(state.events).slice(0, 50);
  renderEvents();
  // Routinen sofort nachziehen, damit „letzter Lauf“ ohne 10-s-Takt stimmt.
  if (entry.action === 'routine') {
    refreshRoutines().then(renderRoutines);
  }
}

function startLive() {
  stopLive();
  // SSE nur zum eigenen Agenten: EventSource kann bei fremden Origins keine
  // Basic-Auth-Anmeldung mitschicken – dort bleibt das Polling.
  if (!isOwnOrigin() || typeof EventSource === 'undefined') {
    startPolling();
    return;
  }
  const source = new EventSource(apiUrl('/events/stream'));
  eventSource = source;

  source.addEventListener('state', (event) => {
    lastLiveAt = Date.now();
    state.live = true;
    stopPolling();
    try {
      applyState(JSON.parse(event.data));
    } catch (error) {
      // kaputte Nachricht: ignorieren, naechste kommt in 2 s
    }
  });

  source.addEventListener('event', (event) => {
    try {
      pushEvent(JSON.parse(event.data));
    } catch (error) {
      // egal
    }
  });

  source.onerror = () => {
    // EventSource verbindet selbst neu; bis dahin pollt die Oberflaeche.
    state.live = false;
    render();
    startPolling();
  };

  liveWatchdog = window.setInterval(() => {
    if (!state.live) return;
    if (Date.now() - lastLiveAt > LIVE_TIMEOUT_MS) {
      stopLive();
      startLive();
    }
  }, 4000);
}

/* ---------------- Geräteliste (localStorage) ---------------- */

function storedBase() {
  const stored = normalizeBase(localStorage.getItem(ACTIVE_DEVICE_KEY) || '');
  return stored || location.origin;
}

function loadDevices() {
  try {
    const list = JSON.parse(localStorage.getItem(DEVICES_KEY) || '[]');
    state.devices = Array.isArray(list)
      ? list.filter((device) => device && typeof device.url === 'string')
      : [];
  } catch (error) {
    state.devices = [];
  }
}

function saveDevices() {
  localStorage.setItem(DEVICES_KEY, JSON.stringify(state.devices));
}

function deviceEntries() {
  const entries = [{ url: location.origin, label: 'Dieser PC', own: true }];
  state.devices.forEach((device) => {
    const url = normalizeBase(device.url);
    if (!url || url === location.origin) return;
    entries.push({
      url,
      label: device.label || url.replace(/^https?:\/\//, ''),
      own: false,
    });
  });
  return entries;
}

function renderDevices() {
  const box = $('deviceList');
  if (!box) return;
  const entries = deviceEntries();
  const signature = entries.map((entry) => entry.url + entry.label).join('|') +
    '#' + state.base + '#' + state.connected + '#' + JSON.stringify(state.deviceStatus);

  if (signature !== state.deviceSignature) {
    state.deviceSignature = signature;
    box.innerHTML = entries.map((entry) => {
      const active = entry.url === state.base;
      const known = entry.url === state.base ? state.connected : state.deviceStatus[entry.url];
      const dot = known === true ? 'ok' : (known === false ? 'off' : '');
      const label = entry.url === state.base && state.status && state.status.device_name
        ? state.status.device_name
        : entry.label;
      return '<div class="devrow" data-url="' + esc(entry.url) + '">' +
        '<span class="dot ' + dot + '" aria-hidden="true"></span>' +
        '<div class="grow">' + esc(label) +
        '<div class="sub">' + esc(entry.url.replace(/^https?:\/\//, '')) + '</div></div>' +
        (active ? '<span class="chip">aktiv</span>'
          : '<button type="button" data-act="use">Wechseln</button>') +
        (!entry.own && !active ? '<button type="button" data-act="remove">Entfernen</button>' : '') +
        '</div>';
    }).join('');
    box.querySelectorAll('.devrow').forEach((row) => {
      const use = row.querySelector('[data-act="use"]');
      if (use) use.onclick = () => switchDevice(row.dataset.url);
      const remove = row.querySelector('[data-act="remove"]');
      if (remove) remove.onclick = () => removeDevice(row.dataset.url);
    });
  }

  const hint = $('deviceHint');
  if (hint) {
    hint.textContent = state.corsOrigins && state.corsOrigins.length
      ? 'Dieser Agent erlaubt fremde Seiten von: ' + state.corsOrigins.join(', ')
      : 'Dieser Agent erlaubt keine fremden Seiten (PC_AGENT_CORS ist leer).';
  }
}

async function checkDevice(url) {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = controller ? window.setTimeout(() => controller.abort(), 2500) : null;
  try {
    const response = await fetch(url.replace(/\/+$/, '') + '/status', {
      cache: 'no-store',
      credentials: url === location.origin ? 'same-origin' : 'include',
      signal: controller ? controller.signal : undefined,
    });
    return response.ok;
  } catch (error) {
    return false;
  } finally {
    if (timer) window.clearTimeout(timer);
  }
}

async function refreshDeviceStatus() {
  const entries = deviceEntries().filter((entry) => entry.url !== state.base);
  for (const entry of entries) {
    state.deviceStatus[entry.url] = await checkDevice(entry.url);
  }
  state.deviceSignature = '';
  renderDevices();
}

function addDevice() {
  const url = normalizeBase($('deviceUrl').value);
  const label = $('deviceLabel').value.trim();
  if (!url) {
    $('deviceHint').textContent = 'Bitte eine Adresse angeben, z. B. http://192.168.1.20:8000';
    return;
  }
  if (url !== location.origin) {
    const existing = state.devices.find((device) => normalizeBase(device.url) === url);
    if (existing) existing.label = label || existing.label || '';
    else state.devices.push({ url, label });
    saveDevices();
  }
  $('deviceUrl').value = '';
  $('deviceLabel').value = '';
  state.deviceSignature = '';
  refreshDeviceStatus();
}

function removeDevice(url) {
  state.devices = state.devices.filter((device) => normalizeBase(device.url) !== url);
  saveDevices();
  delete state.deviceStatus[url];
  state.deviceSignature = '';
  if (state.base === url) {
    switchDevice(location.origin);
    return;
  }
  renderDevices();
}

function resetData() {
  stopLive();
  stopPolling();
  state.status = null;
  state.connected = false;
  state.live = false;
  state.events = [];
  state.metrics = null;
  state.cpuHistory = [];
  state.ramHistory = [];
  state.plugin = null;
  state.games = [];
  state.selectedGame = '';
  state.gameSignature = '';
  state.timerSignature = '';
  state.pendingTotal = null;
  state.pairing = null;
  state.role = 'admin';
  state.users = [];
  state.usersPrimary = null;
  state.routines = [];
  state.routineSignature = '';
  state.deviceSignature = '';
  statusSignature = '';
  eventsSignature = '';
  usersSignature = '';
  $('fileLog').textContent = 'Wird geladen …';
}

function switchDevice(url) {
  const base = normalizeBase(url);
  if (!base || base === state.base) return;
  state.base = base;
  localStorage.setItem(ACTIVE_DEVICE_KEY, base);
  resetData();
  render();
  resume();
}

async function refreshIdentity() {
  if (!isOwnOrigin()) {
    state.pairing = null;
    return;
  }
  try {
    state.pairing = await api('/pairing-info');
  } catch (error) {
    state.pairing = null;
  }
}

async function resume() {
  await refreshIdentity();
  await tick();
  startLive();
  startSlow();
  startPolling();
}


/* ---------------- Routinen (Phase 4) ---------------- */

const DAY_LABELS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];

function triggerText(routine) {
  const trigger = routine.trigger || {};
  if (trigger.type !== 'time') return 'Nur manuell';
  const days = Array.isArray(trigger.days) ? trigger.days : [];
  let dayText = 'Täglich';
  if (days.length && days.length < 7) {
    const workdays = days.length === 5 && days.every((day) => day <= 5);
    const weekend = days.length === 2 && days.indexOf(6) >= 0 && days.indexOf(7) >= 0;
    dayText = workdays ? 'Mo–Fr'
      : (weekend ? 'Sa–So' : days.map((day) => DAY_LABELS[day - 1]).join(', '));
  }
  return dayText + ' · ' + (trigger.at || '--:--');
}

function nextText(routine) {
  if (!routine.enabled) return 'aus';
  if (routine.next_in === null || routine.next_in === undefined) return 'nur manuell';
  return routine.next_in <= 1 ? 'jetzt' : 'in ' + fmtTime(routine.next_in);
}

function actionListText(routine) {
  const actions = Array.isArray(routine.actions) ? routine.actions : [];
  return actions.map((action) => {
    if (action.type === 'shutdown') return 'Herunterfahren in ' + action.delay + ' s';
    if (action.type === 'hibernate') return 'Ruhezustand';
    if (action.type === 'power_plan') return 'Energieplan ' + (PLANS[action.plan] || action.plan);
    return action.type;
  }).join(' · ');
}

async function refreshRoutines() {
  if (!state.connected) {
    state.routines = [];
    return;
  }
  try {
    const data = await api('/routines');
    state.routines = Array.isArray(data.routines) ? data.routines : [];
  } catch (error) {
    // Kachel behält den letzten Stand (z. B. alter Agent ohne /routines).
  }
}

function renderRoutines() {
  const box = $('routineList');
  if (!box) return;
  $('routineForm').hidden = !canWrite();
  $('routineHint').textContent = canWrite()
    ? 'Zeiten gelten lokal für diesen PC. Höchstens 20 Routinen, drei Aktionen je Routine.'
    : 'Diese Sitzung hat nur Lesezugriff.';

  const list = state.routines || [];
  const signature = JSON.stringify(list) + '#' + canWrite();
  if (signature === state.routineSignature) return;
  state.routineSignature = signature;

  if (!list.length) {
    box.innerHTML = '<div class="mut">Noch keine Routinen.</div>';
    return;
  }

  box.innerHTML = list.map((routine) =>
    '<div class="devrow" data-id="' + esc(routine.id) + '">' +
    '<span class="dot ' + (routine.enabled ? 'ok' : '') + '" aria-hidden="true"></span>' +
    '<div class="grow">' + esc(routine.name) +
    '<div class="sub">' + esc(triggerText(routine)) + ' · ' + esc(nextText(routine)) + '</div></div>' +
    (canWrite()
      ? '<button type="button" data-act="toggle">' + (routine.enabled ? 'Aus' : 'An') + '</button>' +
        '<button type="button" data-act="run">Jetzt</button>' +
        '<button type="button" data-act="remove">Entfernen</button>'
      : '<span class="chip">' + (routine.enabled ? 'an' : 'aus') + '</span>') +
    '</div>'
  ).join('');

  box.querySelectorAll('.devrow').forEach((row) => {
    const routine = list.find((entry) => entry.id === row.dataset.id);
    if (!routine) return;
    const toggle = row.querySelector('[data-act="toggle"]');
    if (toggle) toggle.onclick = () => runAction(() => toggleRoutine(routine));
    const run = row.querySelector('[data-act="run"]');
    if (run) run.onclick = () => runAction(() => runRoutine(routine));
    const remove = row.querySelector('[data-act="remove"]');
    if (remove) remove.onclick = () => runAction(() => removeRoutine(routine));
  });
}

async function toggleRoutine(routine) {
  const body = Object.assign({}, routine, { enabled: !routine.enabled });
  await api('/routines', { method: 'POST', body: JSON.stringify(body) });
  await refreshRoutines();
}

async function runRoutine(routine) {
  const ok = await askConfirm({
    title: 'Routine ausführen',
    text: '„' + routine.name + '“ jetzt ausführen?\n' + actionListText(routine),
    ok: 'Ausführen',
    danger: true,
  });
  if (!ok) return;
  await api('/routines/' + encodeURIComponent(routine.id) + '/run', { method: 'POST' });
  await refreshRoutines();
}

async function removeRoutine(routine) {
  const ok = await askConfirm({
    title: 'Routine entfernen',
    text: '„' + routine.name + '“ wirklich entfernen?',
    ok: 'Entfernen',
    danger: true,
  });
  if (!ok) return;
  await api('/routines/' + encodeURIComponent(routine.id), { method: 'DELETE' });
  await refreshRoutines();
}

function selectedRoutineDays() {
  return Array.prototype.slice.call(document.querySelectorAll('#routineDays button.on'))
    .map((button) => Number(button.dataset.day));
}

function updateRoutineAction() {
  const kind = $('routineAction').value;
  $('routineDelay').hidden = kind !== 'shutdown';
  $('routinePlan').hidden = kind !== 'power_plan';
}

async function addRoutine() {
  if (!guardWrite()) return;
  const name = $('routineName').value.trim();
  const at = $('routineTime').value;
  if (!name) {
    showBanner('Bitte einen Namen für die Routine angeben.', 'warn');
    return;
  }
  if (!/^\d{2}:\d{2}$/.test(at)) {
    showBanner('Bitte eine Uhrzeit wählen.', 'warn');
    return;
  }
  const kind = $('routineAction').value;
  let action;
  if (kind === 'shutdown') {
    const raw = parseInt($('routineDelay').value || '60', 10);
    const delay = Math.max(0, Math.min(86400, isNaN(raw) ? 60 : raw));
    action = { type: 'shutdown', delay };
  } else if (kind === 'hibernate') {
    action = { type: 'hibernate' };
  } else {
    action = { type: 'power_plan', plan: $('routinePlan').value };
  }
  const body = {
    name,
    enabled: true,
    trigger: { type: 'time', at, days: selectedRoutineDays() },
    actions: [action],
  };

  $('btnAddRoutine').disabled = true;
  try {
    await api('/routines', { method: 'POST', body: JSON.stringify(body) });
    $('routineName').value = '';
    document.querySelectorAll('#routineDays button.on').forEach((button) => {
      button.classList.remove('on');
      button.setAttribute('aria-pressed', 'false');
    });
    clearBanner();
    await refreshRoutines();
  } catch (error) {
    showBanner('Routine konnte nicht gespeichert werden: ' + error.message, 'warn');
  } finally {
    $('btnAddRoutine').disabled = false;
    render();
  }
}

/* ---------------- Benutzer & Rechte ---------------- */

let usersSignature = '';

async function refreshUsers() {
  if (!state.connected || !canWrite()) {
    state.users = [];
    state.usersPrimary = null;
    return;
  }
  try {
    const data = await api('/users');
    state.users = data.users || [];
    state.usersPrimary = data.primary || null;
    if (Array.isArray(data.cors_origins)) state.corsOrigins = data.cors_origins;
  } catch (error) {
    // Kein Admin / alter Agent: Kachel bleibt bei dem, was sie hat.
  }
}

function renderUsers() {
  const box = $('userList');
  if (!box) return;
  const form = $('userForm');
  form.hidden = !canWrite();

  const list = [];
  if (state.usersPrimary) {
    list.push({ user: state.usersPrimary.user, role: state.usersPrimary.role, primary: true });
  }
  state.users.forEach((entry) => list.push({ user: entry.user, role: entry.role, primary: false }));

  const signature = JSON.stringify(list) + '#' + canWrite();
  if (signature !== usersSignature) {
    usersSignature = signature;
    box.innerHTML = list.length
      ? list.map((entry) =>
        '<div class="devrow">' +
        '<div class="grow">' + esc(entry.user) +
        (entry.primary ? ' <span class="sub">(Hauptbenutzer)</span>' : '') + '</div>' +
        '<span class="chip">' + esc(entry.role) + '</span>' +
        (!entry.primary && canWrite()
          ? '<button type="button" data-user="' + esc(entry.user) + '">Entfernen</button>'
          : '') +
        '</div>'
      ).join('')
      : '<div class="mut">Noch keine Zugangsdaten eingerichtet.</div>';

    box.querySelectorAll('button[data-user]').forEach((button) => {
      button.onclick = () => removeUser(button.dataset.user);
    });
  }

  $('usersHint').textContent = canWrite()
    ? 'Neue Benutzer gelten sofort, ohne Neustart.'
    : 'Diese Sitzung hat nur Lesezugriff.';
}

async function addUser() {
  if (!guardWrite()) return;
  const user = $('newUserName').value.trim();
  const password = $('newUserPass').value;
  const role = $('newUserRole').value;
  if (!user) {
    showBanner('Bitte einen Benutzernamen angeben.', 'warn');
    return;
  }
  if (password.length < 8) {
    showBanner('Das Passwort braucht mindestens 8 Zeichen.', 'warn');
    return;
  }
  $('btnAddUser').disabled = true;
  try {
    await api('/users', { method: 'POST', body: JSON.stringify({ user, password, role }) });
    $('newUserName').value = '';
    $('newUserPass').value = '';
    clearBanner();
    await refreshUsers();
  } catch (error) {
    showBanner('Benutzer konnte nicht angelegt werden: ' + error.message, 'warn');
  } finally {
    $('btnAddUser').disabled = false;
    render();
  }
}

async function removeUser(user) {
  if (!guardWrite()) return;
  const ok = await askConfirm({
    title: 'Benutzer entfernen',
    text: 'Benutzer „' + user + '“ entfernen?',
    ok: 'Entfernen',
    danger: true,
  });
  if (!ok) return;
  try {
    await api('/users/' + encodeURIComponent(user), { method: 'DELETE' });
    clearBanner();
    await refreshUsers();
  } catch (error) {
    showBanner('Benutzer konnte nicht entfernt werden: ' + error.message, 'warn');
  }
  render();
}

/* ---------------- Start ---------------- */

async function boot() {
  applyTheme();
  mount();
  loadDevices();
  state.base = storedBase();

  // Ersteinrichtung nur am eigenen PC (fremde Agenten sind schon eingerichtet).
  if (isOwnOrigin()) {
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
  }

  $('app').hidden = false;
  await refreshIdentity();
  await tick();
  await refreshRoutines();
  render();
  startLive();
  startSlow();
  startPolling();
}

document.addEventListener('DOMContentLoaded', boot);

