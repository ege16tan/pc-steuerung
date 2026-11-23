//! Zentraler Zustand des Agenten.
//!
//! Zugangsdaten und Log liegen im gemeinsamen
//! Datenordner, das Ereignis-Log ist ein Ringpuffer (neueste zuerst) und ein
//! geplantes Herunterfahren wird als Zeitpunkt (Unix-Epoche) gespeichert.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

/// Kappungsgrenze = 50 Ereignisse (wie Deque(maxlen=50) im Python-Agent).
pub const EVENT_MAX: usize = 50;

/// Rolle mit allen Rechten (schalten, Benutzer verwalten).
pub const ROLE_ADMIN: &str = "admin";
/// Rolle mit Lesezugriff (Status, Messwerte, Ereignisse, Log).
pub const ROLE_VIEWER: &str = "viewer";

/// Unbekannte Rolle -> `admin` (alte `credentials.json` ohne `role`-Feld).
pub fn parse_role(value: &str) -> &'static str {
    if value.trim().eq_ignore_ascii_case(ROLE_VIEWER) {
        ROLE_VIEWER
    } else {
        ROLE_ADMIN
    }
}

/// Weiterer Benutzer aus `credentials.json` (Phase 3, Rollen/Rechte).
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct UserEntry {
    pub user: String,
    pub salt: String,
    pub hash: String,
    pub role: String,
}

impl Default for UserEntry {
    fn default() -> Self {
        Self {
            user: String::new(),
            salt: String::new(),
            hash: String::new(),
            role: ROLE_ADMIN.to_string(),
        }
    }
}

impl UserEntry {
    /// Alle drei Felder muessen gefuellt sein, sonst ist der Eintrag unbrauchbar.
    pub fn is_usable(&self) -> bool {
        !self.user.is_empty() && !self.salt.is_empty() && !self.hash.is_empty()
    }

    /// Rolle des Benutzers, nie leer.
    pub fn role(&self) -> &'static str {
        parse_role(&self.role)
    }
}

/// Zugangsdaten aus `credentials.json` (Passwort nur als PBKDF2-Hash).
///
/// Phase 3: Neben dem Hauptbenutzer (`user`/`salt`/`hash`) gibt es eine Liste
/// weiterer Benutzer mit eigener Rolle. Alte Dateien ohne `role`/`users`
/// bleiben gueltig – der Hauptbenutzer ist dann `admin`.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Credentials {
    pub user: String,
    pub salt: String,
    pub hash: String,
    pub role: String,
    pub users: Vec<UserEntry>,
}

impl Default for Credentials {
    fn default() -> Self {
        Self {
            user: String::new(),
            salt: String::new(),
            hash: String::new(),
            role: ROLE_ADMIN.to_string(),
            users: Vec::new(),
        }
    }
}

impl Credentials {
    /// Rolle des Hauptbenutzers, nie leer.
    pub fn role(&self) -> &'static str {
        parse_role(&self.role)
    }
}

/// Token-Zugangsdaten: nur Salt und PBKDF2-Hash werden gespeichert.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TokenCredentials {
    pub salt: String,
    pub hash: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct DeviceSettings {
    pub name: String,
    pub wol_enabled: bool,
    pub mac: String,
}

impl Default for DeviceSettings {
    fn default() -> Self {
        Self { name: String::new(), wol_enabled: false, mac: String::new() }
    }
}

impl Credentials {
    /// Gueltig, wenn der Hauptbenutzer vollstaendig ist oder mindestens ein
    /// weiterer Benutzer; sonst muss die Einrichtung erneut laufen.
    pub fn is_usable(&self) -> bool {
        let primary = !self.user.is_empty() && !self.salt.is_empty() && !self.hash.is_empty();
        primary || self.users.iter().any(UserEntry::is_usable)
    }
}

/// Ereignis fuer `/events` — Feldnamen identisch zum Python-Agent.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Event {
    pub time: String,
    pub action: String,
    pub detail: String,
    pub ok: bool,
}

