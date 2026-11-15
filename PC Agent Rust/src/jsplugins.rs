//! JavaScript-Plugins (Boa).
//!
//! Ein Plugin ist ein Ordner im Datenverzeichnis:
//!
//! ```text
//! <Datenordner>\plugins\<id>\plugin.json   Manifest
//! <Datenordner>\plugins\<id>\main.js       Code (Einstiegspunkt)
//! ```
//!
//! Der Agent startet das Plugin **nur auf Anforderung** und haelt es in einem
//! eigenen Thread am Leben. Der Code laeuft in einer eingebetteten
//! JavaScript-Engine (Boa, reines Rust) und bekommt genau die Funktionen, die
//! das Manifest als `permissions` freigibt:
//!
//! ```js
//! function activate() { pc.log("ich laufe"); }
//! function tick()     { pc.log(pc.metrics().cpu_percent); }
//! function stop()     { pc.log("tschues"); }
//! ```
//!
//! Es gibt bewusst keinen Zugriff auf Dateien, Netzwerk oder Prozesse – nur die
//! hier registrierten Host-Funktionen.

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use boa_engine::{Context as JsContext, JsString, JsValue, NativeFunction, Source};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::app::App;
use crate::journal::log_event;

/// So viele Ausgabezeilen werden je Plugin aufbewahrt.
pub const OUTPUT_MAX: usize = 300;

/// Standard-Einstiegspunkt, wenn das Manifest keinen nennt.
pub const DEFAULT_ENTRY: &str = "main.js";

/// Standard-Takt in Millisekunden.
pub const DEFAULT_INTERVAL_MS: u64 = 1000;

/// Katalog der Plugins (Repository, oeffentlich) – wie beim Modul-Katalog.
pub const CATALOG_URL: &str =
    "https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/plugin-catalog/plugins.json";

/// Manifest eines Plugins.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    pub description: String,
    pub version: String,
    /// Datei im Plugin-Ordner (relativ), Standard `main.js`.
    pub entry: String,
    /// Freigegebene Host-Funktionen: `log`, `status`, `metrics`, `power`,
    /// `games`, `process`.
    pub permissions: Vec<String>,
    /// Abstand der `tick()`-Aufrufe; 0 = kein Takt.
    pub interval_ms: u64,
    /// Vom Agenten mitgeliefert (wird beim ersten Start geschrieben).
    pub builtin: bool,
}

impl Default for Manifest {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            description: String::new(),
            version: "1".to_string(),
            entry: DEFAULT_ENTRY.to_string(),
            permissions: Vec::new(),
            interval_ms: DEFAULT_INTERVAL_MS,
            builtin: false,
        }
    }
}

impl Manifest {
    /// Prueft ID und Einstiegspunkt auf ein sicheres Verzeichnis.
    pub fn is_valid(&self) -> bool {
        !self.id.trim().is_empty() && safe_component(&self.id) && safe_component(&self.entry)
    }
}

/// Nur schmale Namen erlauben (kein `..`, kein Pfad).
fn safe_component(value: &str) -> bool {
    let value = value.trim();
    !value.is_empty()
        && value != "."
        && value != ".."
        && !value.contains('/')
        && !value.contains('\\')
        && !value.contains(':')
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' || ch == '.')
}

/// Paket eines Plugins: Manifest plus Dateien (JSON statt ZIP, damit der Agent
/// keine zusatzliche Crate braucht und das Handy es leicht nachladen kann).
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Bundle {
    pub id: String,
    pub name: String,
    pub description: String,
    pub version: String,
    pub entry: String,
    pub permissions: Vec<String>,
    pub interval_ms: u64,
    /// Pfad (relativ) -> Inhalt.
    pub files: HashMap<String, String>,
}

impl Default for Bundle {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            description: String::new(),
            version: "1".to_string(),
            entry: DEFAULT_ENTRY.to_string(),
            permissions: Vec::new(),
            interval_ms: DEFAULT_INTERVAL_MS,
            files: HashMap::new(),
        }
    }
}

impl Bundle {
    /// Manifest aus dem Paket (ohne Dateien).
    pub fn manifest(&self) -> Manifest {
        Manifest {
            id: self.id.clone(),
            name: self.name.clone(),
            description: self.description.clone(),
            version: self.version.clone(),
            entry: self.entry.clone(),
            permissions: self.permissions.clone(),
            interval_ms: self.interval_ms,
            builtin: false,
        }
    }
}

/// Befehle an einen laufenden Plugin-Thread.
pub enum Command {
    Stop,
}

/// Ein laufendes JavaScript-Plugin.
pub struct JsRun {
    pub tx: Sender<Command>,
    pub started_at: f64,
    pub game: Option<String>,
    pub auto: bool,
    /// Wird vom Thread gesetzt, sobald er fertig ist.
    pub done: Arc<AtomicBool>,
}

