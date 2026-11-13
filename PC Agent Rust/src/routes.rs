//! HTTP-Endpunkte des Agenten:
//! GET /, GET /setup-status, POST /setup,
//! POST /mode, GET /status,
//! POST /shutdown, POST /abort, POST /hibernate, POST /power-plan,
//! POST /wake, POST /ping, GET /events, GET /log,
//! GET /plugins, POST /plugins/{id}/start, POST /plugins/{id}/stop,
//! GET /plugins/{id}/output,
//! GET /games, POST /games/scan, GET/POST /game-config.

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::StatusCode;
use axum::http::header::{CACHE_CONTROL, CONTENT_TYPE};
use axum::response::{Html, IntoResponse, Json, Response};
use serde::Deserialize;
use serde_json::json;

use crate::app::{remaining_seconds, App, DeviceSettings, TokenCredentials};
use crate::auth::{create_credentials, Auth};
use crate::commands::{detect_active_plan, run, PLANS};
use crate::journal::{log_event, tail_lines};
use crate::page::{APP_CSS, APP_JS, PAGE};
use crate::pairing::{lan_ipv4, network_adapters, payload_json};
use crate::wol::{configured_broadcast, parse_mac, send_wol};

pub type SharedApp = Arc<App>;

/// Aktueller Unix-Zeitpunkt in Sekunden (wie time.time()).
fn now_epoch() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs_f64()
}

fn error_response(status: StatusCode, detail: String) -> Response {
    (status, Json(json!({ "detail": detail }))).into_response()
}

/// 404 wie FastAPI: `{"detail":"Not Found"}` statt leerer Antwort.
pub async fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, "Not Found".to_string())
}

/// 405 wie FastAPI: `{"detail":"Method Not Allowed"}`.
pub async fn method_not_allowed() -> Response {
    error_response(
        StatusCode::METHOD_NOT_ALLOWED,
        "Method Not Allowed".to_string(),
    )
}

fn first_query<'a>(query: &'a [(String, String)], name: &str) -> Option<&'a str> {
    // Starlette QueryParams.get liefert bei mehrfachen Schluesseln den letzten Wert.
    query
        .iter()
        .rev()
        .find(|(key, _)| key == name)
        .map(|(_, value)| value.as_str())
}

/// Schnappschuss aus dem Zustands-Mutex (kleine kritische Abschnitte).
struct Snapshot {
    simulate: bool,
    sim_plan: Option<String>,
    pending_at: Option<f64>,
    credentials: Option<crate::app::Credentials>,
}

fn snapshot(app: &App) -> Snapshot {
    let guard = app.inner.lock().expect("Zustands-Sperre");
    Snapshot {
        simulate: guard.simulate,
        sim_plan: guard.sim_plan.clone(),
        pending_at: guard.pending_at,
        credentials: guard.credentials.clone(),
    }
}

/// Requestbody fuer /setup (wie BaseModel SetupRequest im Python-Agent).
#[derive(Debug, Deserialize)]
pub struct SetupRequest {
    #[serde(default = "default_auth_mode")]
    pub auth_mode: String,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub password: String,
}

fn default_auth_mode() -> String { "basic".to_string() }

#[derive(Debug, Deserialize)]
pub struct DeviceSetupRequest {
    pub name: String,
    #[serde(default)]
    pub wol_enabled: bool,
    #[serde(default)]
    pub mac: String,
}

/// Pydantic akzeptiert true/false/1/0/yes/no/on/off (Gross-/Kleinschreibung egal).
pub fn parse_query_bool(value: &str) -> Option<bool> {
    match value.to_ascii_lowercase().as_str() {
        "true" | "1" | "yes" | "on" => Some(true),
        "false" | "0" | "no" | "off" => Some(false),
        _ => None,
    }
}

// ---------------- Endpunkte ohne Anmeldung ----------------

