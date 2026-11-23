//! HTTP-Endpunkte des Agenten:
//! GET /, GET /setup-status, POST /setup,
//! POST /mode, GET /status,
//! GET /sensors (CPU, RAM, GPU, Netzwerk, Datentraeger),
//! GET/POST /warnings (Schwellen mit Dauer, Phase C),
//! POST /shutdown, POST /abort, POST /hibernate, POST /power-plan,
//! POST /wake, POST /ping, GET /events, GET /log,
//! GET /plugins, POST /plugins/{id}/start, POST /plugins/{id}/stop,
//! GET /plugins/{id}/output,
//! GET /games, POST /games/scan, GET/POST /game-config,
//! GET /steam, GET /downloads (Steam, nur Lesen).

use std::convert::Infallible;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::header::{
    ACCESS_CONTROL_ALLOW_CREDENTIALS, ACCESS_CONTROL_ALLOW_HEADERS, ACCESS_CONTROL_ALLOW_METHODS,
    ACCESS_CONTROL_ALLOW_ORIGIN, ACCESS_CONTROL_MAX_AGE, CACHE_CONTROL, CONTENT_TYPE, ORIGIN, VARY,
};
use axum::http::{HeaderValue, Method, StatusCode};
use axum::response::sse::{Event as SseEvent, KeepAlive, Sse};
use axum::response::{Html, IntoResponse, Json, Response};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::app::{parse_role, remaining_seconds, App, Credentials, DeviceSettings, TokenCredentials, UserEntry};
use crate::auth::{create_credentials, Auth, WriteAuth};
use crate::commands::{detect_active_plan, run};
use crate::journal::{log_event, tail_lines};
use crate::live::LiveMessage;
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
    pending_seconds: i64,
    credentials: Option<crate::app::Credentials>,
}

fn snapshot(app: &App) -> Snapshot {
    let guard = app.inner.lock().expect("Zustands-Sperre");
    Snapshot {
        simulate: guard.simulate,
        sim_plan: guard.sim_plan.clone(),
        pending_at: guard.pending_at,
        pending_seconds: guard.pending_seconds,
        credentials: guard.credentials.clone(),
    }
}

/// Anzeigename des PCs aus `device.json` (sonst der Windows-Computername).
fn device_name(app: &App) -> String {
    let settings = app.device_settings.lock().expect("Setup-Sperre");
    if settings.name.trim().is_empty() {
        std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".to_string())
    } else {
        settings.name.clone()
    }
}