/// Zustand aller JavaScript-Plugins (ueber den Mutex in `App` geschuetzt).
#[derive(Default)]
pub struct JsPluginState {
    pub runs: HashMap<String, JsRun>,
    pub output: HashMap<String, VecDeque<String>>,
    pub status: HashMap<String, String>,
}

fn now_epoch() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

/// Wo Plugins liegen (Datenordner zuerst, dann EXE-Ordner).
pub fn plugin_dirs(app: &App) -> Vec<PathBuf> {
    vec![app.data_dir.join("plugins"), app.exe_dir.join("plugins")]
}

/// Plugin-Ordner eines Manifests.
pub fn plugin_dir(app: &App, id: &str) -> PathBuf {
    app.data_dir.join("plugins").join(id)
}

/// Manifest aus einem Plugin-Ordner lesen.
pub fn read_manifest(dir: &Path) -> Option<Manifest> {
    let text = std::fs::read_to_string(dir.join("plugin.json")).ok()?;
    let manifest: Manifest = serde_json::from_str(&text).ok()?;
    manifest.is_valid().then_some(manifest)
}

/// Alle bekannten Manifeste: Datenordner, dann EXE-Ordner; erste ID gewinnt.
pub fn manifests(app: &App) -> Vec<Manifest> {
    let mut out: Vec<Manifest> = Vec::new();
    for dir in plugin_dirs(app) {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            let Some(manifest) = read_manifest(&path) else {
                continue;
            };
            if out.iter().any(|known| known.id == manifest.id) {
                continue;
            }
            out.push(manifest);
        }
    }
    out.sort_by_key(|manifest| manifest.name.to_lowercase());
    out
}

/// Ist das Plugin installiert?
pub fn is_installed(app: &App, id: &str) -> bool {
    plugin_dir(app, id).join("plugin.json").is_file()
}

// ---------------- Eingebaute Plugins ----------------

/// Die eingebauten Plugins werden beim ersten Start in den Datenordner
/// geschrieben und sind danach ganz normale Plugins (deinstallierbar,
/// bearbeitbar, austauschbar).
pub fn builtin_bundles() -> Vec<Bundle> {
    vec![game_optimizer_bundle(), system_monitor_bundle()]
}

fn game_optimizer_bundle() -> Bundle {
    Bundle {
        id: "game-optimizer".to_string(),
        name: "Game Optimization".to_string(),
        description:
            "Optimiert den PC fuer ein Spiel: Energieplan, Hintergrund-Apps, Arbeitsspeicher."
                .to_string(),
        version: "1".to_string(),
        entry: DEFAULT_ENTRY.to_string(),
        permissions: vec![
            "log".to_string(),
            "status".to_string(),
            "metrics".to_string(),
            "power".to_string(),
            "games".to_string(),
            "process".to_string(),
        ],
        interval_ms: 0,
        files: HashMap::from([(
            DEFAULT_ENTRY.to_string(),
            GAME_OPTIMIZER_JS.to_string(),
        )]),
    }
}

fn system_monitor_bundle() -> Bundle {
    Bundle {
        id: "system-monitor".to_string(),
        name: "System-Monitor".to_string(),
        description: "Meldet CPU- und RAM-Auslastung im Plugin-Log (alle 5 s)."
            .to_string(),
        version: "1".to_string(),
        entry: DEFAULT_ENTRY.to_string(),
        permissions: vec!["log".to_string(), "metrics".to_string()],
        interval_ms: 5000,
        files: HashMap::from([(
            DEFAULT_ENTRY.to_string(),
            SYSTEM_MONITOR_JS.to_string(),
        )]),
    }
}

const GAME_OPTIMIZER_JS: &str = r#"
// Game Optimization: Energieplan hoch, Hintergrund-Apps schliessen, RAM
// bereinigen, Spiel-Prioritaet anheben. Beim Stop wird der vorherige
// Energieplan wiederhergestellt.
var previousPlan = null;

function activate() {
  var status = pc.status();
  pc.log("Game Optimization startet" + (status.simulate ? " (SIMULATION)" : ""));
  var config = pc.gameConfig();
  previousPlan = pc.powerPlan();
  pc.log("Energieplan vorher: " + previousPlan);
  pc.setPowerPlan("high");
  pc.log("Energieplan -> Hoechstleistung");

  var killed = pc.killProcesses(config.kill_list || []);
  if (killed.length > 0) {
    pc.log("Hintergrund-Apps geschlossen: " + killed.join(", "));
  }

  var memory = pc.cleanMemory();
  pc.log("RAM: " + memory.used_mb + " MB belegt, " + memory.free_mb + " MB frei ("
    + memory.trimmed + " Prozesse bereinigt)");
  if (!memory.admin) {
    pc.log("Hinweis: ohne Administratorrechte bleibt die Standby-Liste unveraendert.");
  }
  pc.setStatus("optimiert");
  pc.log("> Optimierung aktiv (Modus " + (status.simulate ? "SIMULATION" : "ECHT") + ")");
}