pub async fn index() -> Response {
    let mut response = Html(PAGE).into_response();
    response.headers_mut().insert(
        CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    response
}

/// GET /app.css — Stylesheet der Web-Oberfläche (ohne Login, wie `/`).
pub async fn app_css() -> Response {
    static_asset(APP_CSS, "text/css; charset=utf-8")
}

/// GET /app.js — Skript der Web-Oberfläche (ohne Login, wie `/`).
pub async fn app_js() -> Response {
    static_asset(APP_JS, "application/javascript; charset=utf-8")
}

fn static_asset(body: &'static str, mime: &'static str) -> Response {
    let mut response = body.into_response();
    response.headers_mut().insert(
        CONTENT_TYPE,
        axum::http::HeaderValue::from_static(mime),
    );
    response.headers_mut().insert(
        CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    response
}

// ---------------- CSRF-Schutz fuer localhost ----------------

/// Kopfzeile, die die Web-Oberflaeche bei jeder schreibenden Anfrage mitschickt.
/// `localhost` verlangt keine Anmeldung – ohne diese Pruefung koennte eine fremde
/// Webseite im Browser `POST /shutdown` an `127.0.0.1` ausloesen.
pub const CSRF_HEADER: &str = "x-pc-steuerung";

fn is_mutating(method: &axum::http::Method) -> bool {
    matches!(
        *method,
        axum::http::Method::POST
            | axum::http::Method::PUT
            | axum::http::Method::PATCH
            | axum::http::Method::DELETE
    )
}

/// Prueft schreibende Anfragen von `localhost` auf die CSRF-Kopfzeile.
/// Zugriffe aus dem LAN bleiben unveraendert (dort greift Basic Auth).
pub async fn csrf_guard(
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    if is_mutating(request.method()) && addr.ip().is_loopback() {
        let ok = request
            .headers()
            .get(CSRF_HEADER)
            .and_then(|value| value.to_str().ok())
            .map(|text| text == "1" || text.eq_ignore_ascii_case("true"))
            .unwrap_or(false);
        if !ok {
            return error_response(
                StatusCode::FORBIDDEN,
                format!("CSRF-Schutz: Kopfzeile '{CSRF_HEADER}: 1' fehlt"),
            );
        }
    }
    next.run(request).await
}

pub async fn setup_status(State(app): State<SharedApp>) -> Response {
    let required = {
        let guard = app.inner.lock().expect("Zustands-Sperre");
        guard.credentials.is_none() && guard.token_credentials.is_none() && app.token.is_empty()
    };
    Json(json!({ "required": required })).into_response()
}

/// GET /pairing-info — QR-Inhalt ohne Geheimnisse fuer das Handy.
pub async fn pairing_info(State(app): State<SharedApp>) -> Response {
    let Some(host) = lan_ipv4() else {
        return Json(json!({
            "ready": false,
            "message": "Keine LAN-IPv4-Adresse erkannt."
        }))
        .into_response();
    };
    let settings = app.device_settings.lock().expect("Setup-Sperre").clone();
    let name = if settings.name.trim().is_empty() {
        std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".to_string())
    } else { settings.name.clone() };
    let mac = if settings.wol_enabled { Some(settings.mac.as_str()) } else { None };
    let payload = payload_json(&host, app.port, &name, mac);
    Json(json!({
        "ready": true,
        "host": host,
        "port": app.port,
        "name": name,
        "wol_enabled": settings.wol_enabled,
        "mac": mac,
        "payload": payload,
    }))
    .into_response()
}

/// GET /pairing-qr.svg — QR-Code als SVG; enthaelt nur Name, IP und Port.
pub async fn pairing_qr(State(app): State<SharedApp>) -> Response {
    let Some(host) = lan_ipv4() else {
        return error_response(
            StatusCode::SERVICE_UNAVAILABLE,
            "Keine LAN-IPv4-Adresse erkannt".to_string(),
        );
    };
    let settings = app.device_settings.lock().expect("Setup-Sperre").clone();
    let name = if settings.name.trim().is_empty() {
        std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".to_string())
    } else { settings.name.clone() };
    let mac = if settings.wol_enabled { Some(settings.mac.as_str()) } else { None };
    let payload = payload_json(&host, app.port, &name, mac);
    let Ok(code) = qrcode::QrCode::new(payload.as_bytes()) else {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            "QR-Code konnte nicht erstellt werden".to_string(),
        );
    };
    let svg = code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(256, 256)
        .build();
    let mut response = svg.into_response();
    response.headers_mut().insert(
        CONTENT_TYPE,
        axum::http::HeaderValue::from_static("image/svg+xml; charset=utf-8"),
    );
    response.headers_mut().insert(
        CACHE_CONTROL,
        axum::http::HeaderValue::from_static("no-store"),
    );
    response
}

/// GET /network-adapters — Suche ist nur lokal im PC-Websetup verfügbar.
pub async fn network_adapters_ep(
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
) -> Response {
    if !addr.ip().is_loopback() {
        return error_response(StatusCode::FORBIDDEN, "Nur lokal verfuegbar".to_string());
    }
    Json(network_adapters()).into_response()
}

/// POST /device-setup — speichert Name und optionale WoL-MAC, nur localhost.
pub async fn device_setup(
    State(app): State<SharedApp>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    body: Json<DeviceSetupRequest>,
) -> Response {
    if !addr.ip().is_loopback() {
        return error_response(StatusCode::FORBIDDEN, "Einrichtung nur lokal moeglich".to_string());
    }
    let name = body.name.trim();
    if name.is_empty() || name.chars().count() > 64 {
        return error_response(StatusCode::BAD_REQUEST, "PC-Name muss 1 bis 64 Zeichen haben".to_string());
    }
    let mac = if body.wol_enabled {
        let Some(bytes) = parse_mac(&body.mac) else {
            return error_response(StatusCode::BAD_REQUEST, "MAC-Adresse ungueltig".to_string());
        };
        Some(bytes.iter().map(|byte| format!("{byte:02X}")).collect::<Vec<_>>().join(":"))
    } else { None };
    let settings = DeviceSettings { name: name.to_string(), wol_enabled: mac.is_some(), mac: mac.unwrap_or_default() };
    let Ok(serial) = serde_json::to_string_pretty(&settings) else {
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, "Setup konnte nicht serialisiert werden".to_string());
    };
    if let Err(error) = std::fs::write(&app.device_file, serial) {
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, format!("Setup konnte nicht gespeichert werden: {error}"));
    }
    *app.device_settings.lock().expect("Setup-Sperre") = settings.clone();
    Json(json!({"ok": true, "name": settings.name, "wol_enabled": settings.wol_enabled, "mac": settings.mac})).into_response()
}

