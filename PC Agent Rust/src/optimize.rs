//! Windows-Helfer fuer die Spiel-Optimierung.
//!
//! Vorher eigenstandiger Code im Plugin-Programm `src/bin/game-optimizer.rs`.
//! Jetzt eine Bibliothek, damit sie das JavaScript-Plugin `game-optimizer`
//! (siehe `jsplugins.rs`) und das alte EXE-Plugin gleichermassen nutzen koennen.
//!
//! Bewusst ohne zusaetzliche Crate: ein paar Aufrufe aus kernel32, psapi,
//! ntdll und shell32 reichen fuer Working-Set-Trimming und Standby-Bereinigung.
//!
//! Memory-Clean-Operationen basieren auf Mem Reduct (Henry++, GPL-3.0):
//! - Working Set trimmen (EmptyWorkingSet)
//! - System-File-Cache leeren (SystemFileCacheInformationEx)
//! - Modified Page List flushen (MemoryFlushModifiedList)
//! - Standby List leeren (MemoryPurgeStandbyList) - braucht Admin
//! - Standby Priority-0 List leeren (MemoryPurgeLowPriorityStandbyList) - braucht Admin
//! - Registry Cache leeren (SystemRegistryReconciliationInformation) - Win 8.1+
//! - Memory Combine (SystemCombinePhysicalMemoryInformation) - Win 10+
//! - Volume Cache flushen

use std::path::Path;

/// Energieplan-GUIDs (wie im Agenten).
pub const PLAN_HIGH: &str = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c";
pub const PLAN_BALANCED: &str = "381b4222-f694-41f0-9685-ff5bb260df2e";
pub const PLAN_SAVER: &str = "a1841308-3541-4fab-bc81-f71556f20b4a";

/// Fuehrt einen Befehl aus (delegiert an `crate::commands::run` mit `real = true`,
/// damit der Befehl wirklich ausgefuehrt wird).
pub fn run(cmd: &[&str]) -> (i32, String) {
    crate::commands::run(cmd, false, true)
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

/// Blockliste: kritische Systemprozesse dürfen niemals über die Kill-Liste
/// beendet werden (Fußschuss-Schutz).
const KILL_BLOCKLIST: &[&str] = &[
    "explorer.exe",
    "winlogon.exe",
    "csrss.exe",
    "smss.exe",
    "wininit.exe",
    "services.exe",
    "lsass.exe",
];

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
        let lower = name.to_lowercase();
        if KILL_BLOCKLIST.iter().any(|blocked| *blocked == lower) {
            continue;
        }
        if keep.as_deref() == Some(lower.as_str()) {
            continue;
        }
        if kill_by_name(name) {
            killed.push(name.to_string());
        }
    }
    killed
}

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
    /// `SYSTEM_MEMORY_LIST_COMMAND::MemoryFlushModifiedList`.
    pub const MEMORY_FLUSH_MODIFIED_LIST: u32 = 3;
    /// `SYSTEM_MEMORY_LIST_COMMAND::MemoryPurgeStandbyList`.
    pub const MEMORY_PURGE_STANDBY_LIST: u32 = 4;
    /// `SYSTEM_MEMORY_LIST_COMMAND::MemoryPurgeLowPriorityStandbyList`.
    pub const MEMORY_PURGE_LOW_PRIORITY_STANDBY_LIST: u32 = 5;
    /// `SYSTEM_INFORMATION_CLASS::SystemFileCacheInformationEx`.
    pub const SYSTEM_FILE_CACHE_INFORMATION_EX: u32 = 0x51;
    /// `SYSTEM_INFORMATION_CLASS::SystemRegistryReconciliationInformation`.
    pub const SYSTEM_REGISTRY_RECONCILIATION_INFORMATION: u32 = 0x5C;
    /// `SYSTEM_INFORMATION_CLASS::SystemCombinePhysicalMemoryInformation`.
    pub const SYSTEM_COMBINE_PHYSICAL_MEMORY_INFORMATION: u32 = 0x5F;
}

#[cfg(windows)]
#[repr(C)]
struct SystemFileCacheInformationEx {
    current_size: usize,
    peak_size: usize,
    page_fault_count: usize,
    minimum_working_set: usize,
    maximum_working_set: usize,
    current_size_including_transition_in_pages: usize,
    peak_size_including_transition_in_pages: usize,
    transition_re_purpose_count: usize,
    flags: usize,
}

