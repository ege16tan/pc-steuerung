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
// Systemwerte und Warnungen werden nur im jeweiligen Tab abgefragt (Regel D1).
const SENSORS_MS = 5000;
const WARNINGS_MS = 30000;
const WARN_RULES_MAX = 20;
// Plugins (Phasen B1/B2): Ausgabe nur bei offener Detailansicht, dann alle 3 s.
const PLUGIN_OUTPUT_MS = 3000;
const PLUGIN_OUTPUT_LINES = 40;
// Routinen (Phase E): 1-3 Schritte, Wartezeit vor einem Schritt hoechstens 300 s.
const ROUTINE_MAX_STEPS = 3;
const ROUTINE_WAIT_MAX = 300;
const DEVICES_KEY = 'pcDevices';
const ACTIVE_DEVICE_KEY = 'pcActiveDevice';
// Log/Verlauf (Phase W2): Aufbewahrung der Logdatei-Zeilen und Filter-Chips.
const LOG_LINES_KEY = 'pcLogLines';
const LOG_LINES_CHOICES = [200, 500, 1000];
const LOG_LINES_DEFAULT = 200;

const $ = (id) => document.getElementById(id);
const PLANS = { high: 'Höchstleistung', balanced: 'Ausbalanciert', saver: 'Energiesparen' };

const state = {
  base: location.origin,
  role: 'admin',
  live: false,
  status: null,
  steam: null,
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
  // Tab-gebundene Daten (Phase A2/C): nur abfragen, solange der Tab offen ist.
  activeTab: 'overview',
  sensors: null,
  sensorsAt: 0,
  sensorsError: '',
  sensorsSignature: '',
  // Wie oft hintereinander ein Sensor `null` lieferte (erste Messung ist normal).
  sensorNulls: { cpu: 0, ram: 0, gpu: 0, net: 0 },
  warnings: null,
  warningsError: '',
  warningsSignature: '',
  // Plugins (Phasen B1/B2/B4): Raster und die eine offene Detailansicht.
  // `pluginDetail` = null | { kind: 'plugin', id } | { kind: 'steam' } | { kind: 'game' }.
  pluginDetail: null,
  pluginTileSignature: '',
  pluginOutput: '',
  pluginOutputError: '',
  pluginSettings: null,
  pluginSettingsValues: {},
  pluginSettingsError: '',
  gameConfig: null,
  gameConfigError: '',
  gameDetailSignature: '',
  // Phase W3: Rueckmeldung im Abschnitt „Eigene Suchordner“ (leer = Standardtext)
  // und ob sie als Fehler (Ablehnung) statt als Hinweis gilt.
  scanPathHint: '',
  scanPathError: false,
  // Marktplatz (Phase W1): Katalog nur bei offenem Plugins-Tab laden, kein Takt.
  // `catalog` = null (noch nicht geladen) | [] (leer) | Einträge aus dem Katalog.
  catalog: null,
  catalogVersion: 0,
  catalogError: '',
  catalogSignature: '',
  catalogInstalling: '',
  // Routinen (Phase E): Schritte des Formulars (Rohtexte, geprueft beim Speichern),
  // aufgeklappter Verlauf und die Vorlagen aus /routine-templates.
  routineSteps: [],
  routineTemplates: [],
  routineTemplatesLoaded: false,
  expandedRoutine: '',
  // Log/Verlauf (Phase W2): Filter-Chip und Suchtext der Ereignisliste.
  logFilter: 'all',
  logQuery: '',
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
  banner.className = 'banner' + (kind === 'warn' ? ' warn' : (kind === 'ok' ? ' ok' : ''));
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

  // Plugins (Phasen B1/B2): Raster-Knoepfe oeffnen die Detailansicht im selben Tab.
  $('pluginTiles').onclick = (event) => {
    const button = event.target.closest('button[data-open]');
    if (!button) return;
    openPluginDetailFromKey(button.dataset.open);
  };
  $('btnPluginBack').onclick = closePluginDetail;
  $('btnSaveSettings').onclick = () => savePluginSettings();
  // Marktplatz (Phase W1): nur auf Knopfdruck neu laden – kein Takt.
  $('btnCatalogRefresh').onclick = () => refreshCatalog();
  // Spiel-Optimierung: Verwaltung der Spiele und der Konfiguration.
  $('btnScanDetail').onclick = () => runAction(scanGamesFromDetail);
  $('btnRefreshGames').onclick = () => refreshGameDetail();
  $('btnAddKill').onclick = () => addKillEntry();
  $('btnAddCustom').onclick = () => addCustomGame();
  $('gamesList').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.gameHide !== undefined) hideGame(button.dataset.gameHide, true);
  };
  $('gamesHidden').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.gameShow !== undefined) hideGame(button.dataset.gameShow, false);
  };
  $('killList').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.killRemove !== undefined) removeKillEntry(button.dataset.killRemove);
  };
  $('customGames').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.customRemove !== undefined) removeCustomGame(button.dataset.customRemove);
  };
  // Phase W3: eigene Suchordner und der Automatik-Schalter (beides `/game-config`).
  $('btnAddScanPath').onclick = () => addScanPath();
  $('scanPathInput').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') { event.preventDefault(); addScanPath(); }
  });
  $('scanPaths').onclick = (event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.scanRemove !== undefined) removeScanPath(button.dataset.scanRemove);
  };
  $('gameAutoSwitch').onclick = () => {
    const on = $('gameAutoSwitch').getAttribute('aria-checked') === 'true';
    setGameAuto(!on);
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

  const planSelect = $('routineCondPlan');
  planSelect.innerHTML = '<option value="">Energieplan: egal</option>' + Object.keys(PLANS).map((key) =>
    '<option value="' + key + '">' + PLANS[key] + '</option>').join('');
  $('routineTrigger').onchange = updateRoutineTriggerForm;
  document.querySelectorAll('#routineDays button').forEach((button) => {
    button.onclick = () => {
      const on = button.classList.toggle('on');
      button.setAttribute('aria-pressed', on ? 'true' : 'false');
    };
  });
  // Bedingungen: eigene Tagesgruppe (getrennt von den Tagen des Auslösers).
  document.querySelectorAll('#routineCondDays button').forEach((button) => {
    button.onclick = () => {
      const on = button.classList.toggle('on');
      button.setAttribute('aria-pressed', on ? 'true' : 'false');
    };
  });
  $('routineEvent').onchange = () => {
    // Das Minutenfeld gilt nur fuer „PC inaktiv“.
    $('routineIdle').hidden = $('routineEvent').value !== 'idle';
  };
  $('btnAddStep').onclick = () => {
    if (state.routineSteps.length >= ROUTINE_MAX_STEPS) {
      showBanner('Mehr als ' + ROUTINE_MAX_STEPS + ' Schritte sind nicht möglich.', 'warn');
      return;
    }
    state.routineSteps.push({ type: 'hibernate', delay: '60', plan: 'high', wait: '0' });
    renderRoutineSteps();
  };
  $('routineSteps').addEventListener('change', onRoutineStepInput);
  $('routineSteps').addEventListener('input', onRoutineStepInput);
  $('routineSteps').onclick = (event) => {
    const button = event.target.closest('button[data-step-remove]');
    if (!button) return;
    const index = Number(button.dataset.stepRemove);
    if (!isFinite(index)) return;
    state.routineSteps.splice(index, 1);
    renderRoutineSteps();
  };
  $('btnAddRoutine').onclick = addRoutine;
  $('routineTemplateBtns').onclick = (event) => {
    const button = event.target.closest('button[data-template]');
    if (!button) return;
    createRoutineFromTemplate(button.dataset.template);
  };
  state.routineSteps = [{ type: 'hibernate', delay: '60', plan: 'high', wait: '0' }];
  renderRoutineSteps();
  updateRoutineTriggerForm();

  // Warnregeln (Phase C): nur `admin` darf speichern.
  $('btnAddWarning').onclick = addWarningRule;

  // Log/Verlauf (Phase W2): Filter-Chips, Suche, Export und Aufbewahrung.
  $('logFilterChips').onclick = (event) => {
    const button = event.target.closest('button[data-filter]');
    if (!button) return;
    setLogFilter(button.dataset.filter);
  };
  $('logSearch').addEventListener('input', () => {
    state.logQuery = $('logSearch').value;
    renderEvents();
  });
  $('logLines').value = String(currentLogLines());
  $('logLines').onchange = () => setLogLines($('logLines').value);
  $('btnLogRefresh').onclick = () => refreshLogTab();
  $('btnLogExport').onclick = exportLog;
  $('btnLogCopy').onclick = copyLog;
  $('btnFileLogCopy').onclick = copyFileLog;
  syncLogFilterChips();

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
      stopSensors();
      stopWarnings();
      stopPluginDetailPolling();
    } else {
      startLive();
      if (!state.live) startPolling();
      syncTabPolling();
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
  renderSteam();
  renderEvents();
  renderMetrics();
  renderTimer();
  renderPlugin();
  renderCatalog();
  renderDevices();
  renderUsers();
  renderRoutines();
  renderSensors();
  renderWarnings();
}

function renderSteam() {
  const host = $('steamBody');
  if (!host) return;
  const s = state.steam;
  if (!s || !s.detected) {
    host.textContent = 'Steam nicht gefunden.';
    return;
  }
  if (!s.downloading) {
    host.innerHTML = '<div class="mut">Kein Download aktiv' + (s.running ? ' · Steam läuft' : '') + '.</div>';
    return;
  }
  const pct = (s.progress_percent || 0).toFixed(1);
  const speed = s.speed_bps ? (s.speed_bps / 1048576).toFixed(1) + ' MB/s' : '–';
  const eta = s.eta_seconds ? Math.round(s.eta_seconds / 60) + ' min' : '–';
  host.innerHTML =
    '<div class="row" style="gap:8px;align-items:center">' +
    '<div style="flex:1;height:8px;background:var(--line);border-radius:4px;overflow:hidden">' +
    '<div style="width:' + pct + '%;height:100%;background:var(--primary)"></div></div>' +
    '<span>' + pct + '%</span></div>' +
    '<div class="mut" style="margin-top:4px">' + speed + ' · Restzeit ' + eta + '</div>';
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
      (pending > 0 ? '<button type="button" class="ghost" data-act="abort">Abbrechen</button>' : '');
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

/* ---------------- Log/Verlauf (Phase W2) ----------------
   Quelle: `state.events` aus `GET /events` (Felder `time`, `action`, `detail`,
   `ok`; ein anderer Agent darf auch `text` liefern) und `GET /log?lines=`.

   Die Liste wird nur im Browser gefiltert und gruppiert: Filter-Chips ueber
   `action` (Fehler gewinnen immer), Suchfeld ueber Aktion und Text,
   Zeitgruppen "Heute/Gestern/Aelter". Alles, was von aussen kommt, geht durch
   `esc()`; die Logdatei bleibt reiner Text via `textContent`. */

/// Filter-Chips in fester Reihenfolge; `error` gewinnt gegen alle anderen.
const LOG_FILTERS = [
  { key: 'all', label: 'Alle' },
  { key: 'control', label: 'Steuerung' },
  { key: 'routine', label: 'Routine' },
  { key: 'plugin', label: 'Plugin' },
  { key: 'warning', label: 'Warnung' },
  { key: 'error', label: 'Fehler' },
];

/// Aktionen der Steuerung, Routinen, Plugins und Warnungen (Spiegel des Agenten).
const LOG_CONTROL_ACTIONS = ['shutdown', 'abort', 'hibernate', 'power-plan', 'wake', 'mode'];
const LOG_ROUTINE_ACTIONS = ['routine'];
const LOG_PLUGIN_ACTIONS = ['plugin', 'games-scan', 'game-config'];
const LOG_WARNING_ACTIONS = ['warnung'];

const LOG_GROUP_ORDER = ['today', 'yesterday', 'older'];
const LOG_GROUP_LABELS = { today: 'Heute', yesterday: 'Gestern', older: 'Älter' };

/// Text eines Ereignisses: der Agent liefert `detail`, `text` gilt als Ersatz.
function eventText(event) {
  if (!event) return '';
  const detail = (event.detail === undefined || event.detail === null)
    ? '' : String(event.detail);
  if (detail) return detail;
  return (event.text === undefined || event.text === null) ? '' : String(event.text);
}

/// Kategorie eines Ereignisses; `ok === false` hat Vorrang (Fehler).
function eventCategory(event) {
  if (event && event.ok === false) return 'error';
  const action = String((event && event.action) || '').trim().toLowerCase();
  if (LOG_CONTROL_ACTIONS.indexOf(action) >= 0) return 'control';
  if (LOG_ROUTINE_ACTIONS.indexOf(action) >= 0) return 'routine';
  if (LOG_PLUGIN_ACTIONS.indexOf(action) >= 0) return 'plugin';
  if (LOG_WARNING_ACTIONS.indexOf(action) >= 0) return 'warning';
  // Alles andere (z. B. `ping`, `setup`): nur unter „Alle“ sichtbar.
  return 'other';
}

/// Suchtext normalisieren: aussen trimmen, Gross-/Kleinschreibung egal.
function normalizeLogQuery(query) {
  return String(query || '').trim().toLowerCase();
}

/// Filtert die Ereignisse nach Chip und Suchtext; die Reihenfolge bleibt.
function filterLogEvents(events) {
  const filter = state.logFilter || 'all';
  const needle = normalizeLogQuery(state.logQuery);
  return (Array.isArray(events) ? events : []).filter((event) => {
    if (filter !== 'all' && eventCategory(event) !== filter) return false;
    if (!needle) return true;
    const action = String((event && event.action) || '').toLowerCase();
    const text = eventText(event).toLowerCase();
    return action.indexOf(needle) >= 0 || text.indexOf(needle) >= 0;
  });
}

/// Lokales Datum als `YYYY-MM-DD` (Grundlage der Zeitgruppen).
function logDateKey(date) {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return date.getFullYear() + '-' + month + '-' + day;
}

/// Gruppe eines Ereignisses. Fehlender/kaputter Zeitstempel -> „Älter“;
/// ein Datum in der Zukunft (Uhr laeuft vor) gilt als „Heute“.
function logGroupOf(event, todayKey, yesterdayKey) {
  const time = String((event && event.time) || '').trim();
  const date = time.slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return 'older';
  if (date >= todayKey) return 'today';
  if (date === yesterdayKey) return 'yesterday';
  return 'older';
}

/// Ereignisse in die drei Gruppen aufteilen; leere Gruppen entfallen.
function logSections(events, todayKey, yesterdayKey) {
  const buckets = { today: [], yesterday: [], older: [] };
  events.forEach((event) => {
    buckets[logGroupOf(event, todayKey, yesterdayKey)].push(event);
  });
  return LOG_GROUP_ORDER
    .filter((key) => buckets[key].length)
    .map((key) => ({ key, label: LOG_GROUP_LABELS[key], events: buckets[key] }));
}

/// Eine Zeile der Ereignisliste (Zeit · Aktion · Text, Fehler rot markiert).
function logRow(event) {
  const time = String((event && event.time) || '').trim();
  const clock = time.length >= 16 ? time.slice(11) : (time || '–');
  const action = String((event && event.action) || '').trim() || '–';
  const text = eventText(event);
  const failed = !!(event && event.ok === false);
  return '<div class="logrow' + (failed ? ' err' : '') + '">' +
    '<span class="lt">' + esc(clock) + '</span> · <strong>' + esc(action) + '</strong>' +
    (text ? ' · ' + esc(text) : '') +
    (failed ? ' <span class="lbadge">Fehler</span>' : '') +
    '</div>';
}

/// Eine Zeitgruppe mit Ueberschrift und ihren Zeilen.
function logSection(section) {
  return '<div class="loggroup">' +
    '<div class="loghead">' + esc(section.label) + ' · ' + section.events.length + '</div>' +
    section.events.map(logRow).join('') +
    '</div>';
}

/// Zusatz hinter dem Zaehler: aktiver Chip und Suchtext.
function logFilterSuffix() {
  const parts = [];
  const filter = LOG_FILTERS.find((entry) => entry.key === state.logFilter);
  if (filter && filter.key !== 'all') parts.push('Filter: ' + filter.label);
  const query = String(state.logQuery || '').trim();
  if (query) parts.push('Suche: „' + query + '“');
  return parts.length ? ' · ' + parts.join(' · ') : '';
}

function renderEvents() {
  const box = $('events');
  if (!box) return;
  const events = Array.isArray(state.events) ? state.events : [];
  const filtered = filterLogEvents(events);
  const signature = filtered.map((event) => [String((event && event.time) || ''),
    String((event && event.action) || ''), eventText(event),
    event && event.ok === false ? '1' : '0'].join('|')).join('#') +
    '#' + (state.logFilter || 'all') + '#' + normalizeLogQuery(state.logQuery);

  if (signature !== eventsSignature) {
    eventsSignature = signature;
    if (!filtered.length) {
      box.innerHTML = '';
      box.textContent = (state.logFilter === 'all' && !normalizeLogQuery(state.logQuery))
        ? 'Noch keine Ereignisse.'
        : 'Keine Einträge für diesen Filter.';
    } else {
      const now = new Date();
      const todayKey = logDateKey(now);
      const yesterdayKey = logDateKey(
        new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1));
      box.innerHTML = logSections(filtered, todayKey, yesterdayKey).map(logSection).join('');
    }
  }

  const count = $('logCount');
  if (count) {
    count.textContent = filtered.length + ' von ' + events.length +
      (events.length === 1 ? ' Eintrag' : ' Einträgen') + logFilterSuffix();
  }
}

