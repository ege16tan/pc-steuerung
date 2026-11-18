//! Steam-Erkennung fuer das Marktplatz-Plugin `steam-millennium` (nur Lesen).
//!
//! Das Plugin bekommt bewusst **keinen** Dateisystem-Zugriff: der lesende Teil
//! steckt hier im Agenten und wird ueber `pc.steam()` sowie die Routen
//! `GET /steam` und `GET /downloads` ausgeliefert.
//!
//! Gelesen wird:
//! - der Steam-Pfad aus `HKCU\Software\Valve\Steam` (`SteamPath`), sonst aus
//!   einer Liste ueblicher Installationsorte,
//! - `steamapps\libraryfolders.vdf` (Valve KeyValues) fuer alle Bibliotheken,
//! - `steamapps\appmanifest_<appid>.acf` fuer die Downloads.
//!
//! Millennium (Steam-Client-Modding) wird nur **erkannt**: `<Steam>\millennium`
//! existiert oder `<Steam>\plugins` enthaelt `*.json`-Dateien. Es wird nichts
//! installiert, nichts geschrieben und kein Prozess beendet.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::app::App;
use crate::commands::run;

/// Uebliche Installationsorte, falls die Registry nichts liefert.
pub const FALLBACK_PATHS: [&str; 5] = [
    r"C:\Program Files (x86)\Steam",
    r"D:\Steam",
    r"D:\SteamLibrary",
    r"E:\Steam",
    r"E:\SteamLibrary",
];

/// Ein laufender Steam-Download (eine Zeile der `downloads`-Liste).
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SteamDownload {
    pub app_id: String,
    pub name: String,
    /// Fortschritt in Prozent, auf eine Nachkommastelle gerundet.
    pub percent: f64,
    pub bytes_downloaded: u64,
    pub bytes_total: u64,
}

/// Gesamtstatus fuer `GET /steam` und `pc.steam()`.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SteamStatus {
    pub detected: bool,
    pub steam_path: String,
    pub millennium: bool,
    pub running: bool,
    pub downloading: bool,
    pub progress_percent: f64,
    pub bytes_downloaded: u64,
    pub bytes_total: u64,
    pub speed_bps: u64,
    pub app_id: String,
    pub app_name: String,
    pub downloads: Vec<SteamDownload>,
}

/// Die Felder einer `appmanifest_<appid>.acf`, die wir brauchen.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct AppManifest {
    pub app_id: String,
    pub name: String,
    pub state_flags: u64,
    pub bytes_downloaded: u64,
    pub bytes_to_download: u64,
    pub size_on_disk: u64,
    pub last_updated: u64,
}

impl AppManifest {
    /// Laeuft fuer diese App gerade ein Download/Update?
    pub fn downloading(&self) -> bool {
        is_downloading(self.state_flags)
    }

    /// Fortschritt in Prozent (0.0, wenn die Gesamtgroesse fehlt).
    pub fn percent(&self) -> f64 {
        percent(self.bytes_downloaded, self.bytes_to_download)
    }
}

/// Wertet die `StateFlags` eines `appmanifest_*.acf` aus.
///
/// Bekannte Bits (Valve): 1 = installiert, 2 = Update erforderlich,
/// 4 = vollstaendig installiert, 256 = Update laeuft, 512 = Update pausiert,
/// 1024 = Update gestartet. Ein Download laeuft also genau dann, wenn das
/// Update angestossen wurde: Bit 2 **und** Bit 1024 sind gesetzt. Das sind die
/// in der Checkliste genannten Werte 1026, 1042, 1046, 1050 und 1058. Bit 256
/// allein zaehlt ebenfalls als laufend, Bit 512 (pausiert) ausdruecklich nicht.
pub fn is_downloading(flags: u64) -> bool {
    const UPDATE_REQUIRED: u64 = 2;
    const UPDATE_RUNNING: u64 = 256;
    const UPDATE_STARTED: u64 = 1024;
    flags & UPDATE_RUNNING != 0 || flags & UPDATE_REQUIRED != 0 && flags & UPDATE_STARTED != 0
}

/// Fortschritt in Prozent, auf eine Nachkommastelle gerundet und begrenzt.
fn percent(downloaded: u64, total: u64) -> f64 {
    if total == 0 {
        return 0.0;
    }
    let value = downloaded as f64 * 100.0 / total as f64;
    ((value * 10.0).round() / 10.0).clamp(0.0, 100.0)
}

// ---------------- Steam-Pfad ----------------

