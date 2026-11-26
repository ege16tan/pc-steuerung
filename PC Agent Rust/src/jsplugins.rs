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
//!
//! Pflichtregel D2: Wird die Ausgabe eines Plugins laenger als
//! `idle_timeout_s` (Manifest, Standard 5 Minuten, `0` = nie) nicht abgerufen,
//! beendet der Agent es von selbst ([`idle_loop`]).

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use boa_engine::{Context as JsContext, JsString, JsValue, NativeFunction, Source};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};

use crate::app::App;
use crate::journal::log_event;

/// So viele Ausgabezeilen werden je Plugin aufbewahrt.
pub const OUTPUT_MAX: usize = 300;

/// Standard-Einstiegspunkt, wenn das Manifest keinen nennt.
pub const DEFAULT_ENTRY: &str = "main.js";

/// Standard-Takt in Millisekunden.
pub const DEFAULT_INTERVAL_MS: u64 = 1000;

/// Standard-Timeout ohne Abruf der Ausgabe (Pflichtregel D2).
pub const DEFAULT_IDLE_TIMEOUT_S: u64 = 300;

/// Datei mit den gespeicherten Einstellungen eines Plugins (Phase G4).
/// Sie wird bewusst **nicht** mit `GET /plugins/{id}/bundle` ausgeliefert.
pub const SETTINGS_FILE: &str = "settings.json";

/// Mehr Einstellungsfelder sind nicht sinnvoll (Rest wird verworfen).
pub const SETTINGS_MAX_FIELDS: usize = 20;

/// Laengster erlaubter Schluessel eines Einstellungsfelds.
pub const SETTINGS_KEY_MAX: usize = 40;

/// Laengster erlaubter Textwert.
pub const SETTINGS_TEXT_MAX: usize = 500;

/// Takt der Idle-Pruefung im Agenten.
const IDLE_CHECK: Duration = Duration::from_secs(30);

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
    /// `games`, `process`, `steam`.
    ///
    /// `steam` gibt `pc.steam()` frei (read-only: Steam-Pfad, Millennium,
    /// laufende Downloads). Die Daten liest der Agent in `src/steam.rs`; das
    /// Plugin selbst bekommt keinen Dateisystem-Zugriff.
    pub permissions: Vec<String>,
    /// Abstand der `tick()`-Aufrufe; 0 = kein Takt.
    pub interval_ms: u64,
    /// Beenden, wenn die Ausgabe so viele Sekunden nicht abgerufen wurde
    /// (0 = nie). Ohne Angabe gelten [`DEFAULT_IDLE_TIMEOUT_S`] (Pflichtregel D2).
    pub idle_timeout_s: u64,
    /// Vom Agenten mitgeliefert (wird beim ersten Start geschrieben).
    pub builtin: bool,
    /// Einstellungs-Schema (Phase G4), **ungeprueft** gelesen: ein kaputtes
    /// Feld darf das Plugin nicht unbrauchbar machen. Geprueft wird erst in
    /// [`check_schema`]; verworfene Felder landen im Journal.
    #[serde(default, deserialize_with = "flexible_list", skip_serializing_if = "Vec::is_empty")]
    pub settings: Vec<Value>,
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
            idle_timeout_s: DEFAULT_IDLE_TIMEOUT_S,
            builtin: false,
            settings: Vec::new(),
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

/// Liest `settings` als Liste. Ein einzelnes Objekt wird zur Ein-Feld-Liste,
/// alles andere (Text, Zahl, `null`) landet als Eintrag bzw. bleibt leer –
/// ein kaputtes Schema darf das ganze Manifest nicht unlesbar machen.
fn flexible_list<'de, D>(deserializer: D) -> Result<Vec<Value>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = Value::deserialize(deserializer)?;
    Ok(match value {
        Value::Array(items) => items,
        Value::Null => Vec::new(),
        other => vec![other],
    })
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
    /// Siehe [`Manifest::idle_timeout_s`]; Teil des Pakets, damit der Marktplatz
    /// den Wert mitliefern kann.
    pub idle_timeout_s: u64,
    /// Einstellungs-Schema (Phase G4), ungeprueft wie im Manifest.
    #[serde(default, deserialize_with = "flexible_list", skip_serializing_if = "Vec::is_empty")]
    pub settings: Vec<Value>,
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
            idle_timeout_s: DEFAULT_IDLE_TIMEOUT_S,
            settings: Vec::new(),
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
            idle_timeout_s: self.idle_timeout_s,
            builtin: false,
            settings: self.settings.clone(),
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
    /// Manifest-Wert fuer den Idle-Timeout (0 = nie beenden).
    pub idle_timeout_s: u64,
    /// Letzter Abruf der Ausgabe bzw. Start (Pflichtregel D2).
    pub last_seen: Arc<Mutex<Instant>>,
    /// Host-Kontext, der dem Plugin als `pc.*` API zur Verfügung steht.
    /// Das `Arc` hält den Host-Kontext so lange am Leben wie das Plugin läuft.
    #[allow(dead_code)]
    pub host: Arc<HostCtx>,
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
        // Laeuft absichtlich bis zum Stop: ein Timeout wuerde mitten im Spiel
        // den Energieplan zuruecksetzen.
        idle_timeout_s: 0,
        settings: Vec::new(),
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
        idle_timeout_s: DEFAULT_IDLE_TIMEOUT_S,
        settings: Vec::new(),
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
            let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
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
        if name == "plugin.json" || name == SETTINGS_FILE || !path.is_file() {
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
        idle_timeout_s: manifest.idle_timeout_s,
        settings: Vec::new(),
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
    let mut state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
    state.output.remove(id);
    state.status.remove(id);
    Ok(())
}

// ---------------- Einstellungen (Phase G4) ----------------
//
// Das Manifest darf ein Feld `settings` mitbringen:
//
// ```json
// "settings": [
//   {"key": "refresh_s", "label": "Aktualisierung (s)", "type": "number", "default": 5, "min": 1, "max": 600},
//   {"key": "mode", "label": "Modus", "type": "select", "default": "auto", "options": ["auto", "manuell"]},
//   {"key": "notify", "label": "Benachrichtigen", "type": "switch", "default": true}
// ]
// ```
//
// Die Werte liegen in `<Datenordner>\plugins\<id>\settings.json` als flaches
// Objekt `{"key": wert}`. Ein kaputtes Schema macht das Plugin **nicht**
// unbrauchbar: das betroffene Feld wird verworfen und der Grund landet im
// Journal ([`report_schema_problems`]).

/// Erlaubte Typen eines Einstellungsfelds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SettingType {
    Text,
    Number,
    Switch,
    Select,
}

