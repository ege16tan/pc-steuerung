//! Integrationstests fuer die HTTP-Endpunkte des Agenten.
//!
//! Getestet wird die **gebaute** EXE als echter Kindprozess - ohne Mock und ohne
//! zusaetzliche Crate. Der Agent laeuft mit `PC_AGENT_SIMULATE=1`, einem eigenen
//! Datenordner unter `std::env::temp_dir()` und einem freien Port aus
//! 18300-18400. Echte Aktionen (Herunterfahren, Ruhezustand, Energieplan)
//! werden nie ausgefuehrt, weil die Simulation alles nur protokolliert.
//!
//! Voraussetzung: `cargo build` (oder `cargo build --release`) in
//! `PC Agent Rust`. Fehlt die EXE, werden die Tests mit einer klaren Meldung
//! uebersprungen statt fehlzuschlagen - ein reiner `cargo test`-Lauf ohne
//! vorherigen Build bleibt damit gruen.
//!
//! Der HTTP-Client ist handgeschrieben (`std::net::TcpStream`): eine Anfrage je
//! Verbindung mit `Connection: close`. Gelesen wird, bis `Content-Length`
//! erfuellt ist oder die Verbindung endet - kein TLS, kein Chunked-Handling.
//!
//! Jeder Test startet eine eigene Agent-Instanz (frischer Datenordner) und
//! beendet sie ueber `Drop` wieder; dadurch bleiben die Faelle unabhaengig
//! voneinander und es bleibt kein Prozess zurueck.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

/// Dateiname der Agent-EXE.
const EXE_NAME: &str = "pc-agent.exe";
/// Portbereich der Testinstanzen (bewusst weg von 8000).
const PORT_FIRST: u16 = 18300;
const PORT_LAST: u16 = 18400;
/// So lange darf ein Start hoechstens dauern.
const READY_TIMEOUT: Duration = Duration::from_secs(15);
/// Abstand der Bereitschaftspruefung.
const READY_POLL: Duration = Duration::from_millis(100);
/// So oft wird bei einem Port-Konflikt der naechste Port probiert.
const START_ATTEMPTS: usize = 5;
/// Zeitlimit fuer normale Anfragen (Sensoren lesen blockierend).
const IO_TIMEOUT: Duration = Duration::from_secs(20);
/// Zeitlimit fuer die kurze Bereitschaftspruefung.
const PROBE_TIMEOUT: Duration = Duration::from_millis(500);
/// Kopfzeile gegen CSRF (localhost, siehe `routes::csrf_guard`).
const CSRF_HEADER: (&str, &str) = ("X-PC-Steuerung", "1");

/// Startvorgaenge laufen nacheinander, damit zwei Tests nie gleichzeitig
/// denselben Port reservieren. Die Testkoerper selbst laufen danach parallel.
static START_LOCK: Mutex<()> = Mutex::new(());

/// Ports, die in diesem Lauf schon an einen Agenten vergeben wurden.
static USED_PORTS: Mutex<Vec<u16>> = Mutex::new(Vec::new());

/// Eine laufende Agent-Instanz fuer einen Test.
struct Agent {
    child: Option<Child>,
    port: u16,
    data_dir: PathBuf,
}

impl Agent {
    /// Startet die EXE und wartet, bis sie antwortet.
    /// `None` = EXE fehlt (Meldung bereits ausgegeben, Test ueberspringen).
    fn start() -> Option<Agent> {
        let exe = match agent_exe() {
            Some(path) => path,
            None => {
                eprintln!(
                    "UEBERSPRUNGEN: {} nicht gefunden (erwartet unter 'target/debug/' oder \
                     'target/release/'). Bitte zuerst 'cargo build' in 'PC Agent Rust' ausfuehren.",
                    EXE_NAME
                );
                return None;
            }
        };

        // Nur das Starten ist serialisiert (Port waehlen + warten).
        let _guard = START_LOCK
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());