function stop() {
  if (previousPlan) {
    pc.setPowerPlan(previousPlan);
    pc.log("Energieplan zurueck auf " + previousPlan);
  }
  pc.setStatus("");
  pc.log("> Optimierung beendet");
}
"#;

const SYSTEM_MONITOR_JS: &str = r#"
// System-Monitor: meldet Auslastung, sobald sie sich aendert.
var last = "";

function activate() {
  pc.log("System-Monitor aktiv (Takt 5 s)");
}

function tick() {
  var metrics = pc.metrics();
  var cpu = metrics.cpu_percent === null ? "-" : metrics.cpu_percent;
  var line = "CPU " + cpu + " %, RAM " + metrics.ram_percent + " %";
  if (line !== last) {
    pc.log(line);
    last = line;
  }
  pc.setStatus(line);
}
"#;

/// Schreibt fehlende eingebaute Plugins in den Datenordner.
pub fn ensure_builtins(app: &App) {
    for bundle in builtin_bundles() {
        let dir = plugin_dir(app, &bundle.id);
        if dir.join("plugin.json").is_file() {
            continue;
        }
        let manifest = Manifest {
            builtin: true,
            ..bundle.manifest()
        };
        if install_bundle(app, &bundle, manifest).is_ok() {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(app, &mut guard, "plugin", &format!("{}: eingebautes Plugin bereit", bundle.name), true);
        }
    }
}

/// Paket in den Datenordner schreiben.
pub fn install_bundle(app: &App, bundle: &Bundle, manifest: Manifest) -> Result<Manifest, String> {
    if !manifest.is_valid() {
        return Err("Ungueltige Plugin-ID oder ungueltiger Einstiegspunkt".to_string());
    }
    if bundle.files.is_empty() {
        return Err("Paket ohne Dateien".to_string());
    }
    if !bundle.files.contains_key(&manifest.entry) {
        return Err(format!("Einstiegspunkt '{}' fehlt im Paket", manifest.entry));
    }
    for path in bundle.files.keys() {
        if !safe_component(path) {
            return Err(format!("Unsicherer Dateiname '{}'", path));
        }
    }

    let dir = plugin_dir(app, &manifest.id);
    std::fs::create_dir_all(&dir)
        .map_err(|error| format!("Plugin-Ordner konnte nicht erstellt werden: {error}"))?;
    for (name, content) in &bundle.files {
        std::fs::write(dir.join(name), content)
            .map_err(|error| format!("'{name}' konnte nicht geschrieben werden: {error}"))?;
    }
    let manifest_text = serde_json::to_string_pretty(&manifest)
        .map_err(|error| format!("Manifest konnte nicht geschrieben werden: {error}"))?;
    std::fs::write(dir.join("plugin.json"), manifest_text)
        .map_err(|error| format!("Manifest konnte nicht geschrieben werden: {error}"))?;
    Ok(manifest)
}

/// Ein installiertes Plugin als Paket lesen (fuer Export/Katalog).
pub fn bundle_of(app: &App, id: &str) -> Result<Bundle, String> {
    let manifest = manifests(app)
        .into_iter()
        .find(|manifest| manifest.id == id)
        .ok_or_else(|| format!("Plugin '{id}' ist nicht installiert"))?;
    let dir = plugin_dir(app, id);
    let mut files = HashMap::new();
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Err(format!("Plugin-Ordner fehlt: {}", dir.display()));
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        if name == "plugin.json" || !path.is_file() {
            continue;
        }
        let Ok(content) = std::fs::read_to_string(&path) else {
            continue;
        };
        files.insert(name.to_string(), content);
    }
    Ok(Bundle {
        id: manifest.id,
        name: manifest.name,
        description: manifest.description,
        version: manifest.version,
        entry: manifest.entry,
        permissions: manifest.permissions,
        interval_ms: manifest.interval_ms,
        files,
    })
}

/// Plugin entfernen (Ordner im Datenordner). Eingebaut -> beim Start neu.
pub fn uninstall(app: &App, id: &str) -> Result<(), String> {
    if !safe_component(id) {
        return Err("Ungueltige Plugin-ID".to_string());
    }
    let dir = plugin_dir(app, id);
    if !dir.is_dir() {
        return Err(format!("Plugin '{id}' ist nicht installiert"));
    }
    std::fs::remove_dir_all(&dir).map_err(|error| format!("Plugin-Ordner blieb bestehen: {error}"))?;
    let mut state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
    state.output.remove(id);
    state.status.remove(id);
    Ok(())
}

// ---------------- Katalog ----------------

/// Eintrag im Plugin-Katalog.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct CatalogEntry {
    pub id: String,
    pub name: String,
    pub description: String,
    pub version: String,
    /// URL des Pakets (JSON).
    pub url: String,
}