impl SettingType {
    /// Typ aus dem Manifest; unbekannte Typen ergeben `None` (Feld verwerfen).
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "text" => Some(Self::Text),
            "number" => Some(Self::Number),
            "switch" => Some(Self::Switch),
            "select" => Some(Self::Select),
            _ => None,
        }
    }

    /// Schreibweise im Manifest und in `GET /plugins/{id}/settings`.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Number => "number",
            Self::Switch => "switch",
            Self::Select => "select",
        }
    }
}

/// Ein geprueftes Einstellungsfeld.
#[derive(Clone, Debug, PartialEq)]
pub struct SettingField {
    /// Schluessel in `settings.json` und in `pc.settings()`.
    pub key: String,
    /// Beschriftung in der Oberflaeche (nie leer).
    pub label: String,
    pub kind: SettingType,
    /// Standardwert; passt immer zum Feld.
    pub default: Value,
    /// Nur bei `number`.
    pub min: Option<f64>,
    /// Nur bei `number`.
    pub max: Option<f64>,
    /// Nur bei `select`, mindestens eine Option.
    pub options: Vec<String>,
}

/// Ergebnis der Schema-Pruefung: brauchbare Felder und Gruende fuer Verworfenes.
#[derive(Clone, Debug, Default)]
pub struct Schema {
    pub fields: Vec<SettingField>,
    /// Deutsche Texte fuer das Journal (leer = alles in Ordnung).
    pub problems: Vec<String>,
}

/// Fehler beim Speichern von Einstellungen (die Endpunkte antworten 400/404/422).
#[derive(Clone, Debug)]
pub enum SettingsProblem {
    /// Plugin ist nicht installiert.
    NotFound(String),
    /// Schluessel steht nicht im Schema.
    UnknownKey(String),
    /// Wert passt nicht zum Feld.
    Invalid(String),
}

impl SettingsProblem {
    /// Deutscher Text fuer die Antwort.
    pub fn message(&self) -> &str {
        match self {
            Self::NotFound(message) | Self::UnknownKey(message) | Self::Invalid(message) => message,
        }
    }
}

/// Zahl als JSON-Wert; ganze Zahlen bleiben ganz (`5` statt `5.0`).
fn number_value(value: f64) -> Value {
    if value.fract() == 0.0 && value.abs() < 9.0e15 {
        Value::from(value as i64)
    } else {
        Value::from(value)
    }
}

/// Standardwert, wenn das Manifest keinen (gueltigen) nennt.
fn fallback_default(kind: SettingType, min: Option<f64>, options: &[String]) -> Value {
    match kind {
        SettingType::Text => Value::String(String::new()),
        SettingType::Number => number_value(min.unwrap_or(0.0)),
        SettingType::Switch => Value::Bool(false),
        SettingType::Select => Value::String(options.first().cloned().unwrap_or_default()),
    }
}

/// Prueft einen Wert gegen Typ, Grenzen und Optionen. `None` = ungueltig.
fn coerce_value(
    kind: SettingType,
    min: Option<f64>,
    max: Option<f64>,
    options: &[String],
    value: &Value,
) -> Option<Value> {
    match kind {
        SettingType::Text => value
            .as_str()
            .filter(|text| text.chars().count() <= SETTINGS_TEXT_MAX)
            .map(|text| Value::String(text.to_string())),
        SettingType::Number => {
            let number = value.as_f64()?;
            if !number.is_finite() {
                return None;
            }
            if min.is_some_and(|min| number < min) || max.is_some_and(|max| number > max) {
                return None;
            }
            Some(number_value(number))
        }
        SettingType::Switch => value.as_bool().map(Value::Bool),
        SettingType::Select => value
            .as_str()
            .filter(|text| options.iter().any(|option| option == text))
            .map(|text| Value::String(text.to_string())),
    }
}

/// Prueft einen Wert gegen ein geprueftes Feld (`None` = ungueltig).
pub fn checked_value(field: &SettingField, value: &Value) -> Option<Value> {
    coerce_value(field.kind, field.min, field.max, &field.options, value)
}

/// Liest eine optionale Zahl aus dem Manifest-Feld; falsche Typen sind Fehler.
fn optional_number(obj: &Map<String, Value>, name: &str) -> Result<Option<f64>, String> {
    match obj.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => match value.as_f64() {
            Some(number) if number.is_finite() => Ok(Some(number)),
            _ => Err(format!("'{name}' ist keine Zahl")),
        },
    }
}

/// Ein einzelnes Feld pruefen. `Err` = Feld verwerfen (Text fuer das Journal).
fn parse_field(raw: &Value) -> Result<SettingField, String> {
    let Some(obj) = raw.as_object() else {
        return Err("Eintrag ist kein Objekt".to_string());
    };
    let key = obj
        .get("key")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    if key.is_empty() {
        return Err("Key fehlt".to_string());
    }
    if key.len() > SETTINGS_KEY_MAX {
        return Err(format!(
            "Key '{key}' ist zu lang (hoechstens {SETTINGS_KEY_MAX} Zeichen)"
        ));
    }
    if !key
        .chars()
        .all(|ch| ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '_')
    {
        return Err(format!(
            "Key '{key}' enthaelt unerlaubte Zeichen (nur a-z, 0-9, _)"
        ));
    }
    let label = obj
        .get("label")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim()
        .to_string();
    if label.is_empty() {
        return Err(format!("Einstellung '{key}' hat keine Beschriftung"));
    }
    let type_text = obj.get("type").and_then(Value::as_str).unwrap_or_default();
    let Some(kind) = SettingType::parse(type_text) else {
        return Err(format!(
            "Einstellung '{key}': Typ '{}' ist unbekannt",
            type_text.trim()
        ));
    };
    let min = optional_number(obj, "min").map_err(|error| format!("Einstellung '{key}': {error}"))?;
    let max = optional_number(obj, "max").map_err(|error| format!("Einstellung '{key}': {error}"))?;
    if kind != SettingType::Number && (min.is_some() || max.is_some()) {
        return Err(format!(
            "Einstellung '{key}': min/max gibt es nur bei 'number'"
        ));
    }
    if let (Some(min), Some(max)) = (min, max) {
        if min > max {
            return Err(format!("Einstellung '{key}': min ist groesser als max"));
        }
    }

    let mut options: Vec<String> = Vec::new();
    if kind == SettingType::Select {
        let Some(Value::Array(items)) = obj.get("options") else {
            return Err(format!(
                "Einstellung '{key}': 'select' braucht mindestens eine Option"
            ));
        };
        for item in items {
            let Some(text) = item.as_str().map(str::trim) else {
                return Err(format!("Einstellung '{key}': jede Option muss Text sein"));
            };
            if text.is_empty() {
                return Err(format!("Einstellung '{key}': leere Option"));
            }
            if options.iter().any(|known| known == text) {
                return Err(format!("Einstellung '{key}': Option '{text}' ist doppelt"));
            }
            options.push(text.to_string());
        }
        if options.is_empty() {
            return Err(format!(
                "Einstellung '{key}': 'select' braucht mindestens eine Option"
            ));
        }
    }

    let default = match obj.get("default") {
        None | Some(Value::Null) => fallback_default(kind, min, &options),
        Some(value) => match coerce_value(kind, min, max, &options, value) {
            Some(value) => value,
            None => {
                return Err(format!(
                    "Einstellung '{key}': Standardwert passt nicht zum Feld"
                ))
            }
        },
    };

    Ok(SettingField {
        key,
        label,
        kind,
        default,
        min,
        max,
        options,
    })
}