/// Chip wechseln (nichts nachladen – die Liste liegt schon im Browser).
function setLogFilter(key) {
  const known = LOG_FILTERS.some((entry) => entry.key === key);
  state.logFilter = known ? key : 'all';
  syncLogFilterChips();
  renderEvents();
}

/// Markiert den aktiven Chip (aria-pressed wie die Wochentage der Routinen).
function syncLogFilterChips() {
  const box = $('logFilterChips');
  if (!box) return;
  box.querySelectorAll('button[data-filter]').forEach((button) => {
    const on = button.dataset.filter === state.logFilter;
    button.classList.toggle('on', on);
    button.setAttribute('aria-pressed', on ? 'true' : 'false');
  });
}

/* ---- Log: Export als Textdatei (immer nur die gefilterte Liste) ---- */

/// Zeitpunkt fuer die Kopfzeile, z. B. `23.11.2026 14:05`.
function logExportStamp(date) {
  const pad = (value) => String(value).padStart(2, '0');
  return pad(date.getDate()) + '.' + pad(date.getMonth() + 1) + '.' + date.getFullYear() +
    ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}

/// Eine Zeile: `Zeit | Aktion | Text` und – nur bei Fehlern – `FEHLER`.
function logExportLine(event) {
  const time = String((event && event.time) || '').trim() || '-';
  const action = String((event && event.action) || '').trim() || '-';
  // Zeilenumbrueche im Text wuerden die Datei zerlegen.
  const text = eventText(event).replace(/\s+/g, ' ').trim() || '-';
  const fields = [time, action, text];
  if (event && event.ok === false) fields.push('FEHLER');
  return fields.join(' | ');
}

/// Kompletter Dateiinhalt: Kopfzeile mit PC-Name, Zeitpunkt und Anzahl.
function logExportText(events, now) {
  const list = Array.isArray(events) ? events : [];
  const name = (state.status && state.status.device_name) ||
    (state.pairing && state.pairing.name) || 'PC';
  const lines = [
    'PC Steuerung – Verlauf',
    'PC: ' + name,
    'Exportiert: ' + logExportStamp(now),
    'Einträge: ' + list.length,
    '',
  ];
  if (!list.length) lines.push('Keine Einträge für diesen Filter.');
  else list.forEach((event) => lines.push(logExportLine(event)));
  return lines.join('\r\n') + '\r\n';
}

/// Text als Datei speichern (`Blob` + `URL.createObjectURL` + `<a download>`).
function downloadText(filename, text) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.rel = 'noopener';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // Erst spaeter freigeben: manche Browser brauchen die URL noch kurz.
  window.setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/// In die Zwischenablage kopieren; Fallback fuer Browser ohne Clipboard-API.
async function copyText(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (error) {
    // Berechtigung verweigert -> Fallback unten
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', 'readonly');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand('copy');
    document.body.removeChild(area);
    return !!copied;
  } catch (error) {
    return false;
  }
}

/// „Exportieren“: die gefilterte Liste als `verlauf-YYYY-MM-DD.txt`.
function exportLog() {
  const events = filterLogEvents(Array.isArray(state.events) ? state.events : []);
  const now = new Date();
  downloadText('verlauf-' + logDateKey(now) + '.txt', logExportText(events, now));
  showBanner(events.length === 1 ? '1 Eintrag exportiert.'
    : events.length + ' Einträge exportiert.', 'ok');
}

/// „Kopieren“: derselbe Text wie der Export, nur in die Zwischenablage.
async function copyLog() {
  const events = filterLogEvents(Array.isArray(state.events) ? state.events : []);
  const copied = await copyText(logExportText(events, new Date()));
  if (copied) {
    showBanner('Verlauf kopiert (' + events.length +
      (events.length === 1 ? ' Eintrag).' : ' Einträge).'), 'ok');
  } else {
    showBanner('Kopieren nicht möglich: der Browser erlaubt keinen Zugriff auf die ' +
      'Zwischenablage.', 'warn');
  }
}

/// Eigener Knopf der Logdatei: kopiert genau den angezeigten Text.
async function copyFileLog() {
  const host = $('fileLog');
  const text = (host && host.textContent) || '';
  const copied = text.trim() ? await copyText(text) : false;
  if (copied) showBanner('Logdatei kopiert.', 'ok');
  else showBanner('Logdatei ist leer oder die Zwischenablage ist gesperrt.', 'warn');
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
  renderPluginTiles();
  renderPluginDetail();
}

function pluginStateText(item) {
  if (!item) return 'wird geladen …';
  if (!item.available) return 'fehlt';
  return item.running ? 'aktiv' : 'bereit';
}

function pluginKindText(item) {
  if (!item) return '–';
  return item.kind === 'javascript' ? 'JS' : 'EXE';
}

/* ---------------- Plugins: Raster (Phase B1) ----------------
   Installierte Plugins als Kacheln (2 Spalten) plus die zwei festen Kacheln
   „Steam“ und „Spiel-Optimierung“. Das Plugin `game-optimizer` erscheint nicht
   doppelt: seine Verwaltung liegt hinter der Kachel „Spiel-Optimierung“, die
   Schnellaktionen bleiben in der Übersicht. */

/// Kachel-Daten aus `/plugins` und `/steam` (reine Anzeige).
function pluginTiles() {
  const list = (state.plugins || []).filter((item) => item.id !== 'game-optimizer');
  const tiles = list.map((item) => ({
    key: 'plugin:' + item.id,
    kind: 'plugin',
    id: item.id,
    kindText: pluginKindText(item) + ' · v' + (item.version || '1'),
    name: item.name || item.id,
    stateText: pluginStateText(item),
    active: !!item.running,
  }));

  const steam = state.steam;
  const downloads = steam && Array.isArray(steam.downloads) ? steam.downloads.length : 0;
  tiles.push({
    key: 'steam',
    kind: 'steam',
    id: '',
    kindText: 'Downloads · Millennium',
    name: 'Steam',
    stateText: !steam ? 'wird geladen …'
      : (!steam.detected ? 'nicht erkannt'
        : (steam.downloading ? downloads + (downloads === 1 ? ' Download' : ' Downloads') : 'bereit')),
    active: !!(steam && steam.detected && steam.downloading),
  });

  const optimizer = (state.plugins || []).find((item) => item.id === 'game-optimizer');
  tiles.push({
    key: 'game',
    kind: 'game',
    id: '',
    kindText: 'Spiele · Kill-Liste',
    name: 'Spiel-Optimierung',
    stateText: optimizer ? pluginStateText(optimizer) : 'wird geladen …',
    active: !!(optimizer && optimizer.running),
  });
  return tiles;
}

function renderPluginTiles() {
  const host = $('pluginTiles');
  if (!host) return;
  const tiles = pluginTiles();
  const signature = JSON.stringify(tiles) + '#' + canWrite() + '#' +
    (state.pluginDetail ? state.pluginDetail.kind + ':' + state.pluginDetail.id : '');
  if (signature === state.pluginTileSignature) return;
  state.pluginTileSignature = signature;

  host.innerHTML = tiles.map((tile) =>
    '<div class="tile">' +
    '<div class="lab">' + esc(tile.kindText) + '</div>' +
    '<strong>' + esc(tile.name) + '</strong>' +
    (tile.active ? '<span class="chip">' + esc(tile.stateText) + '</span>'
      : '<span class="mut">' + esc(tile.stateText) + '</span>') +
    '<div class="btns"><button type="button" class="ghost" data-open="' +
    esc(tile.key) + '">Öffnen</button></div>' +
    '</div>'
  ).join('');

  const hint = $('pluginHint');
  if (hint) {
    hint.textContent = 'Die Kachel „Spiel-Optimierung“ verwaltet Spiele, Kill-Liste und ' +
      'eigene Einträge; gestartet wird sie weiterhin über die Übersicht.';
  }
}

/* ---------------- Plugins: Detailansicht (Phasen B2/B4) ----------------
   Im selben Tab eingeblendet (`#pluginDetail`), „Zurück“ führt zum Raster.
   Die Ausgabe wird nur hier abgefragt (3 s) – nie im Hintergrund, nie in
   einem anderen Tab (Regel D1). */

function applyPluginDetailVisibility() {
  const overview = $('pluginOverview');
  const detail = $('pluginDetail');
  if (!overview || !detail) return;
  const onPluginsTab = state.activeTab === 'plugins';
  const showOverview = onPluginsTab && !state.pluginDetail;
  overview.hidden = !showOverview;
  detail.hidden = !onPluginsTab || !state.pluginDetail;
  // Der Marktplatz steht unter dem Raster und weicht der Detailansicht genauso.
  const market = $('pluginMarket');
  if (market) market.hidden = !showOverview;
}

function openPluginDetailFromKey(key) {
  const text = String(key || '');
  const split = text.indexOf(':');
  const kind = split < 0 ? text : text.slice(0, split);
  const id = split < 0 ? '' : text.slice(split + 1);
  openPluginDetail(kind, id);
}

async function openPluginDetail(kind, id) {
  state.pluginDetail = { kind, id: id || '' };
  state.pluginOutput = '';
  state.pluginOutputError = '';
  state.pluginSettings = null;
  state.pluginSettingsValues = {};
  state.pluginSettingsError = '';
  state.gameDetailSignature = '';
  state.scanPathHint = '';
  state.scanPathError = false;
  state.pluginTileSignature = '';
  applyPluginDetailVisibility();
  syncPluginDetailPolling();
  renderPluginDetail();
  renderPluginTiles();

  if (kind === 'plugin') {
    await refreshPluginSettings();
    await refreshPluginOutput();
  } else if (kind === 'steam') {
    await refreshSteamDetail();
  } else if (kind === 'game') {
    await refreshGameDetail();
  }
  renderPluginDetail();
}

function closePluginDetail() {
  state.pluginDetail = null;
  state.pluginOutput = '';
  state.pluginOutputError = '';
  state.pluginSettings = null;
  state.pluginSettingsValues = {};
  state.scanPathHint = '';
  state.scanPathError = false;
  stopPluginDetailPolling();
  state.pluginTileSignature = '';
  applyPluginDetailVisibility();
  renderPluginTiles();
}

/// Zustandszeile eines Plugins in der Detailansicht (nur Anzeige).
function pluginDetailStateText(item, id) {
  if (!item) return 'Plugin „' + id + '“ ist nicht gemeldet – läuft der Agent?';
  if (!item.available) return 'Plugin ist nicht installiert (Ordner fehlt im Datenordner).';
  if (!item.running) {
    return pluginKindText(item) + ' · bereit · v' + (item.version || '1');
  }
  const parts = [pluginKindText(item), 'aktiv', 'seit ' + fmtUptime(item.uptime || 0)];
  if (item.game) parts.push('Spiel: ' + String(item.game).split('\\').pop());
  if (item.auto) parts.push('automatisch');
  if (item.status) parts.push(item.status);
  return parts.join(' · ');
}