/// Gefundener Steam-Pfad. Nur positive Treffer werden gemerkt, damit eine
/// spaetere Installation weiterhin erkannt wird.
static STEAM_PATH_CACHE: Mutex<Option<PathBuf>> = Mutex::new(None);

/// Steam-Pfad: erst die Registry, dann die ueblichen Installationsorte.
pub fn steam_path() -> Option<PathBuf> {
    if let Ok(guard) = STEAM_PATH_CACHE.lock() {
        if let Some(path) = guard.as_ref() {
            if path.is_dir() {
                return Some(path.clone());
            }
        }
    }
    let mut found = registry_steam_path();
    if found.is_none() {
        for candidate in FALLBACK_PATHS {
            let path = PathBuf::from(candidate);
            if path.join("steamapps").is_dir() || path.join("steam.exe").is_file() {
                found = Some(path);
                break;
            }
        }
    }
    if let Some(path) = &found {
        if let Ok(mut guard) = STEAM_PATH_CACHE.lock() {
            *guard = Some(path.clone());
        }
    }
    found
}

/// `reg query "HKCU\Software\Valve\Steam" /v SteamPath` (nur Lesen, immer echt).
fn registry_steam_path() -> Option<PathBuf> {
    let (code, out) = run(
        &[
            "reg",
            "query",
            r"HKCU\Software\Valve\Steam",
            "/v",
            "SteamPath",
        ],
        false,
        true,
    );
    if code != 0 {
        return None;
    }
    for line in out.lines() {
        if let Some(value) = steam_path_value(line) {
            // Steam schreibt den Pfad auch mit "/" (`c:/steam`) - vereinheitlichen.
            let path = PathBuf::from(value.replace('/', "\\"));
            if path.is_dir() {
                return Some(path);
            }
        }
    }
    None
}

/// Liest `    SteamPath    REG_SZ    D:\Steam` (auch `REG_EXPAND_SZ`).
fn steam_path_value(line: &str) -> Option<String> {
    let trimmed = line.trim();
    let lowered = trimmed.to_ascii_lowercase();
    if !lowered.starts_with("steampath") {
        return None;
    }
    let (position, kind_len) = if let Some(position) = lowered.find("reg_expand_sz") {
        (position, "reg_expand_sz".len())
    } else if let Some(position) = lowered.find("reg_sz") {
        (position, "reg_sz".len())
    } else {
        return None;
    };
    let value = trimmed
        .get(position + kind_len..)?
        .trim()
        .trim_matches('"');
    if value.is_empty() {
        None
    } else {
        Some(value.to_string())
    }
}

// ---------------- libraryfolders.vdf ----------------

/// Pfade aus `libraryfolders.vdf` (`"path"  "D:\\Spiele\\Steam"`).
pub fn library_paths(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.to_ascii_lowercase().starts_with("\"path\"") {
            continue;
        }
        let Some(value) = quoted_value(trimmed) else {
            continue;
        };
        // In VDF sind Backslashes verdoppelt; manche Eintraege nutzen "/".
        let path = value.replace("\\\\", "\\").replace('/', "\\");
        if path.trim().is_empty() || out.iter().any(|known| known.eq_ignore_ascii_case(&path)) {
            continue;
        }
        out.push(path);
    }
    out
}

/// Zweiter Wert einer Zeile wie `"key"    "value"`.
fn quoted_value(line: &str) -> Option<String> {
    let mut parts = line.split('"');
    parts.next()?; // vor dem ersten Anfuehrungszeichen
    parts.next()?; // Schluessel
    parts.next()?; // zwischen den Paaren
    parts.next().map(|value| value.to_string())
}

/// Alle Bibliotheksordner: Steam selbst plus die Eintraege der VDF.
pub fn libraries(steam: &Path) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = vec![steam.to_path_buf()];
    let vdf = steam.join("steamapps").join("libraryfolders.vdf");
    if let Ok(text) = std::fs::read_to_string(&vdf) {
        for path in library_paths(&text) {
            let candidate = PathBuf::from(&path);
            if !out.iter().any(|known| same_path(known, &candidate)) {
                out.push(candidate);
            }
        }
    }
    out
}

/// Vergleich von Pfaden unabhaengig von Schreibweise und Trenner.
fn same_path(a: &Path, b: &Path) -> bool {
    a.to_string_lossy()
        .replace('/', "\\")
        .eq_ignore_ascii_case(&b.to_string_lossy().replace('/', "\\"))
}


// ---------------- appmanifest_*.acf ----------------