/// Veraenderbarer Zustand; alles ueber den Mutex geschuetzt.
pub struct InnerApp {
    pub simulate: bool,
    pub sim_plan: Option<String>,
    pub pending_at: Option<f64>,
    pub pending_seconds: i64,
    pub events: VecDeque<Event>,
    pub credentials: Option<Credentials>,
    pub token_credentials: Option<TokenCredentials>,
}

/// Gesamter Agenten-Zustand (wird per Arc geteilt).
pub struct App {
    pub inner: Mutex<InnerApp>,
    /// HTTP-Port fuer die Verbindungs-QR-Anzeige.
    pub port: u16,
    /// Env `PC_AGENT_TOKEN` beim Start (feste Quelle, wie im Python-Agent).
    pub token: String,
    /// Logdatei im gemeinsamen Datenordner.
    pub log_file: PathBuf,
    /// Zugangsdaten im gemeinsamen Datenordner.
    pub creds_file: PathBuf,
    pub token_file: PathBuf,
    pub device_file: PathBuf,
    pub device_settings: Mutex<DeviceSettings>,
    /// Ordner der laufenden EXE (dort liegen Plugins und das Manifest-Verzeichnis).
    pub exe_dir: PathBuf,
    /// Zentraler Datenordner (`%LOCALAPPDATA%\PC Steuerung`).
    pub data_dir: PathBuf,
    /// Laufende Plugins und deren Ausgabe (siehe `plugins`).
    pub plugins: Mutex<crate::plugins::PluginState>,
    /// JavaScript-Plugins (Boa) und deren Ausgabe.
    pub jsplugins: Mutex<crate::jsplugins::JsPluginState>,
    /// Zwischengespeicherte CPU-Stichprobe fuer `/metrics`.
    pub metrics: Mutex<crate::metrics::MetricsState>,
    /// Zwischengespeicherte Netzwerk- und GPU-Messungen fuer `/sensors`.
    pub sensors: Mutex<crate::sensors::SensorsState>,
    /// Zwischengespeicherte Spiele-Liste (`games.json`).
    pub games_cache_file: PathBuf,
    /// Einstellungen der Spiel-Optimierung (`game-config.json`).
    pub game_config_file: PathBuf,
    /// Routinen (Phase 4) im Speicher und ihre Datei (`routines.json`).
    pub routines_file: PathBuf,
    pub routines: Mutex<crate::routines::RoutineState>,
    /// Warnregeln (Phase C) im Speicher und ihre Datei (`warnings.json`).
    pub warnings_file: PathBuf,
    pub warnings: Mutex<crate::warnings::WarningState>,
    /// Live-Updates (SSE) und CORS-Allowlist fuer die Geraeteliste.
    pub live: crate::live::LiveHub,
}

impl App {
    pub fn new(token: String, simulate: bool, base_dir: &Path, port: u16) -> App {
        let creds_file = base_dir.join("credentials.json");
        let log_file = base_dir.join("agent.log");
        let credentials = load_credentials(&creds_file);
        let token_file = base_dir.join("token.json");
        let token_credentials = load_token_credentials(&token_file);
        let device_file = base_dir.join("device.json");
        let device_settings = load_device_settings(&device_file);
        let routines_file = base_dir.join("routines.json");
        let routines = crate::routines::load(&routines_file);
        let warnings_file = base_dir.join("warnings.json");
        let warnings = crate::warnings::load(&warnings_file);
        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|path| path.parent().map(PathBuf::from))
            .unwrap_or_else(|| PathBuf::from("."));
        App {
            inner: Mutex::new(InnerApp {
                simulate,
                sim_plan: None,
                pending_at: None,
                pending_seconds: 0,
                events: VecDeque::new(),
                credentials,
                token_credentials,
            }),
            port,
            token,
            log_file,
            creds_file,
            token_file,
            device_file,
            device_settings: Mutex::new(device_settings),
            exe_dir,
            data_dir: base_dir.to_path_buf(),
            plugins: Mutex::new(crate::plugins::PluginState::default()),
            jsplugins: Mutex::new(crate::jsplugins::JsPluginState::default()),
            metrics: Mutex::new(crate::metrics::MetricsState::default()),
            sensors: Mutex::new(crate::sensors::SensorsState::default()),
            games_cache_file: base_dir.join("games.json"),
            game_config_file: base_dir.join("game-config.json"),
            routines_file,
            routines: Mutex::new(routines),
            warnings_file,
            warnings: Mutex::new(warnings),
            live: crate::live::LiveHub::new(crate::live::cors_origins_from_env()),
        }
    }
}