/// MAC aus den Geraeteeinstellungen – nur wenn Wake-on-LAN aktiviert ist.
fn configured_mac(app: &App) -> Option<String> {
    let settings = app.device_settings.lock().expect("Setup-Sperre");
    if settings.wol_enabled && !settings.mac.trim().is_empty() {
        Some(settings.mac.trim().to_string())
    } else {
        None
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

// ---------------- CORS fuer die Geraeteliste (opt-in) ----------------

/// Antwortet auf Preflight (`OPTIONS`) und setzt CORS-Kopfzeilen, aber nur fuer
/// Origins aus `PC_AGENT_CORS`. Ohne Allowlist verhaelt sich der Agent wie
/// bisher (keine CORS-Kopfzeilen, fremde Seiten koennen nichts lesen).
pub async fn cors_guard(
    State(app): State<SharedApp>,
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> Response {
    let origin = request
        .headers()
        .get(ORIGIN)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_string());
    let allowed = origin
        .as_ref()
        .map(|value| app.live.origin_allowed(value))
        .unwrap_or(false);

    if request.method() == Method::OPTIONS && allowed {
        let mut response = StatusCode::NO_CONTENT.into_response();
        if let Some(origin) = origin {
            apply_cors_headers(&origin, response.headers_mut());
        }
        return response;
    }

    let mut response = next.run(request).await;
    if allowed {
        if let Some(origin) = origin {
            apply_cors_headers(&origin, response.headers_mut());
        }
    }
    response
}

/// Kopfzeilen fuer genau diese Origin. `*` waere falsch, weil Anmeldedaten
/// mitgeschickt werden duerfen (`Allow-Credentials`).
fn apply_cors_headers(origin: &str, headers: &mut axum::http::HeaderMap) {
    let Ok(origin) = HeaderValue::from_str(origin) else {
        return;
    };
    headers.insert(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    headers.insert(ACCESS_CONTROL_ALLOW_CREDENTIALS, HeaderValue::from_static("true"));
    headers.insert(VARY, HeaderValue::from_static("Origin"));
    headers.insert(
        ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, DELETE, OPTIONS"),
    );
    headers.insert(
        ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("authorization, content-type, x-pc-steuerung, x-token"),
    );
    headers.insert(ACCESS_CONTROL_MAX_AGE, HeaderValue::from_static("600"));
}

// ---------------- Live-Updates (Server-Sent Events) ----------------

/// GET /events/stream — haelt die Verbindung offen und schickt `state` alle 2 s
/// sowie `event` sofort bei neuen Ereignissen. Die Anmeldung gilt wie ueberall.
pub async fn events_stream(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let receiver = app.live.subscribe();
    // Erster Stand sofort: der Empfaenger existiert bereits, die Nachricht kommt an.
    app.live.publish(crate::live::state_message(&app).await);

    let stream = futures_util::stream::unfold(receiver, |mut receiver| async move {
        loop {
            match receiver.recv().await {
                Ok(message) => return Some((Ok::<_, Infallible>(sse_event(message)), receiver)),
                // Abgehaengt (langsamer Client): weiterlesen statt abbrechen.
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => return None,
            }
        }
    });

    let keep_alive = KeepAlive::new()
        .interval(Duration::from_secs(15))
        .text("keep-alive");
    let mut response = Sse::new(stream).keep_alive(keep_alive).into_response();
    response.headers_mut().insert(
        CACHE_CONTROL,
        HeaderValue::from_static("no-store"),
    );
    // Zwischenspeicher (Proxy) sollen den Strom nicht puffern.
    response.headers_mut().insert(
        "x-accel-buffering",
        HeaderValue::from_static("no"),
    );
    response
}

/// Rundruf-Nachricht in ein SSE-Ereignis uebersetzen.
fn sse_event(message: LiveMessage) -> SseEvent {
    match message {
        LiveMessage::State(value) => SseEvent::default()
            .event("state")
            .json_data(value)
            .unwrap_or_else(|_| SseEvent::default().event("state").data("{}")),
        LiveMessage::Event(event) => SseEvent::default()
            .event("event")
            .json_data(event)
            .unwrap_or_else(|_| SseEvent::default().event("event").data("{}")),
    }
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
    _auth: WriteAuth,
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

/// Baut den Status (ohne Rollenangabe) – genutzt von `GET /status` und vom
/// Live-Rundruf (`/events/stream`).
pub async fn status_payload(app: &SharedApp) -> serde_json::Value {
    let snap = snapshot(app);
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
    let name = device_name(app);
    let wol = configured_mac(app).is_some();
    json!({
        "online": true,
        "host": host,
        "device_name": name,
        "simulate": snap.simulate,
        "power_plan": power_plan,
        "pending_shutdown": seconds,
        "pending_seconds": snap.pending_seconds,
        "wol_enabled": wol,
        "setup_required": snap.credentials.is_none(),
        "cors_origins": app.live.origins(),
    })
}

/// GET /status — Plan-Erkennung laeuft auch im Simulationsmodus echt ab
/// (nur Lesen), genau wie beim Python-Agent. Enthaelt die Rolle des Aufrufers.
pub async fn status_ep(State(app): State<SharedApp>, auth: Auth) -> Response {
    let mut value = status_payload(&app).await;
    value["role"] = json!(auth.role);
    Json(value).into_response()
}

/// GET /metrics — CPU-Auslastung, Arbeitsspeicher und Laufzeit.
/// Die erste Anfrage liefert `cpu_percent: null` (zwei Stichproben noetig).
pub async fn metrics_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    Json(crate::metrics::read(&app.metrics)).into_response()
}

/// GET /sensors — erweiterte Messwerte: CPU, RAM, GPU, Netzwerk, Datentraeger.
///
/// Datentraeger, Schnittstellen-Zaehler und `nvidia-smi` sind blockierend,
/// deshalb laufen sie in einem eigenen Thread (`spawn_blocking`). Luefter und
/// Temperaturen sind ohne Zusatzbibliothek nicht lesbar (`available: false`).
pub async fn sensors_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let worker = Arc::clone(&app);
    match tokio::task::spawn_blocking(move || {
        crate::sensors::read(&worker.sensors, worker.as_ref())
    })
    .await
    {
        Ok(sensors) => Json(sensors).into_response(),
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Messwerte konnten nicht gelesen werden: {error}"),
        ),
    }
}

/// GET /warnings — Warnregeln, aktive Warnungen und ausgeloeste Ereignisse
/// (Phase C). Geprueft wird im Agenten (alle 30 s), auch bei geschlossener App;
/// die Android-App zeigt nur an und schickt je Ereignis eine Benachrichtigung.
pub async fn warnings_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    Json(crate::warnings::describe(&app)).into_response()
}

