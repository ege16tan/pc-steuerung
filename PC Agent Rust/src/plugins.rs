//! Plugin-Host des Agenten.
//!
//! Ein Plugin ist ein eigenstaendiges Hilfsprogramm (z. B. `game-optimizer.exe`),
//! das der Agent **nur auf Anforderung** startet und danach wieder beendet.
//! Nichts laeuft dauerhaft mit – der Agent gibt das Signal, das Plugin arbeitet.
//!
//! Aufbau: `<EXE-Ordner>\plugins\*.json` (oder `<Datenordner>\plugins\*.json`)
//! beschreibt ein Plugin:
//!
//! ```json
//! { "id": "game-optimizer", "name": "Game Optimization",
//!   "description": "...", "version": "1", "bin": "game-optimizer.exe", "args": [] }
//! ```
//!
//! Fehlt das Manifest, greift fuer `game-optimizer` eine eingebaute Definition,
//! die `game-optimizer.exe` neben der Agenten-EXE sucht.

use std::collections::{HashMap, VecDeque};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::app::App;
use crate::journal::log_event;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Windows-Schalter, damit kein Konsolenfenster aufblinkt.
#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// So viele Ausgabezeilen werden je Plugin aufbewahrt.
pub const OUTPUT_MAX: usize = 300;

/// ID des eingebauten Plugins (Game Optimization).
pub const BUILTIN_GAME_OPTIMIZER: &str = "game-optimizer";

/// Manifest eines Plugins.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Manifest {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_version")]
    pub version: String,
    /// Dateiname relativ zum Plugin-Ordner, zur EXE oder absolut.
    pub bin: String,
    #[serde(default)]
    pub args: Vec<String>,
}

fn default_version() -> String {
    "1".to_string()
}

/// Ein laufendes Plugin samt Kindprozess und Eingabe-Kanal (fuer "stop").
pub struct PluginRun {
    child: Child,
    stdin: Option<std::process::ChildStdin>,
    pub started_at: f64,
    pub game: Option<String>,
    pub auto: bool,
}

/// Plugin-Zustand (alles ueber den Mutex geschuetzt).
#[derive(Default)]
pub struct PluginState {
    runs: HashMap<String, PluginRun>,
    output: HashMap<String, VecDeque<String>>,
}

fn now_epoch() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

/// Sucht Manifeste: erst neben der EXE, dann im Datenordner. Der erste Treffer
/// je ID gewinnt; ohne Manifest gibt es die eingebaute Game-Optimization-Definition.
pub fn manifests(app: &App) -> Vec<Manifest> {
    let mut out: Vec<Manifest> = Vec::new();
    for dir in [app.exe_dir.join("plugins"), app.data_dir.join("plugins")] {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) != Some("json") {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(&path) else {
                continue;
            };
            let Ok(manifest) = serde_json::from_str::<Manifest>(&text) else {
                continue;
            };
            if manifest.id.trim().is_empty() || out.iter().any(|known| known.id == manifest.id) {
                continue;
            }
            out.push(manifest);
        }
    }
    if !out.iter().any(|known| known.id == BUILTIN_GAME_OPTIMIZER) {
        out.push(Manifest {
            id: BUILTIN_GAME_OPTIMIZER.to_string(),
            name: "Game Optimization".to_string(),
            description:
                "Optimiert den PC fuer ein Spiel: Energieplan, Hintergrund-Apps, Arbeitsspeicher."
                    .to_string(),
            version: "1".to_string(),
            bin: "game-optimizer.exe".to_string(),
            args: Vec::new(),
        });
    }
    out.sort_by_key(|manifest| manifest.name.to_lowercase());
    out
}