fn load_token_credentials(path: &Path) -> Option<TokenCredentials> {
    let text = std::fs::read_to_string(path).ok()?;
    let credentials: TokenCredentials = serde_json::from_str(&text).ok()?;
    if credentials.salt.is_empty() || credentials.hash.is_empty() { None } else { Some(credentials) }
}

fn load_device_settings(path: &Path) -> DeviceSettings {
    std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

/// Laedt `credentials.json`; None bei Lesefehler/leeren Feldern (Paritaet).
pub fn load_credentials(path: &Path) -> Option<Credentials> {
    let text = std::fs::read_to_string(path).ok()?;
    let data: Credentials = serde_json::from_str(&text).ok()?;
    if data.is_usable() {
        Some(data)
    } else {
        None
    }
}

/// Restsekunden eines geplanten Herunterfahrens (reine Funktion fuer Tests).
/// Python: max(0, round(at - time.time())) mit ties-to-even.
pub fn remaining_seconds(pending_at: Option<f64>, now: f64) -> i64 {
    match pending_at {
        None => 0,
        Some(at) => {
            let remaining = (at - now).max(0.0);
            let whole = remaining.floor();
            let fraction = remaining - whole;
            let rounded = if fraction < 0.5 {
                whole
            } else if fraction > 0.5 || (whole as i64) % 2 != 0 {
                whole + 1.0
            } else {
                whole
            };
            rounded as i64
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usable_nur_mit_vollstaendigen_feldern() {
        let usable = Credentials {
            user: "u".into(),
            salt: "00".into(),
            hash: "11".into(),
            ..Default::default()
        };
        assert!(usable.is_usable());
        assert_eq!(usable.role(), ROLE_ADMIN);

        let broken = Credentials {
            user: "".into(),
            salt: "00".into(),
            hash: "11".into(),
            ..Default::default()
        };
        assert!(!broken.is_usable());
    }

    #[test]
    fn weitere_benutzer_halten_die_anmeldung_am_leben() {
        // Hauptbenutzer unvollstaendig, aber ein Gast ist eingerichtet.
        let credentials = Credentials {
            users: vec![UserEntry {
                user: "gast".into(),
                salt: "00".into(),
                hash: "11".into(),
                role: ROLE_VIEWER.into(),
            }],
            ..Default::default()
        };
        assert!(credentials.is_usable());
        assert_eq!(credentials.users[0].role(), ROLE_VIEWER);
    }

    #[test]
    fn rolle_wird_normalisiert() {
        assert_eq!(parse_role("viewer"), ROLE_VIEWER);
        assert_eq!(parse_role(" VIEWER "), ROLE_VIEWER);
        assert_eq!(parse_role("Viewer"), ROLE_VIEWER);
        assert_eq!(parse_role("admin"), ROLE_ADMIN);
        // Unbekannt/leer (alte Dateien) -> admin, damit niemand ausgesperrt wird.
        assert_eq!(parse_role(""), ROLE_ADMIN);
        assert_eq!(parse_role("chef"), ROLE_ADMIN);
    }

    #[test]
    fn remaining_seconds_berechnet_verbleibende_zeit() {
        // 120 s geplant, 30 s vergangen -> 90 s
        let now = 1_000_000.0;
        assert_eq!(remaining_seconds(Some(now + 90.0), now), 90);
        // Abgelaufen -> 0
        assert_eq!(remaining_seconds(Some(now - 5.0), now), 0);
        // Es ist nichts geplant -> 0
        assert_eq!(remaining_seconds(None, now), 0);
    }
}