/// Body fuer `POST /warnings`: der komplette Regelsatz (wie `/game-config`).
#[derive(Debug, Deserialize)]
pub struct WarningsRequest {
    #[serde(default)]
    pub rules: Vec<crate::warnings::WarningRule>,
}

/// POST /warnings — Regeln ersetzen (nur `admin`); 422 bei ungueltigen Regeln.
pub async fn warnings_save(
    State(app): State<SharedApp>,
    _auth: WriteAuth,
    Json(body): Json<WarningsRequest>,
) -> Response {
    match crate::warnings::save(&app, body.rules) {
        Ok(rules) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(
                &app,
                &mut guard,
                "warnungen",
                &format!("{} Regeln gespeichert", rules.len()),
                true,
            );
            Json(json!({ "ok": true, "rules": rules })).into_response()
        }
        Err(message) => error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    }
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
    _auth: WriteAuth,
) -> Response {
    let delay = match parse_delay(&query) {
        Ok(delay) => delay,
        Err(message) => return error_response(StatusCode::UNPROCESSABLE_ENTITY, message),
    };
    match crate::actions::perform_shutdown(&app, delay).await {
        Ok(detail) => Json(json!({ "ok": true, "detail": detail })).into_response(),
        Err(detail) => error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
    }
}

/// POST /abort — laufendes Herunterfahren abbrechen; pending immer zurueck.
pub async fn abort_ep(State(app): State<SharedApp>, _auth: WriteAuth) -> Response {
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
pub async fn hibernate_ep(State(app): State<SharedApp>, _auth: WriteAuth) -> Response {
    match crate::actions::perform_hibernate(&app).await {
        Ok(detail) => Json(json!({ "ok": true, "detail": detail })).into_response(),
        Err(detail) => error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
    }
}

/// POST /power-plan?name=high|balanced|saver — Energieplan waehlen.
pub async fn power_plan_ep(
    State(app): State<SharedApp>,
    Query(query): Query<Vec<(String, String)>>,
    _auth: WriteAuth,
) -> Response {
    let name = match first_query(&query, "name") {
        Some(name) => name.to_string(),
        None => return error_response(StatusCode::UNPROCESSABLE_ENTITY, "Name fehlt".to_string()),
    };
    if !crate::actions::plan_known(&name) {
        return error_response(
            StatusCode::UNPROCESSABLE_ENTITY,
            "Ungueltiger Energieplan".to_string(),
        );
    }
    match crate::actions::perform_power_plan(&app, &name).await {
        Ok(detail) => Json(json!({ "ok": true, "plan": name, "detail": detail })).into_response(),
        Err(detail) => error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
    }
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
    _auth: WriteAuth,
) -> Response {
    let mac = match first_query(&query, "mac") {
        Some(mac) => mac.to_string(),
        // Ohne Angabe die eingerichtete MAC verwenden (Web-UI, Tray).
        None => match configured_mac(&app) {
            Some(mac) => mac,
            None => {
                return error_response(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    "MAC fehlt und Wake-on-LAN ist nicht eingerichtet".to_string(),
                )
            }
        },
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
    let mut list = crate::plugins::describe(&app);
    list.extend(crate::jsplugins::describe(&app));
    list.sort_by_key(|entry| {
        entry
            .get("name")
            .and_then(|name| name.as_str())
            .unwrap_or_default()
            .to_lowercase()
    });
    Json(json!({ "plugins": list })).into_response()
}

/// Installiert das JavaScript-Plugin mit dieser ID, sonst None.
fn js_manifest(app: &App, id: &str) -> Option<crate::jsplugins::Manifest> {
    crate::jsplugins::manifests(app)
        .into_iter()
        .find(|manifest| manifest.id == id)
}

/// POST /plugins/{id}/install — Plugin aus dem Katalog nachladen.
pub async fn plugin_install(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: WriteAuth,
) -> Response {
    let worker = Arc::clone(&app);
    let wanted = id.clone();
    let result = tokio::task::spawn_blocking(move || crate::jsplugins::install_from_catalog(&worker, &wanted))
        .await
        .unwrap_or_else(|error| Err(format!("Abbruch: {error}")));
    match result {
        Ok(manifest) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(
                &app,
                &mut guard,
                "plugin",
                &format!("{} installiert", manifest.name),
                true,
            );
            Json(json!({
                "ok": true,
                "id": manifest.id,
                "name": manifest.name,
                "version": manifest.version,
            }))
            .into_response()
        }
        Err(message) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(&app, &mut guard, "plugin", &message, false);
            error_response(StatusCode::BAD_REQUEST, message)
        }
    }
}

