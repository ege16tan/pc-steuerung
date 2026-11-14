//! Routinen (Phase 4): Ausloeser (Uhrzeit oder manuell) und Aktionen
//! (Herunterfahren, Ruhezustand, Energieplan).
//!
//! Gespeichert wird in `routines.json` im Datenordner. Die Schleife in
//! [`run_loop`] prueft alle 10 s, ob eine Routine faellig ist. `last_run` wird
//! **vor** dem Ausfuehren gespeichert – sonst wuerde eine Shutdown-Routine nach
//! dem naechsten Start erneut feuern. Verpasste Zeiten (PC war aus) werden
//! bewusst nicht nachgeholt.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::app::App;
use crate::journal::log_event;

/// Abstand der Faelligkeitspruefung.
pub const TICK: std::time::Duration = std::time::Duration::from_secs(10);
/// So viele Routinen sind hoechstens erlaubt.
pub const MAX_ROUTINES: usize = 20;
/// So viele Aktionen darf eine Routine haben (Herunterfahren zuletzt).
pub const MAX_ACTIONS: usize = 3;
/// Format von `last_run`: lokale Zeit, minutengenau.
const LAST_RUN_FMT: &str = "%Y-%m-%d %H:%M";

/// Ausloeser einer Routine.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Trigger {
    /// `time` (Uhrzeit) oder `manual` (nur „Jetzt“).
    #[serde(rename = "type")]
    pub kind: String,
    /// Uhrzeit `HH:MM` (nur bei `time`).
    pub at: String,
    /// Wochentage 1 = Mo ... 7 = So; leer = taeglich.
    pub days: Vec<u32>,
}

impl Default for Trigger {
    fn default() -> Self {
        Self { kind: "manual".to_string(), at: String::new(), days: Vec::new() }
    }
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
}

impl Default for RoutineAction {
    fn default() -> Self {
        Self { kind: String::new(), delay: 60, plan: String::new() }
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
        Self { version: 1, routines: Vec::new() }
    }
}

/// Zustand im Agenten (hinter einem Mutex).
#[derive(Default)]
pub struct RoutineState {
    pub routines: Vec<Routine>,
}

/// Laedt `routines.json`; fehlende/kaputte Datei = leere Liste.
pub fn load(path: &Path) -> RoutineState {
    let routines = std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<RoutineFile>(&text).ok())
        .map(|file| file.routines)
        .unwrap_or_default();
    RoutineState { routines }
}

/// Schreibt `routines.json` (pretty, wie die uebrigen Konfigurationsdateien).
pub fn save(path: &Path, routines: &[Routine]) -> Result<(), String> {
    let file = RoutineFile { version: 1, routines: routines.to_vec() };
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
    routine.last_run != now.format(LAST_RUN_FMT).to_string()
}

/// Naechster geplanter Lauf (None bei „nur manuell“, aus oder ungueltiger Zeit).
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

/// Prueft eine Routine vor dem Speichern. Die ID muss bereits gesetzt sein.
pub fn validate(routine: &Routine) -> Result<(), String> {
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
        other => return Err(format!("Unbekannter Ausloeser '{other}'")),
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
    Ok(())
}

/// Vereinheitlicht Eingaben (Leerzeichen, Kleinbuchstaben, doppelte Tage).
pub fn normalize(routine: &mut Routine) {
    routine.name = routine.name.trim().to_string();
    routine.trigger.kind = routine.trigger.kind.trim().to_ascii_lowercase();
    routine.trigger.at = routine.trigger.at.trim().to_string();
    let mut days = routine.trigger.days.clone();
    days.sort_unstable();
    days.dedup();
    days.retain(|day| (1..=7).contains(day));
    routine.trigger.days = days;
    for action in &mut routine.actions {
        action.kind = action.kind.trim().to_ascii_lowercase();
        action.plan = action.plan.trim().to_ascii_lowercase();
    }
}

/// Fuehrt alle Aktionen einer Routine aus und schreibt ein Ereignis.
/// Rueckgabe: Kurztext der Aktionen; `Err`, wenn eine Aktion fehlschlug.
pub async fn execute(app: &App, routine: &Routine) -> Result<String, String> {
    let mut parts = Vec::new();
    let mut ok = true;
    for action in &routine.actions {
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
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        log_event(app, &mut guard, "routine", &format!("{}: {detail}", routine.name), ok);
    }
    if ok {
        Ok(detail)
    } else {
        Err(detail)
    }
}