        let mut last_problem = "kein freier Port gefunden".to_string();
        for _ in 0..START_ATTEMPTS {
            let Some(port) = free_port() else {
                break;
            };
            let data_dir = unique_data_dir();
            let mut child = spawn_agent(&exe, port, &data_dir).expect("Agent starten");
            match wait_until_ready(&mut child, port) {
                Ok(()) => {
                    reserve_port(port);
                    return Some(Agent {
                        child: Some(child),
                        port,
                        data_dir,
                    });
                }
                Err(problem) => {
                    // Prozess sauber beenden, bevor der naechste Port probiert wird.
                    let _ = child.kill();
                    let _ = child.wait();
                    remove_dir(&data_dir);
                    last_problem = format!(
                        "Port {port}: {problem} (Log: {})",
                        data_dir.join("agent.log").display()
                    );
                }
            }
        }
        panic!("Agent liess sich nicht starten: {last_problem}");
    }

    /// GET ohne weitere Kopfzeilen.
    fn get(&self, path: &str) -> HttpResponse {
        request(self.port, "GET", path, &[], None, IO_TIMEOUT)
            .unwrap_or_else(|error| panic!("GET {path} fehlgeschlagen: {error}"))
    }

    /// POST mit CSRF-Kopfzeile (localhost braucht sie, siehe `csrf_guard`).
    fn post(&self, path: &str, body: Option<&str>) -> HttpResponse {
        self.post_with(path, body, true)
    }

    /// POST ohne CSRF-Kopfzeile - fuer den 403-Fall.
    fn post_without_csrf(&self, path: &str, body: Option<&str>) -> HttpResponse {
        self.post_with(path, body, false)
    }

    fn post_with(&self, path: &str, body: Option<&str>, csrf: bool) -> HttpResponse {
        let mut headers: Vec<(&str, &str)> = Vec::new();
        if csrf {
            headers.push(CSRF_HEADER);
        }
        if body.is_some() {
            headers.push(("Content-Type", "application/json"));
        }
        request(self.port, "POST", path, &headers, body, IO_TIMEOUT)
            .unwrap_or_else(|error| panic!("POST {path} fehlgeschlagen: {error}"))
    }
}

impl Drop for Agent {
    /// Beendet den Kindprozess auch bei einem Testfehler (kein Prozess-Leak).
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        remove_dir(&self.data_dir);
    }
}

/// Sucht die gebaute EXE: `target/debug` vor `target/release`.
fn agent_exe() -> Option<PathBuf> {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    ["debug", "release"]
        .iter()
        .map(|profile| manifest.join("target").join(profile).join(EXE_NAME))
        .find(|candidate| candidate.is_file())
}

/// Erster Port aus dem Testbereich, der sich binden laesst.
///
/// Geprueft wird bewusst auf `0.0.0.0` (so bindet auch der Agent) und **nicht**
/// auf `127.0.0.1`: Windows laesst eine zweite, genauere Bindung auf denselben
/// Port zu, wenn der Wildcard-Port schon belegt ist. Mit einer 127.0.0.1-Probe
/// wuerden zwei Tests denselben Port bekommen und sich einen Agenten teilen.
fn free_port() -> Option<u16> {
    let used = used_ports();
    (PORT_FIRST..=PORT_LAST).find(|port| {
        !used.contains(port) && TcpListener::bind(("0.0.0.0", *port)).is_ok()
    })
}

/// Ports, die in diesem Lauf schon an einen Agenten vergeben wurden. Der
/// Bereich ist gross genug; freigegeben wird nichts.
fn used_ports() -> Vec<u16> {
    USED_PORTS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

/// Haelt einen vergebenen Port fest (Aufruf unter `START_LOCK`).
fn reserve_port(port: u16) {
    USED_PORTS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .push(port);
}

/// Eindeutiger Datenordner je Lauf und Test.
fn unique_data_dir() -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_nanos())
        .unwrap_or_default();
    std::env::temp_dir().join(format!("pc-agent-http-{}-{}", std::process::id(), nanos))
}

/// Startet den Agenten ohne Konsolenfenster; die Simulation ist fest an.
fn spawn_agent(exe: &Path, port: u16, data_dir: &Path) -> std::io::Result<Child> {
    Command::new(exe)
        .env("PC_AGENT_DATA", data_dir)
        .env("PC_AGENT_PORT", port.to_string())
        .env("PC_AGENT_SIMULATE", "1")
        // Nichts aus der Umgebung des Testlaufs uebernehmen.
        .env_remove("PC_AGENT_TOKEN")
        .env_remove("PC_AGENT_CORS")
        .env_remove("PC_WOL_BROADCAST")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
}