/// DELETE /plugins/{id} — installiertes Plugin entfernen.
pub async fn plugin_delete(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: WriteAuth,
) -> Response {
    match crate::jsplugins::uninstall(&app, &id) {
        Ok(()) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(&app, &mut guard, "plugin", &format!("{id} entfernt"), true);
            Json(json!({ "ok": true, "id": id })).into_response()
        }
        Err(message) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(&app, &mut guard, "plugin", &message, false);
            error_response(StatusCode::BAD_REQUEST, message)
        }
    }
}

/// GET /plugins/{id}/bundle — installiertes Plugin als Paket (JSON).
///
/// Damit kann ein Plugin vom PC in den Katalog uebernommen oder auf ein
/// anderes Geraet uebertragen werden.
pub async fn plugin_bundle(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: Auth,
) -> Response {
    match crate::jsplugins::bundle_of(&app, &id) {
        Ok(bundle) => Json(bundle).into_response(),
        Err(message) => error_response(StatusCode::NOT_FOUND, message),
    }
}

/// GET /plugin-catalog — was der Marktplatz anbietet.
pub async fn plugin_catalog_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let catalog = crate::jsplugins::catalog(&app);
    let installed: Vec<String> = crate::jsplugins::manifests(&app)
        .into_iter()
        .map(|manifest| manifest.id)
        .collect();
    let list: Vec<Value> = catalog
        .into_iter()
        .map(|entry| {
            let installed_flag = installed.contains(&entry.id);
            json!({
                "id": entry.id,
                "name": entry.name,
                "description": entry.description,
                "version": entry.version,
                "installed": installed_flag,
                "kind": "javascript",
            })
        })
        .collect();
    Json(json!({ "catalogVersion": 1, "plugins": list })).into_response()
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
    _auth: WriteAuth,
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

    // JavaScript-Plugin? Dann laeuft es im Agenten, nicht als EXE.
    if js_manifest(&app, &id).is_some() {
        return match crate::jsplugins::start(&app, &id, game, auto) {
            Ok(value) => Json(value).into_response(),
            Err(message) => {
                let mut guard = app.inner.lock().expect("Zustands-Sperre");
                log_event(&app, &mut guard, "plugin", &message, false);
                error_response(StatusCode::BAD_REQUEST, message)
            }
        };
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
    _auth: WriteAuth,
) -> Response {
    if js_manifest(&app, &id).is_some() {
        let worker = Arc::clone(&app);
        let wanted = id.clone();
        let result = tokio::task::spawn_blocking(move || crate::jsplugins::stop(&worker, &wanted))
            .await
            .unwrap_or_else(|_| Err("Abbruch fehlgeschlagen".to_string()));
        return match result {
            Ok(detail) => Json(json!({ "ok": true, "id": id, "detail": detail })).into_response(),
            Err(message) => error_response(StatusCode::CONFLICT, message),
        };
    }

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
    if js_manifest(&app, &id).is_some() {
        return crate::jsplugins::output(&app, &id, lines).into_response();
    }
    crate::plugins::output(&app, &id, lines).into_response()
}

