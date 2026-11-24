//! Warnregeln (Phase C): Schwellen mit Dauer, ausgewertet im Agenten.
//!
//! Die Regeln liegen in `warnings.json` und werden **hier** geprueft - auch bei
//! geschlossener App ([`run_loop`], Takt 30 s). Die Android-App zeigt nur an,
//! was bereits ausgeloest wurde (`GET /warnings`), damit ein Handy im Standby
//! nichts rechnen muss.
//!
//! Pflichtregel D1: gemessen wird ausschliesslich, was eine eingeschaltete Regel
//! braucht. CPU/RAM kommen aus `metrics` (zwei billige Stichproben),
//! Datentraeger aus `sensors` (`GetDiskFreeSpaceEx`) und die Grafikkarte nur,
//! wenn eine GPU-Regel existiert (`nvidia-smi` startet einen Prozess).
//!
//! Die Auswertung selbst ([`evaluate`]) ist eine reine Funktion: Werte, Zustand
//! und Zeitpunkt kommen von aussen, damit sie ohne Windows testbar bleibt.

use std::collections::{BTreeMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::app::App;
use crate::journal::log_event;
use crate::routes::SharedApp;

/// Abstand der Pruefung (und damit auch der Abfragetakt der App).
pub const CHECK_INTERVAL: Duration = Duration::from_secs(30);
/// So viele ausgeloeste Warnungen merkt sich der Agent (neueste zuerst).
pub const HISTORY_MAX: usize = 30;
/// Mehr Regeln sind nicht sinnvoll und werden abgelehnt.
pub const RULES_MAX: usize = 20;
/// Laengste erlaubte Dauer einer Schwelle (24 Stunden).
pub const DURATION_MAX_S: u64 = 86400;

/// Erlaubte Messwerte (`metric` in `warnings.json`).
pub const METRICS: [&str; 4] = ["cpu_percent", "ram_percent", "disk_free_percent", "gpu_percent"];

/// Eine Warnregel: `metric` verletzt `op`/`threshold` fuer `duration_s` Sekunden.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct WarningRule {
    pub id: String,
    /// Anzeigename in Benachrichtigung und Journal ("CPU-Last").
    pub label: String,
    /// Messwert aus [`METRICS`].
    pub metric: String,
    /// `>` oder `<`.
    pub op: String,
    pub threshold: f64,
    /// So lange muss die Schwelle ununterbrochen verletzt sein.
    pub duration_s: u64,
    pub enabled: bool,
}

impl Default for WarningRule {
    fn default() -> Self {
        Self {
            id: String::new(),
            label: String::new(),
            metric: "cpu_percent".to_string(),
            op: ">".to_string(),
            threshold: 90.0,
            duration_s: 30,
            enabled: true,
        }
    }
}

/// Anzeigename eines Messwerts (auch Rueckfall fuer Regeln ohne `label`).
pub fn metric_label(metric: &str) -> &'static str {
    match metric {
        "cpu_percent" => "CPU-Last",
        "ram_percent" => "Arbeitsspeicher",
        "disk_free_percent" => "Freier Speicher",
        "gpu_percent" => "Grafiklast",
        _ => "Messwert",
    }
}

/// Standardregeln: einmal in `warnings.json` angelegt, danach normal editierbar.
pub fn default_rules() -> Vec<WarningRule> {
    vec![
        WarningRule {
            id: "cpu-high".to_string(),
            label: metric_label("cpu_percent").to_string(),
            metric: "cpu_percent".to_string(),
            op: ">".to_string(),
            threshold: 90.0,
            duration_s: 30,
            enabled: true,
        },
        WarningRule {
            id: "ram-high".to_string(),
            label: metric_label("ram_percent").to_string(),
            metric: "ram_percent".to_string(),
            op: ">".to_string(),
            threshold: 90.0,
            duration_s: 60,
            enabled: true,
        },
        WarningRule {
            id: "disk-low".to_string(),
            label: metric_label("disk_free_percent").to_string(),
            metric: "disk_free_percent".to_string(),
            op: "<".to_string(),
            threshold: 10.0,
            duration_s: 60,
            enabled: true,
        },
    ]
}