/// Prueft das Schema aus dem Manifest. Ungueltige Felder werden verworfen
/// (nie Panik, nie Abbruch) und mit Begruendung in `problems` gemeldet.
pub fn check_schema(raw: &[Value]) -> Schema {
    let mut schema = Schema::default();
    for (index, item) in raw.iter().enumerate() {
        if schema.fields.len() >= SETTINGS_MAX_FIELDS {
            schema.problems.push(format!(
                "Mehr als {SETTINGS_MAX_FIELDS} Einstellungen; der Rest wird ignoriert"
            ));
            break;
        }
        match parse_field(item) {
            Ok(field) => {
                if schema.fields.iter().any(|known| known.key == field.key) {
                    schema.problems.push(format!(
                        "Einstellung '{}' verworfen: Key ist doppelt",
                        field.key
                    ));
                    continue;
                }
                schema.fields.push(field);
            }
            Err(message) => {
                schema
                    .problems
                    .push(format!("Feld {} verworfen: {message}", index + 1));
            }
        }
    }
    schema
}

/// Schema als JSON (nur die Felder, die zum Typ passen).
pub fn schema_json(fields: &[SettingField]) -> Vec<Value> {
    fields
        .iter()
        .map(|field| {
            let mut entry = Map::new();
            entry.insert("key".to_string(), Value::String(field.key.clone()));
            entry.insert("label".to_string(), Value::String(field.label.clone()));
            entry.insert(
                "type".to_string(),
                Value::String(field.kind.as_str().to_string()),
            );
            entry.insert("default".to_string(), field.default.clone());
            if field.kind == SettingType::Number {
                if let Some(min) = field.min {
                    entry.insert("min".to_string(), number_value(min));
                }
                if let Some(max) = field.max {
                    entry.insert("max".to_string(), number_value(max));
                }
            }
            if field.kind == SettingType::Select {
                entry.insert(
                    "options".to_string(),
                    Value::Array(
                        field
                            .options
                            .iter()
                            .map(|option| Value::String(option.clone()))
                            .collect(),
                    ),
                );
            }
            Value::Object(entry)
        })
        .collect()
}

/// Standardwerte aller Felder (das sind die Werte ohne `settings.json`).
#[cfg(test)]
fn default_values(fields: &[SettingField]) -> Map<String, Value> {
    fields
        .iter()
        .map(|field| (field.key.clone(), field.default.clone()))
        .collect()
}

/// Laedt gespeicherte Werte und faellt je Feld **still** auf den Standardwert
/// zurueck, wenn Typ, Grenze oder Option nicht passen (`settings.json` ist
/// eine Nutzerdatei, kein Vertrauensbeweis).
pub fn resolve_values(fields: &[SettingField], raw: &Map<String, Value>) -> Map<String, Value> {
    fields
        .iter()
        .map(|field| {
            let value = raw
                .get(&field.key)
                .and_then(|value| checked_value(field, value))
                .unwrap_or_else(|| field.default.clone());
            (field.key.clone(), value)
        })
        .collect()
}

/// Strengere Pruefung fuer `POST /plugins/{id}/settings`: unbekannte
/// Schluessel und ungueltige Werte sind Fehler (kein stiller Rueckfall).
pub fn validate_values(
    fields: &[SettingField],
    raw: &Map<String, Value>,
) -> Result<Map<String, Value>, SettingsProblem> {
    for key in raw.keys() {
        if !fields.iter().any(|field| &field.key == key) {
            return Err(SettingsProblem::UnknownKey(format!(
                "Unbekannter Einstellungsschluessel '{key}'"
            )));
        }
    }
    let mut out = Map::new();
    for field in fields {
        let value = match raw.get(&field.key) {
            None => field.default.clone(),
            Some(value) => match checked_value(field, value) {
                Some(value) => value,
                None => {
                    return Err(SettingsProblem::Invalid(format!(
                        "Wert fuer '{}' ({}) ist ungueltig",
                        field.label, field.key
                    )))
                }
            },
        };
        out.insert(field.key.clone(), value);
    }
    Ok(out)
}

/// Manifest eines **installierten** Plugins (nur Datenordner, dort liegen die
/// bearbeitbaren Einstellungen).
pub fn installed_manifest(app: &App, id: &str) -> Option<Manifest> {
    if !safe_component(id) {
        return None;
    }
    read_manifest(&plugin_dir(app, id))
}

/// Pfad der gespeicherten Einstellungen eines Plugins.
pub fn settings_file(app: &App, id: &str) -> PathBuf {
    plugin_dir(app, id).join(SETTINGS_FILE)
}

/// Werte eines Plugins (fehlende/kaputte Datei = Standardwerte).
fn load_values_with(app: &App, id: &str, fields: &[SettingField]) -> Map<String, Value> {
    if fields.is_empty() {
        return Map::new();
    }
    let raw = std::fs::read_to_string(settings_file(app, id))
        .ok()
        .and_then(|text| serde_json::from_str::<Map<String, Value>>(&text).ok())
        .unwrap_or_default();
    resolve_values(fields, &raw)
}

/// Aktuelle Werte fuer `pc.settings()` – ohne Schema ein leeres Objekt.
pub fn load_values(app: &App, id: &str) -> Map<String, Value> {
    let Some(manifest) = installed_manifest(app, id) else {
        return Map::new();
    };
    let schema = check_schema(&manifest.settings);
    load_values_with(app, id, &schema.fields)
}

/// Antwort von `GET /plugins/{id}/settings`: Schema und aktuelle Werte.
pub fn describe_settings(app: &App, id: &str) -> Result<Value, String> {
    let manifest =
        installed_manifest(app, id).ok_or_else(|| format!("Plugin '{id}' ist unbekannt"))?;
    let schema = check_schema(&manifest.settings);
    let values = load_values_with(app, id, &schema.fields);
    Ok(json!({
        "id": manifest.id,
        "schema": schema_json(&schema.fields),
        "values": values,
    }))
}