/// GET /games — zuletzt gescannte Spiele (aus `games.json`) plus die manuell
/// hinzugefuegten, jeweils inkl. `running` und `hidden`.
///
/// Die Konfiguration wird nur gelesen (`games::load_config`), der Endpunkt
/// braucht deshalb weiterhin keine Schreibrechte. Ausgeblendete Spiele bleiben
/// in der Antwort – die App filtert selbst.
pub async fn games_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let (scanned, scanned_at) = crate::games::cached(&app);
    let config = crate::games::load_config(&app);
    let entries = crate::games::merge_games(&scanned, &config);
    let running = crate::games::running_exes();
    let list: Vec<serde_json::Value> = entries
        .iter()
        .map(|entry| {
            let game = &entry.game;
            let is_running = crate::games::file_name(&game.exe)
                .map(|name| running.contains(&name.to_ascii_lowercase()))
                .unwrap_or(false);
            json!({
                "name": game.name,
                "exe": game.exe,
                "source": game.source,
                "running": is_running,
                "hidden": entry.hidden,
            })
        })
        .collect();
    Json(json!({ "scanned_at": scanned_at, "games": list })).into_response()
}

/// POST /games/scan — Spiele suchen (Steam, Epic, GOG, eigene Ordner).
pub async fn games_scan_ep(State(app): State<SharedApp>, _auth: WriteAuth) -> Response {
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

/// POST /game-config — Kill-Liste, eigene Suchordner, Automatik, ausgeblendete
/// und manuell hinzugefuegte Spiele speichern.
pub async fn game_config_set(
    State(app): State<SharedApp>,
    _auth: WriteAuth,
    Json(mut config): Json<crate::games::GameConfig>,
) -> Response {
    // Manuelle Spiele ohne Quelle eindeutig markieren, damit die App sie erkennt.
    crate::games::fill_custom_source(&mut config);
    match crate::games::save_config(&app, &config) {
        Ok(()) => {
            let mut guard = app.inner.lock().expect("Zustands-Sperre");
            log_event(
                &app,
                &mut guard,
                "game-config",
                &format!(
                    "{} Apps, {} Ordner, automatisch {}, {} ausgeblendet, {} manuell",
                    config.kill_list.len(),
                    config.scan_paths.len(),
                    if config.auto { "an" } else { "aus" },
                    config.hidden.len(),
                    config.custom.len()
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

// ---------------- Steam (nur Lesen) ----------------

/// GET /steam — Steam-Status: Installation, Millennium, Downloads, Tempo.
///
/// Die Datei- und Prozessabfragen laufen in einem eigenen Thread
/// (`spawn_blocking`), damit der HTTP-Dienst fluessig bleibt.
pub async fn steam_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let worker = Arc::clone(&app);
    match tokio::task::spawn_blocking(move || crate::steam::status(&worker)).await {
        Ok(status) => Json(status).into_response(),
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Steam-Status fehlgeschlagen: {error}"),
        ),
    }
}

/// GET /downloads — laufende Steam-Downloads (read-only, MVP).
///
/// Gleiche Datenquelle wie `downloads` in `GET /steam`.
pub async fn downloads_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let worker = Arc::clone(&app);
    match tokio::task::spawn_blocking(move || crate::steam::downloads(&worker)).await {
        Ok(downloads) => Json(json!({ "downloads": downloads })).into_response(),
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Downloads konnten nicht gelesen werden: {error}"),
        ),
    }
}

// ---------------- Benutzer und Rollen (Phase 3) ----------------

/// Body fuer `POST /users`.
#[derive(Debug, Deserialize)]
pub struct NewUserRequest {
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub role: String,
}

/// Schreibt `credentials.json` neu (Rollen und Benutzerliste inklusive).
fn save_credentials_file(app: &App, credentials: &Credentials) -> Result<(), String> {
    let serial = serde_json::to_string_pretty(credentials)
        .map_err(|error| format!("Serialisieren fehlgeschlagen: {error}"))?;
    std::fs::write(&app.creds_file, serial)
        .map_err(|error| format!("Speichern fehlgeschlagen: {error}"))
}

/// GET /users — Hauptbenutzer und weitere Benutzer mit Rolle (ohne Hashes).
/// Nur Administratoren; `viewer` bekommt 403.
pub async fn users_ep(State(app): State<SharedApp>, auth: Auth) -> Response {
    if !auth.is_admin() {
        return error_response(
            StatusCode::FORBIDDEN,
            "Benutzerverwaltung nur fuer Administratoren".to_string(),
        );
    }
    let guard = app.inner.lock().expect("Zustands-Sperre");
    let Some(credentials) = guard.credentials.as_ref() else {
        return Json(json!({
            "primary": serde_json::Value::Null,
            "users": [],
            "cors_origins": app.live.origins(),
        }))
        .into_response();
    };
    let users = credentials
        .users
        .iter()
        .filter(|entry| entry.is_usable())
        .map(|entry| json!({ "user": entry.user, "role": entry.role() }))
        .collect::<Vec<_>>();
    let primary = if credentials.is_usable() {
        json!({ "user": credentials.user, "role": credentials.role() })
    } else {
        serde_json::Value::Null
    };
    Json(json!({
        "primary": primary,
        "users": users,
        "cors_origins": app.live.origins(),
    }))
    .into_response()
}

/// POST /users — Benutzer anlegen oder (gleicher Name) Passwort/Rolle ersetzen.
/// Der Hauptbenutzer bleibt unveraendert; nur Administratoren duerfen das.
pub async fn user_add(
    State(app): State<SharedApp>,
    auth: WriteAuth,
    Json(body): Json<NewUserRequest>,
) -> Response {
    if !auth.0.is_admin() {
        return error_response(
            StatusCode::FORBIDDEN,
            "Nur Administratoren duerfen Benutzer verwalten".to_string(),
        );
    }
    let user = body.user.trim().to_string();
    let password = body.password.clone();
    if user.is_empty() || user.chars().count() > 64 {
        return error_response(
            StatusCode::BAD_REQUEST,
            "Benutzername muss 1 bis 64 Zeichen haben".to_string(),
        );
    }
    if password.chars().count() < 8 {
        return error_response(
            StatusCode::BAD_REQUEST,
            "Passwort muss mindestens 8 Zeichen haben".to_string(),
        );
    }
    let role = parse_role(&body.role);
    let created = match create_credentials(&user, &password) {
        Ok(credentials) => credentials,
        Err(detail) => return error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
    };

    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    let Some(mut credentials) = guard.credentials.clone() else {
        return error_response(
            StatusCode::CONFLICT,
            "Noch keine Zugangsdaten eingerichtet".to_string(),
        );
    };
    if user == credentials.user {
        return error_response(
            StatusCode::CONFLICT,
            "Der Hauptbenutzer kann hier nicht geaendert werden".to_string(),
        );
    }
    let entry = UserEntry {
        user: user.clone(),
        salt: created.salt,
        hash: created.hash,
        role: role.to_string(),
    };
    match credentials.users.iter_mut().find(|existing| existing.user == user) {
        Some(existing) => *existing = entry,
        None => credentials.users.push(entry),
    }
    if let Err(error) = save_credentials_file(&app, &credentials) {
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, error);
    }
    guard.credentials = Some(credentials);
    log_event(
        &app,
        &mut guard,
        "benutzer",
        &format!("{user} als {role} gespeichert"),
        true,
    );
    Json(json!({ "ok": true, "user": user, "role": role })).into_response()
}

/// DELETE /users/{user} — weiteren Benutzer entfernen (Hauptbenutzer bleibt).
pub async fn user_delete(
    State(app): State<SharedApp>,
    Path(user): Path<String>,
    auth: WriteAuth,
) -> Response {
    if !auth.0.is_admin() {
        return error_response(
            StatusCode::FORBIDDEN,
            "Nur Administratoren duerfen Benutzer verwalten".to_string(),
        );
    }
    let mut guard = app.inner.lock().expect("Zustands-Sperre");
    let Some(mut credentials) = guard.credentials.clone() else {
        return error_response(
            StatusCode::NOT_FOUND,
            "Keine Benutzer eingerichtet".to_string(),
        );
    };
    if user == credentials.user {
        return error_response(
            StatusCode::CONFLICT,
            "Der Hauptbenutzer kann nicht geloescht werden".to_string(),
        );
    }
    let before = credentials.users.len();
    credentials.users.retain(|entry| entry.user != user);
    if credentials.users.len() == before {
        return error_response(
            StatusCode::NOT_FOUND,
            format!("Benutzer '{user}' nicht gefunden"),
        );
    }
    if let Err(error) = save_credentials_file(&app, &credentials) {
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, error);
    }
    guard.credentials = Some(credentials);
    log_event(
        &app,
        &mut guard,
        "benutzer",
        &format!("{user} entfernt"),
        true,
    );
    Json(json!({ "ok": true, "removed": user })).into_response()
}

