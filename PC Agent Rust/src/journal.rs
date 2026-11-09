//! Journal-Eintraege: Ringpuffer (50, neueste zuerst), Logdatei an der EXE mit
//! Rotation bei >1 MB und Lesen der letzten Zeilen fuer `/log`.
//! Paritaet zum Python-Agent `_log`, `_append_log`, `/log`.

use std::io::Write;
use std::path::Path;

use crate::app::{App, Event, EVENT_MAX, InnerApp};

/// Logzeile wie im Python-Agent: zwei Leerzeichen als Trenner.
pub fn log_line(entry: &Event) -> String {
    let status = if entry.ok { "ok" } else { "FEHLER" };
    format!(
        "{}  {}  {}  {}",
        entry.time, entry.action, entry.detail, status
    )
}

/// Ereignis aufnehmen (Ringpuffer + Logdatei + Konsole), wie Python `_log`.
pub fn log_event(app: &App, inner: &mut InnerApp, action: &str, detail: &str, ok: bool) {
    let time = chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string();
    let entry = Event {
        time,
        action: action.to_string(),
        detail: detail.to_string(),
        ok,
    };
    inner.events.push_front(entry.clone());
    while inner.events.len() > EVENT_MAX {
        inner.events.pop_back();
    }
    let line = log_line(&entry);
    append_line(&app.log_file, &line);
    // Python druckt True/False (gross), damit die Konsole identisch aussieht.
    let ok_text = if ok { "True" } else { "False" };
    println!(
        "[{}] {} {} ok={}",
        entry.time, entry.action, entry.detail, ok_text
    );
}

/// Haengt eine Zeile an; rotiert vorher zu *.log.1, wenn die Datei >1 MB gross ist.
/// Fehler werden still verschluckt (Paritaet zum Python-Agent).
pub fn append_line(file: &Path, line: &str) {
    maybe_rotate(file);
    let result = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(file)
        .and_then(|mut file| writeln!(file, "{line}"));
    let _ = result; // Fehler wie im Python-Agent ignorieren
}

fn maybe_rotate(file: &Path) {
    if let Ok(metadata) = std::fs::metadata(file) {
        if metadata.len() > 1_000_000 {
            let rotated = rotated_path(file);
            // Windows umbenannt nicht ueberschreibend: Erst das Ziel entfernen.
            if rotated.exists() {
                let _ = std::fs::remove_file(&rotated);
            }
            let _ = std::fs::rename(file, &rotated);
        }
    }
}

fn rotated_path(file: &Path) -> std::path::PathBuf {
    let mut name = file
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(".1");
    file.with_file_name(name)
}

/// Letzte `lines` Zeilen der Logdatei (als Text mit Zeilenenden), None wenn
/// die Datei nicht lesbar ist. Die Existenz-Pruefung macht der Aufrufer wie
/// im Python-Agent (Datei fehlt -> leerer Text).
pub fn tail_lines(file: &Path, lines: usize) -> Result<String, String> {
    let bytes = std::fs::read(file).map_err(|error| format!("{error}"))?;
    // Paritaet: Python liest mit errors="replace"; wir wandeln vermeidlich.
    // Python oeffnet Textdateien mit universal-newline: CRLF und einzelnes CR
    // werden beim Lesen in LF umgewandelt.
    let content = String::from_utf8_lossy(&bytes)
        .replace("\r\n", "\n")
        .replace('\r', "\n");
    let tail: Vec<&str> = content.split_inclusive('\n').rev().take(lines).collect();
    Ok(tail.into_iter().rev().collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    fn entry(ok: bool) -> Event {
        Event {
            time: "2026-11-07 12:00:00".to_string(),
            action: "shutdown".to_string(),
            detail: "in 60s".to_string(),
            ok,
        }
    }

    #[test]
    fn log_line_vollstaendig_formatiert() {
        assert_eq!(
            log_line(&entry(true)),
            "2026-11-07 12:00:00  shutdown  in 60s  ok"
        );
        assert_eq!(
            log_line(&entry(false)),
            "2026-11-07 12:00:00  shutdown  in 60s  FEHLER"
        );
    }

    #[test]
    fn events_ringpuffer_neueste_zuerst() {
        let dir = std::env::temp_dir().join(format!("pcagent-puffer-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let app = App::new(String::new(), true, &dir, 8000);
        let mut inner = InnerApp {
            simulate: true,
            sim_plan: None,
            pending_at: None,
            pending_seconds: 0,
            events: VecDeque::new(),
            credentials: None,
            token_credentials: None,
        };
        for index in 0..60 {
            log_event(&app, &mut inner, "aktion", &index.to_string(), true);
        }
        // Nur die 50 neuesten behalten, neueste zuerst:
        assert_eq!(inner.events.len(), 50);
        assert_eq!(inner.events.front().unwrap().detail, "59");
        assert_eq!(inner.events.back().unwrap().detail, "10");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn tail_lines_liefert_letzte_zeilen() {
        let dir = std::env::temp_dir().join(format!("pcagent-tail-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("agent.log");
        std::fs::write(&log, "eins\nzwei\ndrei\n").unwrap();

        assert_eq!(tail_lines(&log, 2).unwrap(), "zwei\ndrei\n");
        assert_eq!(tail_lines(&log, 999).unwrap(), "eins\nzwei\ndrei\n");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
