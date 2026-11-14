//! Gemeinsame Ausfuehrung der PC-Aktionen (Phase 4).
//!
//! HTTP-Endpunkte und Routinen nutzen dieselben Funktionen. Dadurch verhalten
//! sich Simulation, Journal und `pending_*` in beiden Faellen identisch – es
//! gibt nur eine Stelle, an der `shutdown`/`powercfg` aufgerufen wird.

use std::time::{SystemTime, UNIX_EPOCH};

use crate::app::App;
use crate::commands::{run, PLANS};
use crate::journal::log_event;
use crate::routines::RoutineAction;

/// Aktueller Unix-Zeitpunkt in Sekunden (wie `time.time()`).
fn now_epoch() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

fn simulate(app: &App) -> bool {
    app.inner.lock().expect("Zustands-Sperre").simulate
}

/// Ist der Plan-Name bekannt (`high`/`balanced`/`saver`)?
pub fn plan_known(name: &str) -> bool {
    PLANS.iter().any(|(key, _)| *key == name)
}

/// Herunterfahren planen (`shutdown /s /t`).
pub async fn perform_shutdown(app: &App, delay: u32) -> Result<String, String> {
    let simulate = simulate(app);
    let delay_text = delay.to_string();
    let (code, out) = tokio::task::spawn_blocking(move || {
        run(&["shutdown", "/s", "/t", &delay_text], simulate, false)
    })
    .await
    .unwrap_or((-1, String::new()));

    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    if code != 0 {
        let problem = if out.is_empty() { "Fehler".to_string() } else { out.clone() };
        log_event(app, &mut guard, "shutdown", &problem, false);
        return Err(if out.is_empty() {
            "shutdown fehlgeschlagen".to_string()
        } else {
            out
        });
    }
    guard.pending_at = Some(now_epoch() + delay as f64);
    guard.pending_seconds = delay as i64;
    let info = if delay == 0 {
        "sofort".to_string()
    } else {
        format!("in {delay}s")
    };
    log_event(app, &mut guard, "shutdown", &info, true);
    Ok(if out.is_empty() {
        format!("Herunterfahren in {delay}s")
    } else {
        out
    })
}

/// Ruhezustand (`shutdown /h`).
pub async fn perform_hibernate(app: &App) -> Result<String, String> {
    let simulate = simulate(app);
    let (code, out) =
        tokio::task::spawn_blocking(move || run(&["shutdown", "/h"], simulate, false))
            .await
            .unwrap_or((-1, String::new()));

    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    let state_text = if out.is_empty() {
        "Ruhezustand".to_string()
    } else {
        out.clone()
    };
    log_event(app, &mut guard, "hibernate", &state_text, code == 0);
    if code != 0 {
        return Err(if out.is_empty() {
            "Ruhezustand fehlgeschlagen".to_string()
        } else {
            out
        });
    }
    Ok(if out.is_empty() { "Ruhezustand".to_string() } else { out })
}

/// Energieplan wechseln (`powercfg /setactive`).
pub async fn perform_power_plan(app: &App, name: &str) -> Result<String, String> {
    let Some(plan_guid) = PLANS.iter().find(|(key, _)| *key == name).map(|(_, guid)| *guid) else {
        return Err("Ungueltiger Energieplan".to_string());
    };
    let simulate = simulate(app);
    let (code, out) = tokio::task::spawn_blocking(move || {
        run(&["powercfg", "/setactive", plan_guid], simulate, false)
    })
    .await
    .unwrap_or((-1, String::new()));

    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(
        app,
        &mut guard,
        "power-plan",
        format!("{name} {out}").trim(),
        code == 0,
    );
    if code != 0 {
        return Err(if out.is_empty() {
            "powercfg fehlgeschlagen".to_string()
        } else {
            out
        });
    }
    if simulate {
        guard.sim_plan = Some(name.to_string());
    }
    Ok(out)
}

/// Eine Routine-Aktion ausfuehren.
pub async fn perform(app: &App, action: &RoutineAction) -> Result<String, String> {
    match action.kind.as_str() {
        "shutdown" => perform_shutdown(app, action.delay).await,
        "hibernate" => perform_hibernate(app).await,
        "power_plan" => perform_power_plan(app, &action.plan).await,
        other => Err(format!("Unbekannte Aktion '{other}'")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bekannte_plaene_werden_erkannt() {
        assert!(plan_known("high"));
        assert!(plan_known("balanced"));
        assert!(plan_known("saver"));
        assert!(!plan_known("turbo"));
        assert!(!plan_known(""));
    }
}