// ---------------- Routinen (Phase 4) ----------------

/// GET /routines — alle Routinen inklusive naechstem Lauf (nur lesen fuer viewer).
pub async fn routines_ep(State(app): State<SharedApp>, _auth: Auth) -> Response {
    let now = chrono::Local::now().naive_local();
    let guard = app.routines.lock().expect("Routinen-Sperre");
    let routines = guard
        .routines
        .iter()
        .map(|routine| {
            let mut value = serde_json::to_value(routine).unwrap_or_else(|_| json!({}));
            match crate::routines::next_run(routine, &now) {
                Some(next) => {
                    value["next_run"] = json!(next.format("%Y-%m-%dT%H:%M:%S").to_string());
                    value["next_in"] = json!((next - now).num_seconds().max(0));
                }
                None => {
                    value["next_run"] = serde_json::Value::Null;
                    value["next_in"] = serde_json::Value::Null;
                }
            }
            value
        })
        .collect::<Vec<_>>();
    Json(json!({
        "now": now.format("%Y-%m-%dT%H:%M:%S").to_string(),
        "routines": routines,
    }))
    .into_response()
}

/// POST /routines — Routine anlegen oder ersetzen (nur Administratoren).
pub async fn routine_save(
    State(app): State<SharedApp>,
    _auth: WriteAuth,
    Json(mut routine): Json<crate::routines::Routine>,
) -> Response {
    if routine.id.trim().is_empty() {
        routine.id = crate::routines::new_id();
    }
    crate::routines::normalize(&mut routine);
    if let Err(message) = crate::routines::validate(&routine) {
        return error_response(StatusCode::UNPROCESSABLE_ENTITY, message);
    }

    let mut guard = app.routines.lock().expect("Routinen-Sperre");
    match guard.routines.iter_mut().find(|entry| entry.id == routine.id) {
        Some(existing) => {
            // `last_run` bleibt erhalten: ein erneutes Speichern soll die
            // Routine nicht in derselben Minute noch einmal ausloesen.
            routine.last_run = existing.last_run.clone();
            *existing = routine.clone();
        }
        None => {
            if guard.routines.len() >= crate::routines::MAX_ROUTINES {
                return error_response(
                    StatusCode::UNPROCESSABLE_ENTITY,
                    format!("Hoechstens {} Routinen sind erlaubt", crate::routines::MAX_ROUTINES),
                );
            }
            routine.last_run.clear();
            guard.routines.push(routine.clone());
        }
    }
    let snapshot = guard.routines.clone();
    drop(guard);

    if let Err(error) = crate::routines::save(&app.routines_file, &snapshot) {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Speichern fehlgeschlagen: {error}"),
        );
    }
    let mut inner = app.inner.lock().expect("Zustands-Sperre");
    log_event(
        &app,
        &mut inner,
        "routine",
        &format!("{} gespeichert", routine.name),
        true,
    );
    Json(json!({ "ok": true, "routine": routine })).into_response()
}