/// `appmanifest_<appid>.acf` auswerten; `fallback_id` ist die Nummer aus dem
/// Dateinamen (im ACF selbst steht sie als `"appid"` noch einmal drin).
pub fn parse_acf(text: &str, fallback_id: &str) -> AppManifest {
    let mut manifest = AppManifest {
        app_id: fallback_id.trim().to_string(),
        ..Default::default()
    };
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.starts_with('"') {
            continue;
        }
        let Some((key, value)) = key_value(trimmed) else {
            continue;
        };
        match key.to_ascii_lowercase().as_str() {
            "appid" => manifest.app_id = value.trim().to_string(),
            "name" => manifest.name = value,
            "stateflags" => manifest.state_flags = value.trim().parse().unwrap_or(0),
            "bytesdownloaded" => manifest.bytes_downloaded = value.trim().parse().unwrap_or(0),
            "bytestodownload" => manifest.bytes_to_download = value.trim().parse().unwrap_or(0),
            "sizeondisk" => manifest.size_on_disk = value.trim().parse().unwrap_or(0),
            "lastupdated" => manifest.last_updated = value.trim().parse().unwrap_or(0),
            _ => {}
        }
    }
    if manifest.name.trim().is_empty() {
        manifest.name = format!("App {}", manifest.app_id);
    }
    manifest
}

/// `"key"    "value"` in Schluessel und Wert zerlegen.
fn key_value(line: &str) -> Option<(String, String)> {
    let mut parts = line.split('"');
    parts.next()?;
    let key = parts.next()?;
    parts.next()?;
    let value = parts.next()?;
    Some((key.to_string(), value.to_string()))
}

/// Liest alle `appmanifest_*.acf` einer Bibliothek.
fn manifests_in(library: &Path) -> Vec<AppManifest> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(library.join("steamapps")) else {
        return out;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let lowered = name.to_ascii_lowercase();
        if !lowered.starts_with("appmanifest_") || !lowered.ends_with(".acf") {
            continue;
        }
        let id = &name["appmanifest_".len()..name.len() - ".acf".len()];
        let Ok(text) = std::fs::read_to_string(&path) else {
            continue;
        };
        out.push(parse_acf(&text, id));
    }
    out.sort_by(|a, b| a.app_id.cmp(&b.app_id));
    out
}

/// Laufende Downloads in den angegebenen Bibliotheken (ohne Registry, testbar).
pub fn downloads_in(libraries: &[PathBuf]) -> Vec<SteamDownload> {
    let mut out: Vec<SteamDownload> = Vec::new();
    for library in libraries {
        for manifest in manifests_in(library) {
            if !manifest.downloading() {
                continue;
            }
            if out.iter().any(|known| known.app_id == manifest.app_id) {
                continue;
            }
            out.push(SteamDownload {
                app_id: manifest.app_id.clone(),
                name: manifest.name.clone(),
                percent: manifest.percent(),
                bytes_downloaded: manifest.bytes_downloaded,
                bytes_total: manifest.bytes_to_download,
            });
        }
    }
    out.sort_by_key(|item| item.name.to_ascii_lowercase());
    out
}

// ---------------- Millennium und laufender Steam ----------------

/// Millennium ist vorhanden, wenn `<Steam>\millennium` existiert oder
/// `<Steam>\plugins` mindestens eine `*.json` enthaelt. Nur Erkennung.
pub fn millennium_present(steam: &Path) -> bool {
    if steam.join("millennium").exists() {
        return true;
    }
    let Ok(entries) = std::fs::read_dir(steam.join("plugins")) else {
        return false;
    };
    entries.flatten().any(|entry| {
        let path = entry.path();
        path.is_file()
            && path
                .extension()
                .map(|ext| ext.eq_ignore_ascii_case("json"))
                .unwrap_or(false)
    })
}

/// Laeuft `steam.exe`? (Pruefung ueber `tasklist`, Vorbild `optimize::task_list`.)
pub fn steam_running() -> bool {
    crate::optimize::task_list()
        .iter()
        .any(|(name, _)| name.eq_ignore_ascii_case("steam.exe"))
}


// ---------------- Download-Geschwindigkeit ----------------

/// Letzte Messung fuer die Geschwindigkeit.
#[derive(Clone)]
struct SpeedSample {
    app_id: String,
    bytes: u64,
    bps: u64,
    at: Instant,
}

/// Geschwindigkeit aus zwei Messungen von `BytesDownloaded`. Der Abstand
/// zaehlt: liegt er unter ~700 ms, gilt der zuletzt gemessene Wert weiter;
/// der allererste Aufruf liefert 0 (es fehlt der Vergleichswert). Dadurch
/// blockiert keine Anfrage und `GET /steam` bleibt schnell.
static SPEED: Mutex<Option<SpeedSample>> = Mutex::new(None);