/// POST /setup — Einrichtung nur am PC (localhost), wie im Python-Agent.
/// Reihenfolge wie dort: 403 (falscher Ort) -> 409 (schon eingerichtet)
/// -> 400 (Benutzername) -> 400 (Passwort) -> Schreiben -> Log.
pub async fn setup(
    State(app): State<SharedApp>,
    ConnectInfo(addr): ConnectInfo<std::net::SocketAddr>,
    body: Json<SetupRequest>,
) -> Response {
    let host = addr.ip().to_string();
    let local = host == "127.0.0.1" || host == "::1" || host == "localhost";
    if !local {
        return error_response(
            StatusCode::FORBIDDEN,
            "Einrichtung nur am PC (localhost) moeglich".to_string(),
        );
    }
    // Schon eingerichtet? (wie Python: 409)
    {
        let guard = app.inner.lock().expect("Zustands-Sperre");
        if guard.credentials.is_some() || guard.token_credentials.is_some() || !app.token.is_empty() {
            return error_response(
                StatusCode::CONFLICT,
                "Zugangsdaten sind bereits eingerichtet".to_string(),
            );
        }
    }
    if body.auth_mode == "token" {
        let mut bytes = [0u8; 32];
        if let Err(error) = getrandom::getrandom(&mut bytes) {
            return error_response(StatusCode::INTERNAL_SERVER_ERROR, format!("Token konnte nicht erzeugt werden: {error}"));
        }
        let token = bytes.iter().map(|byte| format!("{byte:02x}")).collect::<String>();
        let hashed = match create_credentials("token", &token) {
            Ok(credentials) => TokenCredentials { salt: credentials.salt, hash: credentials.hash },
            Err(detail) => return error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
        };
        let Ok(serial) = serde_json::to_string_pretty(&hashed) else {
            return error_response(StatusCode::INTERNAL_SERVER_ERROR, "Token konnte nicht gespeichert werden".to_string());
        };
        if let Err(error) = std::fs::write(&app.token_file, serial) {
            return error_response(StatusCode::INTERNAL_SERVER_ERROR, format!("Token konnte nicht gespeichert werden: {error}"));
        }
        let mut guard = app.inner.lock().expect("Zustands-Sperre");
        guard.token_credentials = Some(hashed);
        log_event(&app, &mut guard, "setup", "Token-Zugang eingerichtet", true);
        return Json(json!({"ok": true, "auth_mode": "token", "token": token})).into_response();
    }
    if body.auth_mode != "basic" {
        return error_response(StatusCode::BAD_REQUEST, "Anmeldeart muss basic oder token sein".to_string());
    }
    let user = body.user.trim().to_string();
    let password = body.password.clone(); // wie Python: nicht getrimmt
    if user.is_empty() {
        return error_response(StatusCode::BAD_REQUEST, "Benutzername fehlt".to_string());
    }
    if password.chars().count() < 8 {
        return error_response(
            StatusCode::BAD_REQUEST,
            "Passwort muss mindestens 8 Zeichen haben".to_string(),
        );
    }
    let credentials = match create_credentials(&user, &password) {
        Ok(credentials) => credentials,
        Err(detail) => return error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
    };
    let serial = match serde_json::to_string_pretty(&credentials) {
        Ok(serial) => serial,
        Err(error) => {
            return error_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("Speichern fehlgeschlagen: {error}"),
            )
        }
    };
    if let Err(error) = std::fs::write(&app.creds_file, serial) {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Speichern fehlgeschlagen: {error}"),
        );
    }
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(
        &app,
        &mut guard,
        "setup",
        &format!("Zugangsdaten fuer {user} eingerichtet"),
        true,
    );
    guard.credentials = Some(credentials);
    Json(json!({ "ok": true, "user": user })).into_response()
}