/// DELETE /routines/{id} — Routine entfernen.
pub async fn routine_delete(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: WriteAuth,
) -> Response {
    let mut guard = app.routines.lock().expect("Routinen-Sperre");
    let before = guard.routines.len();
    guard.routines.retain(|routine| routine.id != id);
    if guard.routines.len() == before {
        return error_response(StatusCode::NOT_FOUND, format!("Routine '{id}' nicht gefunden"));
    }
    let snapshot = guard.routines.clone();
    drop(guard);

    if let Err(error) = crate::routines::save(&app.routines_file, &snapshot) {
        return error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Speichern fehlgeschlagen: {error}"),
        );
    }
    let mut inner = app.inner.lock().expect("Zustands-Sperre");
    log_event(
        &app,
        &mut inner,
        "routine",
        &format!("{id} entfernt"),
        true,
    );
    Json(json!({ "ok": true, "removed": id })).into_response()
}

/// POST /routines/{id}/run — Routine sofort ausfuehren (auch wenn sie aus ist).
pub async fn routine_run(
    State(app): State<SharedApp>,
    Path(id): Path<String>,
    _auth: WriteAuth,
) -> Response {
    let routine = {
        let guard = app.routines.lock().expect("Routinen-Sperre");
        guard.routines.iter().find(|routine| routine.id == id).cloned()
    };
    let Some(routine) = routine else {
        return error_response(StatusCode::NOT_FOUND, format!("Routine '{id}' nicht gefunden"));
    };
    match crate::routines::execute(&app, &routine).await {
        Ok(detail) => Json(json!({ "ok": true, "detail": detail })).into_response(),
        Err(detail) => error_response(StatusCode::INTERNAL_SERVER_ERROR, detail),
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
