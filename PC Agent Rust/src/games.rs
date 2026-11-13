//! Spiele-Erkennung fuer die Spiel-Optimierung.
//!
//! Gesucht wird in Steam-Bibliotheken (inkl. `libraryfolders.vdf`), Epic
//! (`LauncherInstalled.dat`), GOG (Registry) und in eigenen Ordnern aus
//! `game-config.json`. Nur Lesen – es wird nichts installiert oder geaendert.

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::app::App;
use crate::commands::run;

/// Ein gefundenes Spiel (Anzeigename + EXE-Pfad).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Game {
    pub name: String,
    pub exe: String,
    #[serde(default)]
    pub source: String,
}

/// Einstellungen der Spiel-Optimierung (`game-config.json`).
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct GameConfig {
    /// Prozessnamen, die beim Optimieren geschlossen werden (z. B. "Discord.exe").
    pub kill_list: Vec<String>,
    /// Zusaetzliche Ordner, in denen nach Spielen gesucht wird.
    pub scan_paths: Vec<String>,
    /// Laufendes Spiel automatisch erkennen, statt in der App auszuwaehlen.
    pub auto: bool,
}

impl Default for GameConfig {
    fn default() -> Self {
        Self {
            kill_list: vec![
                "Discord.exe".to_string(),
                "Spotify.exe".to_string(),
                "OneDrive.exe".to_string(),
            ],
            scan_paths: Vec::new(),
            auto: false,
        }
    }
}