/// Prueft, speichert und liefert die Werte (`POST /plugins/{id}/settings`).
pub fn save_values(
    app: &App,
    id: &str,
    raw: &Map<String, Value>,
) -> Result<Map<String, Value>, SettingsProblem> {
    let manifest = installed_manifest(app, id)
        .ok_or_else(|| SettingsProblem::NotFound(format!("Plugin '{id}' ist unbekannt")))?;
    let schema = check_schema(&manifest.settings);
    let values = validate_values(&schema.fields, raw)?;
    let text = serde_json::to_string_pretty(&Value::Object(values.clone())).map_err(|error| {
        SettingsProblem::Invalid(format!("Einstellungen konnten nicht geschrieben werden: {error}"))
    })?;
    std::fs::write(settings_file(app, id), text).map_err(|error| {
        SettingsProblem::Invalid(format!("Einstellungen konnten nicht geschrieben werden: {error}"))
    })?;
    Ok(values)
}

/// Schreibt verworfene Schema-Felder ins Journal (eine Meldung je Problem).
/// Ohne Probleme passiert nichts.
pub fn report_schema_problems(app: &Arc<App>, manifest: &Manifest) {
    let schema = check_schema(&manifest.settings);
    if schema.problems.is_empty() {
        return;
    }
    let name = if manifest.name.trim().is_empty() {
        manifest.id.clone()
    } else {
        manifest.name.clone()
    };
    let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
    for problem in schema.problems {
        log_event(app, &mut guard, "plugin", &format!("{name}: {problem}"), false);
    }
}

/// Prueft beim Agenten-Start alle installierten Plugins (Phase G4).
pub fn report_all_schema_problems(app: &Arc<App>) {
    for manifest in manifests(app) {
        report_schema_problems(app, &manifest);
    }
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
    /// SHA-256-Hash des Pakets zur Integritätsprüfung (optional, aber empfohlen).
    pub sha256: Option<String>,
}