/// Eine ausgeloeste Warnung (aktiv oder im Verlauf).
#[derive(Clone, Debug, Serialize)]
pub struct ActiveWarning {
    pub id: String,
    pub label: String,
    pub metric: String,
    /// Fertiger Text fuer Benachrichtigung und Journal.
    pub message: String,
    pub value: f64,
    pub threshold: f64,
    pub op: String,
    pub duration_s: u64,
    /// Sekunden seit Beginn der Verletzung (waechst bei jeder Pruefung mit).
    pub since_s: u64,
    /// Beginn der Verletzung als Text (Ortszeit des Agenten).
    pub since: String,
    /// Derselbe Zeitpunkt als Unix-Sekunden - der Schluessel der App.
    pub since_epoch: f64,
}

/// Was sich bei einer Pruefung geaendert hat (fuer Journal/Benachrichtigung).
#[derive(Clone, Debug)]
pub enum Change {
    Triggered(ActiveWarning),
    Cleared { id: String, label: String },
}

/// Zustand im Agenten (hinter einem Mutex).
#[derive(Default)]
pub struct WarningState {
    pub rules: Vec<WarningRule>,
    pub active: BTreeMap<String, ActiveWarning>,
    /// Wann eine Regel zuerst verletzt wurde (fuer die Dauer-Bedingung).
    breached_since: BTreeMap<String, Instant>,
    /// Ausgeloeste Warnungen, neueste zuerst (Ringpuffer).
    pub history: Vec<ActiveWarning>,
    /// Zeitpunkt der letzten Pruefung als Text.
    pub checked_at: Option<String>,
}

/// Zeitstempel fuer neue Warnungen (in Tests einsetzbar).
pub struct Stamp {
    pub time: String,
    pub epoch: f64,
}

fn stamp_now() -> Stamp {
    let now = chrono::Local::now();
    Stamp {
        time: now.format("%Y-%m-%d %H:%M:%S").to_string(),
        epoch: now.timestamp_millis() as f64 / 1000.0,
    }
}

/// Verletzt `value` die Schwelle? Unbekannte Operatoren gelten nie als Verletzung.
pub fn breaches(op: &str, threshold: f64, value: f64) -> bool {
    match op {
        ">" => value > threshold,
        "<" => value < threshold,
        _ => false,
    }
}

/// Text einer Warnung: "CPU-Last: 91.4 % > 90.0 % seit 30 s".
fn message(rule: &WarningRule, value: f64) -> String {
    format!(
        "{}: {:.1} % {} {:.1} % seit {} s",
        rule.label, value, rule.op, rule.threshold, rule.duration_s
    )
}