/// Ein Durchlauf: alle faelligen Routinen markieren und ausfuehren.
pub async fn tick_once(app: &crate::routes::SharedApp) {
    let now = chrono::Local::now().naive_local();
    let due = {
        let guard = app.routines.lock().expect("Routinen-Sperre");
        guard
            .routines
            .iter()
            .filter(|routine| is_due(routine, &now))
            .cloned()
            .collect::<Vec<_>>()
    };
    for routine in due {
        if !mark_ran(app, &routine.id, &now) {
            continue;
        }
        let _ = execute(app, &routine).await;
    }
}

/// `last_run` **vor** dem Ausfuehren setzen und speichern.
///
/// Schlaegt das Speichern fehl, wird nicht ausgefuehrt: sonst wuerde die
/// Routine nach einem Neustart erneut feuern.
fn mark_ran(app: &crate::routes::SharedApp, id: &str, now: &chrono::NaiveDateTime) -> bool {
    let slot = now.format(LAST_RUN_FMT).to_string();
    let (snapshot, previous) = {
        let mut guard = app.routines.lock().expect("Routinen-Sperre");
        let Some(entry) = guard.routines.iter_mut().find(|routine| routine.id == id) else {
            return false;
        };
        let previous = entry.last_run.clone();
        entry.last_run = slot;
        (guard.routines.clone(), previous)
    };
    if let Err(error) = save(&app.routines_file, &snapshot) {
        // Nicht ausfuehren und die Markierung zuruecknehmen: so bleibt die
        // Routine beim naechsten Versuch erneut faellig.
        {
            let mut guard = app.routines.lock().expect("Routinen-Sperre");
            if let Some(entry) = guard.routines.iter_mut().find(|routine| routine.id == id) {
                entry.last_run = previous;
            }
        }
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        log_event(
            app,
            &mut guard,
            "routine",
            &format!("Speichern fehlgeschlagen: {error}"),
            false,
        );
        return false;
    }
    true
}

/// Hintergrund-Schleife: prueft alle 10 s, ob eine Routine faellig ist.
pub async fn run_loop(app: crate::routes::SharedApp) {
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
                days: Vec::new(),
            },
            actions: vec![RoutineAction {
                kind: "hibernate".to_string(),
                delay: 0,
                plan: String::new(),
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
    fn validierung_faengt_unsinn_ab() {
        let mut routine = daily("23:30");
        assert!(validate(&routine).is_ok());

        routine.name = "  ".to_string();
        assert!(validate(&routine).is_err());
        routine.name = "Test".to_string();

        routine.id = String::new();
        assert!(validate(&routine).is_err());
        routine.id = "r-test".to_string();

        routine.trigger.at = "25:00".to_string();
        assert!(validate(&routine).is_err());
        routine.trigger.at = "23:30".to_string();

        routine.trigger.days = vec![0, 8];
        assert!(validate(&routine).is_err());
        routine.trigger.days = Vec::new();

        routine.actions[0].kind = "explode".to_string();
        assert!(validate(&routine).is_err());
        routine.actions[0] = RoutineAction {
            kind: "power_plan".to_string(),
            delay: 0,
            plan: "turbo".to_string(),
        };
        assert!(validate(&routine).is_err());
        routine.actions[0].plan = "saver".to_string();
        assert!(validate(&routine).is_ok());

        routine.actions[0].kind = "shutdown".to_string();
        routine.actions[0].delay = 86401;
        assert!(validate(&routine).is_err());
        routine.actions = Vec::new();
        assert!(validate(&routine).is_err());
    }

    #[test]
    fn alte_oder_leere_datei_bleibt_gueltig() {
        let routine: Routine = serde_json::from_str("{}").unwrap();
        assert!(routine.enabled);
        assert_eq!(routine.trigger.kind, "manual");
        assert!(routine.actions.is_empty());
        assert!(routine.last_run.is_empty());
    }

    #[test]
    fn speichern_und_laden_erhaelt_die_liste() {
        let path = std::env::temp_dir().join(format!("routines-test-{}.json", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let routine = daily("23:30");
        save(&path, std::slice::from_ref(&routine)).unwrap();
        let state = load(&path);
        assert_eq!(state.routines.len(), 1);
        assert_eq!(state.routines[0].name, "Test");
        assert_eq!(state.routines[0].trigger.at, "23:30");
        let _ = std::fs::remove_file(&path);
    }

    #[tokio::test]
    async fn ausfuehren_loggt_aktion_und_ereignis() {
        let dir = std::env::temp_dir().join(format!("routines-run-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let app = std::sync::Arc::new(App::new(String::new(), true, &dir, 0));
        let routine = daily("23:30");

        execute(&app, &routine).await.unwrap();

        let guard = app.inner.lock().expect("Zustands-Sperre");
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
}