// ---------------- Endpunkte mit Anmeldung (Auth) ----------------

/// POST /mode?simulate= — Schaltschalter Simulation/Echt; pending wird
/// zurueckgesetzt und der simulierte Plan entleert (wie Python).
pub async fn set_mode(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let simulate = match first_query(&query, "simulate").and_then(parse_query_bool) {
        Some(simulate) => simulate,
        None => {
            return error_response(
                StatusCode::UNPROCESSABLE_ENTITY,
                "Query-Parameter 'simulate' fehlt oder ist ungueltig".to_string(),
            )
        }
    };
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    guard.simulate = simulate;
    guard.sim_plan = None;
    guard.pending_at = None;
    guard.pending_seconds = 0;
    log_event(
        &app,
        &mut guard,
        "mode",
        if simulate { "SIMULATION" } else { "ECHT" },
        true,
    );
    Json(json!({ "ok": true, "simulate": simulate })).into_response()
}

/// GET /status — Plan-Erkennung laeuft auch im Simulationsmodus echt ab
/// (nur Lesen), genau wie beim Python-Agent.
pub async fn status_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let snap = snapshot(&app);
    let seconds = remaining_seconds(snap.pending_at, now_epoch());
    let power_plan = if snap.simulate && snap.sim_plan.is_some() {
        snap.sim_plan.clone().unwrap()
    } else {
        let simulate = snap.simulate;
        tokio::task::spawn_blocking(move || {
            let (code, out) = run(&["powercfg", "/getactivescheme"], simulate, true);
            if code != 0 {
                "unbekannt".to_string()
            } else {
                detect_active_plan(&out)
            }
        })
        .await
        .unwrap_or_else(|_| "unbekannt".to_string())
    };
    let host = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "?".to_string());
    Json(json!({
        "online": true,
        "host": host,
        "simulate": snap.simulate,
        "power_plan": power_plan,
        "pending_shutdown": seconds,
        "setup_required": snap.credentials.is_none(),
    }))
    .into_response()
}

