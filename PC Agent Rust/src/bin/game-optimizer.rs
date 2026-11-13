//! Game-Optimizer-Plugin.
//!
//! Wird vom Agenten **auf Knopfdruck** gestartet (`/plugins/game-optimizer/start`)
//! und laeuft nur so lange, wie optimiert wird. Ablauf:
//!
//! 1. Energieplan auf Hoechstleistung (vorheriger Plan wird gemerkt)
//! 2. Hintergrund-Apps aus der Kill-Liste schliessen
//! 3. Arbeitsspeicher bereinigen (Working Sets + Standby-Liste)
//! 4. Prioritaet des Spiels auf "hoch" setzen
//! 5. Auf `stop` warten (stdin) und den Energieplan zuruecksetzen
//!
//! Die Speicherbereinigung orientiert sich an Mem Reduct (Henry++, GPL-3.0).
//! Es wurde **kein Code uebernommen** – nachgebaut mit denselben Windows-APIs
//! (`EmptyWorkingSet`, `NtSetSystemInformation`/`SystemMemoryListInformation`).

use std::io::BufRead;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use serde::{Deserialize, Serialize};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Windows-Schalter, damit kein Konsolenfenster aufblinkt.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Energieplan-GUIDs (wie im Agenten).
const PLAN_HIGH: &str = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
const PLAN_BALANCED: &str = "381b4222-f694-41f0-9685-ff5bb260df2e";
const PLAN_SAVER: &str = "a1841308-3541-4fab-bc81-f71556f20b4a";

#[derive(Default)]
struct Options {
    data_dir: Option<PathBuf>,
    game: Option<String>,
    auto: bool,
    simulate: bool,
    once: bool,
}

fn parse_options() -> Options {
    let mut options = Options::default();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--data" => options.data_dir = args.next().map(PathBuf::from),
            "--game" => options.game = args.next(),
            "--auto" => options.auto = true,
            "--simulate" => options.simulate = true,
            "--once" => options.once = true,
            "--help" | "-h" => {
                println!("game-optimizer [--data <ordner>] [--game <exe>] [--auto] [--simulate] [--once]");
                std::process::exit(0);
            }
            _ => {}
        }
    }
    options
}

fn default_data_dir() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .map(|local| local.join("PC Steuerung"))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// Einstellungen aus `game-config.json` (gleiche Datei wie im Agenten).
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
struct GameConfig {
    kill_list: Vec<String>,
    scan_paths: Vec<String>,
    auto: bool,
}

impl Default for GameConfig {
    fn default() -> Self {
        // Bewusst leer: ohne Konfiguration wird nichts beendet.
        Self {
            kill_list: Vec::new(),
            scan_paths: Vec::new(),
            auto: false,
        }
    }
}