impl Default for CatalogEntry {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            description: String::new(),
            version: "1".to_string(),
            url: String::new(),
            sha256: None,
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
    if let Some((text, _)) = download(app, CATALOG_URL) {
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
/// Gibt (Inhalt, SHA-256-Hex) zurück. Downloads sind lesend und laufen auch
/// im Simulationsmodus.
fn download(_app: &App, url: &str) -> Option<(String, String)> {
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
    let bytes = std::fs::read(&target).ok()?;
    let mut hasher = Sha256::new();
    hasher.update(&bytes);
    let hash = format!("{:x}", hasher.finalize());
    let text = String::from_utf8(bytes).ok()?;
    let _ = std::fs::remove_file(&target);
    Some((text, hash))
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
    install_from_url(app, &entry.id, &entry.url, &entry.version, entry.sha256.as_deref())
}

/// Paket von einer URL laden und installieren.
pub fn install_from_url(
    app: &Arc<App>,
    id: &str,
    url: &str,
    version: &str,
    expected_sha256: Option<&str>,
) -> Result<Manifest, String> {
    let (text, hash) = download(app, url)
        .ok_or_else(|| format!("Paket konnte nicht geladen werden: {url}"))?;
    // SHA-256 Integritätsprüfung, wenn im Katalog angegeben.
    if let Some(expected) = expected_sha256 {
        if hash != expected {
            return Err(format!(
                "Paket-Hash stimmt nicht ueberein (erwartet {expected}, erhalten {hash})"
            ));
        }
    }
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
    let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
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
        let mut state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
        let buffer = state.output.entry(id.to_string()).or_default();
        buffer.push_back(line.to_string());
        while buffer.len() > OUTPUT_MAX {
            buffer.pop_front();
        }
    }
    if line.starts_with('>') {
        let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
        log_event(app, &mut guard, "plugin", line.trim_start_matches('>').trim(), true);
    }
}

fn set_status(app: &Arc<App>, id: &str, text: &str) {
    let mut state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
    state.status.insert(id.to_string(), text.to_string());
}

/// Startet ein Plugin.
pub fn start(app: &Arc<App>, id: &str, game: Option<String>, auto: bool) -> Result<Value, String> {
    let manifest = manifests(app)
        .into_iter()
        .find(|manifest| manifest.id == id)
        .ok_or_else(|| format!("Plugin '{id}' ist unbekannt"))?;
    // Verworfene Einstellungsfelder einmal beim Laden ins Journal (Phase G4).
    report_schema_problems(app, &manifest);
    let dir = plugin_dir(app, &manifest.id);
    let entry = dir.join(&manifest.entry);
    let source = std::fs::read_to_string(&entry)
        .map_err(|_| format!("Datei '{}' fehlt", manifest.entry))?;

    {
        let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
        if state.runs.contains_key(&manifest.id) {
            return Err(format!("Plugin '{}' laeuft bereits", manifest.id));
        }
    }

    let (tx, rx) = mpsc::channel::<Command>();
    let done = Arc::new(AtomicBool::new(false));
    let host = Arc::new(HostCtx {
        app: Arc::clone(app),
        plugin_id: manifest.id.clone(),
        permissions: manifest.permissions.iter().cloned().collect(),
    });

    let thread_app = Arc::clone(app);
    let thread_id = manifest.id.clone();
    let thread_manifest = manifest.clone();
    let thread_done = Arc::clone(&done);
    let thread_host = Arc::clone(&host);
    std::thread::Builder::new()
        .name(format!("plugin-{id}"))
        .spawn(move || {
            run_plugin(thread_app, thread_id, thread_manifest, source, thread_host, rx);
            thread_done.store(true, Ordering::SeqCst);
        })
        .map_err(|error| format!("Plugin-Thread konnte nicht gestartet werden: {error}"))?;

    let started_at = now_epoch();
    {
        let mut state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
        state.output.insert(manifest.id.clone(), VecDeque::new());
        state.runs.insert(
            manifest.id.clone(),
            JsRun {
                tx,
                started_at,
                game: game.clone(),
                auto,
                done,
                idle_timeout_s: manifest.idle_timeout_s,
                last_seen: Arc::new(Mutex::new(Instant::now())),
                host: Arc::clone(&host),
            },
        );
    }
    let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
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
        let mut state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
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
    let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
    log_event(app, &mut guard, "plugin", &format!("{id} {detail}"), true);
    Ok(detail)
}

/// Letzte Ausgabezeilen eines Plugins.
pub fn output(app: &Arc<App>, id: &str, lines: usize) -> String {
    let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
    // Ein Abruf gilt als Nutzung: solange die App die Ausgabe liest, laeuft das
    // Plugin weiter (Pflichtregel D2).
    if let Some(run) = state.runs.get(id) {
        if let Ok(mut seen) = run.last_seen.lock() {
            *seen = Instant::now();
        }
    }
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

// ---------------- Idle-Timeout (Pflichtregel D2) ----------------

/// Reine Zeitpruefung: ist der Timeout ohne Abruf abgelaufen? (0 = nie)
pub fn idle_expired(last_seen: Instant, now: Instant, timeout_s: u64) -> bool {
    timeout_s > 0
        && now.saturating_duration_since(last_seen) >= Duration::from_secs(timeout_s)
}

/// Beendet Plugins, deren Ausgabe zu lange niemand abgerufen hat.
pub fn stop_idle(app: &Arc<App>) {
    let due: Vec<(String, u64)> = {
        let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
        let now = Instant::now();
        state
            .runs
            .iter()
            .filter_map(|(id, run)| {
                let last = *run.last_seen.lock().ok()?;
                idle_expired(last, now, run.idle_timeout_s)
                    .then(|| (id.clone(), run.idle_timeout_s))
            })
            .collect()
    };
    for (id, timeout_s) in due {
        push_line(app, &id, &format!("> Nach {timeout_s} s ohne Abruf beendet"));
        // `stop` protokolliert das Ende selbst; ein bereits beendetes Plugin
        // liefert hier nur einen Fehler, der bewusst verworfen wird.
        let _ = stop(app, &id);
    }
}

/// Hintergrund-Schleife fuer [`stop_idle`] (Takt 30 s).
pub async fn idle_loop(app: Arc<App>) {
    let mut ticker = tokio::time::interval(IDLE_CHECK);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        let worker = Arc::clone(&app);
        let _ = tokio::task::spawn_blocking(move || stop_idle(&worker)).await;
    }
}

// ---------------- Host-Funktionen ----------------

/// Zustand, den die Host-Funktionen brauchen.
pub(crate) struct HostCtx {
    app: Arc<App>,
    plugin_id: String,
    permissions: HashSet<String>,
}

/// Baut die JavaScript-Bruecke: `__pc_host(name, json)`.
///
/// `host_ptr` ist ein roher Zeiger auf ein `Arc<HostCtx>`, das im
/// [`JsRun`] gespeichert ist und so lange lebt wie das Plugin.
/// Der Pointer bleibt gültig, weil [`JsRun.host`] das `Arc` besitzt.
fn register_host(context: &mut JsContext, host_ptr: usize) {
    let function = NativeFunction::from_copy_closure_with_captures(
        move |_this: &JsValue, args: &[JsValue], host_ptr: &usize, js_context: &mut JsContext| {
            let host = unsafe { &*(*host_ptr as *const HostCtx) };
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
        host_ptr,
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
    steam: function () { return call("steam", []); },
    settings: function () { return call("settings", []); },
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
    host: Arc<HostCtx>,
    rx: Receiver<Command>,
) {
    let mut context = JsContext::default();
    register_host(&mut context, Arc::as_ptr(&host) as usize);

    let failed = |app: &Arc<App>, id: &str, message: String| {
        push_line(app, id, &format!("> Fehler: {message}"));
    };

    if let Err(error) = context.eval(Source::from_bytes(PRELUDE)) {
        failed(&app, &id, format!("Host-API: {error}"));
        return;
    }
    if let Err(error) = context.eval(Source::from_bytes(&source)) {
        failed(&app, &id, format!("beim Laden: {error}"));
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

while {
        match rx.recv_timeout(interval) {
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if let Err(error) = call_global(&mut context, "tick") {
                    failed(&app, &id, error);
                }
                true
            }
            Ok(Command::Stop) | Err(mpsc::RecvTimeoutError::Disconnected) => false,
        }
    } {}

    if let Err(error) = call_global(&mut context, "stop") {
        failed(&app, &id, error);
    }
    drop(context);
}

/// Eine Host-Funktion ausfuehren. Rueckgabe ist immer JSON.
fn host_call(host: &HostCtx, name: &str, args: &Value) -> Result<Value, String> {
    let permission = match name {
        "log" => Some("log"),
        "status" | "events" | "gameConfig" => Some("status"),
        "metrics" => Some("metrics"),
        "powerPlan" | "setPowerPlan" | "shutdown" | "abort" | "hibernate" => Some("power"),
        "games" | "scanGames" => Some("games"),
        "steam" => Some("steam"),
        "settings" => Some("settings"),
        "killProcesses" | "boost" | "cleanMemory" | "isAdmin" => Some("process"),
        _ => None,
    };
    if let Some(needed) = permission {
        if !host.permissions.contains(needed) {
            // Ohne Freigabe `settings` liefert `pc.settings()` bewusst ein
            // leeres Objekt statt eines Fehlers (Phase G4).
            if name == "settings" {
                return Ok(json!({}));
            }
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
                let guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
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
        // Steam nur lesen: Pfad, Millennium, Downloads, Tempo (src/steam.rs).
        "steam" => Ok(serde_json::to_value(crate::steam::status(app)).unwrap_or(Value::Null)),
        // Einstellungen aus `settings.json` (Phase G4), nur lesend.
        "settings" => Ok(Value::Object(load_values(app, &host.plugin_id))),
        "events" => {
            let guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
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
            let simulate = app.inner.lock().unwrap_or_else(|e| e.into_inner()).simulate;
            let killed = if simulate {
                names.clone()
            } else {
                crate::optimize::kill_list(&names, None)
            };
            Ok(json!({ "killed": killed }))
        }
        "boost" => {
            let exe = args.get(0).and_then(Value::as_str).unwrap_or_default();
            let simulate = app.inner.lock().unwrap_or_else(|e| e.into_inner()).simulate;
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
            let simulate = app.inner.lock().unwrap_or_else(|e| e.into_inner()).simulate;
            if simulate {
                let metrics = crate::metrics::read(&app.metrics);
                return Ok(json!({
                    "simulated": true,
                    "admin": crate::optimize::is_admin(),
                    "trimmed": 0,
                    "system_cache": false,
                    "modified": false,
                    "standby": false,
                    "standby_prio0": false,
                    "registry": false,
                    "combined": false,
                    "volume": false,
                    "used_mb": metrics.ram_used_mb,
                    "free_mb": metrics.ram_total_mb.saturating_sub(metrics.ram_used_mb),
                    "before_used_mb": metrics.ram_used_mb,
                    "before_free_mb": metrics.ram_total_mb.saturating_sub(metrics.ram_used_mb),
                }));
            }
            let before = crate::metrics::read(&app.metrics);
            let (
                trimmed,
                system_cache,
                modified,
                standby,
                standby_prio0,
                registry,
                combined,
                volume,
                admin,
            ) = crate::optimize::clean_memory_full(simulate);
            let after = crate::metrics::read(&app.metrics);
            Ok(json!({
                "admin": admin,
                "trimmed": trimmed,
                "system_cache": system_cache,
                "modified": modified,
                "standby": standby,
                "standby_prio0": standby_prio0,
                "registry": registry,
                "combined": combined,
                "volume": volume,
                "used_mb": after.ram_used_mb,
                "free_mb": after.ram_total_mb.saturating_sub(after.ram_used_mb),
                "before_used_mb": before.ram_used_mb,
                "before_free_mb": before.ram_total_mb.saturating_sub(before.ram_used_mb),
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
        let manifest = install_from_url(&app, "demo", &url, "1", None).unwrap();
        assert_eq!(manifest.id, "demo");
        assert!(is_installed(&app, "demo"));
        let code = std::fs::read_to_string(plugin_dir(&app, "demo").join(DEFAULT_ENTRY)).unwrap();
        assert!(code.contains("activate"));

        // Falsche ID im Paket wird abgelehnt.
        let error = install_from_url(&app, "fremd", &url, "1", None).unwrap_err();
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

    /// Pfad zum Marktplatz-Paket `steam-millennium` im Repository.
    fn steam_package() -> Bundle {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("plugin-catalog")
            .join("steam-millennium.json");
        let text =
            std::fs::read_to_string(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()));
        serde_json::from_str(&text).expect("Paket ist kein gueltiges Plugin-JSON")
    }

    #[test]
    fn katalogpaket_steam_millennium_ist_gueltig() {
        let bundle = steam_package();
        assert_eq!(bundle.id, "steam-millennium");
        assert_eq!(bundle.interval_ms, 2000);
        assert!(bundle.permissions.iter().any(|right| right == "steam"));
        assert!(bundle.permissions.iter().any(|right| right == "log"));
        let manifest = bundle.manifest();
        assert!(manifest.is_valid());
        let code = bundle.files.get(&bundle.entry).expect("main.js fehlt");
        assert!(code.contains("function activate"));
        assert!(code.contains("function tick"));
        assert!(code.contains("pc.steam()"));
    }

    #[test]
    fn katalogpaket_js_laesst_sich_in_boa_laden() {
        let bundle = steam_package();
        let code = bundle.files.get(&bundle.entry).unwrap().clone();
        // Nur laden (kein activate/tick): dabei wird keine Host-Funktion gerufen.
        let mut context = JsContext::default();
        context
            .eval(Source::from_bytes(PRELUDE.as_bytes()))
            .expect("Vorspann laedt");
        context
            .eval(Source::from_bytes(code.as_bytes()))
            .expect("Plugin-Code laedt");
        let check = context
            .eval(Source::from_bytes(b"typeof pc.steam === 'function'"))
            .expect("Pruefung laeuft");
        assert!(check.to_boolean(), "pc.steam() fehlt im Vorspann");
    }

    #[test]
    fn steam_ohne_berechtigung_wird_abgelehnt() {
        let (app, dir) = test_app("host-steam-deny");
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions: HashSet::new(),
        };
        let error = host_call(&host, "steam", &Value::Null).unwrap_err();
        assert!(error.contains("Berechtigung 'steam' fehlt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn steam_mit_berechtigung_liefert_status() {
        let (app, dir) = test_app("host-steam-ok");
        let mut permissions = HashSet::new();
        permissions.insert("steam".to_string());
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions,
        };
        let value = host_call(&host, "steam", &Value::Null).unwrap();
        // Auch ohne Steam-Installation kommt ein vollstaendiges JSON zurueck.
        assert!(value.get("detected").is_some());
        assert!(value.get("millennium").is_some());
        assert!(value.get("downloads").is_some());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn idle_timeout_prueft_nur_nach_ablauf() {
        let start = Instant::now();
        // Vor Ablauf: weiterlaufen.
        assert!(!idle_expired(start, start + Duration::from_secs(299), 300));
        // Genau abgelaufen: beenden.
        assert!(idle_expired(start, start + Duration::from_secs(300), 300));
        // 0 = nie beenden (z. B. Game Optimization laeuft bis zum Stop).
        assert!(!idle_expired(start, start + Duration::from_secs(86_400), 0));
    }

    #[test]
    fn paket_und_manifest_tragen_den_idle_timeout() {
        let bundle = Bundle {
            id: "demo".to_string(),
            idle_timeout_s: 42,
            files: HashMap::from([(DEFAULT_ENTRY.to_string(), "".to_string())]),
            ..Default::default()
        };
        let manifest = bundle.manifest();
        assert_eq!(manifest.idle_timeout_s, 42);
        // Ohne Angabe im Paket gilt der Standard (5 Minuten).
        let plain: Bundle = serde_json::from_str(
            r#"{"id":"x","files":{"main.js":""}}"#,
        )
        .unwrap();
        assert_eq!(plain.idle_timeout_s, DEFAULT_IDLE_TIMEOUT_S);
        assert_eq!(plain.manifest().idle_timeout_s, DEFAULT_IDLE_TIMEOUT_S);
    }
    // ---------------- Einstellungen (Phase G4) ----------------

    /// Beispiel-Schema der Aufgabe: number, select, switch, text.
    fn sample_schema() -> Vec<Value> {
        vec![
            json!({"key": "refresh_s", "label": "Aktualisierung (s)", "type": "number", "default": 5, "min": 1, "max": 600}),
            json!({"key": "mode", "label": "Modus", "type": "select", "default": "auto", "options": ["auto", "manuell"]}),
            json!({"key": "notify", "label": "Benachrichtigen", "type": "switch", "default": true}),
            json!({"key": "name", "label": "Name", "type": "text", "default": ""}),
        ]
    }

    /// Installiert ein Test-Plugin im Datenordner (ohne Agent-Start).
    fn write_plugin(app: &App, id: &str, settings: Vec<Value>) {
        let manifest = Manifest {
            id: id.to_string(),
            name: "Test".to_string(),
            entry: DEFAULT_ENTRY.to_string(),
            permissions: vec!["log".to_string()],
            interval_ms: 0,
            settings,
            ..Default::default()
        };
        let dir = plugin_dir(app, id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("plugin.json"),
            serde_json::to_string_pretty(&manifest).unwrap(),
        )
        .unwrap();
        std::fs::write(dir.join(DEFAULT_ENTRY), "function activate() {}").unwrap();
    }

    #[test]
    fn schema_akzeptiert_das_beispiel() {
        let schema = check_schema(&sample_schema());
        assert!(schema.problems.is_empty(), "{:?}", schema.problems);
        assert_eq!(schema.fields.len(), 4);
        let keys: Vec<&str> = schema.fields.iter().map(|field| field.key.as_str()).collect();
        assert_eq!(keys, vec!["refresh_s", "mode", "notify", "name"]);
        // Nur `number` traegt Grenzen, nur `select` Optionen.
        assert_eq!(schema.fields[0].min, Some(1.0));
        assert_eq!(schema.fields[0].max, Some(600.0));
        assert_eq!(schema.fields[0].default, json!(5));
        assert_eq!(schema.fields[1].options, vec!["auto", "manuell"]);
        assert!(schema.fields[2].options.is_empty());
        assert_eq!(schema.fields[2].default, json!(true));
        // Die JSON-Ausgabe nennt nur die passenden Felder.
        let json_schema = schema_json(&schema.fields);
        assert_eq!(json_schema[0]["type"], json!("number"));
        assert_eq!(json_schema[0]["min"], json!(1));
        assert_eq!(json_schema[0]["max"], json!(600));
        assert!(json_schema[1].get("min").is_none());
        assert!(json_schema[2].get("options").is_none());
        assert_eq!(json_schema[1]["options"], json!(["auto", "manuell"]));
    }

    #[test]
    fn schema_verwirft_ungueltige_felder_ohne_absturz() {
        let raw = vec![
            json!({"key": "Refresh", "label": "Gross", "type": "number", "default": 1}),
            json!({"key": "", "label": "Leer", "type": "text"}),
            json!({"key": "ok", "label": "Gut", "type": "text", "default": "x"}),
            json!({"key": "zahl", "label": "Unbekannter Typ", "type": "farbe", "default": 1}),
            json!({"key": "ohne_label", "label": "  ", "type": "switch", "default": true}),
            json!({"label": "Kein Key", "type": "text"}),
            json!("kein objekt"),
        ];
        let schema = check_schema(&raw);
        // Nur das saubere Feld bleibt uebrig, der Rest wird verworfen.
        assert_eq!(schema.fields.len(), 1);
        assert_eq!(schema.fields[0].key, "ok");
        assert_eq!(schema.problems.len(), 6);
        assert!(schema.problems.iter().all(|text| text.contains("verworfen")));
    }


    #[test]
    fn schema_verwirft_doppelte_keys() {
        let raw = vec![
            json!({"key": "mode", "label": "Modus", "type": "text", "default": ""}),
            json!({"key": "mode", "label": "Nochmal", "type": "text", "default": ""}),
        ];
        let schema = check_schema(&raw);
        assert_eq!(schema.fields.len(), 1);
        assert_eq!(schema.problems.len(), 1);
        assert!(schema.problems[0].contains("doppelt"));
    }

    #[test]
    fn select_ohne_optionen_ist_ungueltig() {
        let ohne = vec![json!({"key": "mode", "label": "Modus", "type": "select", "default": "a"})];
        let schema = check_schema(&ohne);
        assert!(schema.fields.is_empty());
        assert!(schema.problems[0].contains("Option"));

        let leer = vec![json!({"key": "mode", "label": "Modus", "type": "select", "options": []})];
        assert!(check_schema(&leer).fields.is_empty());

        // Standardwert ausserhalb der Optionen ist widerspruechlich.
        let fremd = vec![json!({
            "key": "mode", "label": "Modus", "type": "select",
            "options": ["auto"], "default": "hand"
        })];
        assert!(check_schema(&fremd).fields.is_empty());
    }

    #[test]
    fn number_grenzen_werden_geprueft() {
        // min/max nur bei `number`.
        let text_mit_grenzen = vec![json!({"key": "name", "label": "Name", "type": "text", "min": 1})];
        let schema = check_schema(&text_mit_grenzen);
        assert!(schema.fields.is_empty());
        assert!(schema.problems[0].contains("min/max"));

        // min groesser als max.
        let verdreht = vec![json!({
            "key": "dauer", "label": "Dauer", "type": "number", "min": 10, "max": 5
        })];
        assert!(check_schema(&verdreht).fields.is_empty());

        // Standardwert ausserhalb der Grenzen.
        let zu_gross = vec![json!({
            "key": "dauer", "label": "Dauer", "type": "number", "default": 900, "max": 600
        })];
        assert!(check_schema(&zu_gross).fields.is_empty());

        // Ohne Standardwert gilt min (bzw. 0).
        let ohne_default = vec![json!({"key": "dauer", "label": "Dauer", "type": "number", "min": 2})];
        let schema = check_schema(&ohne_default);
        assert_eq!(schema.fields[0].default, json!(2));
        let bare = vec![json!({"key": "dauer", "label": "Dauer", "type": "number"})];
        assert_eq!(check_schema(&bare).fields[0].default, json!(0));
    }

    #[test]
    fn hoechstens_zwanzig_felder() {
        let raw: Vec<Value> = (0..25)
            .map(|index| json!({"key": format!("feld_{index}"), "label": "Feld", "type": "text"}))
            .collect();
        let schema = check_schema(&raw);
        assert_eq!(schema.fields.len(), SETTINGS_MAX_FIELDS);
        assert!(schema.problems.iter().any(|text| text.contains("Mehr als")));
    }


    #[test]
    fn defaults_ohne_settings_datei() {
        let (app, dir) = test_app("settings-defaults");
        write_plugin(&app, "demo", sample_schema());
        assert!(!settings_file(&app, "demo").exists());
        let values = load_values(&app, "demo");
        assert_eq!(values["refresh_s"], json!(5));
        assert_eq!(values["mode"], json!("auto"));
        assert_eq!(values["notify"], json!(true));
        assert_eq!(values["name"], json!(""));
        // Genau die Standardwerte aus dem Schema.
        let schema = check_schema(&sample_schema());
        assert_eq!(values, default_values(&schema.fields));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn falsche_typen_fallen_auf_den_default_zurueck() {
        let (app, dir) = test_app("settings-fallback");
        write_plugin(&app, "demo", sample_schema());
        // Absichtlich falsche Typen und Werte ausserhalb der Grenzen.
        std::fs::write(
            settings_file(&app, "demo"),
            json!({
                "refresh_s": 9999,
                "mode": "gibtesnicht",
                "notify": "ja",
                "name": 42,
                "unbekannt": true
            })
            .to_string(),
        )
        .unwrap();
        let values = load_values(&app, "demo");
        assert_eq!(values["refresh_s"], json!(5));
        assert_eq!(values["mode"], json!("auto"));
        assert_eq!(values["notify"], json!(true));
        assert_eq!(values["name"], json!(""));
        // Unbekannte Schluessel aus der Datei werden verworfen.
        assert!(!values.contains_key("unbekannt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn speichern_und_laden_ist_ein_rundlauf() {
        let (app, dir) = test_app("settings-roundtrip");
        write_plugin(&app, "demo", sample_schema());
        let mut wanted = Map::new();
        wanted.insert("refresh_s".to_string(), json!(30));
        wanted.insert("mode".to_string(), json!("manuell"));
        wanted.insert("notify".to_string(), json!(false));
        wanted.insert("name".to_string(), json!("Wohnzimmer"));

        let saved = save_values(&app, "demo", &wanted).unwrap();
        assert_eq!(saved["refresh_s"], json!(30));
        assert_eq!(saved["mode"], json!("manuell"));

        // Datei liegt im Plugin-Ordner und ist ein flaches Objekt.
        let text = std::fs::read_to_string(settings_file(&app, "demo")).unwrap();
        let raw: Map<String, Value> = serde_json::from_str(&text).unwrap();
        assert_eq!(raw.len(), 4);
        assert_eq!(raw["name"], json!("Wohnzimmer"));

        // Erneut laden liefert dieselben Werte (auch ueber den Endpunkt-Bauer).
        assert_eq!(load_values(&app, "demo"), saved);
        let described = describe_settings(&app, "demo").unwrap();
        assert_eq!(described["values"]["refresh_s"], json!(30));
        assert_eq!(described["schema"].as_array().unwrap().len(), 4);

        // Teil-Update: fehlende Schluessel bleiben auf dem Standard.
        let mut partial = Map::new();
        partial.insert("notify".to_string(), json!(true));
        let saved = save_values(&app, "demo", &partial).unwrap();
        assert_eq!(saved["notify"], json!(true));
        assert_eq!(saved["refresh_s"], json!(5));
        std::fs::remove_dir_all(&dir).unwrap();
    }


    #[test]
    fn unbekannter_key_wird_abgelehnt() {
        let (app, dir) = test_app("settings-unknown-key");
        write_plugin(&app, "demo", sample_schema());
        let mut wanted = Map::new();
        wanted.insert("gibtesnicht".to_string(), json!(1));
        let error = save_values(&app, "demo", &wanted).unwrap_err();
        assert!(error.message().contains("Unbekannter Einstellungsschluessel"));
        // Nichts geschrieben.
        assert!(!settings_file(&app, "demo").exists());

        // Bekannter Schluessel, falscher Typ.
        let mut bad = Map::new();
        bad.insert("refresh_s".to_string(), json!("viel"));
        let error = save_values(&app, "demo", &bad).unwrap_err();
        assert!(error.message().contains("ungueltig"));

        // Unbekanntes Plugin.
        let error = save_values(&app, "fehlt", &Map::new()).unwrap_err();
        assert!(error.message().contains("ist unbekannt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn einstellungen_stehen_nicht_im_paket() {
        let (app, dir) = test_app("settings-bundle");
        write_plugin(&app, "demo", sample_schema());
        let mut wanted = Map::new();
        wanted.insert("name".to_string(), json!("geheim"));
        save_values(&app, "demo", &wanted).unwrap();
        let bundle = bundle_of(&app, "demo").unwrap();
        assert!(!bundle.files.contains_key(SETTINGS_FILE));
        assert!(bundle.files.contains_key(DEFAULT_ENTRY));
        // Deinstallieren loescht auch die Einstellungen.
        uninstall(&app, "demo").unwrap();
        assert!(!settings_file(&app, "demo").exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn schema_ohne_settings_verhaelt_sich_wie_bisher() {
        let (app, dir) = test_app("settings-none");
        write_plugin(&app, "demo", Vec::new());
        let described = describe_settings(&app, "demo").unwrap();
        assert_eq!(described["schema"], json!([]));
        assert_eq!(described["values"], json!({}));
        assert!(load_values(&app, "demo").is_empty());
        // Leerer Body ist erlaubt (nichts zu speichern).
        assert!(save_values(&app, "demo", &Map::new()).unwrap().is_empty());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn kaputtes_settings_feld_macht_das_manifest_nicht_unlesbar() {
        let (app, dir) = test_app("settings-broken-manifest");
        let plugin_path = plugin_dir(&app, "demo");
        std::fs::create_dir_all(&plugin_path).unwrap();
        std::fs::write(plugin_path.join(DEFAULT_ENTRY), "function activate() {}").unwrap();

        // `settings` ist hier kein Array, sondern ein einzelnes Feld.
        std::fs::write(
            plugin_path.join("plugin.json"),
            r#"{"id":"demo","name":"Test","entry":"main.js","settings":{"key":"mode","label":"Modus","type":"text","default":"x"}}"#,
        )
        .unwrap();
        let manifest = installed_manifest(&app, "demo").expect("Manifest bleibt lesbar");
        assert_eq!(manifest.settings.len(), 1);
        let schema = check_schema(&manifest.settings);
        assert_eq!(schema.fields.len(), 1);
        assert_eq!(load_values(&app, "demo")["mode"], json!("x"));

        // Voelliger Unsinn wird zu einem Eintrag, der beim Pruefen verworfen wird.
        std::fs::write(
            plugin_path.join("plugin.json"),
            r#"{"id":"demo","name":"Test","entry":"main.js","settings":"kaputt"}"#,
        )
        .unwrap();
        let manifest = installed_manifest(&app, "demo").expect("Manifest bleibt lesbar");
        let schema = check_schema(&manifest.settings);
        assert!(schema.fields.is_empty());
        assert_eq!(schema.problems.len(), 1);

        // Ohne Feld ist alles wie bisher.
        std::fs::write(
            plugin_path.join("plugin.json"),
            r#"{"id":"demo","name":"Test","entry":"main.js"}"#,
        )
        .unwrap();
        let manifest = installed_manifest(&app, "demo").expect("Manifest bleibt lesbar");
        assert!(manifest.settings.is_empty());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn pc_settings_ohne_freigabe_ist_leer() {
        let (app, dir) = test_app("settings-deny");
        write_plugin(&app, "x", sample_schema());
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions: HashSet::new(),
        };
        // Ohne Freigabe kein Fehler, sondern ein leeres Objekt.
        let value = host_call(&host, "settings", &Value::Null).unwrap();
        assert_eq!(value, json!({}));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn pc_settings_mit_freigabe_liefert_werte() {
        let (app, dir) = test_app("settings-allow");
        write_plugin(&app, "x", sample_schema());
        let host = HostCtx {
            app: Arc::clone(&app),
            plugin_id: "x".to_string(),
            permissions: HashSet::from(["settings".to_string()]),
        };
        let value = host_call(&host, "settings", &Value::Null).unwrap();
        assert_eq!(value["refresh_s"], json!(5));
        assert_eq!(value["mode"], json!("auto"));

        // Nach dem Speichern liest das Plugin die neuen Werte.
        let mut wanted = Map::new();
        wanted.insert("refresh_s".to_string(), json!(120));
        save_values(&app, "x", &wanted).unwrap();
        let value = host_call(&host, "settings", &Value::Null).unwrap();
        assert_eq!(value["refresh_s"], json!(120));
        std::fs::remove_dir_all(&dir).unwrap();
    }


}
