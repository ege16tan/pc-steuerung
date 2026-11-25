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

    #[link(name = "user32")]
    extern "system" {
        pub fn EnumWindows(
            callback: Option<unsafe extern "system" fn(Handle, isize) -> i32>,
            parameter: isize,
        ) -> i32;
        pub fn IsWindowVisible(window: Handle) -> i32;
        pub fn GetWindowThreadProcessId(window: Handle, process_id: *mut u32) -> u32;
        pub fn PostMessageW(window: Handle, message: u32, wparam: usize, lparam: isize) -> i32;
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

#[derive(Debug, PartialEq, Eq)]
pub enum ProcessCloseError {
    Protected,
    NotFound,
    NameChanged,
    RequestFailed,
}

/// Laufende Apps mit sichtbarem Fenster, ausgenommen kritische System- und
/// Steuerungsprozesse.
pub fn interactive_processes() -> Vec<(String, u32)> {
    #[cfg(windows)]
    {
        let visible_ids = windows_ui::visible_process_ids(None);
        let mut processes = task_list()
            .into_iter()
            .filter(|(name, pid)| visible_ids.contains(pid) && !is_protected_process(name))
            .collect::<Vec<_>>();
        processes.sort_by(|(left_name, left_pid), (right_name, right_pid)| {
            left_name
                .to_lowercase()
                .cmp(&right_name.to_lowercase())
                .then_with(|| left_pid.cmp(right_pid))
        });
        processes.dedup_by_key(|(_, pid)| *pid);
        processes
    }
    #[cfg(not(windows))]
    {
        Vec::new()
    }
}

/// Sendet WM_CLOSE an alle sichtbaren Fenster des ausgewählten Prozesses.
/// Der Prozess wird nie zwangsweise beendet.
pub fn request_process_close(pid: u32, expected_name: &str) -> Result<(), ProcessCloseError> {
    if pid <= 4 || is_protected_process(expected_name) {
        return Err(ProcessCloseError::Protected);
    }

    #[cfg(windows)]
    {
        let Some((actual_name, _)) = interactive_processes()
            .into_iter()
            .find(|(_, actual_pid)| *actual_pid == pid)
        else {
            return Err(ProcessCloseError::NotFound);
        };
        if !actual_name.eq_ignore_ascii_case(expected_name) {
            return Err(ProcessCloseError::NameChanged);
        }
        if windows_ui::post_close_to_process(pid) {
            Ok(())
        } else {
            Err(ProcessCloseError::RequestFailed)
        }
    }
    #[cfg(not(windows))]
    {
        let _ = expected_name;
        Err(ProcessCloseError::NotFound)
    }
}

fn is_protected_process(name: &str) -> bool {
    matches!(
        name.trim().to_ascii_lowercase().as_str(),
        "system"
            | "registry"
            | "idle"
            | "smss.exe"
            | "csrss.exe"
            | "wininit.exe"
            | "services.exe"
            | "lsass.exe"
            | "winlogon.exe"
            | "svchost.exe"
            | "dwm.exe"
            | "explorer.exe"
            | "pc-agent.exe"
            | "pc steuerung.exe"
    )
}

#[cfg(windows)]
mod interactive_windows {
    use super::win;
    use std::collections::HashSet;

    struct WindowScan {
        process_ids: HashSet<u32>,
        target_pid: Option<u32>,
        close_sent: bool,
    }

    unsafe extern "system" fn visit_window(window: win::Handle, parameter: isize) -> i32 {
        if unsafe { win::IsWindowVisible(window) } == 0 {
            return 1;
        }

        let scan = unsafe { &mut *(parameter as *mut WindowScan) };
        let mut process_id = 0;
        unsafe {
            win::GetWindowThreadProcessId(window, &mut process_id);
        }
        if process_id == 0 {
            return 1;
        }

        if scan.target_pid == Some(process_id) {
            scan.close_sent |= unsafe { win::PostMessageW(window, 0x0010, 0, 0) != 0 };
        } else if scan.target_pid.is_none() {
            scan.process_ids.insert(process_id);
        }
        1
    }

    fn scan(target_pid: Option<u32>) -> WindowScan {
        let mut scan = WindowScan {
            process_ids: HashSet::new(),
            target_pid,
            close_sent: false,
        };
        unsafe {
            win::EnumWindows(Some(visit_window), &mut scan as *mut WindowScan as isize);
        }
        scan
    }

    pub fn visible_process_ids(target_pid: Option<u32>) -> HashSet<u32> {
        scan(target_pid).process_ids
    }

    pub fn post_close_to_process(pid: u32) -> bool {
        scan(Some(pid)).close_sent
    }
}

#[cfg(windows)]
use interactive_windows as windows_ui;

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

/// Volumen-Cache flushen (Dateisystem-Caches auf alle Volumes).
#[cfg(windows)]
pub fn flush_volume_cache() -> bool {
    use std::fs::File;

    let mut any_ok = false;
    for letter in b'C'..=b'Z' {
        let path = format!("{}:\\", letter as char);
        if let Ok(file) = File::open(&path) {
            if file.sync_all().is_ok() {
                any_ok = true;
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
pub fn clean_memory_full() -> (usize, bool, bool, bool, bool, bool, bool, bool, bool) {
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
pub fn clean_memory_full() -> (usize, bool, bool, bool, bool, bool, bool, bool, bool) {
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

    #[test]
    fn critical_processes_cannot_be_closed_remotely() {
        assert_eq!(
            request_process_close(4, "System"),
            Err(ProcessCloseError::Protected)
        );
        assert_eq!(
            request_process_close(123, "pc-agent.exe"),
            Err(ProcessCloseError::Protected)
        );
        assert_eq!(
            request_process_close(124, "explorer.exe"),
            Err(ProcessCloseError::Protected)
        );
    }

    #[test]
    fn process_names_are_checked_case_insensitively() {
        assert!(is_protected_process("PC-Agent.exe"));
        assert!(is_protected_process("Explorer.EXE"));
        assert!(!is_protected_process("ollama.exe"));
    }
}