impl Default for CatalogEntry {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            description: String::new(),
            version: "1".to_string(),
            url: String::new(),
        }
    }
}

/// Einbetteter Fallback, falls das Repository nicht erreichbar ist.
const DEFAULT_CATALOG: &str = r#"{
  "catalogVersion": 1,
  "plugins": []
}"#;

/// Katalog vom Server laden; bei Fehlern den eingebauten Stand nehmen.
pub fn catalog(app: &App) -> Vec<CatalogEntry> {
    let mut entries = parse_catalog(DEFAULT_CATALOG);
    if let Some(text) = download(app, CATALOG_URL) {
        if let Some(remote) = try_parse_catalog(&text) {
            if !remote.is_empty() {
                entries = remote;
            }
        }
    }
    for entry in entries.iter_mut() {
        if entry.url.trim().is_empty() {
            entry.url = format!(
                "https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/plugin-catalog/{}.json",
                entry.id
            );
        }
    }
    entries
}

fn try_parse_catalog(text: &str) -> Option<Vec<CatalogEntry>> {
    let value: Value = serde_json::from_str(text).ok()?;
    let array = value.get("plugins")?.as_array()?;
    Some(
        array
            .iter()
            .filter_map(|item| serde_json::from_value::<CatalogEntry>(item.clone()).ok())
            .filter(|entry| !entry.id.trim().is_empty())
            .collect(),
    )
}

fn parse_catalog(text: &str) -> Vec<CatalogEntry> {
    try_parse_catalog(text).unwrap_or_default()
}

/// Laedt eine Datei ueber `curl.exe` (Windows-Bordmittel) als Text.
/// Downloads sind lesend und laufen auch im Simulationsmodus.
fn download(_app: &App, url: &str) -> Option<String> {
    if url.trim().is_empty() || !url.starts_with("http://") && !url.starts_with("https://") {
        return None;
    }
    let target = std::env::temp_dir().join(format!("pc-plugin-{}.tmp", sanitize_file_name(url)));
    let (code, _out) =
        crate::commands::run(&["curl", "-fsSL", "--max-time", "20", "-o", &target.to_string_lossy(), url], false, false);
    if code != 0 {
        let _ = std::fs::remove_file(&target);
        return None;
    }
    let text = std::fs::read_to_string(&target).ok();
    let _ = std::fs::remove_file(&target);
    text
}

fn sanitize_file_name(url: &str) -> String {
    url.chars()
        .map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '_' })
        .take(60)
        .collect()
}

/// Paket eines Katalog-Eintrags laden und installieren.
pub fn install_from_catalog(app: &Arc<App>, id: &str) -> Result<Manifest, String> {
    let entry = catalog(app)
        .into_iter()
        .find(|entry| entry.id == id)
        .ok_or_else(|| format!("Plugin '{id}' steht nicht im Katalog"))?;
    install_from_url(app, &entry.id, &entry.url, &entry.version)
}

/// Paket von einer URL laden und installieren.
pub fn install_from_url(app: &Arc<App>, id: &str, url: &str, version: &str) -> Result<Manifest, String> {
    let text = download(app, url)
        .ok_or_else(|| format!("Paket konnte nicht geladen werden: {url}"))?;
    let bundle: Bundle =
        serde_json::from_str(&text).map_err(|error| format!("Paket ist kein gueltiges Plugin: {error}"))?;
    if bundle.id != id {
        return Err(format!("Paket gehoert zu '{}', erwartet '{id}'", bundle.id));
    }
    let manifest = Manifest {
        version: version.to_string(),
        ..bundle.manifest()
    };
    install_bundle(app, &bundle, manifest)
}

// ---------------- Laufzeit ----------------

/// Zustand aller Plugins fuer `GET /plugins`.
pub fn describe(app: &Arc<App>) -> Vec<Value> {
    let manifests = manifests(app);
    let state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
    manifests
        .into_iter()
        .map(|manifest| {
            let running = state.runs.contains_key(&manifest.id);
            let uptime = state
                .runs
                .get(&manifest.id)
                .map(|run| (now_epoch() - run.started_at).max(0.0) as i64)
                .unwrap_or(0);
            let tail: Vec<String> = state
                .output
                .get(&manifest.id)
                .map(|buffer| {
                    let skip = buffer.len().saturating_sub(8);
                    buffer.iter().skip(skip).cloned().collect()
                })
                .unwrap_or_default();
            json!({
                "id": manifest.id,
                "name": manifest.name,
                "description": manifest.description,
                "version": manifest.version,
                "kind": "javascript",
                "permissions": manifest.permissions,
                "interval_ms": manifest.interval_ms,
                "builtin": manifest.builtin,
                "available": is_installed(app, &manifest.id),
                "running": running,
                "uptime": uptime,
                "game": state.runs.get(&manifest.id).and_then(|run| run.game.clone()),
                "auto": state.runs.get(&manifest.id).map(|run| run.auto).unwrap_or(false),
                "status": state.status.get(&manifest.id).cloned().unwrap_or_default(),
                "tail": tail,
            })
        })
        .collect()
}