/// Loest den Programmnamen des Plugins auf (Plugin-Ordner, Datenordner, EXE-Ordner).
fn resolve_bin(app: &App, manifest: &Manifest) -> Option<PathBuf> {
    let bin = PathBuf::from(&manifest.bin);
    if bin.is_absolute() {
        return bin.is_file().then_some(bin);
    }
    for dir in [
        app.exe_dir.join("plugins"),
        app.data_dir.join("plugins"),
        app.exe_dir.clone(),
    ] {
        let candidate = dir.join(&bin);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

/// Eine Zeile Plugin-Ausgabe aufnehmen (Ringpuffer je Plugin).
pub fn push_line(app: &App, id: &str, line: &str) {
    let mut state = app.plugins.lock().expect("Plugin-Sperre");
    let buffer = state.output.entry(id.to_string()).or_default();
    buffer.push_back(line.to_string());
    while buffer.len() > OUTPUT_MAX {
        buffer.pop_front();
    }
}

/// Zustand aller Plugins fuer `GET /plugins`.
pub fn describe(app: &Arc<App>) -> Vec<Value> {
    let manifests = manifests(app);
    let mut state = app.plugins.lock().expect("Plugin-Sperre");
    let mut out = Vec::with_capacity(manifests.len());
    for manifest in manifests {
        let mut running = false;
        let mut pid: Option<u32> = None;
        let mut uptime = 0i64;
        let mut game: Option<String> = None;
        let mut auto = false;

        // Fertige Prozesse aufraeumen (nicht mehr als "laeuft" melden).
        let finished = match state.runs.get_mut(&manifest.id) {
            None => None,
            Some(run) => match run.child.try_wait() {
                Ok(Some(status)) => Some(status.code()),
                Ok(None) => {
                    running = true;
                    pid = Some(run.child.id());
                    uptime = (now_epoch() - run.started_at).max(0.0) as i64;
                    game = run.game.clone();
                    auto = run.auto;
                    None
                }
                Err(_) => Some(None),
            },
        };
        if let Some(code) = finished {
            state.runs.remove(&manifest.id);
            let buffer = state.output.entry(manifest.id.clone()).or_default();
            buffer.push_back(match code {
                Some(code) => format!("> beendet (Code {code})"),
                None => "> beendet".to_string(),
            });
            while buffer.len() > OUTPUT_MAX {
                buffer.pop_front();
            }
        }

        let available = resolve_bin(app, &manifest).is_some();
        let tail: Vec<String> = state
            .output
            .get(&manifest.id)
            .map(|buffer| {
                let skip = buffer.len().saturating_sub(8);
                buffer.iter().skip(skip).cloned().collect()
            })
            .unwrap_or_default();
        out.push(json!({
            "id": manifest.id,
            "name": manifest.name,
            "description": manifest.description,
            "version": manifest.version,
            "available": available,
            "running": running,
            "pid": pid,
            "uptime": uptime,
            "game": game,
            "auto": auto,
            "tail": tail,
        }));
    }
    out
}

/// Startet ein Plugin. `simulate` wird durchgereicht: im Simulationsmodus
/// fuehrt das Plugin nichts aus, sondern protokolliert nur.
pub fn start(
    app: &Arc<App>,
    id: &str,
    game: Option<String>,
    auto: bool,
    simulate: bool,
) -> Result<Value, String> {
    let manifest = manifests(app)
        .into_iter()
        .find(|manifest| manifest.id == id)
        .ok_or_else(|| format!("Plugin '{id}' ist unbekannt"))?;
    let bin = resolve_bin(app, &manifest)
        .ok_or_else(|| format!("Plugin-Datei '{}' wurde nicht gefunden", manifest.bin))?;
    {
        let state = app.plugins.lock().expect("Plugin-Sperre");
        if state.runs.contains_key(&manifest.id) {
            return Err(format!("Plugin '{}' laeuft bereits", manifest.id));
        }
    }

    let mut command = Command::new(&bin);
    command.args(&manifest.args);
    command.arg("--data").arg(&app.data_dir);
    if let Some(game) = game.as_deref() {
        command.arg("--game").arg(game);
    }
    if auto {
        command.arg("--auto");
    }
    if simulate {
        command.arg("--simulate");
    }
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);

    let mut child = command
        .spawn()
        .map_err(|error| format!("Plugin konnte nicht gestartet werden: {error}"))?;
    let stdin = child.stdin.take();
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let pid = child.id();
    let started_at = now_epoch();

    {
        let mut state = app.plugins.lock().expect("Plugin-Sperre");
        state.output.insert(manifest.id.clone(), VecDeque::new());
        state.runs.insert(
            manifest.id.clone(),
            PluginRun {
                child,
                stdin,
                started_at,
                game: game.clone(),
                auto,
            },
        );
    }
    if let Some(stdout) = stdout {
        spawn_reader(Arc::clone(app), manifest.id.clone(), stdout);
    }
    if let Some(stderr) = stderr {
        spawn_reader(Arc::clone(app), manifest.id.clone(), stderr);
    }

    let detail = match game.as_deref() {
        Some(game) => format!("{} gestartet (pid {pid}, Spiel {game})", manifest.id),
        None => format!("{} gestartet (pid {pid})", manifest.id),
    };
    {
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        log_event(app, &mut guard, "plugin", &detail, true);
    }

    Ok(json!({
        "ok": true,
        "id": manifest.id,
        "pid": pid,
        "game": game,
        "auto": auto,
        "simulate": simulate,
    }))
}

/// Liest die Ausgabe eines Plugins zeilenweise in den Ringpuffer und ins Journal.
fn spawn_reader<R: Read + Send + 'static>(app: Arc<App>, id: String, source: R) {
    std::thread::spawn(move || {
        for line in BufReader::new(source).lines() {
            let Ok(line) = line else {
                break;
            };
            let line = line.trim_end().to_string();
            if line.is_empty() {
                continue;
            }
            push_line(&app, &id, &line);
            // Nur Ergebniszeilen ins Journal, damit das Log nicht zumuellt.
            if line.starts_with('>') {
                let mut guard = app.inner.lock().expect("Zustands-Sperre");
                log_event(
                    &app,
                    &mut guard,
                    "plugin",
                    line.trim_start_matches('>').trim(),
                    true,
                );
            }
        }
    });
}