/// Wendet alle Regeln auf die Messwerte an und liefert die Aenderungen.
///
/// Reine Funktion: keine Uhr, keine Messung, kein Mutex - dadurch testbar.
/// Fehlt ein Wert (z. B. die erste CPU-Stichprobe), gilt die Regel als nicht
/// verletzt; eine laufende Warnung wird dann aufgehoben.
pub fn evaluate(
    state: &mut WarningState,
    values: &BTreeMap<String, f64>,
    now: Instant,
    stamp: &Stamp,
) -> Vec<Change> {
    let rules = state.rules.clone();
    let mut changes = Vec::new();

    // Ausgeschaltete oder geloeschte Regeln: Verletzung und Warnung fallen weg.
    let enabled: HashSet<&str> = rules
        .iter()
        .filter(|rule| rule.enabled)
        .map(|rule| rule.id.as_str())
        .collect();
    let stale: Vec<String> = state
        .breached_since
        .keys()
        .filter(|id| !enabled.contains(id.as_str()))
        .cloned()
        .collect();
    for id in stale {
        state.breached_since.remove(&id);
    }
    let stale: Vec<String> = state
        .active
        .keys()
        .filter(|id| !enabled.contains(id.as_str()))
        .cloned()
        .collect();
    for id in stale {
        if let Some(entry) = state.active.remove(&id) {
            changes.push(Change::Cleared { id, label: entry.label });
        }
    }

    for rule in rules.iter().filter(|rule| rule.enabled) {
        let value = values.get(&rule.metric).copied();
        let breached = value
            .map(|value| breaches(&rule.op, rule.threshold, value))
            .unwrap_or(false);

        if breached {
            let value = value.unwrap_or_default();
            let since = *state.breached_since.entry(rule.id.clone()).or_insert(now);
            let held = now.saturating_duration_since(since);
            match state.active.get_mut(&rule.id) {
                Some(entry) => {
                    // Laufende Warnung mitziehen (Wert und Dauer aendern sich).
                    entry.value = value;
                    entry.since_s = held.as_secs();
                    entry.message = message(rule, value);
                }
                None if held >= Duration::from_secs(rule.duration_s) => {
                    let entry = ActiveWarning {
                        id: rule.id.clone(),
                        label: rule.label.clone(),
                        metric: rule.metric.clone(),
                        message: message(rule, value),
                        value,
                        threshold: rule.threshold,
                        op: rule.op.clone(),
                        duration_s: rule.duration_s,
                        since_s: held.as_secs(),
                        since: stamp.time.clone(),
                        since_epoch: stamp.epoch,
                    };
                    state.active.insert(rule.id.clone(), entry.clone());
                    state.history.insert(0, entry.clone());
                    while state.history.len() > HISTORY_MAX {
                        state.history.pop();
                    }
                    changes.push(Change::Triggered(entry));
                }
                None => {}
            }
        } else {
            state.breached_since.remove(&rule.id);
            if let Some(entry) = state.active.remove(&rule.id) {
                changes.push(Change::Cleared { id: rule.id.clone(), label: entry.label });
            }
        }
    }

    changes
}

/// Messwerte lesen, die zu mindestens einer eingeschalteten Regel passen.
pub fn read_values(app: &App, rules: &[WarningRule]) -> BTreeMap<String, f64> {
    let mut values = BTreeMap::new();
    let needed = |metric: &str| rules.iter().any(|rule| rule.enabled && rule.metric == metric);
    let cpu = needed("cpu_percent");
    let ram = needed("ram_percent");
    if cpu || ram {
        let metrics = crate::metrics::read(&app.metrics);
        if cpu {
            if let Some(percent) = metrics.cpu_percent {
                values.insert("cpu_percent".to_string(), percent);
            }
        }
        if ram && metrics.ram_total_mb > 0 {
            values.insert("ram_percent".to_string(), metrics.ram_percent);
        }
    }
    if needed("disk_free_percent") {
        if let Some(percent) = crate::sensors::lowest_free_percent(&crate::sensors::disks()) {
            values.insert("disk_free_percent".to_string(), percent);
        }
    }
    if needed("gpu_percent") {
        if let Some(percent) = crate::sensors::gpu_percent(&app.sensors) {
            values.insert("gpu_percent".to_string(), percent);
        }
    }
    values
}

/// Eine Pruefung: Werte lesen, Regeln anwenden, Zeitpunkt merken.
pub fn check_now(app: &App) -> Vec<Change> {
    let rules = {
        let guard = app.warnings.lock().expect("Warn-Sperre");
        guard.rules.clone()
    };
    let values = read_values(app, &rules);
    let stamp = stamp_now();
    let mut guard = app.warnings.lock().expect("Warn-Sperre");
    let changes = evaluate(&mut guard, &values, Instant::now(), &stamp);
    guard.checked_at = Some(stamp.time);
    changes
}

/// Antwort von `GET /warnings`: Regeln, aktive Warnungen, Verlauf.
pub fn describe(app: &App) -> Value {
    let guard = app.warnings.lock().expect("Warn-Sperre");
    json!({
        "rules": guard.rules.clone(),
        "active": guard.active.values().cloned().collect::<Vec<_>>(),
        "triggered": guard.history.clone(),
        "checked_at": guard.checked_at.clone(),
        "interval_s": CHECK_INTERVAL.as_secs(),
        "history_max": HISTORY_MAX,
    })
}

