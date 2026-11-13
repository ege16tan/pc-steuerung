//! PC-Agent in Rust: schaltet einen Windows-PC im Heimnetz.
//!
//! Start:   pc-agent.exe   (oder `cargo run --release`)
//! Zusage:
//!          - Zugangsdaten in der Web-Oberflaeche (http://localhost:8000/)
//!            beim ersten Start einrichten (nur am PC moeglich).
//!          - Lokale Zugriffe (PC selbst) brauchen keinen Login.
//!          - PC_AGENT_TOKEN  (Fallback-Schutz, bleibt kompatibel)
//!          - PC_AGENT_SIMULATE=1 (Standard) -> es wird nichts ausgefuehrt,
//!            sondern nur gelogt; /mode?simulate=true|false schaltet um.
//!          - PC_WOL_Broadcast: Umgebungsvariable PC_WOL_BROADCAST fuer /wake.
//!          - PC_AGENT_PORT (optional, Standard 8000) fuer Tests/Parallellauf.

mod app;
mod auth;
mod commands;
mod games;
mod journal;
mod page;
mod pairing;
mod plugins;
mod routes;
mod wol;

use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use routes::SharedApp;

fn main() {
    let token = std::env::var("PC_AGENT_TOKEN").unwrap_or_default();
    let simulate = std::env::var("PC_AGENT_SIMULATE")
        .map(|value| value != "0")
        .unwrap_or(true);
    // Optionaler Port (Standard 8000). Praktisch fuer Tests auf einem
    // anderen Port oder wenn Port 8000 belegt ist.
    let port = std::env::var("PC_AGENT_PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(8000);

    // Zugangsdaten, Token, Geraeteeinstellungen und Log liegen zentral unter
    // %LOCALAPPDATA%\PC Steuerung. `PC_AGENT_DATA` erlaubt einen eigenen Ordner.
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."));
    let data_dir = app_data_dir(&exe_dir);
    if let Err(error) = std::fs::create_dir_all(&data_dir) {
        eprintln!("Warnung: Datenordner konnte nicht erstellt werden: {error}");
    }
    migrate_legacy_data(&exe_dir, &data_dir);
    let shared: SharedApp =
        std::sync::Arc::new(app::App::new(token.clone(), simulate, &data_dir, port));
    // Spiel-Optimierung: Standardprofil einmal anlegen, damit Plugin und
    // Web-Oberflaeche dieselbe Kill-Liste sehen.
    games::ensure_config(shared.as_ref());

    let credentials_missing = {
        let guard = shared.inner.lock().expect("Zustands-Sperre");
        guard.credentials.is_none()
    };
    if credentials_missing && token.is_empty() {
        println!("Hinweis: Noch keine Zugangsdaten eingerichtet.");
        println!("Beim ersten Start die Web-Oberflaeche oeffnen: http://localhost:{port}/");
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio-Laufzeitumgebung");
    runtime.block_on(run_server(shared, simulate, port));
}

/// Zentraler Datenordner: `%LOCALAPPDATA%\PC Steuerung`.
/// `PC_AGENT_DATA` (absolut oder relativ zur EXE) hat Vorrang.
fn app_data_dir(exe_dir: &Path) -> PathBuf {
    if let Some(custom) = std::env::var_os("PC_AGENT_DATA") {
        let path = PathBuf::from(custom);
        return if path.is_absolute() {
            path
        } else {
            exe_dir.join(path)
        };
    }
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .map(|local| local.join("PC Steuerung"))
        .unwrap_or_else(|| exe_dir.to_path_buf())
}

/// Uebernimmt Daten aus alten Ablageorten (`PC App/`, neben der EXE) einmalig.
/// Vorhandene Ziele werden nur ersetzt, wenn die Quelle neuer ist; das Log
/// bleibt erhalten, sobald es am neuen Ort existiert.
fn migrate_legacy_data(exe_dir: &Path, data_dir: &Path) {
    let mut sources: Vec<PathBuf> = exe_dir
        .ancestors()
        .map(|base| base.join("PC App"))
        .collect();
    sources.push(exe_dir.to_path_buf());

    for name in ["credentials.json", "token.json", "device.json", "agent.log"] {
        let destination = data_dir.join(name);
        if name == "agent.log" && destination.is_file() {
            continue;
        }
        let newest = sources
            .iter()
            .map(|dir| dir.join(name))
            .filter(|path| path.is_file() && path != &destination)
            .filter_map(|path| {
                let modified = std::fs::metadata(&path).and_then(|m| m.modified()).ok()?;
                Some((modified, path))
            })
            .max_by_key(|(modified, _)| *modified);
        let Some((source_time, source)) = newest else {
            continue;
        };
        let should_copy = if destination.is_file() {
            let target_time = std::fs::metadata(&destination)
                .and_then(|m| m.modified())
                .ok();
            matches!(target_time, Some(target) if source_time > target)
        } else {
            true
        };
        if should_copy {
            let _ = std::fs::copy(source, &destination);
        }
    }
}

async fn run_server(shared: SharedApp, simulate: bool, port: u16) {
    let router = axum::Router::new()
        .route("/", axum::routing::get(routes::index))
        .route("/app.css", axum::routing::get(routes::app_css))
        .route("/app.js", axum::routing::get(routes::app_js))
        .route("/setup-status", axum::routing::get(routes::setup_status))
        .route("/network-adapters", axum::routing::get(routes::network_adapters_ep))
        .route("/device-setup", axum::routing::post(routes::device_setup))
        .route("/pairing-info", axum::routing::get(routes::pairing_info))
        .route("/pairing-qr.svg", axum::routing::get(routes::pairing_qr))
        .route("/setup", axum::routing::post(routes::setup))
        .route("/mode", axum::routing::post(routes::set_mode))
        .route("/status", axum::routing::get(routes::status_ep))
        .route("/shutdown", axum::routing::post(routes::shutdown_ep))
        .route("/abort", axum::routing::post(routes::abort_ep))
        .route("/hibernate", axum::routing::post(routes::hibernate_ep))
        .route("/power-plan", axum::routing::post(routes::power_plan_ep))
        .route("/wake", axum::routing::post(routes::wake_ep))
        .route("/ping", axum::routing::post(routes::ping_ep))
        .route("/events", axum::routing::get(routes::events_ep))
        .route("/log", axum::routing::get(routes::log_ep))
        .route("/plugins", axum::routing::get(routes::plugins_ep))
        .route("/plugins/{id}/start", axum::routing::post(routes::plugin_start))
        .route("/plugins/{id}/stop", axum::routing::post(routes::plugin_stop))
        .route("/plugins/{id}/output", axum::routing::get(routes::plugin_output))
        .route("/games", axum::routing::get(routes::games_ep))
        .route("/games/scan", axum::routing::post(routes::games_scan_ep))
        .route(
            "/game-config",
            axum::routing::get(routes::game_config_get).post(routes::game_config_set),
        )
        .fallback(routes::not_found)
        .method_not_allowed_fallback(routes::method_not_allowed)
        // CSRF: schreibende Anfragen von localhost brauchen die Kopfzeile
        // `X-PC-Steuerung: 1` (die Web-Oberfläche sendet sie immer).
        .layer(axum::middleware::from_fn(routes::csrf_guard))
        .with_state(shared);

    // Bindung an 0.0.0.0:8000, damit das Handy im LAN zugreifen kann;
    // die Windows-Firewall fragt beim ersten Start einmalig nach.
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port))
        .await
        .expect("Port binden");
    println!(
        "PC-Agent laeuft auf http://0.0.0.0:{port} (SIMULATION={})",
        simulate
    );
    axum::serve(
        listener,
        router.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .expect("HTTP-Server laufen lassen");
}