/// Wartet aktiv (Poll alle 100 ms, hoechstens 15 s) auf die erste Antwort.
fn wait_until_ready(child: &mut Child, port: u16) -> Result<(), String> {
    let deadline = Instant::now() + READY_TIMEOUT;
    let mut last = "keine Antwort".to_string();
    while Instant::now() < deadline {
        if let Ok(Some(status)) = child.try_wait() {
            return Err(format!("Prozess vorzeitig beendet ({status})"));
        }
        match probe_ready(port) {
            Ok(()) => {
                // Nachpruefung: lebt unser Prozess noch? Antwortet ein fremder
                // Server, war der Port belegt und der naechste ist dran.
                return match child.try_wait() {
                    Ok(None) => Ok(()),
                    Ok(Some(status)) => {
                        Err(format!("Prozess nach der ersten Antwort beendet ({status})"))
                    }
                    Err(error) => Err(format!("Prozessstatus nicht lesbar: {error}")),
                };
            }
            Err(problem) => last = problem,
        }
        std::thread::sleep(READY_POLL);
    }
    Err(format!(
        "keine Antwort binnen {} s ({last})",
        READY_TIMEOUT.as_secs()
    ))
}

/// `GET /ping` ist im Agenten nur als `POST` registriert - erreichbar ist der
/// Server deshalb bei **200 oder 405**. Jede andere Antwort gilt als
/// "noch nicht bereit".
fn probe_ready(port: u16) -> Result<(), String> {
    let response = request(port, "GET", "/ping", &[], None, PROBE_TIMEOUT)?;
    if response.status == 200 || response.status == 405 {
        Ok(())
    } else {
        Err(format!("GET /ping antwortet {}", response.status))
    }
}