function renderPluginDetail() {
  if (!$('pluginDetail')) return;
  applyPluginDetailVisibility();
  const detail = state.pluginDetail;
  if (!detail) return;

  const isPlugin = detail.kind === 'plugin';
  $('detailOutputBox').hidden = !isPlugin;
  $('detailSteam').hidden = detail.kind !== 'steam';
  $('detailGames').hidden = detail.kind !== 'game';
  // Idle-Timeout-Hinweis (Phase W3): nur bei einem Plugin, rein informativ.
  const idleInfo = $('detailIdleInfo');
  if (idleInfo) idleInfo.hidden = !isPlugin;
  const write = canWrite();

  if (isPlugin) {
    const item = (state.plugins || []).find((entry) => entry.id === detail.id) || null;
    $('detailKind').textContent = 'Plugin · ' + pluginKindText(item);
    $('detailTitle').textContent = (item && (item.name || item.id)) || detail.id;
    $('detailState').textContent = pluginDetailStateText(item, detail.id);

    const running = !!(item && item.running);
    let html = '';
    if (running) {
      html += '<button type="button" class="ghost" data-detail-act="stop"' +
        (write ? '' : ' disabled') + '>Beenden</button>';
    } else {
      html += '<button type="button" class="go" data-detail-act="start"' +
        (write && item && item.available ? '' : ' disabled') + '>Starten</button>';
    }
    html += '<button type="button" class="ghost" data-detail-act="refresh">Aktualisieren</button>';
    // Entfernen gibt es nur fuer JavaScript-Plugins (Ordner im Datenordner).
    if (item && item.kind === 'javascript') {
      html += '<button type="button" class="ghost" data-detail-act="remove"' +
        (write ? '' : ' disabled') + '>Entfernen</button>';
    }
    const box = $('detailActions');
    box.innerHTML = html;
    box.querySelectorAll('button[data-detail-act]').forEach((button) => {
      const act = button.dataset.detailAct;
      if (act === 'start') {
        button.onclick = () => runAction(() => pluginCommand('start', detail.id));
      } else if (act === 'stop') {
        button.onclick = () => runAction(() => pluginCommand('stop', detail.id));
      } else if (act === 'remove') {
        button.onclick = () => runAction(() => removePlugin(detail.id));
      } else if (act === 'refresh') {
        button.onclick = () => { refreshPluginOutput(); refreshPluginSettings(); };
      }
    });
    // Ohne Schema (404, alter Agent, leeres Schema) bleibt der Abschnitt aus.
    $('detailSettings').hidden = !(state.pluginSettings && state.pluginSettings.length);
    renderPluginOutput();
    return;
  }

  if (detail.kind === 'steam') {
    $('detailKind').textContent = 'Steam · nur Lesen';
    $('detailTitle').textContent = 'Steam + Downloads';
    $('detailSettings').hidden = true;
    const box = $('detailActions');
    box.innerHTML = '<button type="button" class="ghost" data-detail-act="refresh">Aktualisieren</button>';
    box.querySelector('button[data-detail-act="refresh"]').onclick = () => refreshSteamDetail();
    renderSteamDetail();
    return;
  }

  // Spiel-Optimierung: Verwaltung (Schnellaktionen bleiben in der Übersicht).
  const optimizer = (state.plugins || []).find((item) => item.id === 'game-optimizer') || null;
  $('detailKind').textContent = 'Spiel-Optimierung · Verwaltung';
  $('detailTitle').textContent = 'Spiele und Kill-Liste';
  $('detailState').textContent = 'Plugin: ' + pluginStateText(optimizer) +
    ' · Gestartet wird es in der Übersicht mit Spielauswahl.';
  $('detailSettings').hidden = true;
  const box = $('detailActions');
  if (optimizer && optimizer.running) {
    box.innerHTML = '<button type="button" class="ghost" data-detail-act="stop"' +
      (write ? '' : ' disabled') + '>Plugin beenden</button>';
    box.querySelector('button[data-detail-act="stop"]').onclick =
      () => runAction(() => pluginCommand('stop', 'game-optimizer'));
  } else {
    box.innerHTML = '<span class="mut">Das Optimierungs-Plugin läuft nicht.</span>';
  }
  renderGameDetail();
}

/// Start/Stop eines Plugins samt Nachladen der Liste.
async function pluginCommand(action, id) {
  await api('/plugins/' + encodeURIComponent(id) + '/' + action, { method: 'POST' });
  await refreshPlugin();
  if (state.pluginDetail && state.pluginDetail.kind === 'plugin') await refreshPluginOutput();
}

async function removePlugin(id) {
  if (!guardWrite()) return;
  const ok = await askConfirm({
    title: 'Plugin entfernen',
    text: 'Plugin „' + id + '“ entfernen?\nEingebaute Plugins legt der Agent beim nächsten Start neu an.',
    ok: 'Entfernen',
    danger: true,
  });
  if (!ok) return;
  await api('/plugins/' + encodeURIComponent(id), { method: 'DELETE' });
  await refreshPlugin();
  // Marktplatz (Phase W1): die „installiert“-Markierung stimmt sonst nicht mehr.
  await refreshCatalog();
  if (state.pluginDetail && state.pluginDetail.id === id) closePluginDetail();
  state.pluginTileSignature = '';
  render();
}

/* ---------------- Plugins: Marktplatz (Phase W1) ----------------
   Quelle: GET /plugin-catalog (`catalogVersion`, `plugins[]` mit `id`, `name`,
   `description`, `version`, `installed`, `kind`). Geladen wird nur, solange der
   Plugins-Tab offen ist: beim Tabwechsel, nach Aktionen und über
   „Aktualisieren“ – kein Takt, kein Abruf im Hintergrund (Regel D1).
   Ein leerer oder kaputter Katalog ist kein Absturz: der Abschnitt zeigt einen
   ruhigen Hinweis, der Rest der Seite bleibt bedienbar. */

const CATALOG_TEXT_MAX = 140;

/// Beschreibung für die Zeile kürzen; der volle Text bleibt im Katalog.
function catalogDescription(text) {
  const value = String(text || '').replace(/\s+/g, ' ').trim();
  if (!value) return 'Keine Beschreibung im Katalog.';
  return value.length > CATALOG_TEXT_MAX
    ? value.slice(0, CATALOG_TEXT_MAX - 1).trim() + '…'
    : value;
}

/// Zeile unter dem Namen: Art · Version · gekürzte Beschreibung.
function catalogSubText(entry) {
  return [pluginKindText(entry), 'v' + (entry.version || '1'),
    catalogDescription(entry.description)].join(' · ');
}

/// Eine Katalogzeile: Name, Beschreibung, Version, Status und (nur `admin` und
/// nicht installiert) der Knopf „Installieren“.
function catalogRow(entry) {
  const id = String(entry.id);
  const installed = entry.installed === true;
  const busy = state.catalogInstalling === id;
  let action;
  if (installed) {
    action = '<span class="chip">installiert</span>';
  } else if (canWrite()) {
    action = '<button type="button" data-install="' + esc(id) + '"' + (busy ? ' disabled' : '') +
      '>' + (busy ? 'Installiere …' : 'Installieren') + '</button>';
  } else {
    action = '<span class="mut">nicht installiert</span>';
  }
  return '<div class="devrow cols">' +
    '<div class="grow wrap">' + esc(entry.name || id) +
    '<div class="sub">' + esc(catalogSubText(entry)) + '</div></div>' +
    action +
    '</div>';
}

/// Ruhezeile unter der Liste: Ladezustand, Fehler, leerer Katalog oder Stand.
function catalogHintText(entries) {
  if (state.catalogError) return state.catalogError;
  if (state.catalog === null) return 'Marktplatz wird geladen …';
  if (!entries.length) return 'Marktplatz nicht erreichbar – der Katalog ist leer.';
  const count = entries.length === 1 ? '1 Eintrag' : entries.length + ' Einträge';
  const version = state.catalogVersion ? ' · Katalog ' + state.catalogVersion : '';
  return count + ' im Katalog' + version + '.';
}

function renderCatalog() {
  const list = $('catalogList');
  const hint = $('catalogHint');
  if (!list || !hint) return;
  const entries = Array.isArray(state.catalog) ? state.catalog : [];
  const signature = JSON.stringify(entries) + '#' + canWrite() + '#' +
    state.catalogInstalling + '#' + state.catalogError + '#' + state.catalogVersion;
  if (signature !== state.catalogSignature) {
    state.catalogSignature = signature;
    list.innerHTML = entries.length ? entries.map(catalogRow).join('') : '';
    list.hidden = !entries.length;
    list.querySelectorAll('button[data-install]').forEach((button) => {
      button.onclick = () => installPluginFromCatalog(button.dataset.install);
    });
    hint.textContent = catalogHintText(entries);
    hint.className = state.catalogError ? 'mut err' : 'mut';
  }
}

/// Lädt den Katalog – nur bei offenem Plugins-Tab und sichtbarer Seite.
async function refreshCatalog() {
  if (document.hidden || state.activeTab !== 'plugins') return;
  if (!state.connected) {
    state.catalog = null;
    state.catalogVersion = 0;
    state.catalogError = 'Marktplatz nicht erreichbar: keine Verbindung zum Agenten.';
    renderCatalog();
    return;
  }
  try {
    const data = await api('/plugin-catalog');
    const list = (data && Array.isArray(data.plugins)) ? data.plugins : [];
    // Kaputte Einträge (ohne id) werden still übersprungen.
    state.catalog = list.filter((entry) => entry && typeof entry.id === 'string' && entry.id);
    state.catalogVersion =
      (data && typeof data.catalogVersion === 'number') ? data.catalogVersion : 0;
    state.catalogError = '';
  } catch (error) {
    // 404/500/Netzwerk: ruhiger Hinweis im Abschnitt, kein Banner.
    state.catalog = null;
    state.catalogVersion = 0;
    state.catalogError = 'Marktplatz nicht erreichbar: ' + error.message;
  }
  renderCatalog();
}

/// „Installieren“ → POST /plugins/{id}/install; danach Katalog, Kacheln und eine
/// offene Detailansicht neu laden. Erfolg im Banner, Fehler im Abschnitt.
async function installPluginFromCatalog(id) {
  if (!guardWrite()) return;
  if (state.catalogInstalling) return;
  const entry = (Array.isArray(state.catalog) ? state.catalog : [])
    .find((item) => item.id === id) || null;
  if (!entry || entry.installed === true) return;
  const name = entry.name || id;
  const ok = await askConfirm({
    title: 'Plugin installieren',
    text: 'Plugin „' + name + '“ aus dem Katalog installieren?\n' +
      'Das Paket landet im Datenordner, gestartet wird es nicht.',
    ok: 'Installieren',
  });
  if (!ok) return;

  state.catalogInstalling = id;
  state.catalogError = '';
  renderCatalog();
  try {
    await api('/plugins/' + encodeURIComponent(id) + '/install', { method: 'POST' });
    state.catalogError = '';
    state.pluginTileSignature = '';
    await refreshPlugin();
    await refreshCatalog();
    if (state.pluginDetail && state.pluginDetail.kind === 'plugin' &&
        state.pluginDetail.id === id) {
      await refreshPluginSettings();
      await refreshPluginOutput();
    }
    showBanner('Plugin „' + name + '“ installiert.', 'ok');
  } catch (error) {
    // 403/400/422/500: `detail` des Agenten steht in `error.message`.
    state.catalogError = 'Installation fehlgeschlagen: ' + error.message;
  } finally {
    state.catalogInstalling = '';
    renderCatalog();
    render();
  }
}

/* ---- Plugin-Ausgabe (3 s, nur bei offener Detailansicht) ---- */

let pluginDetailTimer = null;

function stopPluginDetailPolling() {
  if (pluginDetailTimer) {
    window.clearInterval(pluginDetailTimer);
    pluginDetailTimer = null;
  }
}

/// Startet/stoppt den Ausgabe-Takt passend zu Tab, Detailansicht und Sichtbarkeit.
function syncPluginDetailPolling() {
  const active = !document.hidden && state.activeTab === 'plugins' &&
    !!state.pluginDetail && state.pluginDetail.kind === 'plugin';
  if (!active) {
    stopPluginDetailPolling();
    return;
  }
  if (!pluginDetailTimer) {
    pluginDetailTimer = window.setInterval(refreshPluginOutput, PLUGIN_OUTPUT_MS);
  }
}

async function refreshPluginOutput() {
  const detail = state.pluginDetail;
  if (!detail || detail.kind !== 'plugin') return;
  // Pflichtregel D1: nie im Hintergrund, nie in einem anderen Tab.
  if (document.hidden || state.activeTab !== 'plugins') return;
  if (!state.connected) {
    state.pluginOutputError = 'Keine Verbindung zum Agenten – keine Ausgabe.';
    renderPluginOutput();
    return;
  }
  try {
    const text = await api('/plugins/' + encodeURIComponent(detail.id) +
      '/output?lines=' + PLUGIN_OUTPUT_LINES);
    state.pluginOutput = typeof text === 'string' ? text : String(text || '');
    state.pluginOutputError = '';
  } catch (error) {
    state.pluginOutputError = 'Ausgabe konnte nicht geladen werden: ' + error.message;
  }
  renderPluginOutput();
}

function renderPluginOutput() {
  const box = $('detailOutput');
  if (!box) return;
  const detail = state.pluginDetail;
  if (!detail || detail.kind !== 'plugin') {
    box.textContent = '';
    return;
  }
  if (state.pluginOutputError) {
    box.textContent = state.pluginOutputError;
    return;
  }
  const text = (state.pluginOutput || '').trim();
  box.textContent = text || 'Noch keine Ausgabe.';
}

/* ---- Plugin-Einstellungen (Schema aus dem Manifest) ---- */

/// Wert eines Felds: gespeicherter Wert, sonst `default` aus dem Schema.
function settingValue(field) {
  const values = state.pluginSettingsValues || {};
  if (Object.prototype.hasOwnProperty.call(values, field.key)) return values[field.key];
  return field.default;
}