/// Validierung wie FastAPI: fehlend -> 0, sonst 0..=86400, alles uebige -> Fehler.
pub fn parse_delay(query: &[(String, String)]) -> Result<u32, String> {
    match first_query(query, "delay") {
        None => Ok(0),
        Some(raw) => {
            let value: u32 = raw
                .parse()
                .map_err(|_| "Ungueltiger delay-Wert".to_string())?;
            if value > 86400 {
                Err("Ungueltiger delay-Wert".to_string())
            } else {
                Ok(value)
            }
        }
    }
}

/// POST /shutdown?delay= — Herunterfahren planen.
pub async fn shutdown_ep(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let delay = match parse_delay(&query) {
        Ok(delay) => delay,
        Err(message) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    };
    let simulate = snapshot(&app).simulate;
    let (code, out) = tokio::task::spawn_blocking(move || {
        let delay_text = delay.to_string();
        run(&["shutdown", "/s", "/t", &delay_text], simulate, false)
    })
    .await
    .unwrap_or((-1, String::new()));
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    if code != 0 {
        let problem = if out.is_empty() {
            "Fehler".to_string()
        } else {
            out.clone()
        };
        log_event(&app, &mut guard, "shutdown", &problem, false);
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            if out.is_empty() {
                "shutdown fehlgeschlagen".to_string()
            } else {
                out
            },
        );
    }
    guard.pending_at = Some(now_epoch() + delay as f64);
    guard.pending_seconds = delay as i64;
    let info = if delay == 0 {
        "sofort".to_string()
    } else {
        format!("in {delay}s")
    };
    log_event(&app, &mut guard, "shutdown", &info, true);
    let detail = if out.is_empty() {
        format!("Herunterfahren in {delay}s")
    } else {
        out
    };
    Json(json!({ "ok": true, "detail": detail })).into_response()
}

/// POST /abort — laufendes Herunterfahren abbrechen; pending immer zurueck.
pub async fn abort_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let simulate = snapshot(&app).simulate;
    let (code, out) = tokio::task::spawn_blocking(move || run(&["shutdown", "/a"], simulate, false))
        .await
        .unwrap_or((-1, String::new()));
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    guard.pending_at = None;
    guard.pending_seconds = 0;
    let state_text = if out.is_empty() {
        "abgebrochen".to_string()
    } else {
        out.clone()
    };
    log_event(&app, &mut guard, "abort", &state_text, code == 0);
    let detail = if out.is_empty() {
        "Herunterfahren abgebrochen".to_string()
    } else {
        out
    };
    Json(json!({ "ok": code == 0, "detail": detail })).into_response()
}

/// POST /hibernate — Windows in den Ruhezustand versetzen.
pub async fn hibernate_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let simulate = snapshot(&app).simulate;
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
    log_event(&app, &mut guard, "hibernate", &state_text, code == 0);
    if code != 0 {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            if out.is_empty() {
                "Ruhezustand fehlgeschlagen".to_string()
            } else {
                out
            },
        );
    }
    let detail = if out.is_empty() {
        "Ruhezustand".to_string()
    } else {
        out
    };
    Json(json!({ "ok": true, "detail": detail })).into_response()
}

/// POST /power-plan?name=high|balanced|saver — Energieplan waehlen.
pub async fn power_plan_ep(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let name = match first_query(&query, "name") {
        Some(name) => name.to_string(),
        None => return error_response(StatusCode::UNPROCESSABLE_ENTITY, "Name fehlt".to_string()),
    };
    let Some(plan_guid) = PLANS.iter().find(|(key, _)| *key == name).map(|(_, guid)| *guid) else {
        return error_response(
            StatusCode::UNPROCESSABLE_ENTITY,
            "Ungueltiger Energieplan".to_string(),
        );
    };
    let simulate = snapshot(&app).simulate;
    let (code, out) = tokio::task::spawn_blocking(move || {
        run(&["powercfg", "/setactive", plan_guid], simulate, false)
    })
    .await
    .unwrap_or((-1, String::new()));
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(
        &app,
        &mut guard,
        "power-plan",
        format!("{name} {out}").trim(),
        code == 0,
    );
    if code != 0 {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            if out.is_empty() {
                "powercfg fehlgeschlagen".to_string()
            } else {
                out
            },
        );
    }
    if simulate {
        guard.sim_plan = Some(name.clone());
    }
    Json(json!({ "ok": true, "plan": name, "detail": out })).into_response()
}

