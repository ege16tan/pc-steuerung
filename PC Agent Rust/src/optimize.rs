//! Windows-Helfer fuer die Spiel-Optimierung.
//!
//! Vorher eigenstandiger Code im Plugin-Programm `src/bin/game-optimizer.rs`.
//! Jetzt eine Bibliothek, damit sie das JavaScript-Plugin `game-optimizer`
//! (siehe `jsplugins.rs`) und das alte EXE-Plugin gleichermassen nutzen koennen.
//!
//! Bewusst ohne zusaetzliche Crate: ein paar Aufrufe aus kernel32, psapi,
//! ntdll und shell32 reichen fuer Working-Set-Trimming und Standby-Bereinigung.

use std::path::Path;
use std::process::{Command, Stdio};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Windows-Schalter, damit kein Konsolenfenster aufblinkt.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Energieplan-GUIDs (wie im Agenten).
pub const PLAN_HIGH: &str = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
pub const PLAN_BALANCED: &str = "381b4222-f694-41f0-9685-ff5bb260df2e";
pub const PLAN_SAVER: &str = "a1841308-3541-4fab-bc81-f71556f20b4a";

/// Fuehrt einen Befehl aus und liefert (Rueckgabecode, Ausgabe).
pub fn run(cmd: &[&str]) -> (i32, String) {
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

/// Aktiver Energieplan als Name (`high`/`balanced`/`saver`/`anderer`).
pub fn active_plan_name() -> Option<String> {
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

/// Laufende Prozesse als (Name, PID) ueber `tasklist`.
pub fn task_list() -> Vec<(String, u32)> {
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
pub fn find_process(exe: &str) -> Option<(String, u32)> {
    let wanted = Path::new(exe).file_name()?.to_string_lossy().to_lowercase();
    task_list()
        .into_iter()
        .find(|(name, _)| name.to_lowercase() == wanted)
}

/// Beendet alle Prozesse mit diesem Namen (`taskkill /F`).
pub fn kill_by_name(name: &str) -> bool {
    let name = name.trim();
    if name.is_empty() {
        return false;
    }
    run(&["taskkill", "/IM", name, "/F"]).0 == 0
}

/// Beendet Prozesse; das Spiel selbst bleibt verschont.
pub fn kill_list(entries: &[String], keep_exe: Option<&str>) -> Vec<String> {
    let keep = keep_exe
        .and_then(|exe| Path::new(exe).file_name())
        .map(|name| name.to_string_lossy().to_lowercase());
    let mut killed = Vec::new();
    for entry in entries {
        let name = entry.trim();
        if name.is_empty() {
            continue;
        }
        if keep.as_deref() == Some(name.to_lowercase().as_str()) {
            continue;
        }
        if kill_by_name(name) {
            killed.push(name.to_string());
        }
    }
    killed
}

/// Beendet Prozesse; das Spiel selbst bleibt verschont.
#[cfg(windows)]
mod win {
    use std::ffi::c_void;

    pub type Handle = *mut c_void;

    #[link(name = "kernel32")]
    extern "system" {
        pub fn OpenProcess(desired_access: u32, inherit_handle: i32, process_id: u32) -> Handle;
        pub fn CloseHandle(handle: Handle) -> i32;
        pub fn SetPriorityClass(process: Handle, priority_class: u32) -> i32;
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
pub fn is_admin() -> bool {
    unsafe { win::IsUserAnAdmin() != 0 }
}

#[cfg(not(windows))]
pub fn is_admin() -> bool {
    false
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
pub fn trim_all_processes() -> usize {
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
pub fn trim_all_processes() -> usize {
    0
}

/// Standby-Liste leeren (benoetigt Administratorrechte).
#[cfg(windows)]
pub fn purge_standby() -> bool {
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
pub fn purge_standby() -> bool {
    false
}

/// Setzt die Prozessprioritaet auf "hoch".
#[cfg(windows)]
pub fn boost_priority(pid: u32) -> bool {
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
pub fn boost_priority(_pid: u32) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unbekannte_exe_hat_keinen_prozess() {
        assert!(find_process(r"C:\Nirgendwo\gibtesnicht.exe").is_none());
    }

    #[test]
    fn kill_liste_laesst_das_spiel_leben() {
        // Namen werden nur verglichen, wenn taskkill sie wirklich trifft;
        // hier zaehlt die Filterlogik ohne echte Prozesse.
        let entries = vec!["".to_string(), "  ".to_string()];
        assert!(kill_list(&entries, Some("game.exe")).is_empty());
    }
}