/// Inhalt von `warnings.json`.
#[derive(Serialize, Deserialize)]
struct WarningFile {
    #[serde(default = "default_version")]
    version: u32,
    rules: Vec<WarningRule>,
}

fn default_version() -> u32 {
    1
}

/// Laedt `warnings.json`. Fehlt die Datei oder ist sie kaputt, gelten die
/// Standardregeln; eine bewusst leere Liste bleibt leer.
pub fn load(path: &Path) -> WarningState {
    let rules = std::fs::read_to_string(path)
        .ok()
        .and_then(|text| serde_json::from_str::<WarningFile>(&text).ok())
        .map(|file| file.rules)
        .unwrap_or_else(default_rules);
    WarningState { rules, ..Default::default() }
}

/// Prueft und normalisiert die Regeln (Feldnamen sind der Vertrag der App).
pub fn validate(rules: Vec<WarningRule>) -> Result<Vec<WarningRule>, String> {
    if rules.len() > RULES_MAX {
        return Err(format!("Hoechstens {RULES_MAX} Regeln"));
    }
    let mut seen: HashSet<String> = HashSet::new();
    let mut out = Vec::with_capacity(rules.len());
    for mut rule in rules {
        rule.id = rule.id.trim().to_string();
        rule.label = rule.label.trim().to_string();
        rule.metric = rule.metric.trim().to_string();
        rule.op = rule.op.trim().to_string();
        if rule.id.is_empty()
            || rule.id.len() > 40
            || !rule
                .id
                .chars()
                .all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_')
        {
            return Err("Regel-ID fehlt oder enthaelt unerlaubte Zeichen".to_string());
        }
        if !seen.insert(rule.id.clone()) {
            return Err(format!("Regel '{}' ist doppelt", rule.id));
        }
        if !METRICS.contains(&rule.metric.as_str()) {
            return Err(format!("Messwert '{}' ist unbekannt", rule.metric));
        }
        if rule.op != ">" && rule.op != "<" {
            return Err(format!(
                "Vergleich '{}' ist unbekannt (erlaubt: >, <)",
                rule.op
            ));
        }
        if !rule.threshold.is_finite() || rule.threshold.abs() > 100_000.0 {
            return Err("Schwelle ist ungueltig".to_string());
        }
        if rule.duration_s > DURATION_MAX_S {
            return Err(format!("Dauer darf hoechstens {DURATION_MAX_S} s sein"));
        }
        if rule.label.is_empty() {
            rule.label = metric_label(&rule.metric).to_string();
        }
        out.push(rule);
    }
    Ok(out)
}

/// Speichert die Regeln und setzt Laufzeit-Zustand zurueck (frischer Start).
pub fn save(app: &App, rules: Vec<WarningRule>) -> Result<Vec<WarningRule>, String> {
    let rules = validate(rules)?;
    let file = WarningFile { version: default_version(), rules: rules.clone() };
    let json = serde_json::to_string_pretty(&file).map_err(|error| error.to_string())?;
    std::fs::write(&app.warnings_file, json).map_err(|error| error.to_string())?;
    {
        let mut guard = app.warnings.lock().expect("Warn-Sperre");
        guard.rules = rules.clone();
        guard.active.clear();
        guard.breached_since.clear();
        guard.history.clear();
        guard.checked_at = None;
    }
    Ok(rules)
}

/// Legt `warnings.json` beim ersten Start an (Standardregeln).
pub fn ensure_config(app: &App) {
    if !app.warnings_file.exists() {
        let _ = save(app, default_rules());
    }
}

