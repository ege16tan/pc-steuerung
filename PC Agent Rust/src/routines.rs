//! Routinen (Phase 4, erweitert in Phase E): Ausloeser (Uhrzeit, manuell,
//! Ereignis) und Aktionsketten (Herunterfahren, Ruhezustand, Energieplan).
//!
//! Gespeichert wird in `routines.json` im Datenordner. Die Schleife in
//! [`run_loop`] prueft alle 10 s, ob eine Routine faellig ist. `last_run` und
//! der Verlaufseintrag werden **vor** dem Ausfuehren gespeichert – sonst
//! wuerde eine Shutdown-Routine nach dem naechsten Start erneut feuern.
//! Verpasste Zeiten (PC war aus) werden bewusst nicht nachgeholt.
//!
//! Phase E: Dazu kommen Ereignis-Ausloeser (`game_stopped`, `download_done`,
//! `idle`), Bedingungen (Wochentage, laufendes Plugin, aktiver Energieplan),
//! Wartezeiten je Aktion, ein Verlauf der letzten Laeufe und zwei Vorlagen.
//! Alle neuen Felder tragen `#[serde(default)]`; `routines.json` schreibt
//! `version` 2, aeltere Dateien (`version` 1) bleiben unveraendert lesbar.
//!
//! Ereignisse loesen hoechstens einmal je Uebergang aus (kein Dauerfeuer):
//! Ein Spiel-Ende zaehlt nur, wenn das Spiel im Takt davor lief, ein Download
//! nur, wenn er im Takt davor lief. Eine Idle-Phase meldet jede Routine genau
//! einmal und wird erst nach neuer Eingabe wieder scharf.

use std::collections::HashSet;
use std::path::Path;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::app::App;
use crate::journal::log_event;
use crate::routes::SharedApp;

/// Abstand der Faelligkeitspruefung.
pub const TICK: Duration = Duration::from_secs(10);
/// So viele Routinen sind hoechstens erlaubt.
pub const MAX_ROUTINES: usize = 20;
/// So viele Aktionen darf eine Routine haben (Herunterfahren zuletzt).
pub const MAX_ACTIONS: usize = 3;
/// So viele Laeufe stehen im Verlauf einer Routine (neueste zuerst).
pub const HISTORY_MAX: usize = 5;
/// Laengste Wartezeit vor einem Schritt in Sekunden (fuenf Minuten).
pub const WAIT_MAX: u32 = 300;
/// Standard fuer `trigger.idle_minutes`.
pub const IDLE_MINUTES_DEFAULT: u32 = 30;
/// Laengste Idle-Zeit in Minuten (ein Tag).
pub const IDLE_MINUTES_MAX: u32 = 1440;
/// Erlaubte Werte fuer `trigger.event`.
pub const EVENTS: [&str; 3] = ["game_stopped", "download_done", "idle"];
/// Version, die beim Speichern von `routines.json` geschrieben wird.
pub const FILE_VERSION: u32 = 2;
/// Format von `last_run`, `next_run` und `history.at`: lokale Zeit, minutengenau.
const TIME_FMT: &str = "%Y-%m-%d %H:%M";

/// Bedingungen einer Routine (Phase E). Leere Felder = keine Einschraenkung.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct Conditions {
    /// Wochentage 1 = Mo ... 7 = So; leer = egal.
    pub days: Vec<u32>,
    /// Plugin-ID, das laufen muss; leer = egal.
    pub plugin: String,
    /// Energieplan (`high`/`balanced`/`saver`), der aktiv sein muss; leer = egal.
    pub power_plan: String,
    /// Name eines Events, das das Plugin ausgegeben haben muss; leer = egal.
    pub plugin_event: String,
    /// Data-Schluessel, der den Wert `plugin_data_value` haben muss; leer = egal.
    pub plugin_data_key: String,
    /// Erwarteter Wert fuer `plugin_data_key`; leer = egal.
    pub plugin_data_value: String,
}

/// Ausloeser einer Routine.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Trigger {
    /// `time` (Uhrzeit), `manual` (nur „Jetzt“) oder `event` (Ereignis).
    #[serde(rename = "type")]
    pub kind: String,
    /// Uhrzeit `HH:MM` (nur bei `time`).
    pub at: String,
    /// Wochentage 1 = Mo ... 7 = So; leer = taeglich (nur bei `time`).
    pub days: Vec<u32>,
    /// Ereignis aus [`EVENTS`] (nur bei `event`).
    pub event: String,
    /// Minuten ohne Eingabe fuer das Ereignis `idle`.
    pub idle_minutes: u32,
    /// Zusaetzliche Bedingungen (gelten fuer Uhrzeit- und Ereignis-Routinen).
    pub conditions: Conditions,
}

impl Default for Trigger {
    fn default() -> Self {
        Self {
            kind: "manual".to_string(),
            at: String::new(),
            days: Vec::new(),
            event: String::new(),
            idle_minutes: IDLE_MINUTES_DEFAULT,
            conditions: Conditions::default(),
        }
    }
}

/// Ein Eintrag im Verlauf einer Routine (`at` = Zeitpunkt, `text` = Kurztext).
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct HistoryEntry {
    pub at: String,
    pub text: String,
}

/// Eine Aktion innerhalb einer Routine.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct RoutineAction {
    /// `shutdown`, `hibernate` oder `power_plan`.
    #[serde(rename = "type")]
    pub kind: String,
    /// Verzoegerung in Sekunden (nur `shutdown`).
    pub delay: u32,
    /// Energieplan (nur `power_plan`).
    pub plan: String,
    /// Wartezeit in Sekunden **vor** diesem Schritt (Phase E3), 0..=[`WAIT_MAX`].
    pub wait: u32,
}

impl Default for RoutineAction {
    fn default() -> Self {
        Self { kind: String::new(), delay: 60, plan: String::new(), wait: 0 }
    }
}

impl RoutineAction {
    /// Kurztext fuer Ereignis-Log und Oberflaeche.
    pub fn label(&self) -> String {
        match self.kind.as_str() {
            "shutdown" => format!("Herunterfahren in {}s", self.delay),
            "hibernate" => "Ruhezustand".to_string(),
            "power_plan" => format!("Energieplan {}", self.plan),
            other => format!("Aktion {other}"),
        }
    }

    fn validate(&self) -> Result<(), String> {
        match self.kind.as_str() {
            "shutdown" => {
                if self.delay > 86400 {
                    return Err("Verzoegerung muss zwischen 0 und 86400 s liegen".to_string());
                }
            }
            "hibernate" => {}
            "power_plan" => {
                if !crate::actions::plan_known(&self.plan) {
                    return Err("Ungueltiger Energieplan".to_string());
                }
            }
            other => return Err(format!("Unbekannte Aktion '{other}'")),
        }
        if self.wait > WAIT_MAX {
            return Err(format!("Wartezeit muss zwischen 0 und {WAIT_MAX} s liegen"));
        }
        Ok(())
    }
}

