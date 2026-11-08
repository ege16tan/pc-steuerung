//! Ausfuehrung der Windows-Befehle (shutdown/powercfg) mit Simulations-Schalter
//! und Erkennung des aktiven Energieplans. Paritaet zum Python-Agent `_run`.

use std::process::{Command, Stdio};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

/// Energieplan-GUIDs (wie Windows-Standard, Werte vom Python-Agent).
pub const PLANS: [(&str, &str); 3] = [
    ("high", "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"),
    ("balanced", "381b4222-f694-41f0-9685-ff5bb260df2e"),
    ("saver", "a1841308-3541-4fab-bc81-f71556f20b4a"),
];

/// Windows-Schalter, damit kein Konsolenfenster aufblinkt.
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Fuehrt einen Befehl aus. `simulate && !real` -> nur Text zurueck.
/// Rueckgabe (Rueckgabecode, Ausgabe), wie Python `_run`.
pub fn run(cmd: &[&str], simulate: bool, real: bool) -> (i32, String) {
    if simulate && !real {
        return (0, format!("simuliert: {}", cmd.join(" ")));
    }
    let mut command = Command::new(cmd[0]);
    command.args(&cmd[1..]);
    command.stdout(Stdio::piped());
    command.stderr(Stdio::piped());
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
    match command.output() {
        Ok(output) => {
            // decoding Paritaet: Python encoding="utf-8", errors="replace".
            let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
            text.push_str(&String::from_utf8_lossy(&output.stderr));
            let code = output.status.code().unwrap_or(-1);
            (code, text.trim().to_string())
        }
        Err(error) => (-1, format!("Befehl nicht gefunden: {error}")),
    }
}

/// Erkennung des aktiven Plans aus der powercfg-Ausgabe:
/// GUID-Vergleich auf kleingeschriebener Ausgabe (wie Python out.lower()).
pub fn detect_active_plan(output: &str) -> String {
    let lowered = output.to_lowercase();
    for (name, guid) in PLANS {
        if lowered.contains(guid) {
            return name.to_string();
        }
    }
    "anderer".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_simuliert_nur_text() {
        let (code, output) = run(&["shutdown", "/s", "/t", "60"], true, false);
        assert_eq!(code, 0);
        assert_eq!(output, "simuliert: shutdown /s /t 60");
    }

    #[test]
    fn detect_active_plan_durch_unterschiedliche_formate() {
        // Typisches echtes powercfg /getactivescheme-Ergebnis:
        let german = "GUID zur Verwaltung von Energieschemata: 381B4222-f694-41f0-9685-ff5bb260df2e (Ausbalanciert)";
        assert_eq!(detect_active_plan(german), "balanced");

        let high = "Energieschema-GUID: 8C5E7FDA-E8BF-4A96-9A85-A6E23A8C635C (Hoechstleistung)";
        assert_eq!(detect_active_plan(high), "high");

        let saver = "a1841308-3541-4fab-bc81-f71556f20b4a (Energiesparen)";
        assert_eq!(detect_active_plan(saver), "saver");

        assert_eq!(detect_active_plan("Nichts Relevantes"), "anderer");
    }
}