/// Entfernt den Test-Datenordner; unter Windows sind Dateien kurz gesperrt,
/// deshalb hoechstens fuenf Versuche.
fn remove_dir(path: &Path) {
    for _ in 0..5 {
        if !path.exists() || std::fs::remove_dir_all(path).is_ok() {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

// ---------------- Handgeschriebener HTTP-Client ----------------

/// Eine gelesene Antwort: Statuszeile, Kopfzeilen, Body als Text.
struct HttpResponse {
    status: u16,
    headers: Vec<(String, String)>,
    body: String,
}

impl HttpResponse {
    /// Erste Kopfzeile mit diesem Namen (Name wird kleingeschrieben verglichen).
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    /// Body als JSON; bei Unsinn eine verstaendliche Meldung.
    fn json(&self) -> Value {
        serde_json::from_str(&self.body)
            .unwrap_or_else(|error| panic!("Antwort ist kein JSON ({error}): {}", self.preview()))
    }

    /// Kurzfassung fuer Fehlermeldungen (Rumpf kann HTML sein).
    fn preview(&self) -> String {
        let text = self.body.trim();
        let short: String = text.chars().take(300).collect();
        format!("Status {}, Body: {short}", self.status)
    }
}

/// Schickt eine Anfrage und liest die Antwort bis zum Verbindungsende.
///
/// Eine Verbindung je Anfrage (`Connection: close`), damit kein
/// Chunked-/Keep-Alive-Zustand verwaltet werden muss.
fn request(
    port: u16,
    method: &str,
    path: &str,
    headers: &[(&str, &str)],
    body: Option<&str>,
    timeout: Duration,
) -> Result<HttpResponse, String> {
    let address: SocketAddr = format!("127.0.0.1:{port}")
        .parse()
        .map_err(|error| format!("Adresse ungueltig: {error}"))?;
    let mut stream = TcpStream::connect_timeout(&address, timeout)
        .map_err(|error| format!("Verbindung zu {address} fehlgeschlagen: {error}"))?;
    stream
        .set_read_timeout(Some(timeout))
        .map_err(|error| format!("Zeitlimit nicht setzbar: {error}"))?;
    stream
        .set_write_timeout(Some(timeout))
        .map_err(|error| format!("Zeitlimit nicht setzbar: {error}"))?;

    let mut text = format!("{method} {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n");
    for (name, value) in headers {
        text.push_str(&format!("{name}: {value}\r\n"));
    }
    if let Some(body) = body {
        text.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    text.push_str("\r\n");
    if let Some(body) = body {
        text.push_str(body);
    }

    stream
        .write_all(text.as_bytes())
        .map_err(|error| format!("Anfrage nicht sendbar: {error}"))?;
    let raw = read_response(&mut stream)?;
    parse_response(&raw)
}

/// Liest bis zum Verbindungsende oder bis die Antwort laut `Content-Length`
/// vollstaendig ist.
///
/// Windows meldet beim Schliessen der Verbindung machmal einen Reset
/// (`os error 10054`), obwohl die Antwort schon komplett angekommen ist. Eine
/// vollstaendige Antwort zaehlt deshalb auch dann.
fn read_response(stream: &mut TcpStream) -> Result<Vec<u8>, String> {
    let mut raw = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        match stream.read(&mut chunk) {
            Ok(0) => return Ok(raw),
            Ok(read) => {
                raw.extend_from_slice(&chunk[..read]);
                if response_complete(&raw) {
                    return Ok(raw);
                }
            }
            Err(error) => {
                if response_complete(&raw) {
                    return Ok(raw);
                }
                return Err(format!("Antwort nicht lesbar: {error}"));
            }
        }
    }
}

/// Ist die Antwort vollstaendig? Grundlage ist `Content-Length`; ohne diese
/// Kopfzeile gilt weiter "bis zum Verbindungsende" (kein Chunked-Handling).
fn response_complete(raw: &[u8]) -> bool {
    let Some(split) = find_bytes(raw, b"\r\n\r\n") else {
        return false;
    };
    let head = String::from_utf8_lossy(&raw[..split]).to_ascii_lowercase();
    let length = head
        .lines()
        .filter_map(|line| line.strip_prefix("content-length:"))
        .filter_map(|value| value.trim().parse::<usize>().ok())
        .next();
    matches!(length, Some(length) if raw.len() >= split + 4 + length)
}

/// Position einer Bytefolge (fuer die Kopfzeilen-Grenze).
fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

/// Zerlegt eine HTTP/1.1-Antwort in Status, Kopfzeilen und Body.
fn parse_response(raw: &[u8]) -> Result<HttpResponse, String> {
    let text = String::from_utf8_lossy(raw).to_string();
    let Some(split) = text.find("\r\n\r\n") else {
        return Err(format!("Antwort ohne Kopfzeilen: {text:?}"));
    };
    let head = &text[..split];
    let body = text[split + 4..].to_string();

    let mut lines = head.split("\r\n");
    let status_line = lines.next().unwrap_or_default();
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| format!("Statuszeile unlesbar: {status_line:?}"))?;
    let headers = lines
        .filter_map(|line| {
            let (name, value) = line.split_once(':')?;
            Some((name.trim().to_ascii_lowercase(), value.trim().to_string()))
        })
        .collect();
    Ok(HttpResponse {
        status,
        headers,
        body,
    })
}

/// Prueft den Statuscode und zeigt bei Fehlschlag die ganze Antwort.
fn expect_status(response: &HttpResponse, expected: u16, what: &str) {
    assert_eq!(
        response.status,
        expected,
        "{what}: erwartet {expected}, bekommen {}",
        response.preview()
    );
}

// ---------------- Faelle ----------------

/// 1) GET /status: online, Simulationsmodus, Energieplan.
#[test]
fn status_zeigt_simulation_und_energieplan() {
    // `None` = EXE fehlt, Meldung kam bereits aus `Agent::start`.
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/status");
    expect_status(&response, 200, "GET /status");
    assert!(
        response
            .header("content-type")
            .unwrap_or_default()
            .contains("application/json"),
        "GET /status muss JSON liefern, Kopfzeilen: {:?}",
        response.headers
    );

    let body = response.json();
    assert_eq!(
        body["online"],
        json!(true),
        "online fehlt oder ist falsch: {body}"
    );
    assert_eq!(
        body["simulate"],
        json!(true),
        "PC_AGENT_SIMULATE=1 muss im Status stehen: {body}"
    );
    assert!(
        body["power_plan"].is_string(),
        "power_plan fehlt oder ist kein Text: {body}"
    );
}

/// 2) GET /metrics: CPU (erste Antwort darf null sein), RAM, Kerne.
#[test]
fn metrics_liefert_werte() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/metrics");
    expect_status(&response, 200, "GET /metrics");
    let body = response.json();

    assert!(
        body.get("cpu_percent").is_some(),
        "cpu_percent fehlt (darf null sein): {body}"
    );
    assert!(
        body["ram_percent"].as_f64().is_some(),
        "ram_percent fehlt oder ist keine Zahl: {body}"
    );
    assert!(
        body["cpu_cores"].as_u64().unwrap_or(0) > 0,
        "cpu_cores muss groesser als 0 sein: {body}"
    );

    // Zweite Stichprobe: ein Prozentwert ist dann erwartbar. Der Test bleibt
    // tolerant, weil die Messung von der Umgebung abhaengt.
    std::thread::sleep(Duration::from_millis(300));
    let second = agent.get("/metrics").json();
    if let Some(percent) = second["cpu_percent"].as_f64() {
        assert!(
            (0.0..=100.0).contains(&percent),
            "cpu_percent ausserhalb 0..=100: {second}"
        );
    }
}

/// 3) GET /sensors: alle sieben Bloecke; fehlende Werte mit `reason`.
#[test]
fn sensors_hat_alle_bloecke() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/sensors");
    expect_status(&response, 200, "GET /sensors");
    let body = response.json();

    for key in ["cpu", "ram", "gpu", "network", "fans", "temperatures"] {
        let block = body
            .get(key)
            .unwrap_or_else(|| panic!("Block '{key}' fehlt: {body}"));
        let available = block["available"].as_bool().unwrap_or_else(|| {
            panic!("'{key}.available' fehlt oder ist kein Wahrheitswert: {block}")
        });
        if !available {
            let reason = block["reason"].as_str().unwrap_or_default();
            assert!(
                !reason.trim().is_empty(),
                "'{key}' ist nicht verfuegbar, nennt aber keinen Grund: {block}"
            );
        }
    }
    assert!(
        body["disks"].is_array(),
        "disks muss eine Liste sein (darf leer sein): {body}"
    );
}