/// Eine Routine mit Ausloeser und Aktionen.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Routine {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub trigger: Trigger,
    pub actions: Vec<RoutineAction>,
    /// Letzter Lauf als `YYYY-MM-DD HH:MM` (verhindert Doppelausloesung).
    pub last_run: String,
    /// Verlauf der letzten [`HISTORY_MAX`] Laeufe (neueste zuerst).
    pub history: Vec<HistoryEntry>,
}

impl Default for Routine {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: String::new(),
            enabled: true,
            trigger: Trigger::default(),
            actions: Vec::new(),
            last_run: String::new(),
            history: Vec::new(),
        }
    }
}

/// Inhalt von `routines.json`.
#[derive(Serialize, Deserialize)]
#[serde(default)]
struct RoutineFile {
    version: u32,
    routines: Vec<Routine>,
}

impl Default for RoutineFile {
    fn default() -> Self {
        Self { version: FILE_VERSION, routines: Vec::new() }
    }
}

/// Zustand im Agenten (hinter einem Mutex).
#[derive(Default)]
pub struct RoutineState {
    pub routines: Vec<Routine>,
    /// Zwischenstand der Ereignis-Auswertung (Phase E1).
    pub events: EventState,
}

/// Ereignisse, die ein Takt ausgeloest hat.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Fired {
    pub game_stopped: bool,
    pub download_done: bool,
}

/// Zwischenstand zwischen zwei Takten.
///
/// `previous_*` ist `None`, solange kein Vergleich moeglich ist (erster Takt
/// oder keine Routine braucht das Ereignis) – dann loest nichts aus.
#[derive(Default)]
pub struct EventState {
    /// Laufende Spiele-EXEs beim letzten Takt (klein geschrieben).
    previous_running: Option<Vec<String>>,
    /// Lief im letzten Takt ein Steam-Download?
    previous_download: Option<bool>,
    /// Routinen, fuer die die laufende Idle-Phase schon gemeldet wurde.
    idle_done: HashSet<String>,
}

/// Laedt `routines.json`; fehlende/kaputte Datei = leere Liste.
///
/// Alte Dateien (`version` 1, ohne `event`/`conditions`/`wait`/`history`)
/// laden unveraendert, weil alle neuen Felder `#[serde(default)]` tragen.
pub fn load(path: &Path) -> RoutineState {
    let mut routines = std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<RoutineFile>(&text).ok())
        .map(|file| file.routines)
        .unwrap_or_default();
    for routine in &mut routines {
        // Von Hand bearbeitete Dateien sollen den Vertrag nicht sprengen.
        routine.history.truncate(HISTORY_MAX);
    }
    RoutineState { routines, ..Default::default() }
}

/// Schreibt `routines.json` (pretty, wie die uebrigen Konfigurationsdateien).
pub fn save(path: &Path, routines: &[Routine]) -> Result<(), String> {
    let file = RoutineFile { version: FILE_VERSION, routines: routines.to_vec() };
    let json = serde_json::to_string_pretty(&file).map_err(|error| error.to_string())?;
    std::fs::write(path, json).map_err(|error| error.to_string())
}

/// Neue ID (`r-` + 8 Hex-Zeichen) aus `getrandom`.
pub fn new_id() -> String {
    let mut bytes = [0u8; 4];
    if getrandom::getrandom(&mut bytes).is_err() {
        // Rueckfall: Uhrzeit, damit auch ohne Zufall eine eindeutige ID entsteht.
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|value| value.subsec_nanos())
            .unwrap_or(0);
        return format!("r-{nanos:08x}");
    }
    format!("r-{:02x}{:02x}{:02x}{:02x}", bytes[0], bytes[1], bytes[2], bytes[3])
}

/// `HH:MM` -> (Stunde, Minute). Nur echte Uhrzeiten (00:00–23:59).
pub fn parse_time(value: &str) -> Option<(u32, u32)> {
    let (hour, minute) = value.trim().split_once(':')?;
    if hour.len() != 2 || minute.len() != 2 {
        return None;
    }
    let hour: u32 = hour.parse().ok()?;
    let minute: u32 = minute.parse().ok()?;
    if hour > 23 || minute > 59 {
        return None;
    }
    Some((hour, minute))
}

/// Passt der Wochentag? Leere Liste = taeglich.
pub fn matches_day(days: &[u32], weekday: u32) -> bool {
    days.is_empty() || days.contains(&weekday)
}

/// Sind die Bedingungen erfuellt (Phase E2)?
///
/// Reine Funktion: Wochentag, Plugin-Zustand und aktiver Plan kommen von
/// aussen, damit die Auswertung ohne Windows testbar bleibt. Plugin-Event und
/// Plugin-Data werden als vorgepruefte Booleans uebergeben.
pub fn conditions_met(
    conditions: &Conditions,
    weekday: u32,
    plugin_running: bool,
    active_plan: &str,
    plugin_event_ok: bool,
    plugin_data_ok: bool,
) -> bool {
    if !matches_day(&conditions.days, weekday) {
        return false;
    }
    if !conditions.plugin.is_empty() && !plugin_running {
        return false;
    }
    if !conditions.plugin_event.is_empty() && !plugin_event_ok {
        return false;
    }
    if !conditions.plugin_data_key.is_empty() && !plugin_data_ok {
        return false;
    }
    if !conditions.power_plan.is_empty() && conditions.power_plan != active_plan {
        return false;
    }
    true
}

/// Ist die Routine in dieser Minute faellig (und lief noch nicht)?
pub fn is_due(routine: &Routine, now: &chrono::NaiveDateTime) -> bool {
    if !routine.enabled || routine.trigger.kind != "time" {
        return false;
    }
    let Some((hour, minute)) = parse_time(&routine.trigger.at) else {
        return false;
    };
    use chrono::{Datelike, Timelike};
    if now.hour() != hour || now.minute() != minute {
        return false;
    }
    if !matches_day(&routine.trigger.days, now.date().weekday().number_from_monday()) {
        return false;
    }
    routine.last_run != now.format(TIME_FMT).to_string()
}

/// Naechster geplanter Lauf (None bei „nur manuell“, Ereignis, aus oder
/// ungueltiger Zeit).
pub fn next_run(routine: &Routine, now: &chrono::NaiveDateTime) -> Option<chrono::NaiveDateTime> {
    if !routine.enabled || routine.trigger.kind != "time" {
        return None;
    }
    let (hour, minute) = parse_time(&routine.trigger.at)?;
    let due_now = is_due(routine, now);
    for offset in 0..=8 {
        let date = now.date() + chrono::Duration::days(offset);
        use chrono::Datelike;
        if !matches_day(&routine.trigger.days, date.weekday().number_from_monday()) {
            continue;
        }
        let slot = date.and_hms_opt(hour, minute, 0)?;
        if due_now && offset == 0 {
            // Gerade faellig: die Oberflaeche zeigt „jetzt“ statt morgen.
            return Some(*now);
        }
        if slot > *now {
            return Some(slot);
        }
    }
    None
}