fn measure_speed(app_id: &str, bytes: u64) -> u64 {
    const MIN_GAP: Duration = Duration::from_millis(700);
    let Ok(mut guard) = SPEED.lock() else {
        return 0;
    };
    let now = Instant::now();
    let previous = guard
        .as_ref()
        .map(|sample| (sample.app_id.clone(), sample.bytes, sample.bps, sample.at));
    match previous {
        Some((known_id, known_bytes, known_bps, at)) if known_id == app_id => {
            let elapsed = now.saturating_duration_since(at);
            if elapsed >= MIN_GAP {
                let delta = bytes.saturating_sub(known_bytes);
                let measured = (delta as f64 / elapsed.as_secs_f64()) as u64;
                *guard = Some(SpeedSample {
                    app_id: app_id.to_string(),
                    bytes,
                    bps: measured,
                    at: now,
                });
                measured
            } else {
                known_bps
            }
        }
        _ => {
            *guard = Some(SpeedSample {
                app_id: app_id.to_string(),
                bytes,
                bps: 0,
                at: now,
            });
            0
        }
    }
}

/// Vergisst die letzte Messung (kein Download mehr aktiv).
fn reset_speed() {
    if let Ok(mut guard) = SPEED.lock() {
        *guard = None;
    }
}

// ---------------- Oeffentliche API ----------------

/// Laufende Steam-Downloads; leer, wenn Steam nicht gefunden wird.
pub fn downloads(_app: &App) -> Vec<SteamDownload> {
    let Some(steam) = steam_path() else {
        return Vec::new();
    };
    downloads_in(&libraries(&steam))
}