fn load_config(data_dir: &Path) -> GameConfig {
    std::fs::read_to_string(data_dir.join("game-config.json"))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

/// Ergebnis der Optimierung (wird als `game-optimizer.status.json` abgelegt).
#[derive(Debug, Default, Serialize)]
struct Report {
    simulate: bool,
    game: Option<String>,
    started_at: String,
    admin: bool,
    power_plan_before: Option<String>,
    power_plan: Option<String>,
    killed: Vec<String>,
    memory_before_mb: u64,
    memory_after_mb: u64,
    steps: Vec<String>,
    state: String,
}

fn write_status(data_dir: &Path, report: &Report) {
    if let Ok(json) = serde_json::to_string_pretty(report) {
        let _ = std::fs::write(data_dir.join("game-optimizer.status.json"), json);
    }
}

/// Normale Ausgabezeile (nur im Plugin-Log sichtbar).
fn say(message: &str) {
    println!("{message}");
}

/// Ergebniszeile – landet zusaetzlich im Agenten-Journal.
fn result(message: &str) {
    println!("> {message}");
}

fn main() {
    let options = parse_options();
    let data_dir = options.data_dir.clone().unwrap_or_else(default_data_dir);
    let _ = std::fs::create_dir_all(&data_dir);
    let config = load_config(&data_dir);

    let mut report = Report {
        simulate: options.simulate,
        game: options.game.clone(),
        started_at: chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string(),
        admin: is_admin(),
        ..Default::default()
    };

    result(&format!(
        "Game Optimization startet{}",
        if options.simulate {
            " (SIMULATION - nichts wird ausgefuehrt)"
        } else {
            ""
        }
    ));
    if let Some(game) = options.game.as_deref() {
        result(&format!("Spiel: {game}"));
    } else if options.auto {
        result("Modus: automatisch (kein Spiel uebergeben)");
    }

    // 1) Energieplan
    report.power_plan_before = active_plan_name();
    if options.simulate {
        report.power_plan = Some("high".to_string());
        result("Energieplan -> Hoechstleistung (simuliert)");
    } else {
        let (code, out) = run(&["powercfg", "/setactive", PLAN_HIGH]);
        if code == 0 {
            report.power_plan = Some("high".to_string());
            result("Energieplan -> Hoechstleistung");
        } else {
            result(&format!("Energieplan fehlgeschlagen: {out}"));
        }
    }

    // 2) Hintergrund-Apps schliessen (niemals das Spiel selbst)
    let game_name = options
        .game
        .as_deref()
        .and_then(|path| Path::new(path).file_name())
        .map(|name| name.to_string_lossy().to_lowercase());
    for entry in &config.kill_list {
        let name = entry.trim();
        if name.is_empty() {
            continue;
        }
        if let Some(game) = game_name.as_deref() {
            if game == name.to_lowercase() {
                continue;
            }
        }
        if options.simulate {
            report.killed.push(format!("{name} (simuliert)"));
            continue;
        }
        let (code, out) = run(&["taskkill", "/IM", name, "/F"]);
        if code == 0 {
            report.killed.push(name.to_string());
        } else if !out.is_empty() {
            say(&format!("{name}: {out}"));
        }
    }
    if !report.killed.is_empty() {
        result(&format!(
            "Hintergrund-Apps geschlossen: {}",
            report.killed.join(", ")
        ));
    }

    // 3) Arbeitsspeicher bereinigen
    report.memory_before_mb = available_mb();
    if options.simulate {
        result("Arbeitsspeicher wuerde bereinigt (simuliert)");
    } else {
        let trimmed = trim_all_processes();
        let purged = report.admin && purge_standby();
        report.memory_after_mb = available_mb();
        if !report.admin {
            say("Hinweis: ohne Administratorrechte bleibt die Standby-Liste unberuehrt.");
        }
        result(&format!(
            "Arbeitsspeicher frei: {} MB -> {} MB ({} Prozesse bereinigt, Standby {})",
            report.memory_before_mb,
            report.memory_after_mb,
            trimmed,
            if purged { "geleert" } else { "unveraendert" }
        ));
    }

    // 4) Prioritaet des Spiels anheben
    if let Some(game) = options.game.as_deref() {
        if options.simulate {
            result("Spiel-Prioritaet -> hoch (simuliert)");
        } else if let Some((name, pid)) = find_process(game) {
            if boost_priority(pid) {
                result(&format!("{name} (pid {pid}) -> hohe Prioritaet"));
            } else {
                result(&format!("{name}: Prioritaet konnte nicht gesetzt werden"));
            }
        } else {
            result("Spiel laeuft nicht - Prioritaet uebersprungen");
        }
    }

    report.state = "optimiert".to_string();
    write_status(&data_dir, &report);
    result(&format!(
        "Optimierung aktiv (Modus {})",
        if options.simulate { "SIMULATION" } else { "ECHT" }
    ));

    if options.once {
        return;
    }

    wait_for_stop();

    // Zurueck zum vorherigen Energieplan (Apps werden bewusst nicht neu gestartet).
    if !options.simulate {
        if let Some(previous) = report.power_plan_before.clone() {
            if let Some(guid) = plan_guid(&previous) {
                let (code, _) = run(&["powercfg", "/setactive", guid]);
                if code == 0 {
                    result(&format!("Energieplan zurueck auf {previous}"));
                }
            }
        }
    }
    report.state = "beendet".to_string();
    write_status(&data_dir, &report);
    result("Optimierung beendet");
}

/// Wartet auf `stop` auf der Standardeingabe. Schliesst der Agent den Kanal
/// (z. B. weil er beendet wurde), endet die Schleife ebenfalls – und der
/// Energieplan wird trotzdem zurueckgesetzt.
fn wait_for_stop() {
    let stdin = std::io::stdin();
    for line in stdin.lock().lines().map_while(Result::ok) {
        if line.trim().eq_ignore_ascii_case("stop") {
            break;
        }
    }
}

fn plan_guid(name: &str) -> Option<&'static str> {
    match name {
        "high" => Some(PLAN_HIGH),
        "balanced" => Some(PLAN_BALANCED),
        "saver" => Some(PLAN_SAVER),
        _ => None,
    }
}