/// `next_run` fuer die GET-Antwort: `YYYY-MM-DD HH:MM`; leer ohne festen Lauf
/// (`manual`, `event` oder aus).
pub fn next_run_text(routine: &Routine, now: &chrono::NaiveDateTime) -> String {
    next_run(routine, now)
        .map(|slot| slot.format(TIME_FMT).to_string())
        .unwrap_or_default()
}

/// Wertet einen Takt der Ereignis-Auswertung aus (reine Funktion).
///
/// `running` = Spiele-EXEs, die jetzt laufen (`None` = nicht geprueft),
/// `downloading` = Steam laedt jetzt (`None` = nicht geprueft). Ohne
/// Vergleichswert aus dem Takt davor loest nichts aus – beim ersten Takt und
/// nach dem Einschalten einer Routine gibt es also kein Fehlfeuer.
pub fn update_events(
    state: &mut EventState,
    running: Option<&[String]>,
    downloading: Option<bool>,
) -> Fired {
    let mut fired = Fired::default();
    match running {
        Some(current) => {
            if let Some(previous) = &state.previous_running {
                // Ein Spiel lief im Takt davor und ist jetzt weg.
                fired.game_stopped =
                    previous.iter().any(|name| !current.iter().any(|now| now == name));
            }
            state.previous_running = Some(current.to_vec());
        }
        None => state.previous_running = None,
    }
    match downloading {
        Some(current) => {
            if state.previous_download == Some(true) && !current {
                fired.download_done = true;
            }
            state.previous_download = Some(current);
        }
        None => state.previous_download = None,
    }
    fired
}

/// Meldet die Idle-Phase einer Routine genau einmal.
///
/// `idle_s` sind die Sekunden ohne Eingabe. Faellt der Wert unter die Schwelle,
/// gab es neue Eingabe – die Phase ist beendet und die Routine wird wieder
/// scharf. Rueckgabe `true` = Ereignis jetzt melden.
pub fn idle_due(state: &mut EventState, routine_id: &str, idle_s: u64, idle_minutes: u32) -> bool {
    let minutes = if idle_minutes == 0 { 1 } else { idle_minutes } as u64;
    if idle_s < minutes * 60 {
        state.idle_done.remove(routine_id);
        return false;
    }
    state.idle_done.insert(routine_id.to_string())
}

/// Sekunden seit der letzten Eingabe (Tastatur/Maus); 0, wenn unbekannt.
#[cfg(windows)]
pub fn idle_seconds() -> u64 {
    let mut info = win::LastInputInfo {
        cb_size: std::mem::size_of::<win::LastInputInfo>() as u32,
        dw_time: 0,
    };
    if unsafe { win::GetLastInputInfo(&mut info) } == 0 {
        return 0;
    }
    // GetTickCount laeuft nach 49,7 Tagen ueber; wrapping_sub faengt das ab.
    let idle_ms = unsafe { win::GetTickCount() }.wrapping_sub(info.dw_time);
    (idle_ms / 1000) as u64
}

/// Ohne Windows gibt es keine Eingabemessung – das Ereignis loest nie aus.
#[cfg(not(windows))]
pub fn idle_seconds() -> u64 {
    0
}

#[cfg(windows)]
mod win {
    /// `LASTINPUTINFO` aus `winuser.h`.
    #[repr(C)]
    #[derive(Default)]
    pub struct LastInputInfo {
        pub cb_size: u32,
        pub dw_time: u32,
    }

    #[link(name = "user32")]
    extern "system" {
        pub fn GetLastInputInfo(info: *mut LastInputInfo) -> i32;
    }

    #[link(name = "kernel32")]
    extern "system" {
        pub fn GetTickCount() -> u32;
    }
}

/// Prueft eine Routine vor dem Speichern. Die ID muss bereits gesetzt sein.
///
/// `plugins` sind die bekannten JavaScript-Plugin-IDs; eine Bedingung auf ein
/// unbekanntes Plugin wird abgelehnt.
pub fn validate(routine: &Routine, plugins: &[String]) -> Result<(), String> {
    let name = routine.name.trim();
    if name.is_empty() || name.chars().count() > 64 {
        return Err("Name muss 1 bis 64 Zeichen haben".to_string());
    }
    if routine.id.is_empty()
        || routine.id.len() > 40
        || !routine.id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err("Ungueltige Routine-ID".to_string());
    }
    match routine.trigger.kind.as_str() {
        "time" => {
            if parse_time(&routine.trigger.at).is_none() {
                return Err("Uhrzeit muss im Format HH:MM angegeben werden".to_string());
            }
            if routine.trigger.days.iter().any(|day| !(1..=7).contains(day)) {
                return Err("Wochentage muessen zwischen 1 (Mo) und 7 (So) liegen".to_string());
            }
        }
        "manual" => {}
        "event" => {
            if routine.trigger.event.is_empty() {
                return Err("Ereignis fehlt (game_stopped, download_done oder idle)".to_string());
            }
            if !EVENTS.contains(&routine.trigger.event.as_str()) {
                return Err(format!("Unbekanntes Ereignis '{}'", routine.trigger.event));
            }
            if routine.trigger.event == "idle"
                && !(1..=IDLE_MINUTES_MAX).contains(&routine.trigger.idle_minutes)
            {
                return Err(format!(
                    "Idle-Zeit muss zwischen 1 und {IDLE_MINUTES_MAX} Minuten liegen"
                ));
            }
        }
        other => return Err(format!("Unbekannter Ausloeser '{other}'")),
    }
    // Ereignis nur beim passenden Ausloeser (normalize raeumt es vorher weg).
    if !routine.trigger.event.is_empty() && !EVENTS.contains(&routine.trigger.event.as_str()) {
        return Err(format!("Unbekanntes Ereignis '{}'", routine.trigger.event));
    }

    let conditions = &routine.trigger.conditions;
    if conditions.days.iter().any(|day| !(1..=7).contains(day)) {
        return Err("Wochentage muessen zwischen 1 (Mo) und 7 (So) liegen".to_string());
    }
    if !conditions.plugin.is_empty() && !plugins.contains(&conditions.plugin) {
        return Err(format!("Unbekanntes Plugin '{}'", conditions.plugin));
    }
    // Plugin-Event / Data beziehen sich auf dasselbe Plugin: wenn eines gesetzt
    // ist, muss das Plugin bekannt sein.
    if (!conditions.plugin_event.is_empty() || !conditions.plugin_data_key.is_empty())
        && conditions.plugin.is_empty()
    {
        return Err("Plugin-Bedingung braucht eine Plugin-ID".to_string());
    }
    if (!conditions.plugin_event.is_empty() || !conditions.plugin_data_key.is_empty())
        && !plugins.contains(&conditions.plugin)
    {
        return Err(format!("Unbekanntes Plugin '{}'", conditions.plugin));
    }
    if !conditions.power_plan.is_empty() && !crate::actions::plan_known(&conditions.power_plan) {
        return Err("Ungueltiger Energieplan in den Bedingungen".to_string());
    }

    if routine.actions.is_empty() {
        return Err("Mindestens eine Aktion ist noetig".to_string());
    }
    if routine.actions.len() > MAX_ACTIONS {
        return Err(format!("Hoechstens {MAX_ACTIONS} Aktionen pro Routine"));
    }
    for action in &routine.actions {
        action.validate()?;
    }
    // Herunterfahren beendet den PC – danach kann kein Schritt mehr laufen.
    if let Some(index) = routine.actions.iter().position(|action| action.kind == "shutdown") {
        if index + 1 != routine.actions.len() {
            return Err("Herunterfahren muss die letzte Aktion sein".to_string());
        }
    }
    Ok(())
}