/// Gibt eine Zeile aus (Ringpuffer + Journal fuer Ergebniszeilen).
pub fn push_line(app: &Arc<App>, id: &str, line: &str) {
    {
        let mut state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
        let buffer = state.output.entry(id.to_string()).or_default();
        buffer.push_back(line.to_string());
        while buffer.len() > OUTPUT_MAX {
            buffer.pop_front();
        }
    }
    if line.starts_with('>') {
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        log_event(app, &mut guard, "plugin", line.trim_start_matches('>').trim(), true);
    }
}

fn set_status(app: &Arc<App>, id: &str, text: &str) {
    let mut state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
    state.status.insert(id.to_string(), text.to_string());
}

/// Startet ein Plugin.
pub fn start(app: &Arc<App>, id: &str, game: Option<String>, auto: bool) -> Result<Value, String> {
    let manifest = manifests(app)
        .into_iter()
        .find(|manifest| manifest.id == id)
        .ok_or_else(|| format!("Plugin '{id}' ist unbekannt"))?;
    let dir = plugin_dir(app, &manifest.id);
    let entry = dir.join(&manifest.entry);
    let source = std::fs::read_to_string(&entry)
        .map_err(|_| format!("Datei '{}' fehlt", manifest.entry))?;

    {
        let state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
        if state.runs.contains_key(&manifest.id) {
            return Err(format!("Plugin '{}' laeuft bereits", manifest.id));
        }
    }

    let (tx, rx) = mpsc::channel::<Command>();
    let done = Arc::new(AtomicBool::new(false));
    let host = Box::new(HostCtx {
        app: Arc::clone(app),
        plugin_id: manifest.id.clone(),
        permissions: manifest.permissions.iter().cloned().collect(),
    });
    let host_ptr = Box::into_raw(host) as usize;

    let thread_app = Arc::clone(app);
    let thread_id = manifest.id.clone();
    let thread_manifest = manifest.clone();
    let thread_done = Arc::clone(&done);
    std::thread::Builder::new()
        .name(format!("plugin-{id}"))
        .spawn(move || {
            run_plugin(thread_app, thread_id, thread_manifest, source, host_ptr, rx);
            thread_done.store(true, Ordering::SeqCst);
        })
        .map_err(|error| format!("Plugin-Thread konnte nicht gestartet werden: {error}"))?;

    let started_at = now_epoch();
    {
        let mut state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
        state.output.insert(manifest.id.clone(), VecDeque::new());
        state.runs.insert(
            manifest.id.clone(),
            JsRun { tx, started_at, game: game.clone(), auto, done },
        );
    }
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(app, &mut guard, "plugin", &format!("{} gestartet", manifest.name), true);
    drop(guard);

    Ok(json!({
        "ok": true,
        "id": manifest.id,
        "game": game,
        "auto": auto,
    }))
}