/// Aktiver Energieplan als Name (`high`/`balanced`/`saver`/`anderer`).
fn active_plan_name() -> Option<String> {
    let (code, out) = run(&["powercfg", "/getactivescheme"]);
    if code != 0 {
        return None;
    }
    let lowered = out.to_lowercase();
    for (name, guid) in [
        ("high", PLAN_HIGH),
        ("balanced", PLAN_BALANCED),
        ("saver", PLAN_SAVER),
    ] {
        if lowered.contains(guid) {
            return Some(name.to_string());
        }
    }
    Some("anderer".to_string())
}

/// Fuehrt einen Befehl aus und liefert (Rueckgabecode, Ausgabe).
fn run(cmd: &[&str]) -> (i32, String) {
    if cmd.is_empty() {
        return (-1, "leerer Befehl".to_string());
    }
    let mut command = Command::new(cmd[0]);
    command.args(&cmd[1..]);
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
    match command.output() {
        Ok(output) => {
            let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
            text.push_str(&String::from_utf8_lossy(&output.stderr));
            (output.status.code().unwrap_or(-1), text.trim().to_string())
        }
        Err(error) => (-1, format!("Befehl nicht gefunden: {error}")),
    }
}

/// Laufende Prozesse als (Name, PID) ueber `tasklist`.
fn task_list() -> Vec<(String, u32)> {
    let (code, out) = run(&["tasklist", "/fo", "csv", "/nh"]);
    if code != 0 {
        return Vec::new();
    }
    out.lines()
        .filter_map(|line| {
            let line = line.trim();
            if !line.starts_with('"') {
                return None;
            }
            let mut parts = line.split('"');
            parts.next()?;
            let name = parts.next()?.to_string();
            parts.next()?;
            let pid: u32 = parts.next()?.trim().parse().ok()?;
            if name.is_empty() {
                None
            } else {
                Some((name, pid))
            }
        })
        .collect()
}

/// Findet den Prozess zu einer EXE (ueber den Dateinamen).
fn find_process(exe: &str) -> Option<(String, u32)> {
    let wanted = Path::new(exe).file_name()?.to_string_lossy().to_lowercase();
    task_list()
        .into_iter()
        .find(|(name, _)| name.to_lowercase() == wanted)
}

// ---------------- Windows-API (Arbeitsspeicher) ----------------
//
// Absichtlich ohne zusaetzliche Crate: ein paar Aufrufe aus kernel32, psapi,
// ntdll und shell32 reichen fuer Working-Set-Trimming und Standby-Bereinigung.

#[cfg(windows)]
mod win {
    use std::ffi::c_void;

    pub type Handle = *mut c_void;

    #[repr(C)]
    #[derive(Default)]
    pub struct MemoryStatusEx {
        pub length: u32,
        pub memory_load: u32,
        pub total_phys: u64,
        pub avail_phys: u64,
        pub total_page_file: u64,
        pub avail_page_file: u64,
        pub total_virtual: u64,
        pub avail_virtual: u64,
        pub avail_extended_virtual: u64,
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn OpenProcess(desired_access: u32, inherit_handle: i32, process_id: u32) -> Handle;
        pub fn CloseHandle(handle: Handle) -> i32;
        pub fn SetPriorityClass(process: Handle, priority_class: u32) -> i32;
        pub fn GlobalMemoryStatusEx(buffer: *mut MemoryStatusEx) -> i32;
    }

    #[link(name = "psapi")]
    extern "system" {
        pub fn EmptyWorkingSet(process: Handle) -> i32;
    }

    #[link(name = "ntdll")]
    extern "system" {
        pub fn NtSetSystemInformation(info_class: u32, info: *mut c_void, length: u32) -> i32;
    }

    #[link(name = "shell32")]
    extern "system" {
        pub fn IsUserAnAdmin() -> i32;
    }

    pub const PROCESS_QUERY_INFORMATION: u32 = 0x0400;
    pub const PROCESS_SET_QUOTA: u32 = 0x0100;
    pub const PROCESS_SET_INFORMATION: u32 = 0x0200;
    pub const HIGH_PRIORITY_CLASS: u32 = 0x0080;
    /// `SYSTEM_INFORMATION_CLASS::SystemMemoryListInformation`.
    pub const SYSTEM_MEMORY_LIST_INFORMATION: u32 = 0x50;
    /// `SYSTEM_MEMORY_LIST_COMMAND::MemoryPurgeStandbyList`.
    pub const MEMORY_PURGE_STANDBY_LIST: u32 = 4;
}