/// Nimmt einen laufenden Plugin-Prozess aus der Verwaltung (fuer `/stop`).
pub fn take_run(app: &App, id: &str) -> Result<PluginRun, String> {
    let mut state = app.plugins.lock().expect("Plugin-Sperre");
    state
        .runs
        .remove(id)
        .ok_or_else(|| format!("Plugin '{id}' laeuft nicht"))
}

/// Bittet das Plugin um ein sauberes Ende ("stop" auf stdin) und greift nach
/// drei Sekunden hart ein. Blockiert – deshalb im Aufrufer via spawn_blocking.
pub fn stop_run(mut run: PluginRun) -> String {
    if let Some(stdin) = run.stdin.as_mut() {
        let _ = writeln!(stdin, "stop");
        let _ = stdin.flush();
    }
    for _ in 0..30 {
        match run.child.try_wait() {
            Ok(Some(status)) => {
                return match status.code() {
                    Some(code) => format!("beendet (Code {code})"),
                    None => "beendet".to_string(),
                }
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(100)),
            Err(error) => return format!("Statusfehler: {error}"),
        }
    }
    let _ = run.child.kill();
    let _ = run.child.wait();
    "hart beendet".to_string()
}

/// Letzte Ausgabezeilen eines Plugins (fuer `GET /plugins/{id}/output`).
pub fn output(app: &App, id: &str, lines: usize) -> String {
    let state = app.plugins.lock().expect("Plugin-Sperre");
    let Some(buffer) = state.output.get(id) else {
        return String::new();
    };
    let skip = buffer.len().saturating_sub(lines);
    let mut text = buffer
        .iter()
        .skip(skip)
        .cloned()
        .collect::<Vec<_>>()
        .join("\n");
    if !text.is_empty() {
        text.push('\n');
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_app(name: &str) -> (Arc<App>, PathBuf) {
        let dir = std::env::temp_dir().join(format!("pcagent-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let app: Arc<App> = Arc::new(App::new(String::new(), true, &dir, 8000));
        (app, dir)
    }

    #[test]
    fn eingebautes_plugin_ist_immer_bekannt() {
        let (app, dir) = test_app("plugins-builtin");
        let list = manifests(&app);
        assert!(list.iter().any(|m| m.id == BUILTIN_GAME_OPTIMIZER));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn manifest_aus_plugin_ordner_doppelt_nicht() {
        let (app, dir) = test_app("plugins-manifest");
        let plugin_dir = dir.join("plugins");
        std::fs::create_dir_all(&plugin_dir).unwrap();
        std::fs::write(
            plugin_dir.join("game-optimizer.json"),
            r#"{"id":"game-optimizer","name":"Game Optimization","bin":"game-optimizer.exe"}"#,
        )
        .unwrap();
        let list = manifests(&app);
        let found = list
            .iter()
            .filter(|m| m.id == BUILTIN_GAME_OPTIMIZER)
            .count();
        assert_eq!(found, 1, "eingebaute Definition darf das Manifest nicht doppeln");
        assert_eq!(list[0].version, "1");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn start_meldet_unbekanntes_plugin() {
        let (app, dir) = test_app("plugins-unknown");
        let error = start(&app, "gibt-es-nicht", None, false, true).unwrap_err();
        assert!(error.contains("unbekannt"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn start_ohne_datei_meldet_fehlendes_programm() {
        let (app, dir) = test_app("plugins-missing-bin");
        let error = start(&app, BUILTIN_GAME_OPTIMIZER, None, false, true).unwrap_err();
        assert!(error.contains("nicht gefunden"), "unerwartet: {error}");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}