/// Port-Pruefung wie FastAPI: fehlend -> 9, sonst 1..=65535, alles uebige -> 422.
pub fn parse_port(query: &[(String, String)]) -> Result<u16, String> {
    match first_query(query, "port") {
        None => Ok(9),
        Some(raw) => {
            let port: u16 = raw
                .parse()
                .map_err(|_| "Ungueltiger Port".to_string())?;
            if port < 1 {
                Err("Ungueltiger Port".to_string())
            } else {
                Ok(port)
            }
        }
    }
}

/// POST /wake?mac=&port= — Magic Packet senden (MAC wird sauber geprueft).
pub async fn wake_ep(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let mac = match first_query(&query, "mac") {
        Some(mac) => mac.to_string(),
        None => {
            return error_response(StatusCode::UNPROCESSABLE_ENTITY, "MAC fehlt".to_string())
        }
    };
    let port = match parse_port(&query) {
        Ok(port) => port,
        Err(message) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    };
    let simulate = snapshot(&app).simulate;
    let mut detail = format!("Magic Packet an {mac} (Port {port})");
    if simulate {
        detail.push_str(" (simuliert)");
    }
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    let Some(mac_bytes) = parse_mac(&mac) else {
        // Unlesbare MAC: sauber Fehler melden, statt unkontrolliert abzubrechen.
        log_event(&app, &mut guard, "wake", &detail, false);
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, "Wake fehlgeschlagen".to_string());
    };
    drop(guard);
    let broadcast = configured_broadcast();
    let ok = tokio::task::spawn_blocking(move || send_wol(&mac_bytes, port, &broadcast, simulate))
        .await
        .unwrap_or(false);
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(&app, &mut guard, "wake", &detail, ok);
    if !ok {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Wake fehlgeschlagen".to_string(),
        );
    }
    Json(json!({ "ok": true, "mac": mac, "port": port })).into_response()
}

/// POST /ping — Verbindungstest vom Handy.
pub async fn ping_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(&app, &mut guard, "ping", "Verbindungstest vom Handy", true);
    Json(json!({ "ok": true, "detail": "Verbindung steht" })).into_response()
}

/// GET /events — Ringpuffer, neueste zuerst (wie Python list(deque)).
pub async fn events_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let events = {
        let guard = app.inner.lock().expect("Zustands-Sperre");
        guard.events.iter().cloned().collect::<Vec<_>>()
    };
    Json(json!({ "events": events })).into_response()
}

/// Validierung wie FastAPI: fehlend -> 200, sonst 1..=1000, alles uebige -> 422.
pub fn parse_lines(query: &[(String, String)]) -> Result<usize, String> {
    match first_query(query, "lines") {
        None => Ok(200),
        Some(raw) => {
            let value: usize = raw
                .parse()
                .map_err(|_| "Ungueltiger lines-Wert".to_string())?;
            if (1..=1000).contains(&value) {
                Ok(value)
            } else {
                Err("Ungueltiger lines-Wert".to_string())
            }
        }
    }
}

/// GET /log?lines= — letzte Zeilen der Logdatei als reinen Text.
pub async fn log_ep(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let lines = match parse_lines(&query) {
        Ok(lines) => lines,
        Err(message) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    };
    if !app.log_file.exists() {
        // Paritaet: fehlende Datei -> leerer Text (kein Fehler).
        return String::from("").into_response();
    }
    let file = app.log_file.clone();
    match tokio::task::spawn_blocking(move || tail_lines(&file, lines)).await {
        Ok(Ok(text)) => text.into_response(),
        Ok(Err(error)) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Log konnte nicht gelesen werden: {error}"),
        ),
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Log konnte nicht gelesen werden: {error}"),
        ),
    }
}