function renderPluginSettings() {
  const box = $('detailSettings');
  const host = $('detailSettingsFields');
  if (!box || !host) return;
  const schema = state.pluginSettings;
  if (!schema || !schema.length) {
    box.hidden = true;
    host.innerHTML = '';
    return;
  }
  box.hidden = false;
  const write = canWrite();
  host.innerHTML = schema.map((field) => {
    const key = esc(field.key);
    const label = esc(field.label || field.key);
    const id = 'set_' + field.key;
    const value = settingValue(field);
    if (field.type === 'switch') {
      return '<div class="checkline">' +
        '<button type="button" class="switch" role="switch" aria-checked="' +
        (value === true ? 'true' : 'false') + '" data-setting="' + key + '" aria-label="' +
        label + '"' + (write ? '' : ' disabled') + '></button>' +
        '<label>' + label + '</label></div>';
    }
    if (field.type === 'select') {
      const options = Array.isArray(field.options) ? field.options : [];
      return '<div class="field"><label for="' + id + '">' + label + '</label>' +
        '<select id="' + id + '" data-setting="' + key + '"' + (write ? '' : ' disabled') + '>' +
        options.map((option) => '<option value="' + esc(option) + '"' +
          (option === value ? ' selected' : '') + '>' + esc(option) + '</option>').join('') +
        '</select></div>';
    }
    if (field.type === 'number') {
      const min = typeof field.min === 'number' ? ' min="' + field.min + '"' : '';
      const max = typeof field.max === 'number' ? ' max="' + field.max + '"' : '';
      const text = (value === null || value === undefined) ? '' : String(value);
      return '<div class="field"><label for="' + id + '">' + label + '</label>' +
        '<input id="' + id + '" data-setting="' + key + '" type="number" step="1"' + min + max +
        ' value="' + esc(text) + '"' + (write ? '' : ' disabled') + '></div>';
    }
    const text = (value === null || value === undefined) ? '' : String(value);
    return '<div class="field"><label for="' + id + '">' + label + '</label>' +
      '<input id="' + id + '" data-setting="' + key + '" value="' + esc(text) + '"' +
      (write ? '' : ' disabled') + '></div>';
  }).join('');

  // Schalter sind Knoepfe mit aria-checked (wie bei den Warnregeln).
  host.querySelectorAll('button.switch[data-setting]').forEach((button) => {
    button.onclick = () => {
      const on = button.getAttribute('aria-checked') === 'true';
      button.setAttribute('aria-checked', on ? 'false' : 'true');
    };
  });
  $('btnSaveSettings').disabled = !write;
  $('detailSettingsHint').textContent = state.pluginSettingsError
    ? state.pluginSettingsError
    : (write ? 'Die Werte gelten ab dem nächsten Start des Plugins.'
      : 'Diese Sitzung hat nur Lesezugriff.');
}

async function refreshPluginSettings() {
  const detail = state.pluginDetail;
  if (!detail || detail.kind !== 'plugin') return;
  if (!state.connected) return;
  state.pluginSettingsError = '';
  try {
    const data = await api('/plugins/' + encodeURIComponent(detail.id) + '/settings');
    const schema = data && Array.isArray(data.schema) ? data.schema : [];
    // Leeres Schema (oder 404) = kein Einstellungs-Abschnitt.
    state.pluginSettings = schema.length ? schema : null;
    state.pluginSettingsValues = (schema.length && data && data.values &&
      typeof data.values === 'object') ? data.values : {};
  } catch (error) {
    state.pluginSettings = null;
    state.pluginSettingsValues = {};
    if (error && error.status && error.status !== 404) {
      state.pluginSettingsError = 'Einstellungen nicht lesbar: ' + error.message;
    }
  }
  renderPluginSettings();
}

/// Liest die Felder, prueft Zahlen gegen min/max und speichert den ganzen Satz.
async function savePluginSettings() {
  const detail = state.pluginDetail;
  if (!detail || detail.kind !== 'plugin' || !state.pluginSettings) return;
  if (!guardWrite()) return;

  const values = {};
  const schema = state.pluginSettings;
  for (let i = 0; i < schema.length; i++) {
    const field = schema[i];
    const input = document.querySelector('[data-setting="' + field.key + '"]');
    if (!input) continue;
    if (field.type === 'switch') {
      values[field.key] = input.getAttribute('aria-checked') === 'true';
    } else if (field.type === 'number') {
      const raw = String(input.value || '').trim().replace(',', '.');
      const number = raw === '' ? NaN : Number(raw);
      if (!isFinite(number)) {
        showBanner('„' + (field.label || field.key) + '“ braucht eine Zahl.', 'warn');
        return;
      }
      if (typeof field.min === 'number' && number < field.min) {
        showBanner('„' + (field.label || field.key) + '“ muss mindestens ' + field.min +
          ' sein.', 'warn');
        return;
      }
      if (typeof field.max === 'number' && number > field.max) {
        showBanner('„' + (field.label || field.key) + '“ darf höchstens ' + field.max +
          ' sein.', 'warn');
        return;
      }
      values[field.key] = number;
    } else {
      values[field.key] = String(input.value || '');
    }
  }

  $('btnSaveSettings').disabled = true;
  try {
    const answer = await api('/plugins/' + encodeURIComponent(detail.id) + '/settings', {
      method: 'POST',
      body: JSON.stringify({ values }),
    });
    // Der Agent antwortet mit dem vollstaendigen Satz – der gilt jetzt.
    state.pluginSettingsValues = (answer && answer.values && typeof answer.values === 'object')
      ? answer.values : values;
    state.pluginSettingsError = '';
    clearBanner();
    renderPluginSettings();
    $('detailSettingsHint').textContent = 'Gespeichert um ' +
      new Date().toLocaleTimeString('de-DE') + '.';
    await refreshPlugin();
  } catch (error) {
    // 400/422/404: `detail` des Agenten steht in `error.message`.
    showBanner('Einstellungen nicht gespeichert: ' + error.message, 'warn');
  } finally {
    $('btnSaveSettings').disabled = !canWrite();
    render();
  }
}

/* ---------------- Steam-Detailansicht (Phase B4) ---------------- */

async function refreshSteamDetail() {
  if (!state.connected) return;
  try {
    state.steam = await api('/steam');
  } catch (error) {
    // Der nächste Tick versucht es erneut; die Anzeige bleibt stehen.
  }
  renderSteamDetail();
}

function steamDownloadRow(download) {
  const percent = typeof download.percent === 'number' ? download.percent : 0;
  const staged = typeof download.percent_staged === 'number' ? download.percent_staged : null;
  const speed = (typeof download.speed_bps === 'number' && download.speed_bps > 0)
    ? rateText(download.speed_bps) : '–';
  const eta = (typeof download.eta_seconds === 'number' && download.eta_seconds > 0)
    ? fmtTime(download.eta_seconds) : '–';
  return '<div class="devrow cols">' +
    '<div class="grow">' + esc(download.name || 'Download') +
    '<div class="sub">' + num1(percent) + ' % · Tempo ' + esc(speed) +
    ' · Restzeit ' + esc(eta) + '</div>' +
    '<div class="meter"><span style="width:' + Math.max(0, Math.min(100, percent)).toFixed(1) +
    '%"></span></div>' +
    (staged !== null && staged > 0 && staged < 100
      ? '<div class="sub">auf der Platte ' + num1(staged) + ' %</div>' : '') +
    '</div>' +
    '<span class="chip">' + num1(percent) + ' %</span>' +
    '</div>';
}

let steamDetailSignature = '';

function renderSteamDetail() {
  const line = $('steamStateLine');
  const box = $('steamDownloads');
  if (!line || !box) return;
  const steam = state.steam;
  const signature = JSON.stringify(steam || null);
  if (signature === steamDetailSignature) return;
  steamDetailSignature = signature;
  if (!steam) {
    line.textContent = 'Steam-Status wird geladen …';
    box.innerHTML = '<div class="mut">Keine Downloads</div>';
    return;
  }
  line.textContent = steam.detected
    ? 'Erkannt' + (steam.steam_path ? ' · ' + steam.steam_path : '') +
      ' · ' + (steam.running ? 'Steam läuft' : 'Steam ist nicht gestartet') +
      ' · Millennium ' + (steam.millennium ? 'erkannt' : 'nicht erkannt')
    : 'Steam ist auf diesem PC nicht erkannt.';
  const list = (steam.detected && Array.isArray(steam.downloads)) ? steam.downloads : [];
  box.innerHTML = list.length
    ? list.map(steamDownloadRow).join('')
    : '<div class="mut">Keine Downloads</div>';
}

/* ---------------- Spiel-Optimierung: Spiele und Konfiguration (Phase B4) --- */

function normalizeGameConfig(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const strings = (value) => (Array.isArray(value)
    ? value.filter((entry) => typeof entry === 'string' && entry.trim() !== '')
    : []);
  const custom = Array.isArray(raw.custom)
    ? raw.custom
      .filter((entry) => entry && typeof entry.exe === 'string' && entry.exe.trim() !== '')
      .map((entry) => ({
        name: (typeof entry.name === 'string' && entry.name.trim()) ? entry.name : entry.exe,
        exe: entry.exe,
        source: (typeof entry.source === 'string' && entry.source) ? entry.source : 'manuell',
      }))
    : [];
  return {
    kill_list: strings(raw.kill_list),
    scan_paths: strings(raw.scan_paths),
    auto: raw.auto === true,
    hidden: strings(raw.hidden),
    custom,
  };
}

async function refreshGameDetail() {
  if (!state.connected) return;
  await refreshGames();
  try {
    state.gameConfig = normalizeGameConfig(await api('/game-config'));
    state.gameConfigError = '';
  } catch (error) {
    state.gameConfig = null;
    state.gameConfigError = 'Spiel-Konfiguration nicht verfügbar: ' + error.message;
  }
  state.gameDetailSignature = '';
  renderGameDetail();
}

function gameConfigOrEmpty() {
  return state.gameConfig || {
    kill_list: [], scan_paths: [], auto: false, hidden: [], custom: [],
  };
}

/// Speichert den kompletten Satz (`POST /game-config` ersetzt alles).
/// Liefert `true` bei Erfolg (die Aufrufer leeren danach ihre Eingabefelder).
async function saveGameConfig(patch) {
  if (!guardWrite()) return false;
  // Ohne geladene Konfiguration nicht speichern: POST ersetzt den ganzen Satz,
  // ein leerer Ersatz würde die echten Einstellungen überschreiben.
  if (!state.gameConfig) {
    showBanner('Spiel-Konfiguration ist nicht geladen – Änderungen sind gesperrt.', 'warn');
    return false;
  }
  const body = Object.assign({}, gameConfigOrEmpty(), patch);
  try {
    await api('/game-config', { method: 'POST', body: JSON.stringify(body) });
    state.gameConfig = normalizeGameConfig(body);
    state.gameConfigError = '';
    clearBanner();
  } catch (error) {
    showBanner('Spiel-Konfiguration nicht gespeichert: ' + error.message, 'warn');
    return false;
  }
  await refreshGameDetail();
  render();
  return true;
}

async function scanGamesFromDetail() {
  await api('/games/scan', { method: 'POST' });
  await refreshGameDetail();
}

async function hideGame(exe, hide) {
  if (!guardWrite()) return;
  const config = gameConfigOrEmpty();
  const hidden = config.hidden.filter((entry) => entry !== exe);
  if (hide) hidden.push(exe);
  await saveGameConfig({ hidden });
}

async function addKillEntry() {
  if (!guardWrite()) return;
  const input = $('killInput');
  const name = String(input.value || '').trim();
  if (!name) {
    showBanner('Bitte einen Prozessnamen angeben, z. B. Discord.exe', 'warn');
    return;
  }
  const config = gameConfigOrEmpty();
  if (config.kill_list.some((entry) => entry.toLowerCase() === name.toLowerCase())) {
    showBanner('„' + name + '“ steht schon in der Kill-Liste.', 'warn');
    return;
  }
  const saved = await saveGameConfig({ kill_list: config.kill_list.concat([name]) });
  if (saved) input.value = '';
}

async function removeKillEntry(name) {
  if (!guardWrite()) return;
  const config = gameConfigOrEmpty();
  await saveGameConfig({ kill_list: config.kill_list.filter((entry) => entry !== name) });
}

async function addCustomGame() {
  if (!guardWrite()) return;
  const name = String($('customName').value || '').trim();
  const exe = String($('customExe').value || '').trim();
  if (!exe) {
    showBanner('Bitte den Pfad zur EXE angeben.', 'warn');
    return;
  }
  const config = gameConfigOrEmpty();
  if (config.custom.some((entry) => entry.exe.toLowerCase() === exe.toLowerCase())) {
    showBanner('Dieses Spiel steht schon in der Liste.', 'warn');
    return;
  }
  const saved = await saveGameConfig({
    custom: config.custom.concat([{ name: name || exe, exe, source: 'manuell' }]),
  });
  if (saved) {
    $('customName').value = '';
    $('customExe').value = '';
  }
}

async function removeCustomGame(exe) {
  if (!guardWrite()) return;
  const config = gameConfigOrEmpty();
  await saveGameConfig({ custom: config.custom.filter((entry) => entry.exe !== exe) });
}

/* ---- Eigene Suchordner und Automatik (Phase W3) ----
   Beides liegt in `/game-config` und wird über denselben Weg gespeichert
   (`POST` ersetzt den kompletten Satz; hier also immer der geladene Satz plus
   genau dieses eine Feld). */