/// Vereinheitlicht Eingaben (Leerzeichen, Kleinbuchstaben, doppelte Tage).
pub fn normalize(routine: &mut Routine) {
    routine.name = routine.name.trim().to_string();
    routine.trigger.kind = routine.trigger.kind.trim().to_ascii_lowercase();
    routine.trigger.at = routine.trigger.at.trim().to_string();
    routine.trigger.event = routine.trigger.event.trim().to_ascii_lowercase();
    if routine.trigger.kind != "event" {
        // `event` gehoert nur zum Ereignis-Ausloeser; ein Rest aus der
        // Oberflaeche wuerde sonst als Bedingung durchrutschen.
        routine.trigger.event.clear();
    }
    routine.trigger.days = sorted_days(&routine.trigger.days);
    routine.trigger.conditions.days = sorted_days(&routine.trigger.conditions.days);
    routine.trigger.conditions.plugin =
        routine.trigger.conditions.plugin.trim().to_ascii_lowercase();
    routine.trigger.conditions.power_plan =
        routine.trigger.conditions.power_plan.trim().to_ascii_lowercase();
    routine.trigger.conditions.plugin_event =
        routine.trigger.conditions.plugin_event.trim().to_ascii_lowercase();
    routine.trigger.conditions.plugin_data_key =
        routine.trigger.conditions.plugin_data_key.trim().to_ascii_lowercase();
    routine.trigger.conditions.plugin_data_value =
        routine.trigger.conditions.plugin_data_value.trim().to_string();
    for action in &mut routine.actions {
        action.kind = action.kind.trim().to_ascii_lowercase();
        action.plan = action.plan.trim().to_ascii_lowercase();
    }
}

/// Tage sortieren, doppelt entfernen, Unmoegliches verwerfen.
fn sorted_days(days: &[u32]) -> Vec<u32> {
    let mut out = days.to_vec();
    out.sort_unstable();
    out.dedup();
    out.retain(|day| (1..=7).contains(day));
    out
}

/// Kurztext der geplanten Aktionen ("Energieplan saver, Herunterfahren in 60s").
///
/// Steht so im Verlauf und in der Antwort auf „Jetzt“.
pub fn plan_text(routine: &Routine) -> String {
    routine
        .actions
        .iter()
        .map(RoutineAction::label)
        .collect::<Vec<_>>()
        .join(", ")
}

/// Vorlagen fuer `GET /routine-templates` (fertig ausgefuellte, gueltige Routinen).
///
/// Bewusst nur zwei: eine fuer den Feierabend (Wochentage, Energieplan sparen,
/// dann Herunterfahren) und eine fuer die Nacht (taeglich, Herunterfahren).
pub fn templates() -> Vec<Routine> {
    vec![
        Routine {
            id: "feierabend".to_string(),
            name: "Feierabend".to_string(),
            enabled: true,
            trigger: Trigger {
                kind: "time".to_string(),
                at: "18:00".to_string(),
                days: vec![1, 2, 3, 4, 5],
                ..Default::default()
            },
            actions: vec![
                RoutineAction {
                    kind: "power_plan".to_string(),
                    plan: "saver".to_string(),
                    delay: 0,
                    wait: 0,
                },
                RoutineAction {
                    kind: "shutdown".to_string(),
                    plan: String::new(),
                    delay: 60,
                    wait: 3,
                },
            ],
            ..Default::default()
        },
        Routine {
            id: "nachts-sparen".to_string(),
            name: "Nachts sparen".to_string(),
            enabled: true,
            trigger: Trigger {
                kind: "time".to_string(),
                at: "23:30".to_string(),
                ..Default::default()
            },
            actions: vec![RoutineAction {
                kind: "shutdown".to_string(),
                plan: String::new(),
                delay: 120,
                wait: 0,
            }],
            ..Default::default()
        },
    ]
}

/// Fuehrt die Aktionskette einer Routine aus (mit Wartezeiten je Schritt).
///
/// Rueckgabe: Kurztext der Aktionen; `Err`, wenn eine Aktion fehlschlug. Die
/// Kette laeuft in der Regel in einem eigenen Task (siehe [`start_run`]),
/// damit der Scheduler und die HTTP-Antwort nicht warten muessen.
pub async fn run_chain(app: &App, routine: &Routine) -> Result<String, String> {
    let mut parts = Vec::new();
    let mut ok = true;
    for action in &routine.actions {
        if action.wait > 0 {
            // Wartezeit vor diesem Schritt (Phase E3, hoechstens WAIT_MAX).
            tokio::time::sleep(Duration::from_secs(action.wait as u64)).await;
        }
        match crate::actions::perform(app, action).await {
            Ok(_) => parts.push(action.label()),
            Err(error) => {
                ok = false;
                parts.push(format!("{}: {error}", action.label()));
            }
        }
    }
    let detail = parts.join(", ");
    {
        let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
        log_event(app, &mut guard, "routine", &format!("{}: {detail}", routine.name), ok);
    }
    if ok {
        Ok(detail)
    } else {
        Err(detail)
    }
}

/// Schreibt einen Verlaufseintrag (neueste zuerst, hoechstens [`HISTORY_MAX`]).
fn push_history(routine: &mut Routine, at: &str, text: &str) {
    routine
        .history
        .insert(0, HistoryEntry { at: at.to_string(), text: text.to_string() });
    routine.history.truncate(HISTORY_MAX);
}