// ---------------- Plugins und Spiel-Optimierung ----------------

/// GET /plugins — alle bekannten Plugins mit Laufzeitstatus.
pub async fn plugins_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    Json(json!({ "plugins": crate::plugins::describe(&app) })).into_response()
}

/// POST /plugins/{id}/start?game=&auto= — Plugin starten (nur auf Knopfdruck).
///
/// `auto=1` bedeutet: der Agent erkennt selbst das laufende Spiel aus der
/// gescannten Liste. Es gibt bewusst **keinen** Hintergrund-Waechter, der
/// 24/7 lauscht – gestartet wird nur, was angefordert wird.
pub async fn plugin_start(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let simulate = snapshot(&app).simulate;
    let auto = first_query(&query, "auto")
        .and_then(parse_query_bool)
        .unwrap_or(false);
    let mut game = first_query(&query, "game").map(|value| value.to_string());

    if game.is_none() && auto {
        let (games, _) = crate::games::cached(&app);
        match crate::games::find_running(&games) {
            Some(found) => game = Some(found.exe),
            None => {
                let mut guard = app.inner.lock().expect("Zustands-Sperre");
                log_event(
                    &app,
                    &mut guard,
                    "game-mode",
                    "kein laufendes Spiel erkannt",
                    false,
                );
                return error_response(
                    StatusCode::CONFLICT,
                    "Kein laufendes Spiel erkannt. Erst Spiele scannen oder eines aus der Liste waehlen."
                        .to_string(),
                );
            }
        }
    }

    match crate::plugins::start(&app, &id, game, auto, simulate) {
        Ok(value) => Json(value).into_response(),
        Err(message) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(&app, &mut guard, "plugin", &message, false);
            error_response(StatusCode::BAD_REQUEST, message)
        }
    }
}

/// POST /plugins/{id}/stop — Plugin sauber beenden (Energieplan wird zurueckgesetzt).
pub async fn plugin_stop(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: Auth,
) -> Response {
    let run = match crate::plugins::take_run(&app, &id) {
        Ok(run) => run,
        Err(message) => return error_response(StatusCode::CONFLICT, message),
    };
    let detail = tokio::task::spawn_blocking(move || crate::plugins::stop_run(run))
        .await
        .unwrap_or_else(|_| "Abbruch fehlgeschlagen".to_string());
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    log_event(&app, &mut guard, "plugin", &format!("{id} {detail}"), true);
    Json(json!({ "ok": true, "id": id, "detail": detail })).into_response()
}

/// GET /plugins/{id}/output?lines= — Ausgabe des Plugins als reiner Text.
pub async fn plugin_output(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: Auth,
) -> Response {
    let lines = match parse_lines(&query) {
        Ok(lines) => lines,
        Err(message) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    };
    crate::plugins::output(&app, &id, lines).into_response()
}

/// GET /games — zuletzt gescannte Spiele (aus `games.json`), inkl. "laeuft".
pub async fn games_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let (games, scanned_at) = crate::games::cached(&app);
    let running = crate::games::running_exes();
    let list: Vec<serde_json::Value> = games
        .iter()
        .map(|game| {
            let is_running = crate::games::file_name(&game.exe)
                .map(|name| running.contains(&name.to_ascii_lowercase()))
                .unwrap_or(false);
            json!({
                "name": game.name,
                "exe": game.exe,
                "source": game.source,
                "running": is_running,
            })
        })
        .collect();
    Json(json!({ "scanned_at": scanned_at, "games": list })).into_response()
}

/// POST /games/scan — Spiele suchen (Steam, Epic, GOG, eigene Ordner).
pub async fn games_scan_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let worker = Arc::clone(&app);
    let result = tokio::task::spawn_blocking(move || crate::games::scan_and_store(&worker)).await;
    match result {
        Ok(games) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(
                &app,
                &mut guard,
                "games-scan",
                &format!("{} Spiele gefunden", games.len()),
                true,
            );
            Json(json!({ "ok": true, "count": games.len(), "games": games })).into_response()
        }
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Scan fehlgeschlagen: {error}"),
        ),
    }
}