/// Stoppt ein Plugin (wartet bis zu drei Sekunden auf das Ende).
pub fn stop(app: &Arc<App>, id: &str) -> Result<String, String> {
    let run = {
        let mut state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
        state.runs.remove(id)
    }
    .ok_or_else(|| format!("Plugin '{id}' laeuft nicht"))?;

    let _ = run.tx.send(Command::Stop);
    for _ in 0..30 {
        if run.done.load(Ordering::SeqCst) {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let detail = if run.done.load(Ordering::SeqCst) {
        "beendet".to_string()
    } else {
        "beendet sich nicht".to_string()
    };
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(app, &mut guard, "plugin", &format!("{id} {detail}"), true);
    Ok(detail)
}

/// Letzte Ausgabezeilen eines Plugins.
pub fn output(app: &Arc<App>, id: &str, lines: usize) -> String {
    let state = app.jsplugins.lock().expect("JS-Plugin-Sperre");
    let Some(buffer) = state.output.get(id) else {
        return String::new();
    };
    let skip = buffer.len().saturating_sub(lines);
    let mut text = buffer.iter().skip(skip).cloned().collect::<Vec<_>>().join("\n");
    if !text.is_empty() {
        text.push('\n');
    }
    text
}

// ---------------- Host-Funktionen ----------------

/// Zustand, den die Host-Funktionen brauchen.
struct HostCtx {
    app: Arc<App>,
    plugin_id: String,
    permissions: HashSet<String>,
}

/// Baut die JavaScript-Bruecke: `__pc_host(name, json)`.
fn register_host(context: &mut JsContext, host_ptr: usize) {
    let function = NativeFunction::from_copy_closure(
        move |_this: &JsValue, args: &[JsValue], js_context: &mut JsContext| {
            let host = unsafe { &*(host_ptr as *const HostCtx) };
            let name = arg_string(args.first(), js_context);
            let payload = arg_string(args.get(1), js_context);
            let parsed: Value = if payload.trim().is_empty() {
                Value::Array(Vec::new())
            } else {
                serde_json::from_str(&payload).unwrap_or(Value::Array(Vec::new()))
            };
            let answer = match host_call(host, &name, &parsed) {
                Ok(value) => serde_json::to_string(&value).unwrap_or_else(|_| "null".to_string()),
                Err(error) => json!({ "__error": error }).to_string(),
            };
            Ok(JsValue::from(JsString::from(answer)))
        },
    );
    let _ = context.register_global_builtin_callable(
        boa_engine::js_string!("__pc_host"),
        2,
        function,
    );
}

fn arg_string(value: Option<&JsValue>, context: &mut JsContext) -> String {
    value
        .and_then(|value| value.to_string(context).ok())
        .map(|text| text.to_std_string_escaped())
        .unwrap_or_default()
}

/// Der JavaScript-Vorspann: definiert `pc.*` und wirft bei Host-Fehlern.
const PRELUDE: &str = r#"
(function () {
  function call(name, args) {
    var parsed = JSON.parse(__pc_host(name, JSON.stringify(args || [])));
    if (parsed && parsed.__error) {
      throw new Error(String(parsed.__error));
    }
    return parsed;
  }
  globalThis.pc = {
    log: function (text) { call("log", [String(text)]); },
    now: function () { return call("now", []); },
    status: function () { return call("status", []); },
    metrics: function () { return call("metrics", []); },
    powerPlan: function () { return call("powerPlan", []); },
    setPowerPlan: function (name) { return call("setPowerPlan", [String(name)]); },
    shutdown: function (seconds) { return call("shutdown", [Number(seconds) || 0]); },
    abort: function () { return call("abort", []); },
    hibernate: function () { return call("hibernate", []); },
    games: function () { return call("games", []); },
    scanGames: function () { return call("scanGames", []); },
    gameConfig: function () { return call("gameConfig", []); },
    events: function () { return call("events", []); },
    killProcesses: function (names) { return call("killProcesses", [names]); },
    boost: function (exe) { return call("boost", [String(exe)]); },
    cleanMemory: function () { return call("cleanMemory", []); },
    setStatus: function (text) { call("setStatus", [String(text)]); },
    isAdmin: function () { return call("isAdmin", []); }
  };
})();
"#;

/// Ruft eine globale Funktion auf, falls es sie gibt.
fn call_global(context: &mut JsContext, name: &str) -> Result<(), String> {
    let probe = context
        .eval(Source::from_bytes(&format!("typeof {name} === 'function'")))
        .map_err(|error| format!("{name}: {error}"))?;
    if !probe.to_boolean() {
        return Ok(());
    }
    context
        .eval(Source::from_bytes(&format!("{name}();")))
        .map_err(|error| format!("{name}: {error}"))?;
    Ok(())
}

/// Thread-Hauptschleife eines Plugins.
fn run_plugin(
    app: Arc<App>,
    id: String,
    manifest: Manifest,
    source: String,
    host_ptr: usize,
    rx: Receiver<Command>,
) {
    let mut context = JsContext::default();
    register_host(&mut context, host_ptr);

    let failed = |app: &Arc<App>, id: &str, message: String| {
        push_line(app, id, &format!("> Fehler: {message}"));
    };

    if let Err(error) = context.eval(Source::from_bytes(PRELUDE)) {
        failed(&app, &id, format!("Host-API: {error}"));
        unsafe { drop(Box::from_raw(host_ptr as *mut HostCtx)) };
        return;
    }
    if let Err(error) = context.eval(Source::from_bytes(&source)) {
        failed(&app, &id, format!("beim Laden: {error}"));
        unsafe { drop(Box::from_raw(host_ptr as *mut HostCtx)) };
        return;
    }
    if let Err(error) = call_global(&mut context, "activate") {
        failed(&app, &id, error);
    }

    let interval = if manifest.interval_ms == 0 {
        // Kein Takt: lange warten, bis ein Befehl kommt.
        Duration::from_secs(3600)
    } else {
        Duration::from_millis(manifest.interval_ms)
    };

    loop {
        match rx.recv_timeout(interval) {
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if let Err(error) = call_global(&mut context, "tick") {
                    failed(&app, &id, error);
                }
            }
            Ok(Command::Stop) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    if let Err(error) = call_global(&mut context, "stop") {
        failed(&app, &id, error);
    }
    drop(context);
    unsafe { drop(Box::from_raw(host_ptr as *mut HostCtx)) };
}

/// Eine Host-Funktion ausfuehren. Rueckgabe ist immer JSON.
fn host_call(host: &HostCtx, name: &str, args: &Value) -> Result<Value, String> {
    let permission = match name {
        "log" => Some("log"),
        "status" | "events" | "gameConfig" => Some("status"),
        "metrics" => Some("metrics"),
        "powerPlan" | "setPowerPlan" | "shutdown" | "abort" | "hibernate" => Some("power"),
        "games" | "scanGames" => Some("games"),
        "killProcesses" | "boost" | "cleanMemory" | "isAdmin" => Some("process"),
        _ => None,
    };
    if let Some(needed) = permission {
        if !host.permissions.contains(needed) {
            return Err(format!(
                "Plugin '{}' darf '{}' nicht (Berechtigung '{}' fehlt)",
                host.plugin_id, name, needed
            ));
        }
    }

    let app = &host.app;
    match name {
        "log" => {
            let text = args.get(0).and_then(Value::as_str).unwrap_or_default();
            push_line(app, &host.plugin_id, text);
            Ok(Value::Null)
        }
        "setStatus" => {
            let text = args.get(0).and_then(Value::as_str).unwrap_or_default();
            set_status(app, &host.plugin_id, text);
            Ok(Value::Null)
        }
        "now" => Ok(json!(now_epoch() * 1000.0)),
        "status" => {
            let (simulate, pending_seconds, sim_plan, events) = {
                let guard = app.inner.lock().expect("Zustands-Sperre");
                (
                    guard.simulate,
                    crate::app::remaining_seconds(guard.pending_at, now_epoch()),
                    guard.sim_plan.clone(),
                    guard.events.len(),
                )
            };
            // powercfg nicht unter der Sperre aufrufen.
            let plan = sim_plan.or_else(crate::optimize::active_plan_name);
            Ok(json!({
                "simulate": simulate,
                "pending_seconds": pending_seconds,
                "power_plan": plan,
                "events": events,
            }))
        }
        "metrics" => {
            let metrics = crate::metrics::read(&app.metrics);
            Ok(serde_json::to_value(metrics).unwrap_or(Value::Null))
        }
        "powerPlan" => Ok(json!(crate::optimize::active_plan_name())),
        "setPowerPlan" => {
            let plan = args.get(0).and_then(Value::as_str).unwrap_or_default();
            crate::actions::power_plan_sync(app, plan)?;
            Ok(json!({ "ok": true, "plan": plan }))
        }
        "shutdown" => {
            let seconds = args.get(0).and_then(Value::as_u64).unwrap_or(0) as u32;
            let text = crate::actions::shutdown_sync(app, seconds)?;
            Ok(json!({ "ok": true, "text": text }))
        }
        "abort" => {
            let text = crate::actions::abort_sync(app)?;
            Ok(json!({ "ok": true, "text": text }))
        }
        "hibernate" => {
            let text = crate::actions::hibernate_sync(app)?;
            Ok(json!({ "ok": true, "text": text }))
        }
        "games" => {
            let (games, scanned_at) = crate::games::cached(app);
            Ok(json!({ "scanned_at": scanned_at, "games": games }))
        }
        "scanGames" => {
            let games = crate::games::scan_and_store(app);
            Ok(json!({ "count": games.len(), "games": games }))
        }
        "gameConfig" => {
            let config = crate::games::load_config(app);
            Ok(serde_json::to_value(config).unwrap_or(Value::Null))
        }
        "events" => {
            let guard = app.inner.lock().expect("Zustands-Sperre");
            Ok(json!(guard.events.iter().collect::<Vec<_>>()))
        }
        "killProcesses" => {
            let names: Vec<String> = args
                .get(0)
                .and_then(Value::as_array)
                .map(|array| {
                    array
                        .iter()
                        .filter_map(Value::as_str)
                        .map(|name| name.to_string())
                        .collect()
                })
                .unwrap_or_default();
            let simulate = app.inner.lock().expect("Zustands-Sperre").simulate;
            let killed = if simulate {
                names.clone()
            } else {
                crate::optimize::kill_list(&names, None)
            };
            Ok(json!({ "killed": killed }))
        }
        "boost" => {
            let exe = args.get(0).and_then(Value::as_str).unwrap_or_default();
            let simulate = app.inner.lock().expect("Zustands-Sperre").simulate;
            if simulate {
                return Ok(json!({ "ok": true, "simulated": true }));
            }
            match crate::optimize::find_process(exe) {
                Some((name, pid)) => Ok(json!({
                    "ok": crate::optimize::boost_priority(pid),
                    "process": name,
                    "pid": pid,
                })),
                None => Ok(json!({ "ok": false, "error": "Prozess laeuft nicht" })),
            }
        }
        "cleanMemory" => {
            let simulate = app.inner.lock().expect("Zustands-Sperre").simulate;
            if simulate {
                let metrics = crate::metrics::read(&app.metrics);
                return Ok(json!({
                    "simulated": true,
                    "admin": crate::optimize::is_admin(),
                    "trimmed": 0,
                    "used_mb": metrics.ram_used_mb,
                    "free_mb": metrics.ram_total_mb.saturating_sub(metrics.ram_used_mb),
                }));
            }
            let before = crate::metrics::read(&app.metrics);
            let trimmed = crate::optimize::trim_all_processes();
            let admin = crate::optimize::is_admin();
            let purged = admin && crate::optimize::purge_standby();
            let after = crate::metrics::read(&app.metrics);
            Ok(json!({
                "admin": admin,
                "purged": purged,
                "trimmed": trimmed,
                "used_mb": after.ram_used_mb,
                "free_mb": after.ram_total_mb.saturating_sub(after.ram_used_mb),
                "before_used_mb": before.ram_used_mb,
            }))
        }
        "isAdmin" => Ok(json!(crate::optimize::is_admin())),
        other => Err(format!("Unbekannte Host-Funktion '{other}'")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_app(name: &str) -> (Arc<App>, PathBuf) {
        let dir = std::env::temp_dir().join(format!("pcagent-js-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let app: Arc<App> = Arc::new(App::new(String::new(), true, &dir, 8000));
        (app, dir)
    }

    #[test]
    fn unbekannte_hostfunktion_ist_ein_fehler() {
        let (app, dir) = test_app("host-unknown");
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions: HashSet::new(),
        };
        let error = host_call(&host, "gibtesnicht", &Value::Null).unwrap_err();
        assert!(error.contains("Unbekannte Host-Funktion"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn fehlende_berechtigung_wird_abgelehnt() {
        let (app, dir) = test_app("host-permission");
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions: HashSet::new(),
        };
        let error = host_call(&host, "shutdown", &json!([0])).unwrap_err();
        assert!(error.contains("Berechtigung 'power' fehlt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn manifest_prueft_kleine_namen() {
        let mut manifest = Manifest {
            id: "game-optimizer".to_string(),
            entry: "main.js".to_string(),
            ..Default::default()
        };
        assert!(manifest.is_valid());
        manifest.id = "../kaputt".to_string();
        assert!(!manifest.is_valid());
        manifest.id = "ok".to_string();
        manifest.entry = "sub/main.js".to_string();
        assert!(!manifest.is_valid());
    }

    #[test]
    fn paket_ohne_einstiegspunkt_fehlt() {
        let (app, dir) = test_app("bundle-missing-entry");
        let bundle = Bundle {
            id: "demo".to_string(),
            files: HashMap::from([("other.js".to_string(), String::new())]),
            ..Default::default()
        };
        let error = install_bundle(&app, &bundle, bundle.manifest()).unwrap_err();
        assert!(error.contains("Einstiegspunkt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn eingebautes_plugin_liest_sich_aus_dem_datenordner() {
        let (app, dir) = test_app("builtin-install");
        ensure_builtins(&app);
        let list = manifests(&app);
        assert!(list.iter().any(|manifest| manifest.id == "game-optimizer"));
        let text = std::fs::read_to_string(
            plugin_dir(&app, "game-optimizer").join(DEFAULT_ENTRY),
        )
        .unwrap();
        assert!(text.contains("function activate"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// Kleiner HTTP-Server, der genau eine Datei ausliefert.
    fn serve_once(body: String) -> (u16, std::thread::JoinHandle<()>) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let mut stream = stream;
                let mut buffer = [0u8; 2048];
                let _ = stream.read(&mut buffer);
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(response.as_bytes());
            }
        });
        (port, handle)
    }

    #[test]
    fn paket_vom_server_wird_installiert() {
        let (app, dir) = test_app("install-http");
        let bundle = Bundle {
            id: "demo".to_string(),
            name: "Demo".to_string(),
            files: HashMap::from([(
                DEFAULT_ENTRY.to_string(),
                "function activate() { pc.log('da'); }".to_string(),
            )]),
            ..Default::default()
        };
        let (port, server) = serve_once(serde_json::to_string(&bundle).unwrap());
        let url = format!("http://127.0.0.1:{port}/demo.json");
        let manifest = install_from_url(&app, "demo", &url, "1").unwrap();
        assert_eq!(manifest.id, "demo");
        assert!(is_installed(&app, "demo"));
        let code = std::fs::read_to_string(plugin_dir(&app, "demo").join(DEFAULT_ENTRY)).unwrap();
        assert!(code.contains("activate"));

        // Falsche ID im Paket wird abgelehnt.
        let error = install_from_url(&app, "fremd", &url, "1").unwrap_err();
        assert!(error.contains("gehoert zu 'demo'"));

        // Der Server laeuft bis zum Ende des Testprogramms weiter.
        drop(server);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn paket_export_liefert_manifest_und_dateien() {
        let (app, dir) = test_app("bundle-export");
        ensure_builtins(&app);
        let bundle = bundle_of(&app, "system-monitor").unwrap();
        assert_eq!(bundle.id, "system-monitor");
        assert!(bundle.files.contains_key(DEFAULT_ENTRY));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}