/// 4) GET /warnings: Regeln, aktive Warnungen, Verlauf, Takt.
#[test]
fn warnings_hat_regeln_und_takt() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/warnings");
    expect_status(&response, 200, "GET /warnings");
    let body = response.json();

    for key in ["rules", "active", "triggered"] {
        assert!(
            body[key].is_array(),
            "'{key}' fehlt oder ist keine Liste: {body}"
        );
    }
    assert!(
        body["interval_s"].as_u64().unwrap_or(0) > 0,
        "interval_s fehlt oder ist 0: {body}"
    );
}

/// 5) POST /warnings: ohne CSRF-Kopfzeile 403, mit Kopfzeile gespeichert.
#[test]
fn warnings_schreiben_braucht_csrf_kopfzeile() {
    let Some(agent) = Agent::start() else { return };

    let before = agent.get("/warnings").json();
    let original = before["rules"].clone();
    assert!(
        original.is_array(),
        "Regelsatz fehlt vor dem Speichern: {before}"
    );

    let rules = json!({
        "rules": [{
            "id": "test-cpu",
            "label": "Testlast",
            "metric": "cpu_percent",
            "op": ">",
            "threshold": 75.0,
            "duration_s": 5,
            "enabled": true
        }]
    });
    let body = rules.to_string();

    // Ohne Kopfzeile: der CSRF-Schutz greift bei localhost.
    let denied = agent.post_without_csrf("/warnings", Some(&body));
    expect_status(&denied, 403, "POST /warnings ohne X-PC-Steuerung");

    // Mit Kopfzeile: gespeichert und im naechsten GET sichtbar.
    let saved = agent.post("/warnings", Some(&body));
    expect_status(&saved, 200, "POST /warnings mit X-PC-Steuerung");
    assert_eq!(saved.json()["ok"], json!(true), "Antwort ohne ok");

    let after = agent.get("/warnings").json();
    let ids: Vec<&str> = after["rules"]
        .as_array()
        .map(|rules| rules.iter().filter_map(|rule| rule["id"].as_str()).collect())
        .unwrap_or_default();
    assert_eq!(
        ids,
        vec!["test-cpu"],
        "gespeicherte Regel nicht sichtbar: {after}"
    );

    // Aufraeumen: den vorherigen Regelsatz zurueckschreiben.
    let restore = json!({ "rules": original }).to_string();
    let restored = agent.post("/warnings", Some(&restore));
    expect_status(&restored, 200, "POST /warnings (zuruecksetzen)");
}