#[cfg(windows)]
#[repr(C)]
struct MemoryCombineInformationEx {
    unused: [u64; 4],
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

/// System-File-Cache leeren (Minimum/Maximum Working Set auf MAXSIZE_T setzen).
#[cfg(windows)]
pub fn purge_system_file_cache() -> bool {
    let mut info = SystemFileCacheInformationEx {
        current_size: 0,
        peak_size: 0,
        page_fault_count: 0,
        minimum_working_set: usize::MAX,
        maximum_working_set: usize::MAX,
        current_size_including_transition_in_pages: 0,
        peak_size_including_transition_in_pages: 0,
        transition_re_purpose_count: 0,
        flags: 0,
    };
    let status = unsafe {
        win::NtSetSystemInformation(
            win::SYSTEM_FILE_CACHE_INFORMATION_EX,
            &mut info as *mut _ as *mut std::ffi::c_void,
            std::mem::size_of::<SystemFileCacheInformationEx>() as u32,
        )
    };
    status >= 0
}

#[cfg(not(windows))]
pub fn purge_system_file_cache() -> bool {
    false
}

/// Modified Page List flushen.
#[cfg(windows)]
pub fn flush_modified_list() -> bool {
    let mut command: u32 = win::MEMORY_FLUSH_MODIFIED_LIST;
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
pub fn flush_modified_list() -> bool {
    false
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

/// Standby Priority-0 Liste leeren (benoetigt Administratorrechte).
#[cfg(windows)]
pub fn purge_standby_priority0() -> bool {
    let mut command: u32 = win::MEMORY_PURGE_LOW_PRIORITY_STANDBY_LIST;
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
pub fn purge_standby_priority0() -> bool {
    false
}

/// Registry Cache leeren (Windows 8.1+).
#[cfg(windows)]
pub fn purge_registry_cache() -> bool {
    let status = unsafe {
        win::NtSetSystemInformation(
            win::SYSTEM_REGISTRY_RECONCILIATION_INFORMATION,
            std::ptr::null_mut(),
            0,
        )
    };
    status >= 0
}

#[cfg(not(windows))]
pub fn purge_registry_cache() -> bool {
    false
}

/// Memory Combine (Windows 10+).
#[cfg(windows)]
pub fn combine_memory() -> bool {
    let mut info = MemoryCombineInformationEx { unused: [0; 4] };
    let status = unsafe {
        win::NtSetSystemInformation(
            win::SYSTEM_COMBINE_PHYSICAL_MEMORY_INFORMATION,
            &mut info as *mut _ as *mut std::ffi::c_void,
            std::mem::size_of::<MemoryCombineInformationEx>() as u32,
        )
    };
    status >= 0
}

#[cfg(not(windows))]
pub fn combine_memory() -> bool {
    false
}

/// Volumen-Cache flushen (Dateisystem-Caches auf feste Laufwerke).
#[cfg(windows)]
pub fn flush_volume_cache() -> bool {
    use std::fs::File;

    let mut any_ok = false;
    for letter in b'C'..=b'Z' {
        let path = format!("{}:\\", letter as char);
        if let Ok(metadata) = std::fs::metadata(&path) {
            if metadata.file_type().is_dir() {
                if let Ok(file) = File::open(&path) {
                    if file.sync_all().is_ok() {
                        any_ok = true;
                    }
                }
            }
        }
    }
    any_ok
}

#[cfg(not(windows))]
pub fn flush_volume_cache() -> bool {
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

/// Fuehrt alle Memory-Clean-Operationen aus (wie Mem Reduct).
/// Liefert zurueck: (trimmed, system_cache_purged, modified_flushed, standby_purged,
/// standby_prio0_purged, registry_purged, combined, volume_flushed, admin)
#[cfg(windows)]
pub fn clean_memory_full(simulate: bool) -> (usize, bool, bool, bool, bool, bool, bool, bool, bool) {
    if simulate {
        return (0, false, false, false, false, false, false, false, is_admin());
    }
    let admin = is_admin();
    let trimmed = trim_all_processes();
    let system_cache = purge_system_file_cache();
    let modified = flush_modified_list();
    let (standby, standby_prio0, registry, combined) = if admin {
        (
            purge_standby(),
            purge_standby_priority0(),
            purge_registry_cache(),
            combine_memory(),
        )
    } else {
        (false, false, false, false)
    };
    let volume = flush_volume_cache();
    (trimmed, system_cache, modified, standby, standby_prio0, registry, combined, volume, admin)
}

#[cfg(not(windows))]
pub fn clean_memory_full(_simulate: bool) -> (usize, bool, bool, bool, bool, bool, bool, bool, bool) {
    (0, false, false, false, false, false, false, false, false)
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
        let entries = vec!["".to_string(), "  ".to_string()];
        assert!(kill_list(&entries, Some("game.exe")).is_empty());
    }
}