/// Gesamtstatus fuer `GET /steam` und die Plugin-Freigabe `steam`.
///
/// Ohne Steam-Installation: `detected = false` und sonst nur leere Werte –
/// kein Fehler und kein Panik.
pub fn status(_app: &App) -> SteamStatus {
    let Some(steam) = steam_path() else {
        reset_speed();
        return SteamStatus::default();
    };
    let list = downloads_in(&libraries(&steam));
    let millennium = millennium_present(&steam);
    let running = steam_running();
    // Kopfzeile des Status ist der erste Download (alphabetisch nach Name).
    let primary = list.first().cloned();
    let speed_bps = match &primary {
        Some(item) => measure_speed(&item.app_id, item.bytes_downloaded),
        None => {
            reset_speed();
            0
        }
    };
    SteamStatus {
        detected: true,
        steam_path: steam.display().to_string(),
        millennium,
        running,
        downloading: !list.is_empty(),
        progress_percent: primary.as_ref().map(|item| item.percent).unwrap_or(0.0),
        bytes_downloaded: primary
            .as_ref()
            .map(|item| item.bytes_downloaded)
            .unwrap_or(0),
        bytes_total: primary.as_ref().map(|item| item.bytes_total).unwrap_or(0),
        speed_bps,
        app_id: primary
            .as_ref()
            .map(|item| item.app_id.clone())
            .unwrap_or_default(),
        app_name: primary
            .as_ref()
            .map(|item| item.name.clone())
            .unwrap_or_default(),
        downloads: list,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Eigenes Test-Verzeichnis (der Prozess-PID macht den Namen eindeutig).
    fn test_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("pcagent-steam-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    const ACF_CS2: &str = r#""AppState"
{
	"appid"		"730"
	"name"		"Counter-Strike 2"
	"StateFlags"		"1042"
	"LastUpdated"		"1700000000"
	"SizeOnDisk"		"29000000"
	"BytesDownloaded"		"12345678"
	"BytesToDownload"		"29000000"
}
"#;

    #[test]
    fn stateflags_erkennen_laufende_downloads() {
        // Die in der Checkliste genannten Werte laufen alle.
        for flags in [1026u64, 1042, 1046, 1050, 1058] {
            assert!(is_downloading(flags), "StateFlags {flags} muss laufen");
        }
        // 256 = Update laeuft ebenfalls, 512 = pausiert nicht.
        assert!(is_downloading(256));
        // Installiert/nichts zu tun.
        for flags in [0u64, 1, 4, 6, 512, 514, 2048] {
            assert!(!is_downloading(flags), "StateFlags {flags} darf nicht laufen");
        }
    }

    #[test]
    fn acf_wird_gelesen() {
        let manifest = parse_acf(ACF_CS2, "730");
        assert_eq!(manifest.app_id, "730");
        assert_eq!(manifest.name, "Counter-Strike 2");
        assert_eq!(manifest.state_flags, 1042);
        assert_eq!(manifest.bytes_downloaded, 12345678);
        assert_eq!(manifest.bytes_to_download, 29000000);
        assert_eq!(manifest.size_on_disk, 29000000);
        assert_eq!(manifest.last_updated, 1700000000);
        assert!(manifest.downloading());
        assert_eq!(manifest.percent(), 42.6);
    }

    #[test]
    fn acf_ohne_namen_bekommt_ersatznamen() {
        let manifest = parse_acf("\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n}\n", "440");
        assert_eq!(manifest.name, "App 440");
        assert!(!manifest.downloading());
        assert_eq!(manifest.percent(), 0.0);
    }

    #[test]
    fn vdf_liest_bibliothekspfade() {
        let text = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"D:\\\\Spiele\\\\Steam\"\n\t}\n}";
        assert_eq!(
            library_paths(text),
            vec![
                r"C:\Program Files (x86)\Steam".to_string(),
                r"D:\Spiele\Steam".to_string()
            ]
        );
        assert!(library_paths("").is_empty());
    }

    #[test]
    fn registry_zeile_liefert_den_pfad() {
        assert_eq!(
            steam_path_value(r"    SteamPath    REG_SZ    D:\Steam"),
            Some(r"D:\Steam".to_string())
        );
        assert_eq!(
            steam_path_value(r"    SteamPath    REG_SZ    C:\Program Files (x86)\Steam"),
            Some(r"C:\Program Files (x86)\Steam".to_string())
        );
        assert_eq!(steam_path_value(r"    SteamExe    REG_SZ    D:\Steam\steam.exe"), None);
        assert_eq!(steam_path_value("HKEY_CURRENT_USER\\Software\\Valve\\Steam"), None);
    }


    #[test]
    fn downloads_finden_nur_laufende_apps() {
        let dir = test_dir("downloads");
        let steamapps = dir.join("steamapps");
        std::fs::create_dir_all(&steamapps).unwrap();
        // 730 laeuft (1042), 440 ist fertig installiert (4).
        std::fs::write(steamapps.join("appmanifest_730.acf"), ACF_CS2).unwrap();
        std::fs::write(
            steamapps.join("appmanifest_440.acf"),
            "\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"name\"\t\t\"Team Fortress 2\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"BytesDownloaded\"\t\t\"0\"\n\t\"BytesToDownload\"\t\t\"0\"\n}\n",
        )
        .unwrap();
        // Fremde Dateien werden ignoriert.
        std::fs::write(steamapps.join("libraryfolders.vdf"), "\"libraryfolders\"\n{\n}\n").unwrap();

        let list = downloads_in(&[dir.clone()]);
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].app_id, "730");
        assert_eq!(list[0].name, "Counter-Strike 2");
        assert_eq!(list[0].percent, 42.6);
        assert_eq!(list[0].bytes_total, 29000000);

        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn downloads_ohne_steam_sind_leer() {
        let dir = test_dir("leer");
        assert!(downloads_in(&[dir.clone()]).is_empty());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn millennium_wird_erkannt() {
        let basis = test_dir("millennium");
        let ohne = basis.join("ohne");
        std::fs::create_dir_all(&ohne).unwrap();
        assert!(!millennium_present(&ohne));

        let ordner = basis.join("ordner");
        std::fs::create_dir_all(ordner.join("millennium")).unwrap();
        assert!(millennium_present(&ordner));

        let json = basis.join("json");
        std::fs::create_dir_all(json.join("plugins")).unwrap();
        std::fs::write(json.join("plugins").join("millennium.json"), "{}").unwrap();
        assert!(millennium_present(&json));

        let nur_text = basis.join("text");
        std::fs::create_dir_all(nur_text.join("plugins")).unwrap();
        std::fs::write(nur_text.join("plugins").join("liesmich.txt"), "x").unwrap();
        assert!(!millennium_present(&nur_text));

        std::fs::remove_dir_all(&basis).unwrap();
    }

    #[test]
    fn prozent_rundet_auf_eine_nachkommastelle() {
        assert_eq!(percent(1, 3), 33.3);
        assert_eq!(percent(1, 1), 100.0);
        assert_eq!(percent(0, 0), 0.0);
        // Kein Ueberlauf ueber 100 % bei krummen Werten.
        assert_eq!(percent(11, 10), 100.0);
    }
}