/// Vergleichsform eines Ordnerpfads: `\` und `/` gelten gleich, Gross/Klein
/// egal, ein Trenner am Ende zählt nicht (Windows-Pfade). `C:\` bleibt `c:\`.
function scanPathKey(path) {
  const text = String(path || '').trim();
  if (!text) return '';
  const trimmed = text.replace(/[\\/]+$/, '');
  const key = trimmed.replace(/\//g, '\\').toLowerCase();
  return /^[a-z]:$/.test(key) ? key + '\\' : key;
}

/// Rückmeldung im Abschnitt: Text plus (bei Ablehnung) Fehlerfarbe.
function setScanPathHint(text, isError) {
  state.scanPathHint = text || '';
  state.scanPathError = !!isError;
  renderGameDetail();
}

async function addScanPath() {
  if (!guardWrite()) return;
  const input = $('scanPathInput');
  if (!input) return;
  // Nur Leerzeichen ist keine Eingabe – der Ordnername selbst darf aber
  // Leerzeichen enthalten, deshalb wird nur aussen getrimmt.
  const path = String(input.value || '').trim();
  if (!path) {
    setScanPathHint('Bitte einen Ordner angeben, z. B. D:\\Spiele.', true);
    input.focus();
    return;
  }
  const config = gameConfigOrEmpty();
  const key = scanPathKey(path);
  if (config.scan_paths.some((entry) => scanPathKey(entry) === key)) {
    setScanPathHint('„' + path + '“ steht schon in den Suchordnern.', true);
    return;
  }
  const saved = await saveGameConfig({ scan_paths: config.scan_paths.concat([path]) });
  if (saved) {
    input.value = '';
    setScanPathHint('„' + path + '“ hinzugefügt – wirkt beim nächsten „Spiele suchen“.', false);
  }
}

async function removeScanPath(path) {
  if (!guardWrite()) return;
  const config = gameConfigOrEmpty();
  const key = scanPathKey(path);
  const remaining = config.scan_paths.filter((entry) => scanPathKey(entry) !== key);
  const saved = await saveGameConfig({ scan_paths: remaining });
  if (saved) setScanPathHint('„' + path + '“ entfernt.', false);
}

/// Schalter „Laufendes Spiel automatisch erkennen“ (`game-config.auto`).
async function setGameAuto(on) {
  if (!guardWrite()) return;
  const saved = await saveGameConfig({ auto: !!on });
  if (!saved) {
    // Der Schalter zeigt weiter den gespeicherten Wert – kein Schalter, der lügt.
    state.gameDetailSignature = '';
    renderGameDetail();
  }
}

/// Pfad für die Anzeige kürzen; der volle Pfad bleibt im `data`-Attribut.
function shortExe(exe) {
  const text = String(exe || '');
  const parts = text.split(/[\\/]/).filter(Boolean);
  if (parts.length <= 3) return text;
  return '…\\' + parts.slice(-3).join('\\');
}

function renderGameDetail() {
  const listBox = $('gamesList');
  if (!listBox) return;
  const write = canWrite();
  const games = Array.isArray(state.games) ? state.games : [];
  const config = gameConfigOrEmpty();
  const signature = JSON.stringify(games) + '#' + JSON.stringify(config) + '#' +
    state.scannedAt + '#' + write + '#' + state.gameConfigError + '#' +
    state.scanPathHint + '#' + state.scanPathError;
  if (signature === state.gameDetailSignature) return;
  state.gameDetailSignature = signature;

  const visible = games.filter((game) => !game.hidden);
  const flagged = games.filter((game) => game.hidden);

  listBox.innerHTML = visible.length
    ? visible.map((game) =>
      '<div class="devrow">' +
      '<span class="dot ' + (game.running ? 'ok' : '') + '" aria-hidden="true"></span>' +
      '<div class="grow">' + esc(game.name) +
      '<div class="sub">' + esc(shortExe(game.exe)) + ' · ' + esc(game.source || 'unbekannt') +
      (game.running ? ' · läuft' : '') + '</div></div>' +
      (write ? '<button type="button" data-game-hide="' + esc(game.exe) + '">Ausblenden</button>'
        : '<span class="chip">sichtbar</span>') +
      '</div>').join('')
    : '<div class="mut">Keine Spiele gefunden – „Spiele suchen“ startet den Scan.</div>';

  // Ausgeblendet: die Konfiguration gewinnt, Markierungen aus /games kommen dazu.
  const hiddenEntries = config.hidden.slice();
  flagged.forEach((game) => {
    if (hiddenEntries.indexOf(game.exe) < 0) hiddenEntries.push(game.exe);
  });
  $('gamesHidden').innerHTML = hiddenEntries.length
    ? hiddenEntries.map((entry) =>
      '<div class="devrow">' +
      '<div class="grow">' + esc(entry) + '</div>' +
      (write ? '<button type="button" data-game-show="' + esc(entry) + '">Einblenden</button>'
        : '<span class="chip">ausgeblendet</span>') +
      '</div>').join('')
    : '<div class="mut">Nichts ausgeblendet.</div>';

  $('killList').innerHTML = config.kill_list.length
    ? config.kill_list.map((entry) =>
      '<div class="devrow">' +
      '<div class="grow">' + esc(entry) + '</div>' +
      (write ? '<button type="button" data-kill-remove="' + esc(entry) + '">Entfernen</button>' : '') +
      '</div>').join('')
    : '<div class="mut">Kill-Liste ist leer.</div>';

  $('customGames').innerHTML = config.custom.length
    ? config.custom.map((entry) =>
      '<div class="devrow">' +
      '<div class="grow">' + esc(entry.name) +
      '<div class="sub">' + esc(entry.exe) + '</div></div>' +
      (write ? '<button type="button" data-custom-remove="' + esc(entry.exe) + '">Entfernen</button>'
        : '') +
      '</div>').join('')
    : '<div class="mut">Noch kein eigenes Spiel.</div>';

  // Eigene Suchordner: Liste mit „Entfernen“, Eingabe steht fest im HTML (der
  // Text geht beim Neuzeichnen der Liste nicht verloren).
  $('scanPaths').innerHTML = config.scan_paths.length
    ? config.scan_paths.map((entry) =>
      '<div class="devrow">' +
      '<div class="grow" title="' + esc(entry) + '">' + esc(entry) + '</div>' +
      (write ? '<button type="button" data-scan-remove="' + esc(entry) + '">Entfernen</button>'
        : '<span class="chip">Suchordner</span>') +
      '</div>').join('')
    : '<div class="mut">Noch kein eigener Ordner.</div>';

  const scanHint = $('scanPathHint');
  scanHint.textContent = state.scanPathHint ||
    (write ? 'Neue Ordner gelten beim nächsten „Spiele suchen“.'
      : 'Diese Sitzung hat nur Lesezugriff.');
  scanHint.className = state.scanPathError ? 'mut err' : 'mut';

  const pathInput = $('scanPathInput');
  const addPathButton = $('btnAddScanPath');
  if (pathInput) pathInput.disabled = !write;
  if (addPathButton) addPathButton.disabled = !write;

  // Automatik-Schalter zeigt immer den gespeicherten Wert aus `/game-config`.
  const autoSwitch = $('gameAutoSwitch');
  if (autoSwitch) {
    autoSwitch.setAttribute('aria-checked', config.auto ? 'true' : 'false');
    autoSwitch.disabled = !write;
  }

  $('gamesHint').textContent = 'Scan: ' + (state.scannedAt || 'noch nie') + ' · ' +
    visible.length + ' sichtbar, ' + hiddenEntries.length + ' ausgeblendet.';

  $('gameConfigHint').textContent = state.gameConfigError
    ? state.gameConfigError
    : (write
      ? 'Kill-Liste und eigene Spiele gelten sofort, der Automatik-Schalter beim ' +
        'nächsten Start der Optimierung.'
      : 'Diese Sitzung hat nur Lesezugriff.');
}

/* ---------------- Systemwerte (Phase A2) ----------------
   Quelle: GET /sensors. Abgefragt wird nur, solange der Tab „Systemwerte“
   offen ist (Takt 5 s) – im Hintergrund und in fremden Tabs nie (Regel D1).
   Fehlende Werte bleiben fehlend: kein 0 %, keine Schätzung. */

const SENSOR_UNAVAILABLE = 'Nicht verfügbar';

/// Eine Nachkommastelle mit Komma (deutsche Schreibweise).
function num1(value) {
  return (Math.round(value * 10) / 10).toFixed(1).replace('.', ',');
}

function pctText(value) {
  return num1(value) + ' %';
}

function mbText(mb) {
  return Math.round(mb).toLocaleString('de-DE') + ' MB';
}

function gbText(gb) {
  return num1(gb) + ' GB';
}

/// Der Agent liefert `rx_bps`/`tx_bps` als Bytes pro Sekunde (trotz „bps“).
/// Deshalb durchgehend MB/s bzw. kB/s – nie Bits und Bytes gemischt.
function rateText(bytesPerSecond) {
  if (typeof bytesPerSecond !== 'number' || !isFinite(bytesPerSecond)) return '–';
  const mb = bytesPerSecond / 1048576;
  if (mb >= 1) return num1(mb) + ' MB/s';
  const kb = bytesPerSecond / 1024;
  if (kb >= 1) return Math.round(kb) + ' kB/s';
  return Math.round(bytesPerSecond) + ' B/s';
}

/// Großer Wert in einer Kachel; `plain` nimmt die kleinere Textgröße (.txt).
function setBigText(id, text, plain) {
  const el = $(id);
  if (!el) return;
  el.textContent = text;
  el.classList.toggle('txt', !!plain);
}

/// Farbe eines Balkens nach Auslastung (nur Darstellung, kein Messwert).
function meterClass(percent) {
  if (typeof percent !== 'number') return '';
  if (percent >= 90) return 'danger';
  return percent >= 75 ? 'warn' : '';
}

function setMeter(id, percent) {
  const bar = $(id);
  if (!bar) return;
  const value = typeof percent === 'number' ? Math.max(0, Math.min(100, percent)) : null;
  bar.className = value === null ? '' : meterClass(value);
  bar.style.width = value === null ? '0%' : value.toFixed(1) + '%';
}

/// Lüfter/Temperaturen: nur `available` und `reason` – nie Werte erfinden.
function renderSensorText(idVal, idSub, part, fallback) {
  setBigText(idVal, SENSOR_UNAVAILABLE, true);
  const sub = $(idSub);
  if (sub) sub.textContent = (part && part.reason) || fallback;
}

/// Zählt aufeinanderfolgende Antworten ohne Wert. Der erste Durchlauf ist bei
/// CPU/RAM/GPU/Netzwerk normal (noch keine zwei Stichproben); bleibt der Wert
/// danach `null`, gilt er als nicht lesbar statt als „wird ermittelt“.
function sensorNullRound(key, missing) {
  const seen = missing ? (state.sensorNulls[key] || 0) + 1 : 0;
  state.sensorNulls[key] = seen;
  return seen;
}

function diskRow(disk) {
  const used = typeof disk.percent === 'number'
    ? Math.max(0, Math.min(100, disk.percent))
    : null;
  const cls = used === null ? '' : meterClass(used);
  // Punkt: grün = reichlich frei, rot = fast voll, grau = dazwischen/unbekannt.
  const dot = used === null ? '' : (used >= 90 ? 'off' : (used < 75 ? 'ok' : ''));
  const free = typeof disk.free_gb === 'number' ? gbText(disk.free_gb) + ' frei' : 'frei unbekannt';
  const total = typeof disk.total_gb === 'number' ? ' von ' + gbText(disk.total_gb) : '';
  return '<div class="devrow">' +
    '<span class="dot ' + dot + '" aria-hidden="true"></span>' +
    '<div class="grow">' + esc(disk.name || 'Laufwerk') +
    '<div class="sub">' + esc(free + total) + '</div>' +
    '<div class="meter"><span class="' + cls + '" style="width:' +
    (used === null ? '0' : used.toFixed(1)) + '%"></span></div>' +
    '</div>' +
    '<span class="chip">' + (used === null ? SENSOR_UNAVAILABLE : num1(used) + ' %') + '</span>' +
    '</div>';
}

function renderSensors() {
  if (!$('sysCpuVal')) return;
  const signature = JSON.stringify(state.sensors) + '#' + state.sensorsAt +
    '#' + state.sensorsError;
  if (signature === state.sensorsSignature) return;
  state.sensorsSignature = signature;

  const data = state.sensors;
  const hint = $('sensUpdated');
  if (state.sensorsError) {
    hint.textContent = state.sensorsError;
  } else if (!data) {
    hint.textContent = 'Systemwerte werden geladen …';
  } else {
    hint.textContent = 'Stand: ' + new Date(state.sensorsAt).toLocaleTimeString('de-DE') +
      ' · Abfrage alle 5 s, nur solange dieser Tab offen ist.';
  }

  // CPU: die erste Antwort des Agenten hat `percent: null`.
  const cpu = data && data.cpu;
  if (!data) {
    setBigText('sysCpuVal', '…', true);
    $('sysCpuSub').textContent = 'wird geladen …';
  } else if (!cpu || cpu.available === false) {
    renderSensorText('sysCpuVal', 'sysCpuSub', cpu, 'Der Agent liefert keinen Prozessorwert.');
  } else if (typeof cpu.percent !== 'number') {
    if (sensorNullRound('cpu', true) <= 1) {
      setBigText('sysCpuVal', 'Messwert wird ermittelt …', true);
      $('sysCpuSub').textContent = cpu.cores ? cpu.cores + ' Kerne' : 'erste Messung läuft';
    } else {
      renderSensorText('sysCpuVal', 'sysCpuSub', cpu, 'Der Agent meldet keinen Prozessorwert.');
    }
  } else {
    sensorNullRound('cpu', false);
    setBigText('sysCpuVal', pctText(cpu.percent), false);
    $('sysCpuSub').textContent = cpu.cores ? cpu.cores + ' Kerne' : 'Auslastung';
  }

  // Arbeitsspeicher
  const ram = data && data.ram;
  if (!data) {
    setBigText('sysRamVal', '…', true);
    $('sysRamSub').textContent = 'wird geladen …';
    setMeter('sysRamBar', null);
  } else if (!ram || ram.available === false) {
    renderSensorText('sysRamVal', 'sysRamSub', ram, 'Der Agent liefert keinen Speicherwert.');
    setMeter('sysRamBar', null);
  } else if (typeof ram.percent !== 'number') {
    if (sensorNullRound('ram', true) <= 1) {
      setBigText('sysRamVal', 'Messwert wird ermittelt …', true);
      $('sysRamSub').textContent = 'erste Messung läuft';
    } else {
      renderSensorText('sysRamVal', 'sysRamSub', ram, 'Der Agent meldet keinen Speicherwert.');
    }
    setMeter('sysRamBar', null);
  } else {
    sensorNullRound('ram', false);
    setBigText('sysRamVal', pctText(ram.percent), false);
    $('sysRamSub').textContent =
      (typeof ram.used_mb === 'number' && typeof ram.total_mb === 'number')
        ? mbText(ram.used_mb) + ' von ' + mbText(ram.total_mb)
        : 'belegter Arbeitsspeicher';
    setMeter('sysRamBar', ram.percent);
  }

  // Grafikkarte
  const gpu = data && data.gpu;
  if (!data) {
    setBigText('sysGpuVal', '…', true);
    $('sysGpuName').textContent = '';
    $('sysGpuSub').textContent = 'wird geladen …';
  } else if (!gpu || gpu.available === false) {
    renderSensorText('sysGpuVal', 'sysGpuSub', gpu, 'Der Agent liefert keinen Grafikwert.');
    $('sysGpuName').textContent = '';
  } else if (typeof gpu.percent !== 'number') {
    $('sysGpuName').textContent = gpu.name || '';
    if (sensorNullRound('gpu', true) <= 1) {
      setBigText('sysGpuVal', 'Messwert wird ermittelt …', true);
      $('sysGpuSub').textContent = 'erste Messung läuft';
    } else {
      renderSensorText('sysGpuVal', 'sysGpuSub', gpu, 'Der Agent meldet keinen Grafikwert.');
    }
  } else {
    sensorNullRound('gpu', false);
    setBigText('sysGpuVal', pctText(gpu.percent), false);
    $('sysGpuName').textContent = gpu.name || 'Grafikkarte';
    $('sysGpuSub').textContent =
      (typeof gpu.memory_used_mb === 'number' && typeof gpu.memory_total_mb === 'number')
        ? 'Speicher ' + mbText(gpu.memory_used_mb) + ' von ' + mbText(gpu.memory_total_mb)
        : 'Auslastung';
  }

  // Netzwerk
  const net = data && data.network;
  if (!data) {
    setBigText('sysNetName', '…', true);
    $('sysNetRx').textContent = 'Empfang –';
    $('sysNetTx').textContent = 'Senden –';
  } else if (!net || net.available === false) {
    renderSensorText('sysNetName', 'sysNetRx', net, 'Der Agent liefert keine Netzwerkwerte.');
    $('sysNetTx').textContent = '';
  } else {
    setBigText('sysNetName', net.name || 'Netzwerk', true);
    // Erst die zweite Stichprobe ergibt eine Rate; bleibt sie aus: „nicht messbar“.
    const hasRate = typeof net.rx_bps === 'number' || typeof net.tx_bps === 'number';
    const unknown = sensorNullRound('net', !hasRate) <= 1
      ? 'Messwert wird ermittelt …'
      : 'nicht messbar';
    $('sysNetRx').textContent = 'Empfang: ' +
      (typeof net.rx_bps === 'number' ? rateText(net.rx_bps) : unknown);
    $('sysNetTx').textContent = 'Senden: ' +
      (typeof net.tx_bps === 'number' ? rateText(net.tx_bps) : unknown);
  }

  // Datenträger
  const disks = (data && Array.isArray(data.disks)) ? data.disks : [];
  const diskBox = $('sysDisks');
  if (!data) {
    diskBox.innerHTML = '<div class="mut">wird geladen …</div>';
  } else if (!disks.length) {
    diskBox.innerHTML = '<div class="mut">Keine Datenträger gemeldet.</div>';
  } else {
    diskBox.innerHTML = disks.map(diskRow).join('');
  }

  // Lüfter und Temperaturen: ohne Zusatzbibliothek nicht lesbar.
  const fans = data && data.fans;
  const temps = data && data.temperatures;
  if (!data) {
    setBigText('sysFansVal', '…', true);
    $('sysFansSub').textContent = 'wird geladen …';
    setBigText('sysTempVal', '…', true);
    $('sysTempSub').textContent = 'wird geladen …';
  } else {
    renderSensorText('sysFansVal', 'sysFansSub', fans,
      'Der Agent kann Lüfterdrehzahlen nicht lesen.');
    renderSensorText('sysTempVal', 'sysTempSub', temps,
      'Der Agent kann Temperaturen nicht lesen.');
  }
}

let sensorsTimer = null;

function startSensors() {
  if (document.hidden) return;
  if (!sensorsTimer) sensorsTimer = window.setInterval(refreshSensors, SENSORS_MS);
  refreshSensors();
}

function stopSensors() {
  if (sensorsTimer) {
    window.clearInterval(sensorsTimer);
    sensorsTimer = null;
  }
}

async function refreshSensors() {
  // Pflichtregel D1: nie im Hintergrund, nie in einem anderen Tab.
  if (document.hidden || state.activeTab !== 'system') return;
  if (!state.connected) {
    state.sensors = null;
    state.sensorsError = 'Keine Verbindung zum Agenten – keine Systemwerte.';
    renderSensors();
    return;
  }
  try {
    state.sensors = await api('/sensors');
    state.sensorsAt = Date.now();
    state.sensorsError = '';
  } catch (error) {
    state.sensors = null;
    state.sensorsError = 'Systemwerte konnten nicht geladen werden: ' + error.message;
  }
  renderSensors();
}

/* ---------------- Warnungen (Phase C) ----------------
   Quelle: GET /warnings; speichern mit POST /warnings (immer der komplette
   Regelsatz). Abgefragt wird nur im Tab „Warnungen“, Takt 30 s = interval_s. */

const WARN_METRIC_SHORT = {
  cpu_percent: 'CPU',
  ram_percent: 'RAM',
  gpu_percent: 'GPU',
  disk_free_percent: 'Frei auf Platte',
};

function warnMetricShort(metric) {
  return WARN_METRIC_SHORT[metric] || String(metric || 'Messwert');
}

/// Kurztext einer Regel, z. B. „> 90,0 % · 30 s“.
function warnRuleText(rule) {
  const op = rule.op === '<' ? '<' : '>';
  const threshold = typeof rule.threshold === 'number' ? num1(rule.threshold) : '–';
  const duration = typeof rule.duration_s === 'number' ? rule.duration_s : 0;
  return op + ' ' + threshold + ' % · ' + duration + ' s';
}

function renderWarnings() {
  if (!$('warnRules')) return;
  const signature = JSON.stringify(state.warnings) + '#' + canWrite() + '#' +
    state.warningsError;
  if (signature !== state.warningsSignature) {
    state.warningsSignature = signature;
    renderWarnRules();
    renderWarnActive();
    renderWarnHistory();
  }

  const hint = $('warnRulesHint');
  const data = state.warnings;
  if (state.warningsError) {
    hint.textContent = state.warningsError;
  } else if (!data) {
    hint.textContent = 'Warnungen werden geladen …';
  } else {
    const every = typeof data.interval_s === 'number' ? data.interval_s : WARNINGS_MS / 1000;
    hint.textContent = 'Zuletzt geprüft: ' + (data.checked_at || 'noch nicht') +
      ' · Takt ' + every + ' s';
  }

  $('warnEditor').hidden = !canWrite();
  $('warnFormHint').textContent = canWrite()
    ? 'Höchstens ' + WARN_RULES_MAX +
      ' Regeln. Dauer in Sekunden – mehr als 3600 s lehnt der Agent ab.'
    : 'Diese Sitzung hat nur Lesezugriff.';
}

function renderWarnRules() {
  const box = $('warnRules');
  const data = state.warnings;
  if (!data) {
    box.innerHTML = '<div class="mut">Wird geladen …</div>';
    return;
  }
  const rules = Array.isArray(data.rules) ? data.rules : [];
  if (!rules.length) {
    box.innerHTML = '<div class="mut">Keine Regel angelegt – der Agent warnt dann nie.</div>';
    return;
  }
  const write = canWrite();
  box.innerHTML = rules.map((rule) => {
    const label = rule.label || warnMetricShort(rule.metric);
    return '<div class="devrow" data-warn="' + esc(rule.id) + '">' +
      '<div class="grow">' + esc(label) +
      '<div class="sub">' + esc(warnMetricShort(rule.metric)) + ' ' +
      esc(warnRuleText(rule)) + '</div></div>' +
      (write
        ? '<button type="button" class="switch" role="switch" aria-checked="' +
          (rule.enabled ? 'true' : 'false') + '" aria-label="Regel ein- oder ausschalten" ' +
          'data-warn-toggle="' + esc(rule.id) + '"></button>' +
          '<button type="button" data-warn-remove="' + esc(rule.id) + '">Löschen</button>'
        : '<span class="chip">' + (rule.enabled ? 'an' : 'aus') + '</span>') +
      '</div>';
  }).join('');

  box.querySelectorAll('[data-warn-toggle]').forEach((button) => {
    const rule = rules.find((entry) => entry.id === button.dataset.warnToggle);
    if (!rule) return;
    button.onclick = () => saveWarningRules(rules.map((entry) => entry.id === rule.id
      ? Object.assign({}, entry, { enabled: !rule.enabled })
      : entry));
  });
  box.querySelectorAll('[data-warn-remove]').forEach((button) => {
    const rule = rules.find((entry) => entry.id === button.dataset.warnRemove);
    if (!rule) return;
    button.onclick = () => removeWarningRule(rule, rules);
  });
}

function renderWarnActive() {
  const box = $('warnActive');
  const data = state.warnings;
  if (!data) {
    box.innerHTML = '<div class="mut">Wird geladen …</div>';
    return;
  }
  const list = Array.isArray(data.active) ? data.active : [];
  if (!list.length) {
    box.innerHTML = '<div class="mut">Keine aktive Warnung</div>';
    return;
  }
  box.innerHTML = list.map((entry) =>
    '<div class="devrow">' +
    '<span class="dot off" aria-hidden="true"></span>' +
    '<div class="grow">' + esc(entry.message) +
    '<div class="sub">seit ' + esc(fmtTime(entry.since_s || 0)) + '</div></div>' +
    '</div>').join('');
}

function renderWarnHistory() {
  const box = $('warnHistory');
  const data = state.warnings;
  if (!data) {
    box.textContent = 'Wird geladen …';
    return;
  }
  const list = Array.isArray(data.triggered) ? data.triggered : [];
  if (!list.length) {
    box.textContent = 'Noch keine Warnung ausgelöst.';
    return;
  }
  box.innerHTML = list.slice(0, 20).map((entry) => {
    const time = entry.since ? String(entry.since).slice(11) : '–';
    return '<div>' + esc(time) + ' · ' + esc(entry.message) + '</div>';
  }).join('');
}

/// Eindeutige, erlaubte Regel-ID aus Messwert und Vergleich (a–z, 0–9, -_).
function nextWarningId(rules, metric, op) {
  const base = metric.replace(/_percent$/, '').replace(/_/g, '-') +
    (op === '<' ? '-low' : '-high');
  const used = rules.map((rule) => rule.id);
  if (used.indexOf(base) < 0) return base;
  for (let i = 2; i < 100; i++) {
    const candidate = base + '-' + i;
    if (candidate.length <= 40 && used.indexOf(candidate) < 0) return candidate;
  }
  return 'regel-' + Date.now().toString(36);
}

/// Speichert den kompletten Regelsatz und lädt danach neu.
async function saveWarningRules(rules) {
  if (!guardWrite()) return;
  const button = $('btnAddWarning');
  if (button) button.disabled = true;
  try {
    await api('/warnings', { method: 'POST', body: JSON.stringify({ rules }) });
    clearBanner();
  } catch (error) {
    // 422 vom Agenten (z. B. Dauer zu lang) als Bannertext zeigen.
    showBanner('Warnregeln nicht gespeichert: ' + error.message, 'warn');
  } finally {
    if (button) button.disabled = false;
    await refreshWarnings();
    render();
  }
}

async function addWarningRule() {
  if (!guardWrite()) return;
  const metric = $('warnMetric').value;
  const op = $('warnOp').value === '<' ? '<' : '>';
  const thresholdRaw = $('warnThreshold').value.trim();
  const durationRaw = $('warnDuration').value.trim();
  const threshold = Number(thresholdRaw.replace(',', '.'));
  const duration = parseInt(durationRaw, 10);
  if (!thresholdRaw || !isFinite(threshold) || threshold < 0 || threshold > 100) {
    showBanner('Die Schwelle muss eine Zahl zwischen 0 und 100 sein (Prozent).', 'warn');
    return;
  }
  if (!durationRaw || !isFinite(duration) || duration < 0 || duration > 86400) {
    showBanner('Die Dauer muss zwischen 0 und 86400 Sekunden liegen.', 'warn');
    return;
  }
  const rules = ((state.warnings && state.warnings.rules) || []).slice();
  if (rules.length >= WARN_RULES_MAX) {
    showBanner('Mehr als ' + WARN_RULES_MAX + ' Regeln sind nicht möglich.', 'warn');
    return;
  }
  rules.push({
    id: nextWarningId(rules, metric, op),
    // Leerer Name: der Agent setzt den deutschen Namen zum Messwert ein.
    label: '',
    metric,
    op,
    threshold,
    duration_s: duration,
    enabled: $('warnEnabled').checked,
  });
  await saveWarningRules(rules);
}

async function removeWarningRule(rule, rules) {
  if (!guardWrite()) return;
  const ok = await askConfirm({
    title: 'Warnregel löschen',
    text: 'Regel „' + (rule.label || warnMetricShort(rule.metric)) + '“ (' +
      warnMetricShort(rule.metric) + ' ' + warnRuleText(rule) + ') löschen?',
    ok: 'Löschen',
    danger: true,
  });
  if (!ok) return;
  await saveWarningRules(rules.filter((entry) => entry.id !== rule.id));
}

let warningsTimer = null;

function startWarnings() {
  if (document.hidden) return;
  if (!warningsTimer) warningsTimer = window.setInterval(refreshWarnings, WARNINGS_MS);
  refreshWarnings();
}

function stopWarnings() {
  if (warningsTimer) {
    window.clearInterval(warningsTimer);
    warningsTimer = null;
  }
}

async function refreshWarnings() {
  // Pflichtregel D1: nie im Hintergrund, nie in einem anderen Tab.
  if (document.hidden || state.activeTab !== 'warnings') return;
  if (!state.connected) {
    state.warningsError = 'Keine Verbindung zum Agenten – keine Warnungen.';
    renderWarnings();
    return;
  }
  try {
    state.warnings = await api('/warnings');
    state.warningsError = '';
  } catch (error) {
    state.warningsError = 'Warnungen konnten nicht geladen werden: ' + error.message;
  }
  renderWarnings();
}

/* ---------------- Tab-Wechsel steuert die Tab-Abfragen ---------------- */

/// Startet/stoppt die Abfragen, die nur zum offenen Tab gehören (Regel D1).
function syncTabPolling() {
  if (state.activeTab === 'system') startSensors();
  else stopSensors();
  if (state.activeTab === 'warnings') startWarnings();
  else stopWarnings();
  // Plugins: Ausgabe-Takt nur bei offener Detailansicht (3 s), sonst gar nichts.
  syncPluginDetailPolling();
  // Marktplatz (Phase W1): nur beim Wechsel auf den Plugins-Tab laden, kein Takt.
  if (state.activeTab === 'plugins') refreshCatalog();
  // Routinen: Vorlagen und die Plugin-Auswahl der Bedingungen nachladen.
  if (state.activeTab === 'routines') {
    loadRoutineTemplates();
    renderRoutinePluginOptions();
  }
  // Log (Phase W2): Ereignisse und Logdatei nur beim offenen Log-Tab, kein Takt.
  if (state.activeTab === 'log') refreshLogTab();
}

function selectTab(tab) {
  state.activeTab = tab;
  document.querySelectorAll('.tab').forEach((button) => {
    const on = button.dataset.tab === tab;
    button.classList.toggle('on', on);
    button.setAttribute('aria-selected', on ? 'true' : 'false');
  });
  document.querySelectorAll('[data-tab]').forEach((el) => {
    if (el.classList.contains('tab')) return;
    el.hidden = el.dataset.tab !== tab;
  });
  // Beide Plugin-Kacheln tragen data-tab="plugins" – die Detailansicht
  // übersteuert das Raster, sobald sie offen ist.
  applyPluginDetailVisibility();
  try { localStorage.setItem('pcTab', tab); } catch (e) { /* privater Modus */ }
  syncTabPolling();
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
    // Nach einer Aktion den Verlauf nachziehen – nur wenn der Log-Tab offen ist.
    refreshLogTab();
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

/* ---- Logdatei: Aufbewahrung und Laden (nur im offenen Log-Tab) ---- */

/// Gespeicherte Zeilenzahl (localStorage `pcLogLines`), Standard 200.
function currentLogLines() {
  let stored = null;
  try { stored = localStorage.getItem(LOG_LINES_KEY); } catch (e) { stored = null; }
  const value = Number(stored);
  return LOG_LINES_CHOICES.indexOf(value) >= 0 ? value : LOG_LINES_DEFAULT;
}

/// Auswahl im Feld „200/500/1000 Zeilen“ übernehmen und sofort nachladen.
function setLogLines(raw) {
  const value = Number(raw);
  const lines = LOG_LINES_CHOICES.indexOf(value) >= 0 ? value : LOG_LINES_DEFAULT;
  try { localStorage.setItem(LOG_LINES_KEY, String(lines)); } catch (e) { /* privater Modus */ }
  const select = $('logLines');
  if (select) select.value = String(lines);
  refreshLogTab();
}

/// Ereignisse und Logdatei nachladen – nur im aktiven Log-Tab (Regel D1).
async function refreshLogTab() {
  if (document.hidden || state.activeTab !== 'log') return;
  if (!state.connected) {
    const fileBox = $('fileLog');
    if (fileBox) fileBox.textContent = 'Keine Verbindung zum Agenten – kein Verlauf.';
    return;
  }
  try {
    state.events = (await api('/events')).events || [];
  } catch (error) {
    // Liste bleibt stehen; die Logdatei wird trotzdem versucht.
  }
  renderEvents();
  await refreshLog();
}

async function refreshLog() {
  // Pflichtregel D1: nie im Hintergrund, nie in einem anderen Tab.
  if (document.hidden || state.activeTab !== 'log') return;
  const box = $('fileLog');
  if (!box) return;
  if (!state.connected) {
    box.textContent = 'Keine Verbindung zum Agenten – kein Verlauf.';
    return;
  }
  try {
    const text = await api('/log?lines=' + currentLogLines());
    box.textContent = (text || '').trim() || 'noch keine Einträge';
  } catch (error) {
    box.textContent = 'Log konnte nicht geladen werden: ' + error.message;
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
      state.steam = await api('/steam');
    } catch (error) {
      state.steam = null;
    }
    // Ereignisse (`/events`) werden nur im Log-Tab geladen – refreshLogTab().
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
    // Die Logdatei haengt am offenen Log-Tab (kein Takt, siehe syncTabPolling).
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
  // Tab-gebundene Daten gehören zum alten Agenten.
  state.sensors = null;
  state.sensorsAt = 0;
  state.sensorsError = '';
  state.sensorsSignature = '';
  state.sensorNulls = { cpu: 0, ram: 0, gpu: 0, net: 0 };
  state.warnings = null;
  state.warningsError = '';
  state.warningsSignature = '';
  // Plugins (Raster/Detail) und Spiel-Konfiguration gehören zum alten Agenten.
  state.pluginDetail = null;
  state.pluginTileSignature = '';
  state.pluginOutput = '';
  state.pluginOutputError = '';
  state.pluginSettings = null;
  state.pluginSettingsValues = {};
  state.pluginSettingsError = '';
  state.gameConfig = null;
  state.gameConfigError = '';
  state.gameDetailSignature = '';
  // Marktplatz (Phase W1): der Katalog gehört zum alten Agenten.
  state.catalog = null;
  state.catalogVersion = 0;
  state.catalogError = '';
  state.catalogInstalling = '';
  state.catalogSignature = '';
  stopPluginDetailPolling();
  // Routinen: Vorlagen erneut laden, Verlauf zuklappen.
  state.routineTemplates = [];
  state.routineTemplatesLoaded = false;
  state.expandedRoutine = '';
  condPluginSignature = '';
  templateButtonsSignature = '';
  steamDetailSignature = '';
  statusSignature = '';
  eventsSignature = '';
  usersSignature = '';
  // Log/Verlauf (Phase W2): Filter und Suche gehören zum alten Agenten.
  state.logFilter = 'all';
  state.logQuery = '';
  const searchBox = $('logSearch');
  if (searchBox) searchBox.value = '';
  syncLogFilterChips();
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
  syncTabPolling();
}


/* ---------------- Routinen (Phase 4 + Phase E) ----------------
   Vertrag: GET /routines liefert `now`, `routines[]` mit `trigger`, `actions`,
   `last_run`, `history` (max. 5), `next_run` und `next_in`. Auslöser sind
   `time` (HH:MM + Wochentage), `manual` oder `event` (game_stopped /
   download_done / idle); Bedingungen stehen unter `trigger.conditions`. */

const DAY_LABELS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
// Kurztexte der Ereignis-Auslöser (Spiegel von routines::EVENTS im Agenten).
const ROUTINE_EVENTS = {
  game_stopped: 'Spiel beendet',
  download_done: 'Download fertig',
  idle: 'PC inaktiv',
};

/// "Mo–Fr 18:00", "Bei Download fertig", "Manuell".
function triggerText(routine) {
  const trigger = routine.trigger || {};
  if (trigger.type === 'event') {
    const label = ROUTINE_EVENTS[trigger.event] || trigger.event || 'Ereignis';
    const minutes = typeof trigger.idle_minutes === 'number' ? trigger.idle_minutes : 0;
    return 'Bei ' + label + (trigger.event === 'idle' && minutes ? ' (' + minutes + ' min)' : '');
  }
  if (trigger.type !== 'time') return 'Manuell';
  const days = Array.isArray(trigger.days) ? trigger.days : [];
  let dayText = 'Täglich';
  if (days.length && days.length < 7) {
    const workdays = days.length === 5 && days.every((day) => day <= 5);
    const weekend = days.length === 2 && days.indexOf(6) >= 0 && days.indexOf(7) >= 0;
    dayText = workdays ? 'Mo–Fr'
      : (weekend ? 'Sa–So' : days.map((day) => DAY_LABELS[day - 1]).join(', '));
  }
  return dayText + ' ' + (trigger.at || '--:--');
}

/// Kurztext der Bedingungen (leer = keine Einschränkung).
function conditionsText(routine) {
  const trigger = routine.trigger || {};
  const conditions = trigger.conditions || {};
  const parts = [];
  const days = Array.isArray(conditions.days) ? conditions.days : [];
  if (days.length) parts.push(days.map((day) => DAY_LABELS[day - 1]).join(', '));
  if (conditions.plugin) parts.push('Plugin ' + conditions.plugin);
  if (conditions.power_plan) {
    parts.push('Plan ' + (PLANS[conditions.power_plan] || conditions.power_plan));
  }
  return parts.join(' · ');
}

/// "Nächster Lauf: 2026-11-24 18:00 (in 20 h)" aus `next_run`/`next_in`.
function nextText(routine) {
  if (!routine.enabled) return 'aus';
  const trigger = routine.trigger || {};
  if (routine.next_run) {
    const soon = (typeof routine.next_in === 'number' && routine.next_in > 0)
      ? ' (in ' + fmtTime(routine.next_in) + ')' : '';
    return 'Nächster Lauf: ' + routine.next_run + soon;
  }
  return trigger.type === 'time' ? 'Nächster Lauf: –' : 'kein fester Termin';
}

function actionListText(routine) {
  const actions = Array.isArray(routine.actions) ? routine.actions : [];
  return actions.map((action) => {
    const wait = (typeof action.wait === 'number' && action.wait > 0)
      ? ' (nach ' + action.wait + ' s)' : '';
    if (action.type === 'shutdown') return 'Herunterfahren in ' + action.delay + ' s' + wait;
    if (action.type === 'hibernate') return 'Ruhezustand' + wait;
    if (action.type === 'power_plan') {
      return 'Energieplan ' + (PLANS[action.plan] || action.plan) + wait;
    }
    return String(action.type) + wait;
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
    ? 'Höchstens 20 Routinen, drei Schritte je Routine. Herunterfahren beendet den PC – deshalb nur als letzter Schritt.'
    : 'Diese Sitzung hat nur Lesezugriff.';
  renderRoutinePluginOptions();
  renderRoutineTemplates();

  const list = state.routines || [];
  const signature = JSON.stringify(list) + '#' + canWrite() + '#' + state.expandedRoutine;
  if (signature === state.routineSignature) return;
  state.routineSignature = signature;

  if (!list.length) {
    box.innerHTML = '<div class="mut">Noch keine Routinen.</div>';
    return;
  }

  box.innerHTML = list.map((routine) => {
    const open = state.expandedRoutine === routine.id;
    const history = Array.isArray(routine.history) ? routine.history.slice(0, 5) : [];
    const conditions = conditionsText(routine);
    return '<div class="devrow cols" data-id="' + esc(routine.id) + '">' +
      '<span class="dot ' + (routine.enabled ? 'ok' : '') + '" aria-hidden="true"></span>' +
      '<div class="grow">' + esc(routine.name) +
      '<div class="sub">' + esc(triggerText(routine)) + ' · ' + esc(nextText(routine)) + '</div>' +
      '<div class="sub">' + esc(actionListText(routine)) +
      (conditions ? ' · nur ' + esc(conditions) : '') + '</div></div>' +
      '<button type="button" data-act="history" aria-expanded="' + (open ? 'true' : 'false') +
      '">' + (open ? 'Verlauf ▲' : 'Verlauf ▼') + '</button>' +
      (canWrite()
        ? '<button type="button" data-act="toggle">' + (routine.enabled ? 'Aus' : 'An') + '</button>' +
          '<button type="button" data-act="run">Jetzt</button>' +
          '<button type="button" data-act="remove">Entfernen</button>'
        : '<span class="chip">' + (routine.enabled ? 'an' : 'aus') + '</span>') +
      (open
        ? '<div class="full log">' + (history.length
          ? history.map((entry) => esc(String(entry.at || '') + ' · ' + String(entry.text || '')))
            .join('<br>')
          : 'Noch kein Lauf.') + '</div>'
        : '') +
      '</div>';
  }).join('');

  box.querySelectorAll('.devrow').forEach((row) => {
    const routine = list.find((entry) => entry.id === row.dataset.id);
    if (!routine) return;
    const history = row.querySelector('[data-act="history"]');
    if (history) history.onclick = () => toggleRoutineHistory(routine);
    const toggle = row.querySelector('[data-act="toggle"]');
    if (toggle) toggle.onclick = () => runAction(() => toggleRoutine(routine));
    const run = row.querySelector('[data-act="run"]');
    if (run) run.onclick = () => runAction(() => runRoutine(routine));
    const remove = row.querySelector('[data-act="remove"]');
    if (remove) remove.onclick = () => runAction(() => removeRoutine(routine));
  });
}

/// Klappt den Verlauf einer Routine auf oder zu (höchstens einer gleichzeitig).
function toggleRoutineHistory(routine) {
  state.expandedRoutine = state.expandedRoutine === routine.id ? '' : routine.id;
  state.routineSignature = '';
  renderRoutines();
}

/// Ergebnis eines Laufs/Anlegens unter der Liste anzeigen (kein Fehler-Banner).
function showRoutineResult(text) {
  const line = $('routineLastRun');
  if (line) line.textContent = text || '';
}

async function toggleRoutine(routine) {
  // Immer das ganze Objekt senden; `next_run`/`next_in` ignoriert der Agent.
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
  const answer = await api('/routines/' + encodeURIComponent(routine.id) + '/run',
    { method: 'POST' });
  // Antwort ist `{"ok":true,"detail":"..."}` (die Kette läuft danach weiter).
  showRoutineResult('„' + routine.name + '“ gestartet: ' +
    ((answer && answer.detail) || 'Aktionen laufen.'));
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
  if (state.expandedRoutine === routine.id) state.expandedRoutine = '';
  await refreshRoutines();
  showRoutineResult('„' + routine.name + '“ entfernt.');
}

function selectedRoutineDays() {
  return Array.prototype.slice.call(document.querySelectorAll('#routineDays button.on'))
    .map((button) => Number(button.dataset.day));
}

function selectedConditionDays() {
  return Array.prototype.slice.call(document.querySelectorAll('#routineCondDays button.on'))
    .map((button) => Number(button.dataset.day));
}

/// Zeigt nur die Felder des gewählten Auslösers (Uhrzeit oder Ereignis).
function updateRoutineTriggerForm() {
  const select = $('routineTrigger');
  const kind = select ? select.value : 'time';
  const timeBox = $('routineTimeBox');
  const eventBox = $('routineEventBox');
  if (timeBox) timeBox.hidden = kind !== 'time';
  if (eventBox) eventBox.hidden = kind !== 'event';
  const idle = $('routineIdle');
  if (idle) idle.hidden = ($('routineEvent') && $('routineEvent').value !== 'idle');
}

/* ---- Schritte des Formulars (Phase E3: 1-3 Aktionen mit Wartezeit) ---- */

const ROUTINE_STEP_LABELS = {
  hibernate: 'Ruhezustand',
  power_plan: 'Energieplan',
  shutdown: 'Herunterfahren',
};

/// Baut die Schritt-Zeilen; die Werte stehen als Rohtext im Zustand, damit ein
/// Neuzeichnen (z. B. nach einem Wechsel der Aktion) nichts verliert.
function renderRoutineSteps() {
  const box = $('routineSteps');
  if (!box) return;
  const steps = state.routineSteps || [];
  const write = canWrite();
  if (!steps.length) {
    box.innerHTML = '<div class="mut">Kein Schritt – bitte „Schritt hinzufügen“ nutzen.</div>';
    return;
  }
  box.innerHTML = steps.map((step, index) => {
    const number = index + 1;
    const options = Object.keys(ROUTINE_STEP_LABELS).map((value) =>
      '<option value="' + value + '"' + (step.type === value ? ' selected' : '') + '>' +
      ROUTINE_STEP_LABELS[value] + '</option>').join('');
    const plans = Object.keys(PLANS).map((key) =>
      '<option value="' + key + '"' + (step.plan === key ? ' selected' : '') + '>' +
      PLANS[key] + '</option>').join('');
    return '<div class="devrow cols" data-step-row="' + index + '">' +
      '<div class="grow">Schritt ' + number +
      '<div class="sub">' + esc(ROUTINE_STEP_LABELS[step.type] || step.type) +
      (step.type === 'shutdown' ? ' in ' + esc(String(step.delay || '0')) + ' s' : '') +
      ' · Wartezeit davor ' + esc(String(step.wait || '0')) + ' s</div></div>' +
      (write && steps.length > 1
        ? '<button type="button" data-step-remove="' + index + '">Entfernen</button>' : '') +
      '<div class="row" style="margin-top:8px">' +
      '<select data-step="' + index + '" data-field="type" aria-label="Aktion für Schritt ' +
      number + '"' + (write ? '' : ' disabled') + '>' + options + '</select>' +
      '<input data-step="' + index + '" data-field="wait" type="number" min="0" max="' +
      ROUTINE_WAIT_MAX + '" step="1" value="' + esc(String(step.wait || '0')) +
      '" aria-label="Wartezeit vor Schritt ' + number + ' in Sekunden"' +
      (write ? '' : ' disabled') + '>' +
      (step.type === 'shutdown'
        ? '<input data-step="' + index + '" data-field="delay" type="number" min="0" max="86400" ' +
          'step="10" value="' + esc(String(step.delay || '60')) +
          '" aria-label="Countdown von Schritt ' + number + ' in Sekunden"' +
          (write ? '' : ' disabled') + '>'
        : '') +
      (step.type === 'power_plan'
        ? '<select data-step="' + index + '" data-field="plan" aria-label="Energieplan für Schritt ' +
          number + '"' + (write ? '' : ' disabled') + '>' + plans + '</select>'
        : '') +
      '</div>' +
      '<div class="full sub">Reihenfolge: Aktion · Wartezeit davor (0–' + ROUTINE_WAIT_MAX +
      ' s) · Countdown · Energieplan. Herunterfahren nur als letzter Schritt.</div>' +
      '</div>';
  }).join('');
}

/// Eingaben der Schritte übernehmen; ein Wechsel der Aktion zeichnet neu.
function onRoutineStepInput(event) {
  const element = event.target.closest('[data-field]');
  if (!element) return;
  const index = Number(element.dataset.step);
  const step = state.routineSteps[index];
  if (!step) return;
  const field = element.dataset.field;
  step[field] = element.value;
  if (field !== 'type') return;

  // Herunterfahren beendet den PC: hoechstens einmal und nur am Ende.
  if (step.type === 'shutdown') {
    state.routineSteps.forEach((other, otherIndex) => {
      if (otherIndex !== index && other.type === 'shutdown') other.type = 'hibernate';
    });
    if (index !== state.routineSteps.length - 1) {
      state.routineSteps.splice(index, 1);
      state.routineSteps.push(step);
    }
  }
  renderRoutineSteps();
}

/// Rohtext aus einem Zahlenfeld pruefen; `null` = ungueltig.
function stepNumber(raw, min, max, fallback) {
  const text = String(raw === undefined || raw === null ? '' : raw).trim().replace(',', '.');
  if (!text) return fallback;
  const value = Number(text);
  if (!isFinite(value) || value < min || value > max) return null;
  return Math.round(value);
}

/// Baut `actions` aus den Formular-Schritten (mit Pruefung im UI).
function routineActionsFromSteps() {
  const steps = state.routineSteps || [];
  if (!steps.length) {
    showBanner('Bitte mindestens einen Schritt anlegen.', 'warn');
    return null;
  }
  if (steps.length > ROUTINE_MAX_STEPS) {
    showBanner('Höchstens ' + ROUTINE_MAX_STEPS + ' Schritte je Routine.', 'warn');
    return null;
  }
  const actions = [];
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    const wait = stepNumber(step.wait, 0, ROUTINE_WAIT_MAX, 0);
    if (wait === null) {
      showBanner('Wartezeit vor Schritt ' + (i + 1) + ' muss zwischen 0 und ' +
        ROUTINE_WAIT_MAX + ' Sekunden liegen.', 'warn');
      return null;
    }
    if (step.type === 'shutdown') {
      if (i + 1 !== steps.length) {
        showBanner('Herunterfahren muss der letzte Schritt sein.', 'warn');
        return null;
      }
      const delay = stepNumber(step.delay, 0, 86400, 60);
      if (delay === null) {
        showBanner('Der Countdown von Schritt ' + (i + 1) +
          ' muss zwischen 0 und 86400 Sekunden liegen.', 'warn');
        return null;
      }
      actions.push({ type: 'shutdown', delay, wait });
    } else if (step.type === 'hibernate') {
      actions.push({ type: 'hibernate', wait });
    } else if (step.type === 'power_plan') {
      actions.push({ type: 'power_plan', plan: step.plan || 'high', wait });
    } else {
      showBanner('Unbekannter Schritt ' + (i + 1) + '.', 'warn');
      return null;
    }
  }
  return actions;
}

/* ---- Bedingungen: Plugin-Auswahl aus GET /plugins ---- */

let condPluginSignature = '';

function renderRoutinePluginOptions() {
  const select = $('routineCondPlugin');
  if (!select) return;
  const plugins = state.plugins || [];
  const signature = plugins.map((item) => item.id).join(',');
  if (signature === condPluginSignature) return;
  condPluginSignature = signature;
  const current = select.value;
  select.innerHTML = '<option value="">Plugin: egal</option>' + plugins.map((item) =>
    '<option value="' + esc(item.id) + '">' + esc(item.name || item.id) + '</option>').join('');
  select.value = plugins.some((item) => item.id === current) ? current : '';
}

/* ---- Vorlagen (Phase E5, GET /routine-templates) ---- */

/// Vorlagen einmal je Geraet laden; 404 = alter Agent, Abschnitt bleibt aus.
async function loadRoutineTemplates() {
  if (state.routineTemplatesLoaded || !state.connected) return;
  try {
    const data = await api('/routine-templates');
    state.routineTemplates = Array.isArray(data && data.templates) ? data.templates : [];
    state.routineTemplatesLoaded = true;
  } catch (error) {
    state.routineTemplates = [];
    // 404 heisst „kennt der Agent nicht“ – kein zweiter Versuch, kein Fehler.
    state.routineTemplatesLoaded = !!(error && error.status === 404);
  }
  renderRoutineTemplates();
}

let templateButtonsSignature = '';

function renderRoutineTemplates() {
  const box = $('routineTemplates');
  const host = $('routineTemplateBtns');
  if (!box || !host) return;
  const templates = state.routineTemplates || [];
  if (!templates.length) {
    box.hidden = true;
    host.innerHTML = '';
    templateButtonsSignature = '';
    return;
  }
  box.hidden = false;
  const signature = templates.map((template) => template.id).join(',') + '#' + canWrite();
  if (signature === templateButtonsSignature) return;
  templateButtonsSignature = signature;
  const write = canWrite();
  host.innerHTML = templates.map((template) =>
    '<button type="button" class="ghost" data-template="' + esc(template.id) + '"' +
    (write ? '' : ' disabled') + '>' + esc(template.name || template.id) + '</button>').join('');
}

/// Frische Routine aus einer Vorlage: freie ID, kein Verlauf, eingeschaltet.
function routineFromTemplate(template) {
  const used = (state.routines || []).map((entry) => entry.id);
  const base = String(template.id || 'routine').replace(/[^a-z0-9_-]/gi, '-') || 'routine';
  let id = base;
  if (used.indexOf(id) >= 0) {
    id = '';
    for (let i = 2; i < 100; i++) {
      if (used.indexOf(base + '-' + i) < 0) { id = base + '-' + i; break; }
    }
    if (!id) id = base + '-' + Date.now().toString(36);
  }
  const trigger = Object.assign({
    type: 'manual', at: '', days: [], event: '', idle_minutes: 30,
    conditions: { days: [], plugin: '', power_plan: '' },
  }, template.trigger || {});
  const actions = Array.isArray(template.actions)
    ? template.actions.map((action) => Object.assign(
      { type: 'hibernate', delay: 60, plan: '', wait: 0 }, action))
    : [];
  return { id, name: template.name || base, enabled: true, trigger, actions };
}

async function createRoutineFromTemplate(templateId) {
  if (!guardWrite()) return;
  const template = (state.routineTemplates || []).find((entry) => entry.id === templateId);
  if (!template) return;
  const body = routineFromTemplate(template);
  const ok = await askConfirm({
    title: 'Vorlage anlegen',
    text: 'Routine „' + body.name + '“ aus der Vorlage anlegen?\n' +
      triggerText(body) + '\n' + actionListText(body),
    ok: 'Anlegen',
  });
  if (!ok) return;
  try {
    await api('/routines', { method: 'POST', body: JSON.stringify(body) });
    clearBanner();
    showRoutineResult('Vorlage „' + body.name + '“ als „' + body.id + '“ angelegt.');
    await refreshRoutines();
  } catch (error) {
    showBanner('Vorlage konnte nicht angelegt werden: ' + error.message, 'warn');
  }
  render();
}

/* ---- Neue Routine aus dem Formular (POST /routines mit dem ganzen Objekt) ---- */

async function addRoutine() {
  if (!guardWrite()) return;
  const name = $('routineName').value.trim();
  if (!name) {
    showBanner('Bitte einen Namen für die Routine angeben.', 'warn');
    return;
  }

  const kind = $('routineTrigger').value;
  const trigger = {
    type: kind === 'event' ? 'event' : (kind === 'manual' ? 'manual' : 'time'),
    at: '',
    days: [],
    event: '',
    idle_minutes: 30,
    conditions: {
      days: selectedConditionDays(),
      plugin: $('routineCondPlugin').value,
      power_plan: $('routineCondPlan').value,
    },
  };
  if (trigger.type === 'time') {
    const at = $('routineTime').value;
    if (!/^\d{2}:\d{2}$/.test(at)) {
      showBanner('Bitte eine Uhrzeit wählen (HH:MM).', 'warn');
      return;
    }
    trigger.at = at;
    trigger.days = selectedRoutineDays();
  } else if (trigger.type === 'event') {
    const event = $('routineEvent').value;
    trigger.event = ROUTINE_EVENTS[event] ? event : 'game_stopped';
    if (trigger.event === 'idle') {
      const minutes = stepNumber($('routineIdle').value, 1, 1440, 30);
      if (minutes === null) {
        showBanner('Die Idle-Zeit muss zwischen 1 und 1440 Minuten liegen.', 'warn');
        return;
      }
      trigger.idle_minutes = minutes;
    }
  }

  const actions = routineActionsFromSteps();
  if (!actions) return;

  const body = { name, enabled: true, trigger, actions };
  $('btnAddRoutine').disabled = true;
  try {
    const answer = await api('/routines', { method: 'POST', body: JSON.stringify(body) });
    clearBanner();
    const created = (answer && answer.routine) ? answer.routine : body;
    // Formular zurücksetzen: Name, Tage, Bedingungen und Schritte.
    $('routineName').value = '';
    document.querySelectorAll('#routineDays button.on, #routineCondDays button.on')
      .forEach((button) => {
        button.classList.remove('on');
        button.setAttribute('aria-pressed', 'false');
      });
    $('routineCondPlugin').value = '';
    $('routineCondPlan').value = '';
    state.routineSteps = [{ type: 'hibernate', delay: '60', plan: 'high', wait: '0' }];
    renderRoutineSteps();
    await refreshRoutines();
    const stored = (state.routines || []).find((entry) => entry.id === created.id) || created;
    showRoutineResult('Routine „' + name + '“ angelegt · ' + triggerText(stored) +
      ' · ' + nextText(stored));
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

  // Tab-Switching (selectTab startet/stoppt auch die Tab-Abfragen)
  document.querySelectorAll('.tab').forEach((btn) => {
    btn.addEventListener('click', () => selectTab(btn.dataset.tab));
  });
  // Gespeicherten Tab wiederherstellen (nur bekannte Tabs übernehmen)
  const knownTabs = Array.prototype.map.call(document.querySelectorAll('.tab'),
    (el) => el.dataset.tab);
  let savedTab = 'overview';
  try { savedTab = localStorage.getItem('pcTab') || 'overview'; } catch (e) { /* egal */ }
  const savedBtn = knownTabs.indexOf(savedTab) >= 0
    ? document.querySelector('.tab[data-tab="' + savedTab + '"]')
    : null;
  if (savedBtn) savedBtn.click();

  $('app').hidden = false;
  await refreshIdentity();
  await tick();
  await refreshPlugin();
  await refreshRoutines();
  // Vorlagen und die Plugin-Auswahl der Routinen-Bedingungen einmal vorladen.
  await loadRoutineTemplates();
  renderRoutinePluginOptions();
  render();
  startLive();
  startSlow();
  startPolling();
  // Tab-Abfragen erst jetzt – vorher ist die Verbindung noch unbekannt.
  syncTabPolling();
}

document.addEventListener('DOMContentLoaded', boot);