#[cfg(windows)]
fn is_admin() -> bool {
    unsafe { win::IsUserAnAdmin() != 0 }
}

#[cfg(not(windows))]
fn is_admin() -> bool {
    false
}

/// Frei verfuegbarer physischer Speicher in MB.
#[cfg(windows)]
fn available_mb() -> u64 {
    let mut status = win::MemoryStatusEx {
        length: std::mem::size_of::<win::MemoryStatusEx>() as u32,
        ..Default::default()
    };
    let ok = unsafe { win::GlobalMemoryStatusEx(&mut status) };
    if ok == 0 {
        0
    } else {
        status.avail_phys / (1024 * 1024)
    }
}

#[cfg(not(windows))]
fn available_mb() -> u64 {
    0
}

#[cfg(windows)]
fn trim_process(pid: u32) -> bool {
    let access = win::PROCESS_QUERY_INFORMATION | win::PROCESS_SET_QUOTA;
    let handle = unsafe { win::OpenProcess(access, 0, pid) };
    if handle.is_null() {
        return false;
    }
    let ok = unsafe { win::EmptyWorkingSet(handle) } != 0;
    unsafe {
        win::CloseHandle(handle);
    }
    ok
}

/// Working Sets aller zugaenglichen Prozesse trimmen; Rueckgabe: Anzahl Erfolge.
#[cfg(windows)]
fn trim_all_processes() -> usize {
    let mut count = 0;
    for (_, pid) in task_list() {
        if pid <= 4 {
            // Idle (0) und System (4) bleiben unberuehrt.
            continue;
        }
        if trim_process(pid) {
            count += 1;
        }
    }
    count
}

#[cfg(not(windows))]
fn trim_all_processes() -> usize {
    0
}

/// Standby-Liste leeren (benoetigt Administratorrechte).
#[cfg(windows)]
fn purge_standby() -> bool {
    let mut command: u32 = win::MEMORY_PURGE_STANDBY_LIST;
    let status = unsafe {
        win::NtSetSystemInformation(
            win::SYSTEM_MEMORY_LIST_INFORMATION,
            &mut command as *mut u32 as *mut std::ffi::c_void,
            std::mem::size_of::<u32>() as u32,
        )
    };
    status >= 0
}

#[cfg(not(windows))]
fn purge_standby() -> bool {
    false
}

#[cfg(windows)]
fn boost_priority(pid: u32) -> bool {
    let handle = unsafe { win::OpenProcess(win::PROCESS_SET_INFORMATION, 0, pid) };
    if handle.is_null() {
        return false;
    }
    let ok = unsafe { win::SetPriorityClass(handle, win::HIGH_PRIORITY_CLASS) } != 0;
    unsafe {
        win::CloseHandle(handle);
    }
    ok
}

#[cfg(not(windows))]
fn boost_priority(_pid: u32) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_guid_kennt_die_drei_bekannten_plaene() {
        assert_eq!(plan_guid("high"), Some(PLAN_HIGH));
        assert_eq!(plan_guid("balanced"), Some(PLAN_BALANCED));
        assert_eq!(plan_guid("saver"), Some(PLAN_SAVER));
        assert_eq!(plan_guid("anderer"), None);
    }

    #[test]
    fn config_ohne_datei_beendet_nichts() {
        let dir = std::env::temp_dir().join(format!("pcagent-go-cfg-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let config = load_config(&dir);
        assert!(config.kill_list.is_empty(), "ohne Datei darf nichts beendet werden");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn config_liest_kill_liste() {
        let dir = std::env::temp_dir().join(format!("pcagent-go-cfg2-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("game-config.json"),
            r#"{"kill_list":["Discord.exe"],"scan_paths":[],"auto":true}"#,
        )
        .unwrap();
        let config = load_config(&dir);
        assert_eq!(config.kill_list, vec!["Discord.exe".to_string()]);
        assert!(config.auto);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn unbekannte_exe_hat_keinen_prozess() {
        assert!(find_process(r"C:\Nirgendwo\gibtesnicht.exe").is_none());
    }
}