/// GET /game-config — Einstellungen der Spiel-Optimierung.
pub async fn game_config_get(State(app): State<SharedApp>, _auth: Auth) -> Response {
    Json(crate::games::load_config(&app)).into_response()
}

/// POST /game-config — Kill-Liste, eigene Suchordner und Automatik speichern.
pub async fn game_config_set(
    State(app): State<SharedApp>,
    _auth: Auth,
    Json(config): Json<crate::games::GameConfig>,
) -> Response {
    match crate::games::save_config(&app, &config) {
        Ok(()) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(
                &app,
                &mut guard,
                "game-config",
                &format!(
                    "{} Apps, {} Ordner, automatisch {}",
                    config.kill_list.len(),
                    config.scan_paths.len(),
                    if config.auto { "an" } else { "aus" }
                ),
                true,
            );
            Json(json!({ "ok": true })).into_response()
        }
        Err(message) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Speichern fehlgeschlagen: {message}"),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairs(list: &[(&str, &str)]) -> Vec<(String, String)> {
        list.iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn delay_wie_fastapi_validiert() {
        // fehlend -> 0 (Python: Query(0, ge=..))
        assert_eq!(parse_delay(&pairs(&[])), Ok(0));
        assert_eq!(parse_delay(&pairs(&[("delay", "60")])), Ok(60));
        assert_eq!(parse_delay(&pairs(&[("delay", "0")])), Ok(0));
        assert_eq!(parse_delay(&pairs(&[("delay", "86400")])), Ok(86400));
        // oberhalb 86400/0-Fehler:
        assert!(parse_delay(&pairs(&[("delay", "86401")])).is_err());
        assert!(parse_delay(&pairs(&[("delay", "abc")])).is_err());
        assert!(parse_delay(&pairs(&[("delay", "-5")])).is_err());
    }

    #[test]
    fn port_wie_fastapi_validiert() {
        // fehlend -> Standard 9
        assert_eq!(parse_port(&pairs(&[])), Ok(9));
        assert_eq!(parse_port(&pairs(&[("port", "7")])), Ok(7));
        assert!(parse_port(&pairs(&[("port", "0")])).is_err());
        assert!(parse_port(&pairs(&[("port", "99999")])).is_err());
        assert!(parse_port(&pairs(&[("port", "abc")])).is_err());
    }

    #[test]
    fn lines_wie_fastapi_validiert() {
        assert_eq!(parse_lines(&pairs(&[])), Ok(200));
        assert_eq!(parse_lines(&pairs(&[("lines", "1")])), Ok(1));
        assert_eq!(parse_lines(&pairs(&[("lines", "1000")])), Ok(1000));
        assert!(parse_lines(&pairs(&[("lines", "0")])).is_err());
        assert!(parse_lines(&pairs(&[("lines", "1001")])).is_err());
        assert!(parse_lines(&pairs(&[("lines", "abc")])).is_err());
    }

    #[test]
    fn csrf_gilt_nur_fuer_schreibende_methoden() {
        use axum::http::Method;
        assert!(is_mutating(&Method::POST));
        assert!(is_mutating(&Method::PUT));
        assert!(is_mutating(&Method::PATCH));
        assert!(is_mutating(&Method::DELETE));
        assert!(!is_mutating(&Method::GET));
        assert!(!is_mutating(&Method::HEAD));
        assert!(!is_mutating(&Method::OPTIONS));
    }

    #[test]
    fn bool_werte_wie_pydantic() {
        assert_eq!(parse_query_bool("true"), Some(true));
        assert_eq!(parse_query_bool("TRUE"), Some(true));
        assert_eq!(parse_query_bool("1"), Some(true));
        assert_eq!(parse_query_bool("yes"), Some(true));
        assert_eq!(parse_query_bool("on"), Some(true));
        assert_eq!(parse_query_bool("false"), Some(false));
        assert_eq!(parse_query_bool("0"), Some(false));
        assert_eq!(parse_query_bool("Off"), Some(false));
        assert_eq!(parse_query_bool("vielleicht"), None);
        assert_eq!(parse_query_bool(""), None);
    }
}