/// 6) POST /shutdown + /abort: `pending_shutdown` folgt der Simulation.
#[test]
fn shutdown_und_abort_setzen_pending() {
    let Some(agent) = Agent::start() else { return };

    let started = agent.post("/shutdown?delay=60", None);
    expect_status(&started, 200, "POST /shutdown?delay=60");

    let status = agent.get("/status").json();
    let pending = status["pending_shutdown"].as_f64().unwrap_or(-1.0);
    assert!(
        pending > 0.0,
        "pending_shutdown muss nach /shutdown groesser als 0 sein: {status}"
    );

    let aborted = agent.post("/abort", None);
    expect_status(&aborted, 200, "POST /abort");

    let status = agent.get("/status").json();
    let pending = status["pending_shutdown"].as_f64().unwrap_or(-1.0);
    assert_eq!(
        pending, 0.0,
        "pending_shutdown muss nach /abort 0 sein: {status}"
    );
}

/// 7) GET /games: `scanned_at` und eine Liste (darf leer sein).
#[test]
fn games_liste_ist_lesbar() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/games");
    expect_status(&response, 200, "GET /games");
    let body = response.json();

    assert!(
        body.get("scanned_at").is_some(),
        "scanned_at fehlt (darf null sein): {body}"
    );
    let games = body["games"]
        .as_array()
        .unwrap_or_else(|| panic!("games ist keine Liste: {body}"));
    for game in games {
        for key in ["name", "exe"] {
            assert!(game[key].is_string(), "Spiel ohne Text in '{key}': {game}");
        }
        for key in ["running", "hidden"] {
            assert!(
                game[key].is_boolean(),
                "Spiel ohne Wahrheitswert in '{key}': {game}"
            );
        }
    }
}

/// 8) GET /processes: liefert eine Liste sichtbarer Anwendungen.
#[test]
fn processes_liefert_sichtbare_apps() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/processes");
    expect_status(&response, 200, "GET /processes");
    let body = response.json();
    let processes = body["processes"]
        .as_array()
        .unwrap_or_else(|| panic!("processes ist keine Liste: {body}"));
    for process in processes {
        assert!(
            process["pid"].as_u64().unwrap_or(0) > 4,
            "Prozess ohne gueltige PID: {process}"
        );
        assert!(
            process["name"].as_str().is_some_and(|name| !name.is_empty()),
            "Prozess ohne Namen: {process}"
        );
    }
}

/// 9) POST /service/stop: speichert den Marker und beendet den Agenten.
#[test]
fn service_stop_speichert_marker_und_beendet_agenten() {
    let Some(mut agent) = Agent::start() else { return };

    let response = agent.post("/service/stop", Some("{}"));
    expect_status(&response, 200, "POST /service/stop");
    assert!(
        agent.data_dir.join("service-stop.requested").is_file(),
        "Fernstopp-Marker fehlt nach erfolgreicher Antwort"
    );

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if agent
            .child
            .as_mut()
            .is_some_and(|child| child.try_wait().ok().flatten().is_some())
        {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "Agent wurde nach der Stopp-Anforderung nicht beendet"
        );
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// 10) Unbekannter Pfad: 404 wie FastAPI.
#[test]
fn unbekannter_pfad_ist_404() {
    let Some(agent) = Agent::start() else { return };

    let response = agent.get("/gibtesnicht");
    expect_status(&response, 404, "GET /gibtesnicht");
    let body = response.json();
    let detail = body["detail"].as_str().unwrap_or_default();
    assert!(
        detail.contains("Not Found"),
        "404 ohne detail 'Not Found': {body}"
    );
}