/// Hintergrund-Schleife: prueft alle [`CHECK_INTERVAL`] und schreibt jede
/// ausgeloeste Warnung genau einmal ins Journal.
pub async fn run_loop(app: SharedApp) {
    let mut ticker = tokio::time::interval(CHECK_INTERVAL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        let worker = Arc::clone(&app);
        let changes = tokio::task::spawn_blocking(move || check_now(&worker))
            .await
            .unwrap_or_default();
        if changes.is_empty() {
            continue;
        }
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        for change in changes {
            match change {
                Change::Triggered(entry) => {
                    log_event(&app, &mut guard, "warnung", &entry.message, false)
                }
                Change::Cleared { id, label } => log_event(
                    &app,
                    &mut guard,
                    "warnung",
                    &format!("{label} wieder im Normalbereich ({id})"),
                    true,
                ),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(id: &str, metric: &str, op: &str, threshold: f64, duration_s: u64) -> WarningRule {
        WarningRule {
            id: id.to_string(),
            label: metric_label(metric).to_string(),
            metric: metric.to_string(),
            op: op.to_string(),
            threshold,
            duration_s,
            enabled: true,
        }
    }

    fn stamp() -> Stamp {
        Stamp { time: "2026-11-23 12:00:00".to_string(), epoch: 1_763_000_000.0 }
    }

    fn values(pairs: &[(&str, f64)]) -> BTreeMap<String, f64> {
        pairs
            .iter()
            .map(|(name, value)| (name.to_string(), *value))
            .collect()
    }

    #[test]
    fn vergleich_nach_operator() {
        assert!(breaches(">", 90.0, 90.5));
        assert!(!breaches(">", 90.0, 90.0));
        assert!(breaches("<", 10.0, 9.9));
        assert!(!breaches("<", 10.0, 10.0));
        // Unbekannter Operator loest nie aus.
        assert!(!breaches("!=", 0.0, 5.0));
    }

    #[test]
    fn warnung_erst_nach_der_dauer() {
        let mut state = WarningState {
            rules: vec![rule("cpu", "cpu_percent", ">", 90.0, 30)],
            ..Default::default()
        };
        let start = Instant::now();
        let high = values(&[("cpu_percent", 95.0)]);

        // Erste Beobachtung: ab hier laeuft die Dauer.
        assert!(evaluate(&mut state, &high, start, &stamp()).is_empty());
        assert!(state.active.is_empty());

        // 20 s spaeter: noch keine Warnung.
        assert!(evaluate(&mut state, &high, start + Duration::from_secs(20), &stamp()).is_empty());

        // 30 s nach der ersten Beobachtung: genau einmal ausloesen.
        let changes = evaluate(&mut state, &high, start + Duration::from_secs(30), &stamp());
        assert_eq!(changes.len(), 1);
        assert!(matches!(changes[0], Change::Triggered(_)));
        assert_eq!(state.history.len(), 1);
        assert_eq!(state.active.len(), 1);

        // 40 s: keine zweite Warnung, aber `since_s` waechst.
        assert!(evaluate(&mut state, &high, start + Duration::from_secs(40), &stamp()).is_empty());
        assert_eq!(state.active["cpu"].since_s, 40);
        assert_eq!(state.history.len(), 1);
    }

    #[test]
    fn kurzer_ausschlag_zaehlt_nicht() {
        let mut state = WarningState {
            rules: vec![rule("cpu", "cpu_percent", ">", 90.0, 30)],
            ..Default::default()
        };
        let start = Instant::now();
        let high = values(&[("cpu_percent", 95.0)]);
        let low = values(&[("cpu_percent", 20.0)]);
        assert!(evaluate(&mut state, &high, start, &stamp()).is_empty());
        assert!(evaluate(&mut state, &low, start + Duration::from_secs(20), &stamp()).is_empty());
        // Nach dem Rueckfall beginnt die Dauer von vorn.
        assert!(evaluate(&mut state, &high, start + Duration::from_secs(25), &stamp()).is_empty());
        assert!(evaluate(&mut state, &high, start + Duration::from_secs(45), &stamp()).is_empty());
    }

    #[test]
    fn rueckfall_hebt_warnung_auf() {
        let mut state = WarningState {
            rules: vec![rule("disk", "disk_free_percent", "<", 10.0, 0)],
            ..Default::default()
        };
        let start = Instant::now();
        let low = values(&[("disk_free_percent", 5.0)]);
        let ok = values(&[("disk_free_percent", 25.0)]);
        // Dauer 0: sofort ausloesen.
        let changes = evaluate(&mut state, &low, start, &stamp());
        assert_eq!(changes.len(), 1);
        let changes = evaluate(&mut state, &ok, start + Duration::from_secs(30), &stamp());
        assert_eq!(changes.len(), 1);
        assert!(matches!(changes[0], Change::Cleared { .. }));
        assert!(state.active.is_empty());
        // Der Verlauf bleibt erhalten (eine Benachrichtigung je Ereignis).
        assert_eq!(state.history.len(), 1);
    }

    #[test]
    fn fehlender_wert_loest_nicht_aus() {
        let mut state = WarningState {
            rules: vec![rule("gpu", "gpu_percent", ">", 50.0, 0)],
            ..Default::default()
        };
        let start = Instant::now();
        let empty = BTreeMap::new();
        assert!(evaluate(&mut state, &empty, start, &stamp()).is_empty());
        assert!(state.active.is_empty());
    }

    #[test]
    fn ausgeschaltete_regel_wird_ignoriert() {
        let mut disabled = rule("cpu", "cpu_percent", ">", 90.0, 0);
        disabled.enabled = false;
        let mut state = WarningState { rules: vec![disabled], ..Default::default() };
        let start = Instant::now();
        let high = values(&[("cpu_percent", 99.0)]);
        assert!(evaluate(&mut state, &high, start, &stamp()).is_empty());
    }

    #[test]
    fn verlauf_bleibt_begrenzt() {
        let mut state = WarningState {
            rules: vec![rule("cpu", "cpu_percent", ">", 90.0, 0)],
            ..Default::default()
        };
        state.history = (0..HISTORY_MAX)
            .map(|index| ActiveWarning {
                id: format!("alt-{index}"),
                label: "alt".to_string(),
                metric: "cpu_percent".to_string(),
                message: "alt".to_string(),
                value: 0.0,
                threshold: 0.0,
                op: ">".to_string(),
                duration_s: 0,
                since_s: 0,
                since: "2026-11-23 11:00:00".to_string(),
                since_epoch: index as f64,
            })
            .collect();
        let high = values(&[("cpu_percent", 99.0)]);
        assert_eq!(evaluate(&mut state, &high, Instant::now(), &stamp()).len(), 1);
        assert_eq!(state.history.len(), HISTORY_MAX);
        assert_eq!(state.history[0].id, "cpu");
    }

    #[test]
    fn validierung_prueft_messwert_und_operator() {
        let bad_metric = WarningRule {
            id: "x".to_string(),
            metric: "luftfeuchte".to_string(),
            ..Default::default()
        };
        assert!(validate(vec![bad_metric]).is_err());

        let bad_op = WarningRule {
            id: "x".to_string(),
            op: "!=".to_string(),
            ..Default::default()
        };
        assert!(validate(vec![bad_op]).is_err());

        let duplicate = rule("x", "cpu_percent", ">", 90.0, 0);
        assert!(validate(vec![duplicate.clone(), duplicate]).is_err());

        let bad_id = WarningRule {
            id: "../cpu".to_string(),
            ..Default::default()
        };
        assert!(validate(vec![bad_id]).is_err());

        let too_long = rule("x", "ram_percent", ">", 90.0, DURATION_MAX_S + 1);
        assert!(validate(vec![too_long]).is_err());
    }

    #[test]
    fn validierung_ergaenzt_fehlenden_namen() {
        let mut incomplete = rule("ram", "ram_percent", ">", 90.0, 60);
        incomplete.label = "  ".to_string();
        let rules = validate(vec![incomplete]).unwrap();
        assert_eq!(rules[0].label, "Arbeitsspeicher");
    }

    #[test]
    fn standardregeln_sind_gueltig() {
        let rules = validate(default_rules()).unwrap();
        assert_eq!(rules.len(), 3);
        assert!(rules.iter().all(|rule| !rule.label.is_empty()));
    }
}