/// Startet eine Routine: Verlaufseintrag schreiben (und bei `scheduled`
/// zusaetzlich `last_run` setzen – **vor** dem Ausfuehren), dann die Kette in
/// einem eigenen Task starten.
///
/// Rueckgabe: Zeitstempel des Laufs; `None`, wenn die Routine fehlt oder das
/// Speichern fehlschlug (dann wird bewusst nichts ausgefuehrt).
fn start_run(
    app: &SharedApp,
    routine: &Routine,
    now: &chrono::NaiveDateTime,
    scheduled: bool,
) -> Option<String> {
    let slot = now.format(TIME_FMT).to_string();
    let planned = plan_text(routine);
    let (snapshot, previous) = {
        let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
        let previous = guard.routines.clone();
        let entry = guard.routines.iter_mut().find(|entry| entry.id == routine.id)?;
        if scheduled {
            entry.last_run = slot.clone();
        }
        push_history(entry, &slot, &planned);
        (guard.routines.clone(), previous)
    };
    if let Err(error) = save(&app.routines_file, &snapshot) {
        // Nicht ausfuehren und alles zuruecknehmen: sonst wuerde die Routine
        // beim naechsten Takt erneut feuern (bzw. der Verlauf waere falsch).
        {
            let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
            guard.routines = previous;
        }
        let mut guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
        log_event(
            app,
            &mut guard,
            "routine",
            &format!("Speichern fehlgeschlagen: {error}"),
            false,
        );
        return None;
    }

    let task_app = app.clone();
    let task_routine = routine.clone();
    let task_slot = slot.clone();
    tokio::spawn(async move {
        if let Err(detail) = run_chain(&task_app, &task_routine).await {
            mark_failed(&task_app, &task_routine.id, &task_slot, &planned, &detail);
        }
    });
    Some(slot)
}

/// Ersetzt nach einem Fehler den Verlaufstext des Laufs durch den echten Text.
fn mark_failed(app: &SharedApp, routine_id: &str, at: &str, planned: &str, detail: &str) {
    let snapshot = {
        let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = guard.routines.iter_mut().find(|entry| entry.id == routine_id) else {
            return;
        };
        let Some(hist) = entry
            .history
            .iter_mut()
            .find(|hist| hist.at == at && hist.text == planned)
        else {
            return;
        };
        hist.text = detail.to_string();
        guard.routines.clone()
    };
    let _ = save(&app.routines_file, &snapshot);
}

/// „Jetzt“/Test-Knopf: Verlauf schreiben und Kette starten (ohne `last_run`,
/// damit der geplante Lauf nicht ausfaellt).
pub fn run_now(app: &SharedApp, routine: &Routine) -> Result<String, String> {
    let now = chrono::Local::now().naive_local();
    match start_run(app, routine, &now, false) {
        Some(_) => Ok(plan_text(routine)),
        None => Err("Routine konnte nicht gestartet werden".to_string()),
    }
}

/// Namen der Spiele, die gerade laufen (EXE-Namen, klein geschrieben).
///
/// Grundlage sind die zuletzt gescannten Spiele (`games.json`) und `tasklist` –
/// dadurch zaehlt nur das Ende eines **bekannten** Spiels, nicht jeder
/// beliebige Prozess. `tasklist` startet einen Prozess, deshalb in einem
/// Blocking-Task.
async fn running_games(app: &SharedApp) -> Vec<String> {
    let task_app = app.clone();
    tokio::task::spawn_blocking(move || {
        let (games, _) = crate::games::cached(task_app.as_ref());
        if games.is_empty() {
            return Vec::new();
        }
        let running = crate::games::running_exes();
        if running.is_empty() {
            return Vec::new();
        }
        let mut names: Vec<String> = Vec::new();
        for game in &games {
            let Some(name) = crate::games::file_name(&game.exe) else {
                continue;
            };
            let name = name.to_ascii_lowercase();
            if !running.contains(&name) && !names.contains(&name) {
                names.push(name);
            }
        }
        names
    })
    .await
    .unwrap_or_default()
}

/// Laeuft das JavaScript-Plugin gerade?
fn plugin_running(app: &App, id: &str) -> bool {
    if id.is_empty() {
        return true;
    }
    let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
    state.runs.contains_key(id)
}

/// Hat das Plugin ein bestimmtes Event ausgegeben (Ringpuffer pruefen)?
fn plugin_event_emitted(app: &App, plugin_id: &str, event_name: &str) -> bool {
    if plugin_id.is_empty() || event_name.is_empty() {
        return false;
    }
    let state = app.jsplugins.lock().unwrap_or_else(|e| e.into_inner());
    state
        .events
        .get(plugin_id)
        .map(|events| events.iter().any(|event| event.name == event_name))
        .unwrap_or(false)
}

/// Stimmt ein Data-Schluessel des Plugins mit einem erwarteten Wert ueberein?
fn plugin_data_equals(app: &App, plugin_id: &str, key: &str, value: &str) -> bool {
    if plugin_id.is_empty() || key.is_empty() {
        return false;
    }
    let data = crate::jsplugins::load_data(app, plugin_id);
    data.get(key).map(|v| v.as_str() == Some(value)).unwrap_or(false)
}

/// Aktiver Energieplan (wie in `GET /status`; im Simulationsmodus der gemerkte).
async fn active_plan(app: &SharedApp) -> String {
    let (simulate, sim_plan) = {
        let guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
        (guard.simulate, guard.sim_plan.clone())
    };
    if simulate {
        if let Some(plan) = sim_plan {
            return plan;
        }
    }
    tokio::task::spawn_blocking(move || {
        let (code, out) = crate::commands::run(&["powercfg", "/getactivescheme"], simulate, true);
        if code != 0 {
            "unbekannt".to_string()
        } else {
            crate::commands::detect_active_plan(&out)
        }
    })
    .await
    .unwrap_or_else(|_| "unbekannt".to_string())
}

