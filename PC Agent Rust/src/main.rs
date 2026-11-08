//! PC-Agent in Rust: schaltet einen Windows-PC im Heimnetz.
//!
//! Start:   pc-agent.exe   (oder `cargo run --release`)
//! Zusage:  Verhaelt sich wie der FastAPI-Agent:
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
mod journal;
mod page;
mod routes;
mod wol;

use std::net::SocketAddr;
use std::path::PathBuf;

use routes::SharedApp;

fn main() {
    let token = std::env::var("PC_AGENT_TOKEN").unwrap_or_default();
    let simulate = std::env::var("PC_AGENT_SIMULATE")
        .map(|value| value != "0")
        .unwrap_or(true);
    // Optionaler Port (Standard 8000 wie im Python-Agent). Praktisch, wenn der
    // Python-Agent parallel laeuft oder fuer einen Test auf einem anderen Port.
    let port = std::env::var("PC_AGENT_PORT")
        .ok()
        .and_then(|value| value.parse::<u16>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(8000);

    // Wie der Python-Agent: Dateien neben der Ausfuehrungs-EXE.
    let base_dir = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."));
    let shared: SharedApp = std::sync::Arc::new(app::App::new(token.clone(), simulate, &base_dir));

    let credentials_missing = {
        let guard = shared.inner.lock().expect("Zustands-Sperre");
        guard.credentials.is_none()
    };
    if credentials_missing && token.is_empty() {
        println!("Hinweis: Noch keine Zugangsdaten eingerichtet.");
        println!("Beim ersten Start die Web-Oberflaeche oeffnen: http://localhost:8000/");
    }

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio-Laufzeitumgebung");
    runtime.block_on(run_server(shared, simulate, port));
}

async fn run_server(shared: SharedApp, simulate: bool, port: u16) {
    let router = axum::Router::new()
        .route("/", axum::routing::get(routes::index))
        .route("/setup-status", axum::routing::get(routes::setup_status))
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
        .fallback(routes::not_found)
        .method_not_allowed_fallback(routes::method_not_allowed)
        .with_state(shared);

    // Gleiche Bindung wie der Python-Agent (uvicorn auf 0.0.0.0:8000);
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