pub fn load_config(app: &App) -> GameConfig {
    std::fs::read_to_string(&app.game_config_file)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn save_config(app: &App, config: &GameConfig) -> Result<(), String> {
    let json = serde_json::to_string_pretty(config).map_err(|error| error.to_string())?;
    std::fs::write(&app.game_config_file, json).map_err(|error| error.to_string())
}

/// Legt `game-config.json` beim ersten Start an. Ohne Datei arbeitet das Plugin
/// bewusst mit leerer Kill-Liste – damit App und Plugin aber dieselbe Liste
/// zeigen, schreibt der Agent die Standardwerte einmal hin.
pub fn ensure_config(app: &App) {
    if !app.game_config_file.exists() {
        let _ = save_config(app, &GameConfig::default());
    }
}

#[derive(Serialize, Deserialize)]
struct GamesCache {
    #[serde(default)]
    scanned_at: String,
    #[serde(default)]
    games: Vec<Game>,
}

/// Zuletzt gescannte Liste und Zeitpunkt (None, wenn noch nie gescannt).
pub fn cached(app: &App) -> (Vec<Game>, Option<String>) {
    let Ok(text) = std::fs::read_to_string(&app.games_cache_file) else {
        return (Vec::new(), None);
    };
    match serde_json::from_str::<GamesCache>(&text) {
        Ok(cache) => (cache.games, Some(cache.scanned_at)),
        Err(_) => (Vec::new(), None),
    }
}

fn store_cache(app: &App, games: &[Game]) {
    let cache = GamesCache {
        scanned_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
        games: games.to_vec(),
    };
    if let Ok(json) = serde_json::to_string_pretty(&cache) {
        let _ = std::fs::write(&app.games_cache_file, json);
    }
}

/// Dateiname einer EXE (fuer den Vergleich mit laufenden Prozessen).
pub fn file_name(exe: &str) -> Option<String> {
    Path::new(exe)
        .file_name()
        .map(|name| name.to_string_lossy().to_string())
}

/// Namen aller laufenden Prozesse (klein geschrieben) ueber `tasklist`.
pub fn running_exes() -> Vec<String> {
    let (code, out) = run(&["tasklist", "/fo", "csv", "/nh"], false, true);
    if code != 0 {
        return Vec::new();
    }
    out.lines()
        .filter_map(|line| {
            let line = line.trim();
            if !line.starts_with('"') {
                return None;
            }
            let name = line.trim_start_matches('"').split('"').next()?;
            if name.is_empty() {
                None
            } else {
                Some(name.to_ascii_lowercase())
            }
        })
        .collect()
}

/// Erstes Spiel aus der Liste, dessen EXE gerade laeuft.
pub fn find_running(games: &[Game]) -> Option<Game> {
    let running = running_exes();
    if running.is_empty() {
        return None;
    }
    games
        .iter()
        .find(|game| {
            file_name(&game.exe)
                .map(|name| running.iter().any(|running| *running == name.to_ascii_lowercase()))
                .unwrap_or(false)
        })
        .cloned()
}

/// Vollstaendiger Scan; das Ergebnis wird zwischengespeichert.
pub fn scan_and_store(app: &App) -> Vec<Game> {
    let games = scan(app);
    store_cache(app, &games);
    games
}

/// Sucht Spiele in allen bekannten Quellen und entfernt Doppelte.
pub fn scan(app: &App) -> Vec<Game> {
    let config = load_config(app);
    let mut games: Vec<Game> = Vec::new();
    games.extend(steam_games());
    games.extend(epic_games());
    games.extend(gog_games());
    for path in &config.scan_paths {
        games.extend(folder_games(Path::new(path)));
    }
    let mut seen = HashSet::new();
    games.retain(|game| {
        let key = game.exe.replace('/', "\\").to_ascii_lowercase();
        seen.insert(key)
    });
    games.sort_by_key(|game| game.name.to_ascii_lowercase());
    games
}

// ---------------- Registry-Hilfen ----------------

/// `reg query` ausfuehren (nur Lesen, immer echt).
pub fn reg_query(key: &str, value: Option<&str>) -> Vec<String> {
    let mut args: Vec<&str> = vec!["reg", "query", key];
    match value {
        Some(value) => {
            args.push("/v");
            args.push(value);
        }
        None => args.push("/s"),
    }
    let (code, out) = run(&args, false, true);
    if code != 0 {
        return Vec::new();
    }
    out.lines().map(|line| line.to_string()).collect()
}

/// Liest `WERT    REG_SZ    Inhalt` aus einer `reg query`-Zeile.
fn reg_value(lines: &[String], name: &str) -> Option<String> {
    let wanted = name.to_ascii_lowercase();
    for line in lines {
        let trimmed = line.trim();
        let Some(head) = trimmed.get(..wanted.len()) else {
            continue;
        };
        if !head.eq_ignore_ascii_case(&wanted) {
            continue;
        }
        let rest = &trimmed[wanted.len()..];
        if !rest.chars().next().map(|c| c.is_whitespace()).unwrap_or(false) {
            continue;
        }
        let kind = rest.split_whitespace().next().unwrap_or_default();
        if !kind.eq_ignore_ascii_case("REG_SZ") && !kind.eq_ignore_ascii_case("REG_EXPAND_SZ") {
            continue;
        }
        let Some(position) = rest.find(kind) else {
            continue;
        };
        let value = rest[position + kind.len()..].trim();
        if !value.is_empty() {
            return Some(value.to_string());
        }
    }
    None
}

// ---------------- Steam ----------------

fn steam_games() -> Vec<Game> {
    let mut roots: Vec<PathBuf> = Vec::new();
    if let Some(path) = steam_install_path() {
        roots.push(PathBuf::from(path));
    }
    for base in ["ProgramFiles(x86)", "ProgramFiles"] {
        if let Ok(dir) = std::env::var(base) {
            let candidate = PathBuf::from(dir).join("Steam");
            if candidate.is_dir() {
                roots.push(candidate);
            }
        }
    }
    roots.sort();
    roots.dedup();

    let mut libraries: Vec<PathBuf> = Vec::new();
    for root in &roots {
        push_unique(&mut libraries, root.clone());
    }
    for root in &roots {
        let vdf = root.join("steamapps").join("libraryfolders.vdf");
        if let Ok(text) = std::fs::read_to_string(&vdf) {
            for path in vdf_paths(&text) {
                push_unique(&mut libraries, PathBuf::from(path));
            }
        }
    }

    let mut out = Vec::new();
    for library in libraries {
        let common = library.join("steamapps").join("common");
        let Ok(entries) = std::fs::read_dir(&common) else {
            continue;
        };
        for entry in entries.flatten() {
            if !entry.path().is_dir() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            if let Some(exe) = pick_best_exe(&entry.path()) {
                out.push(Game {
                    name,
                    exe: exe.display().to_string(),
                    source: "Steam".to_string(),
                });
            }
        }
    }
    out
}

/// Fuegt einen Bibliothekspfad ein, wenn er nicht schon (schreibweisenunabhaengig) drin ist.
fn push_unique(list: &mut Vec<PathBuf>, path: PathBuf) {
    let key = path.to_string_lossy().replace('/', "\\").to_ascii_lowercase();
    let exists = list
        .iter()
        .any(|known| known.to_string_lossy().replace('/', "\\").to_ascii_lowercase() == key);
    if !exists {
        list.push(path);
    }
}

fn steam_install_path() -> Option<String> {
    let lines = reg_query(r"HKCU\Software\Valve\Steam", Some("SteamPath"));
    if let Some(value) = reg_value(&lines, "SteamPath") {
        return Some(value);
    }
    let lines = reg_query(r"HKLM\SOFTWARE\WOW6432Node\Valve\Steam", Some("InstallPath"));
    reg_value(&lines, "InstallPath")
}

/// Liest `"path"		"D:\\Spiele\\Steam"`-Zeilen aus `libraryfolders.vdf`.
fn vdf_paths(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.to_ascii_lowercase().starts_with("\"path\"") {
            continue;
        }
        if let Some(value) = quoted_value(trimmed) {
            // In VDF sind Backslashes verdoppelt; manche Eintraege nutzen "/".
            out.push(value.replace("\\\\", "\\").replace('/', "\\"));
        }
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

// ---------------- Epic ----------------

#[derive(Deserialize)]
struct LauncherInstalled {
    #[serde(rename = "InstallationList", default)]
    installation_list: Vec<EpicEntry>,
}

#[derive(Deserialize)]
struct EpicEntry {
    #[serde(rename = "InstallLocation", default)]
    install_location: String,
    #[serde(rename = "AppName", default)]
    app_name: String,
}

fn epic_games() -> Vec<Game> {
    let mut out = Vec::new();
    let Some(program_data) = std::env::var_os("ProgramData").map(PathBuf::from) else {
        return out;
    };
    let path = program_data
        .join("Epic")
        .join("UnrealEngineLauncher")
        .join("LauncherInstalled.dat");
    let Ok(text) = std::fs::read_to_string(&path) else {
        return out;
    };
    let Ok(data) = serde_json::from_str::<LauncherInstalled>(&text) else {
        return out;
    };
    for entry in data.installation_list {
        if entry.install_location.trim().is_empty() {
            continue;
        }
        let dir = PathBuf::from(&entry.install_location);
        let Some(exe) = pick_best_exe(&dir) else {
            continue;
        };
        let name = if entry.app_name.trim().is_empty() {
            dir.file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_else(|| "Epic-Spiel".to_string())
        } else {
            entry.app_name
        };
        out.push(Game {
            name,
            exe: exe.display().to_string(),
            source: "Epic".to_string(),
        });
    }
    out
}

// ---------------- GOG ----------------

fn gog_games() -> Vec<Game> {
    let mut out = Vec::new();
    for root in [
        r"HKLM\SOFTWARE\WOW6432Node\GOG.com\Games",
        r"HKLM\SOFTWARE\GOG.com\Games",
    ] {
        let lines = reg_query(root, None);
        if lines.is_empty() {
            continue;
        }
        let mut name: Option<String> = None;
        let mut path: Option<String> = None;
        for line in lines.iter() {
            if line.trim_start().starts_with("HKEY_") {
                flush_gog(&mut out, &mut name, &mut path);
                continue;
            }
            let single = std::slice::from_ref(line);
            if let Some(value) = reg_value(single, "GAMENAME") {
                name = Some(value);
            }
            if let Some(value) = reg_value(single, "PATH") {
                path = Some(value);
            }
        }
        flush_gog(&mut out, &mut name, &mut path);
    }
    out
}

fn flush_gog(out: &mut Vec<Game>, name: &mut Option<String>, path: &mut Option<String>) {
    if let Some(dir) = path.take() {
        if let Some(exe) = pick_best_exe(Path::new(&dir)) {
            let label = name.take().unwrap_or_else(|| {
                Path::new(&dir)
                    .file_name()
                    .map(|name| name.to_string_lossy().to_string())
                    .unwrap_or_else(|| "GOG-Spiel".to_string())
            });
            out.push(Game {
                name: label,
                exe: exe.display().to_string(),
                source: "GOG".to_string(),
            });
        }
    }
    *name = None;
    *path = None;
}

// ---------------- Eigene Ordner ----------------

/// Ein Spiel je Unterordner (eine Ebene tief).
fn folder_games(root: &Path) -> Vec<Game> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(root) else {
        return out;
    };
    for entry in entries.flatten() {
        if !entry.path().is_dir() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        if let Some(exe) = pick_best_exe(&entry.path()) {
            out.push(Game {
                name,
                exe: exe.display().to_string(),
                source: "Ordner".to_string(),
            });
        }
    }
    out
}

/// Waehlt die wahrscheinlichste Spiel-EXE in einem Ordner: Name passt zum
/// Ordnernamen, sonst die groesste Datei; Setup-/Redist-Dateien fliegen raus.
pub fn pick_best_exe(dir: &Path) -> Option<PathBuf> {
    let mut candidates: Vec<(u64, PathBuf)> = Vec::new();
    collect_exes(dir, 3, &mut candidates);
    if candidates.is_empty() {
        return None;
    }
    let folder = dir
        .file_name()
        .map(|name| normalize(&name.to_string_lossy()))
        .unwrap_or_default();
    candidates.sort_by_key(|(size, path)| {
        let stem = path
            .file_stem()
            .map(|name| normalize(&name.to_string_lossy()))
            .unwrap_or_default();
        let mut score: i64 = 0;
        if !folder.is_empty() && stem == folder {
            score += 1_000_000;
        } else if !folder.is_empty() && !stem.is_empty() && folder.contains(&stem) {
            score += 500_000;
        }
        if stem.contains("launcher") {
            score -= 400_000;
        }
        score + (*size / 1024) as i64
    });
    candidates.pop().map(|(_, path)| path)
}

fn collect_exes(dir: &Path, depth: usize, out: &mut Vec<(u64, PathBuf)>) {
    if depth == 0 || out.len() >= 400 {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        if out.len() >= 400 {
            return;
        }
        let Ok(file_type) = entry.file_type() else {
            continue;
        };
        let path = entry.path();
        if file_type.is_dir() {
            let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
            if is_noise_dir(&name) {
                continue;
            }
            collect_exes(&path, depth - 1, out);
        } else if file_type.is_file() {
            let is_exe = path
                .extension()
                .and_then(|ext| ext.to_str())
                .map(|ext| ext.eq_ignore_ascii_case("exe"))
                .unwrap_or(false);
            if !is_exe {
                continue;
            }
            let name = path
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_default();
            if is_noise(&name) {
                continue;
            }
            let size = entry.metadata().map(|meta| meta.len()).unwrap_or(0);
            out.push((size, path));
        }
    }
}

/// Installer, Redistributables und Crash-Handler sind keine Spiele.
fn is_noise(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    const NOISE: [&str; 24] = [
        "unins",
        "vcredist",
        "vc_redist",
        "dxsetup",
        "dxwebsetup",
        "dotnet",
        "crashpad",
        "crashreport",
        "crashhandler",
        "eac_launcher",
        "touchup",
        "setup",
        "install",
        "prereq",
        "cleanup",
        "physx",
        "pbsvc",
        "ue4prereq",
        "systemsoftware",
        "battleye",
        "anticheat",
        "settings",
        "backup",
        "config",
    ];
    NOISE.iter().any(|needle| lower.contains(needle))
}

/// Ordner, die nur Installer/Redistributables enthalten (nicht durchsuchen).
fn is_noise_dir(name: &str) -> bool {
    const NOISE_DIRS: [&str; 10] = [
        "redist",
        "_commonredist",
        "directx",
        "support",
        "installers",
        "prerequisites",
        "easyanticheat",
        "punkbuster",
        "dotnet",
        "unitycrashhandler",
    ];
    NOISE_DIRS.iter().any(|needle| name.contains(needle))
}

/// Nur Kleinbuchstaben und Ziffern (fuer Namensvergleiche).
fn normalize(text: &str) -> String {
    text.chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vdf_liest_bibliothekspfade() {
        let text = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"D:\\\\Spiele\\\\Steam\"\n\t}\n}";
        let paths = vdf_paths(text);
        assert_eq!(
            paths,
            vec![
                r"C:\Program Files (x86)\Steam".to_string(),
                r"D:\Spiele\Steam".to_string()
            ]
        );
    }

    #[test]
    fn reg_wert_wird_gelesen() {
        let lines = vec![
            "".to_string(),
            r"HKEY_CURRENT_USER\Software\Valve\Steam".to_string(),
            r"    SteamPath    REG_SZ    D:\Spiele\Steam".to_string(),
        ];
        assert_eq!(
            reg_value(&lines, "SteamPath"),
            Some(r"D:\Spiele\Steam".to_string())
        );
        assert_eq!(reg_value(&lines, "Anderer"), None);
    }

    #[test]
    fn installer_und_redist_sind_keine_spiele() {
        assert!(is_noise("unins000.exe"));
        assert!(is_noise("vc_redist.x64.exe"));
        assert!(is_noise("UnityCrashHandler64.exe"));
        assert!(!is_noise("Cyberpunk2077.exe"));
    }

    #[test]
    fn redist_ordner_werden_uebersprungen() {
        let dir = std::env::temp_dir().join(format!("pcagent-redist-{}", std::process::id()));
        let game_dir = dir.join("Testspiel");
        std::fs::create_dir_all(game_dir.join("_CommonRedist").join("DirectX")).unwrap();
        std::fs::write(game_dir.join("_CommonRedist").join("DirectX").join("DXSETUP.exe"), vec![0u8; 65536])
            .unwrap();
        std::fs::write(game_dir.join("Testspiel.exe"), vec![0u8; 1024]).unwrap();

        let best = pick_best_exe(&game_dir).unwrap();
        assert_eq!(
            best.file_name().unwrap().to_string_lossy(),
            "Testspiel.exe",
            "Redist-Ordner darf nicht als Spiel erkannt werden"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn namensvergleich_ignoriert_schreibweise_und_zeichen() {
        assert_eq!(normalize("Cyberpunk 2077"), "cyberpunk2077");
        assert_eq!(normalize("Dishonored2"), "dishonored2");
    }

    #[test]
    fn beste_exe_passt_zum_ordnernamen() {
        let dir = std::env::temp_dir().join(format!("pcagent-games-{}", std::process::id()));
        let game_dir = dir.join("Testspiel");
        std::fs::create_dir_all(game_dir.join("bin")).unwrap();
        std::fs::write(game_dir.join("bin").join("unins000.exe"), vec![0u8; 4096]).unwrap();
        std::fs::write(game_dir.join("Testspiel.exe"), vec![0u8; 1024]).unwrap();
        std::fs::write(game_dir.join("Engine.exe"), vec![0u8; 8192]).unwrap();

        let best = pick_best_exe(&game_dir).unwrap();
        assert_eq!(
            best.file_name().unwrap().to_string_lossy(),
            "Testspiel.exe",
            "Name passend zum Ordner gewinnt gegen groessere Fremd-EXE"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn find_running_ohne_treffer_ist_leer() {
        let games = vec![Game {
            name: "Gibt es nicht".to_string(),
            exe: r"C:\Nirgendwo\gibtesnichtspiel.exe".to_string(),
            source: "Test".to_string(),
        }];
        assert!(find_running(&games).is_none());
    }
}