/// Ein Durchlauf: Uhrzeiten und Ereignisse pruefen, faellige Routinen starten.
pub async fn tick_once(app: &SharedApp) {
    let now = chrono::Local::now().naive_local();
    use chrono::Datelike;
    let weekday = now.date().weekday().number_from_monday();

    let routines: Vec<Routine> = {
        let guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
        guard.routines.iter().filter(|routine| routine.enabled).cloned().collect()
    };
    let needs = |event: &str| {
        routines
            .iter()
            .any(|routine| routine.trigger.kind == "event" && routine.trigger.event == event)
    };
    let wants_game = needs("game_stopped");
    let wants_download = needs("download_done");
    let wants_idle = needs("idle");
    let wants_plan = routines
        .iter()
        .any(|routine| !routine.trigger.conditions.power_plan.is_empty());

    // Nur pruefen, was eine eingeschaltete Routine braucht: `tasklist` und
    // `powercfg` starten Prozesse, Steam liest die Platte.
    let running = if wants_game { Some(running_games(app).await) } else { None };
    let downloading = if wants_download {
        let task_app = app.clone();
        Some(
            tokio::task::spawn_blocking(move || {
                crate::steam::status(task_app.as_ref()).downloading
            })
            .await
            .unwrap_or(false),
        )
    } else {
        None
    };
    let idle_s = if wants_idle { idle_seconds() } else { 0 };
    let plan = if wants_plan { active_plan(app).await } else { String::new() };

    let fired = {
        let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
        update_events(&mut guard.events, running.as_deref(), downloading)
    };
    // Idle-Phasen je Routine genau einmal melden – auch dann, wenn eine
    // Bedingung den Lauf gerade verhindert (die Phase ist verbraucht).
    let mut idle_flags: Vec<(String, bool)> = Vec::new();
    if wants_idle {
        let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
        let events = &mut guard.events;
        for routine in routines.iter().filter(|routine| {
            routine.trigger.kind == "event" && routine.trigger.event == "idle"
        }) {
            let due = idle_due(events, &routine.id, idle_s, routine.trigger.idle_minutes);
            idle_flags.push((routine.id.clone(), due));
        }
    }

    for routine in &routines {
        let due = match routine.trigger.kind.as_str() {
            "time" => is_due(routine, &now),
            "event" => match routine.trigger.event.as_str() {
                "game_stopped" => fired.game_stopped,
                "download_done" => fired.download_done,
                "idle" => idle_flags
                    .iter()
                    .any(|(id, due)| id == &routine.id && *due),
                _ => false,
            },
            _ => false,
        };
        if !due {
            continue;
        }
        let conditions = &routine.trigger.conditions;
        let plugin_event_ok = if conditions.plugin_event.is_empty() {
            true
        } else {
            plugin_event_emitted(app, &conditions.plugin, &conditions.plugin_event)
        };
        let plugin_data_ok = if conditions.plugin_data_key.is_empty() {
            true
        } else {
            plugin_data_equals(
                app,
                &conditions.plugin,
                &conditions.plugin_data_key,
                &conditions.plugin_data_value,
            )
        };
        if !conditions_met(
            conditions,
            weekday,
            plugin_running(app, &conditions.plugin),
            &plan,
            plugin_event_ok,
            plugin_data_ok,
        ) {
            continue;
        }
        // `last_run` und Verlauf werden in `start_run` vor dem Ausfuehren
        // gespeichert; die Kette selbst laeuft in einem eigenen Task.
        let _ = start_run(app, routine, &now, true);
    }
}

