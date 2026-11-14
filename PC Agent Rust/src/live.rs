//! Live-Updates per Server-Sent Events (SSE) und die CORS-Allowlist fuer die
//! Geraeteliste im Browser (Phase 3).
//!
//! Alle Clients haengen an **einem** Rundruf: die Schleife in [`run_loop`] baut
//! alle zwei Sekunden einen Stand (Status, Messwerte, Ereignisse) und schickt
//! ihn an alle offenen Verbindungen. Ereignisse werden zusaetzlich sofort beim
//! Entstehen verteilt (siehe `journal::log_event`), damit die Liste live wirkt.
//!
//! CORS ist **opt-in**: ohne `PC_AGENT_CORS` antwortet der Agent wie bisher ohne
//! CORS-Kopfzeilen. Erlaubt sind nur exakte Origins (`http://host:port`); ein
//! pauschales `*` gibt es bewusst nicht, weil Anmeldungen mitgeschickt werden.

use std::time::Duration;

use serde_json::Value;
use tokio::sync::broadcast;

use crate::app::Event;
use crate::routes::SharedApp;

/// Abstand der Status-Rundrufe.
pub const STATE_INTERVAL: Duration = Duration::from_secs(2);
/// Puffer fuer kurzzeitig langsame Clients.
const CHANNEL_CAPACITY: usize = 64;
/// So viele Ereignisse haengen am Rundruf (der Ringpuffer hat 50).
const STATE_EVENTS: usize = 20;

/// Eine Rundruf-Nachricht: entweder ein kompletter Stand oder ein einzelnes Ereignis.
#[derive(Clone, Debug)]
pub enum LiveMessage {
    State(Value),
    Event(Event),
}

/// Sender fuer Live-Updates plus die CORS-Allowlist.
pub struct LiveHub {
    tx: broadcast::Sender<LiveMessage>,
    origins: Vec<String>,
}

impl LiveHub {
    pub fn new(origins: Vec<String>) -> LiveHub {
        let (tx, _) = broadcast::channel(CHANNEL_CAPACITY);
        LiveHub { tx, origins }
    }

    /// Neue Verbindung fuer `/events/stream`.
    pub fn subscribe(&self) -> broadcast::Receiver<LiveMessage> {
        self.tx.subscribe()
    }

    /// Nachricht an alle offenen Verbindungen (ohne Empfaenger: verpufft).
    pub fn publish(&self, message: LiveMessage) {
        let _ = self.tx.send(message);
    }

    /// Anzahl offener SSE-Verbindungen (steuert die Rundruf-Schleife).
    pub fn receiver_count(&self) -> usize {
        self.tx.receiver_count()
    }

    /// Ist diese Origin freigeschaltet? Vergleich ohne Gross-/Kleinschreibung
    /// und ohne abschliessenden Schraegstrich.
    pub fn origin_allowed(&self, origin: &str) -> bool {
        let Some(origin) = normalize_origin(origin) else {
            return false;
        };
        self.origins.iter().any(|allowed| allowed == &origin)
    }

    /// Freigegebene Origins (fuer `/status`, damit die UI es erklaeren kann).
    pub fn origins(&self) -> &[String] {
        &self.origins
    }
}

/// `http://host:port` bzw. `https://host`, ohne Slash am Ende, klein geschrieben.
fn normalize_origin(value: &str) -> Option<String> {
    let value = value.trim().trim_end_matches('/');
    if value.is_empty() {
        return None;
    }
    let lower = value.to_ascii_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") {
        Some(lower)
    } else {
        None
    }
}

/// `PC_AGENT_CORS` (Komma-getrennt) in eine Allowlist uebersetzen.
/// Leere/ungueltige Eintraege werden still uebergangen.
pub fn parse_origins(raw: &str) -> Vec<String> {
    let mut origins = Vec::new();
    for part in raw.split(',') {
        let Some(origin) = normalize_origin(part) else {
            continue;
        };
        if !origins.contains(&origin) {
            origins.push(origin);
        }
    }
    origins
}

/// Allowlist aus der Umgebung.
pub fn cors_origins_from_env() -> Vec<String> {
    parse_origins(&std::env::var("PC_AGENT_CORS").unwrap_or_default())
}

/// Kompletter Stand fuer die Rundruf-Nachricht.
pub async fn state_message(app: &SharedApp) -> LiveMessage {
    let status = crate::routes::status_payload(app).await;
    let metrics = crate::metrics::read(&app.metrics);
    let events = {
        let guard = app.inner.lock().expect("Zustands-Sperre");
        guard.events.iter().take(STATE_EVENTS).cloned().collect::<Vec<_>>()
    };
    LiveMessage::State(serde_json::json!({
        "status": status,
        "metrics": metrics,
        "events": events,
    }))
}

/// Hintergrund-Schleife: alle zwei Sekunden ein Rundruf, aber nur solange
/// mindestens eine SSE-Verbindung offen ist (sonst kein `powercfg` im Leerlauf).
pub async fn run_loop(app: SharedApp) {
    let mut ticker = tokio::time::interval(STATE_INTERVAL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticker.tick().await;
        if app.live.receiver_count() == 0 {
            continue;
        }
        let message = state_message(&app).await;
        app.live.publish(message);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn origins_werden_normalisiert() {
        assert_eq!(
            parse_origins("http://192.168.1.5:8000/"),
            vec!["http://192.168.1.5:8000".to_string()]
        );
        assert_eq!(
            parse_origins("HTTP://PC.LOCAL:8000, https://hub.example"),
            vec![
                "http://pc.local:8000".to_string(),
                "https://hub.example".to_string()
            ]
        );
        // Dubletten und Unsinn fliegen raus, leere Eingabe = kein CORS.
        assert_eq!(
            parse_origins("http://a:1, http://A:1 , kaputt,  , ftp://x"),
            vec!["http://a:1".to_string()]
        );
        assert!(parse_origins("").is_empty());
        assert!(parse_origins("192.168.1.5:8000").is_empty());
    }

    #[test]
    fn allowlist_prueft_exakt() {
        let hub = LiveHub::new(parse_origins("http://192.168.1.5:8000"));
        assert!(hub.origin_allowed("http://192.168.1.5:8000"));
        assert!(hub.origin_allowed("HTTP://192.168.1.5:8000/"));
        // Andere Ports/Hosts sind nicht erlaubt, `*` nie.
        assert!(!hub.origin_allowed("http://192.168.1.5:8001"));
        assert!(!hub.origin_allowed("http://evil.example"));
        assert!(!hub.origin_allowed("*"));
        assert!(!hub.origin_allowed(""));
        assert_eq!(hub.receiver_count(), 0);
    }
}