/// Hintergrund-Schleife: prueft alle 10 s Uhrzeiten und Ereignisse.
pub async fn run_loop(app: SharedApp) {
    let mut ticker = tokio::time::interval(TICK);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        tick_once(&app).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    /// 14.11.2026 ist ein Samstag (Wochentag 6).
    fn at(hour: u32, minute: u32) -> chrono::NaiveDateTime {
        NaiveDate::from_ymd_opt(2026, 11, 14)
            .unwrap()
            .and_hms_opt(hour, minute, 0)
            .unwrap()
    }

    fn daily(time: &str) -> Routine {
        Routine {
            id: "r-test".to_string(),
            name: "Test".to_string(),
            trigger: Trigger {
                kind: "time".to_string(),
                at: time.to_string(),
                ..Default::default()
            },
            actions: vec![RoutineAction {
                kind: "hibernate".to_string(),
                ..Default::default()
            }],
            ..Default::default()
        }
    }

    /// Routine mit Ereignis-Ausloeser (Phase E1).
    fn event(event: &str) -> Routine {
        Routine {
            id: "r-event".to_string(),
            name: "Ereignis".to_string(),
            trigger: Trigger {
                kind: "event".to_string(),
                event: event.to_string(),
                ..Default::default()
            },
            actions: vec![RoutineAction {
                kind: "hibernate".to_string(),
                ..Default::default()
            }],
            ..Default::default()
        }
    }

    #[test]
    fn uhrzeit_wird_scharf_geprueft() {
        assert_eq!(parse_time("23:30"), Some((23, 30)));
        assert_eq!(parse_time(" 00:00 "), Some((0, 0)));
        assert_eq!(parse_time("24:00"), None);
        assert_eq!(parse_time("23:60"), None);
        assert_eq!(parse_time("7:05"), None);
        assert_eq!(parse_time("2030"), None);
        assert_eq!(parse_time(""), None);
    }

    #[test]
    fn wochentage_leer_ist_taeglich() {
        assert!(matches_day(&[], 6));
        assert!(matches_day(&[1, 2, 3, 4, 5], 3));
        assert!(!matches_day(&[1, 2, 3, 4, 5], 6));
        assert!(matches_day(&[6, 7], 7));
    }

    #[test]
    fn faellig_nur_einmal_pro_minute() {
        let mut routine = daily("23:30");
        assert!(is_due(&routine, &at(23, 30)));
        assert!(!is_due(&routine, &at(23, 31)));
        // Schon gelaufen -> in derselben Minute nicht erneut.
        routine.last_run = "2026-11-14 23:30".to_string();
        assert!(!is_due(&routine, &at(23, 30)));
        // Ausgeschaltet oder „nur manuell“ -> nie faellig.
        routine.enabled = false;
        assert!(!is_due(&routine, &at(23, 30)));
    }

    #[test]
    fn faellig_nur_am_gewaehlten_wochentag() {
        let mut routine = daily("08:00");
        routine.trigger.days = vec![1, 2, 3, 4, 5];
        assert!(!is_due(&routine, &at(8, 0)));
        routine.trigger.kind = "manual".to_string();
        assert!(!is_due(&routine, &at(8, 0)));
    }

    #[test]
    fn naechster_lauf_heute_morgen_oder_naechste_woche() {
        let routine = daily("23:30");
        assert_eq!(next_run(&routine, &at(20, 0)), Some(at(23, 30)));
        // Gerade faellig: „jetzt“ statt morgen.
        assert_eq!(next_run(&routine, &at(23, 30)), Some(at(23, 30)));

        // Nach dem Lauf kommt der naechste Tag.
        let mut done = routine.clone();
        done.last_run = "2026-11-14 23:30".to_string();
        let sunday = NaiveDate::from_ymd_opt(2026, 11, 15).unwrap();
        assert_eq!(next_run(&done, &at(23, 30)), Some(sunday.and_hms_opt(23, 30, 0).unwrap()));

        // Montag–Freitag: am Samstag ist der naechste Lauf Montag.
        let mut workdays = daily("08:00");
        workdays.trigger.days = vec![1, 2, 3, 4, 5];
        let monday = NaiveDate::from_ymd_opt(2026, 11, 16).unwrap();
        assert_eq!(next_run(&workdays, &at(9, 0)), Some(monday.and_hms_opt(8, 0, 0).unwrap()));

        // Aus oder „nur manuell“ -> kein naechster Lauf.
        let mut off = daily("23:30");
        off.enabled = false;
        assert_eq!(next_run(&off, &at(20, 0)), None);
        let mut manual = daily("23:30");
        manual.trigger.kind = "manual".to_string();
        assert_eq!(next_run(&manual, &at(20, 0)), None);
    }

    #[test]
    fn next_run_text_nutzt_minutenformat() {
        // Phase E4: `YYYY-MM-DD HH:MM`, leer bei manuell/Ereignis/aus.
        let routine = daily("23:30");
        assert_eq!(next_run_text(&routine, &at(20, 0)), "2026-11-14 23:30");
        let mut manual = daily("23:30");
        manual.trigger.kind = "manual".to_string();
        assert_eq!(next_run_text(&manual, &at(20, 0)), "");
        let event_routine = event("game_stopped");
        assert_eq!(next_run_text(&event_routine, &at(20, 0)), "");
        let mut off = daily("23:30");
        off.enabled = false;
        assert_eq!(next_run_text(&off, &at(20, 0)), "");
    }

    #[test]
    fn validierung_faengt_unsinn_ab() {
        let mut routine = daily("23:30");
        assert!(validate(&routine, &[]).is_ok());

        routine.name = "  ".to_string();
        assert!(validate(&routine, &[]).is_err());
        routine.name = "Test".to_string();

        routine.id = String::new();
        assert!(validate(&routine, &[]).is_err());
        routine.id = "r-test".to_string();

        routine.trigger.at = "25:00".to_string();
        assert!(validate(&routine, &[]).is_err());
        routine.trigger.at = "23:30".to_string();

        routine.trigger.days = vec![0, 8];
        assert!(validate(&routine, &[]).is_err());
        routine.trigger.days = Vec::new();

        routine.actions[0].kind = "explode".to_string();
        assert!(validate(&routine, &[]).is_err());
        routine.actions[0] = RoutineAction {
            kind: "power_plan".to_string(),
            plan: "turbo".to_string(),
            ..Default::default()
        };
        assert!(validate(&routine, &[]).is_err());
        routine.actions[0].plan = "saver".to_string();
        assert!(validate(&routine, &[]).is_ok());

        routine.actions[0].kind = "shutdown".to_string();
        routine.actions[0].delay = 86401;
        assert!(validate(&routine, &[]).is_err());
        routine.actions = Vec::new();
        assert!(validate(&routine, &[]).is_err());
    }

    #[test]
    fn wartezeit_wird_geprueft() {
        // Phase E3: 0..=300 s je Schritt.
        let mut routine = daily("23:30");
        routine.actions = vec![
            RoutineAction {
                kind: "power_plan".to_string(),
                plan: "saver".to_string(),
                delay: 0,
                wait: WAIT_MAX,
            },
            RoutineAction { kind: "shutdown".to_string(), delay: 60, wait: 3, ..Default::default() },
        ];
        assert!(validate(&routine, &[]).is_ok());

        routine.actions[0].wait = WAIT_MAX + 1;
        assert!(validate(&routine, &[]).is_err());
        routine.actions[0].wait = 0;
        assert!(validate(&routine, &[]).is_ok());

        // Herunterfahren muss die letzte Aktion bleiben.
        routine.actions = vec![
            RoutineAction { kind: "shutdown".to_string(), delay: 60, wait: 0, ..Default::default() },
            RoutineAction { kind: "hibernate".to_string(), ..Default::default() },
        ];
        assert!(validate(&routine, &[]).is_err());
    }

    #[test]
    fn ereignis_und_bedingungen_werden_geprueft() {
        let routine = event("game_stopped");
        assert!(validate(&routine, &[]).is_ok());

        let mut unknown = event("game_started");
        assert!(validate(&unknown, &[]).is_err());
        unknown.trigger.event = String::new();
        assert!(validate(&unknown, &[]).is_err());

        // Idle-Zeit 1..=1440 Minuten.
        let mut idle = event("idle");
        assert!(validate(&idle, &[]).is_ok());
        idle.trigger.idle_minutes = 0;
        assert!(validate(&idle, &[]).is_err());
        idle.trigger.idle_minutes = IDLE_MINUTES_MAX + 1;
        assert!(validate(&idle, &[]).is_err());

        // Plugin muss existieren, Plan muss bekannt sein.
        let mut conditions = event("game_stopped");
        conditions.trigger.conditions.plugin = "gibtsnicht".to_string();
        assert!(validate(&conditions, &[]).is_err());
        assert!(validate(&conditions, &["gibtsnicht".to_string()]).is_ok());
        conditions.trigger.conditions.plugin = String::new();
        conditions.trigger.conditions.power_plan = "turbo".to_string();
        assert!(validate(&conditions, &[]).is_err());
        conditions.trigger.conditions.power_plan = "saver".to_string();
        assert!(validate(&conditions, &[]).is_ok());
        conditions.trigger.conditions.days = vec![9];
        assert!(validate(&conditions, &[]).is_err());
    }

    #[test]
    fn alte_datei_ohne_neue_felder_laedt() {
        // `version` 1, ohne event/conditions/wait/history (Stand Phase 4).
        let json = r#"{
          "version": 1,
          "routines": [
            {
              "id": "r-alt",
              "name": "Alt",
              "enabled": true,
              "trigger": {"type": "time", "at": "23:30", "days": [1, 2, 3]},
              "actions": [{"type": "shutdown", "delay": 60}],
              "last_run": "2026-11-13 23:30"
            }
          ]
        }"#;
        let path = std::env::temp_dir().join(format!("routines-alt-test-{}.json", std::process::id()));
        std::fs::write(&path, json).unwrap();
        let state = load(&path);
        assert_eq!(state.routines.len(), 1);
        let routine = &state.routines[0];
        assert_eq!(routine.name, "Alt");
        assert_eq!(routine.last_run, "2026-11-13 23:30");
        // Neue Felder bekommen ihre Standardwerte.
        assert_eq!(routine.trigger.event, "");
        assert_eq!(routine.trigger.idle_minutes, IDLE_MINUTES_DEFAULT);
        assert_eq!(routine.trigger.conditions, Conditions::default());
        assert_eq!(routine.actions[0].wait, 0);
        assert!(routine.history.is_empty());
        assert!(validate(routine, &[]).is_ok());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn leere_routine_bekommt_standardwerte() {
        let routine: Routine = serde_json::from_str("{}").unwrap();
        assert!(routine.enabled);
        assert_eq!(routine.trigger.kind, "manual");
        assert_eq!(routine.trigger.idle_minutes, IDLE_MINUTES_DEFAULT);
        assert!(routine.actions.is_empty());
        assert!(routine.last_run.is_empty());
        assert!(routine.history.is_empty());
    }

    #[test]
    fn ereignis_spielende_loest_genau_einmal_aus() {
        let mut state = EventState::default();
        let laeuft = vec!["game.exe".to_string()];
        // Erster Takt: nur Vergleichsbasis, kein Ereignis.
        assert!(!update_events(&mut state, Some(&laeuft), None).game_stopped);
        // Spiel laeuft weiter -> nichts.
        assert!(!update_events(&mut state, Some(&laeuft), None).game_stopped);
        // Spiel ist weg -> einmal ausloesen.
        assert!(update_events(&mut state, Some(&[]), None).game_stopped);
        // Bleibt weg -> kein Dauerfeuer.
        assert!(!update_events(&mut state, Some(&[]), None).game_stopped);
        // Nicht geprueft (keine Routine braucht es) -> Basis wird vergessen.
        assert!(!update_events(&mut state, None, None).game_stopped);
        assert!(!update_events(&mut state, Some(&[]), None).game_stopped);
    }

    #[test]
    fn ereignis_download_ende_loest_genau_einmal_aus() {
        let mut state = EventState::default();
        assert!(!update_events(&mut state, None, Some(false)).download_done);
        // Download startet.
        assert!(!update_events(&mut state, None, Some(true)).download_done);
        // Download laeuft weiter.
        assert!(!update_events(&mut state, None, Some(true)).download_done);
        // Download ist fertig -> einmal ausloesen.
        assert!(update_events(&mut state, None, Some(false)).download_done);
        // Bleibt aus -> kein Dauerfeuer.
        assert!(!update_events(&mut state, None, Some(false)).download_done);
    }

    #[test]
    fn idle_loest_nur_einmal_je_phase_aus() {
        let mut state = EventState::default();
        // Noch keine 30 Minuten -> nichts.
        assert!(!idle_due(&mut state, "r-idle", 60, 30));
        // 30 Minuten ohne Eingabe -> einmal.
        assert!(idle_due(&mut state, "r-idle", 1800, 30));
        // Weiter idle -> kein Dauerfeuer.
        assert!(!idle_due(&mut state, "r-idle", 2400, 30));
        // Neue Eingabe -> Phase beendet.
        assert!(!idle_due(&mut state, "r-idle", 5, 30));
        // Und die naechste Phase loest wieder aus.
        assert!(idle_due(&mut state, "r-idle", 1800, 30));
        // Andere Routinen haben ihre eigene Phase.
        assert!(idle_due(&mut state, "r-other", 1800, 30));
    }

    #[test]
    fn bedingungen_blockieren_und_erlauben() {
        let conditions = Conditions {
            days: vec![6],
            plugin: "game-optimizer".to_string(),
            power_plan: "saver".to_string(),
            ..Default::default()
        };
        // Samstag, Plugin laeuft, Plan saver -> erlaubt.
        assert!(conditions_met(&conditions, 6, true, "saver", true, true));
        // Wochentag passt nicht.
        assert!(!conditions_met(&conditions, 5, true, "saver", true, true));
        // Plugin laeuft nicht.
        assert!(!conditions_met(&conditions, 6, false, "saver", true, true));
        // Anderer Energieplan.
        assert!(!conditions_met(&conditions, 6, true, "high", true, true));
        // Leere Bedingungen schraenken nichts ein.
        assert!(conditions_met(&Conditions::default(), 1, false, "anderer", true, true));
        // Nur der Wochentag gesetzt.
        let day_only = Conditions { days: vec![1, 2, 3, 4, 5], ..Default::default() };
        assert!(conditions_met(&day_only, 3, false, "", true, true));
        assert!(!conditions_met(&day_only, 6, true, "saver", true, true));
    }

    #[test]
    fn historie_bleibt_bei_fuenf_eintraegen() {
        let mut routine = daily("23:30");
        for index in 0..7 {
            push_history(&mut routine, &format!("2026-11-1{index} 18:00"), &format!("Lauf {index}"));
        }
        assert_eq!(routine.history.len(), HISTORY_MAX);
        // Neueste zuerst.
        assert_eq!(routine.history[0].text, "Lauf 6");
        assert_eq!(routine.history[0].at, "2026-11-16 18:00");
        assert_eq!(routine.history[HISTORY_MAX - 1].text, "Lauf 2");
    }

    #[test]
    fn vorlagen_liefern_zwei_gueltige_routinen() {
        let list = templates();
        assert_eq!(list.len(), 2);
        for routine in &list {
            assert!(
                validate(routine, &[]).is_ok(),
                "Vorlage {} ist ungueltig: {:?}",
                routine.id,
                validate(routine, &[])
            );
        }
        // Feierabend: Mo–Fr 18:00, Energieplan saver, dann Shutdown in 60 s.
        assert_eq!(list[0].id, "feierabend");
        assert_eq!(list[0].name, "Feierabend");
        assert_eq!(list[0].trigger.at, "18:00");
        assert_eq!(list[0].trigger.days, vec![1, 2, 3, 4, 5]);
        assert_eq!(list[0].actions[0].kind, "power_plan");
        assert_eq!(list[0].actions[0].plan, "saver");
        assert_eq!(list[0].actions[1].kind, "shutdown");
        assert_eq!(list[0].actions[1].delay, 60);
        assert_eq!(list[0].actions[1].wait, 3);
        assert_eq!(plan_text(&list[0]), "Energieplan saver, Herunterfahren in 60s");
        // Nachts sparen: taeglich 23:30, Shutdown in 120 s.
        assert_eq!(list[1].id, "nachts-sparen");
        assert_eq!(list[1].trigger.at, "23:30");
        assert!(list[1].trigger.days.is_empty());
        assert_eq!(list[1].actions[0].delay, 120);
    }

    #[tokio::test]
    async fn ausfuehren_loggt_aktion_und_ereignis() {
        let dir = std::env::temp_dir().join(format!("routines-run-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let app = std::sync::Arc::new(App::new(String::new(), true, &dir, 0));
        let routine = daily("23:30");

        run_chain(&app, &routine).await.unwrap();

        let guard = app.inner.lock().unwrap_or_else(|e| e.into_inner());
        assert!(guard
            .events
            .iter()
            .any(|event| event.action == "hibernate" && event.ok));
        assert!(guard
            .events
            .iter()
            .any(|event| event.action == "routine" && event.detail.contains("Test")));
        drop(guard);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn lauf_schreibt_verlauf_und_letzten_lauf() {
        let dir = std::env::temp_dir().join(format!("routines-history-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let app = std::sync::Arc::new(App::new(String::new(), true, &dir, 0));
        let mut routine = daily("23:30");
        routine.actions = vec![RoutineAction {
            kind: "power_plan".to_string(),
            plan: "saver".to_string(),
            delay: 0,
            wait: 0,
        }];
        {
            let mut guard = app.routines.lock().unwrap_or_else(|e| e.into_inner());
            guard.routines.push(routine.clone());
        }

        // Geplanter Lauf: `last_run` und Verlauf stehen vor dem Ausfuehren.
        assert!(start_run(&app, &routine, &at(18, 0), true).is_some());
        let state = load(&app.routines_file);
        assert_eq!(state.routines[0].last_run, "2026-11-14 18:00");
        assert_eq!(state.routines[0].history.len(), 1);
        assert_eq!(state.routines[0].history[0].at, "2026-11-14 18:00");
        assert_eq!(state.routines[0].history[0].text, "Energieplan saver");

        // „Jetzt“ schreibt ebenfalls in den Verlauf, laesst `last_run` aber stehen.
        run_now(&app, &routine).unwrap();
        let state = load(&app.routines_file);
        assert_eq!(state.routines[0].last_run, "2026-11-14 18:00");
        assert_eq!(state.routines[0].history.len(), 2);
        assert_eq!(state.routines[0].history[0].text, "Energieplan saver");
        let _ = std::fs::remove_dir_all(&dir);
    }
}

