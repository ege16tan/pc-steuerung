# AGENTS.md

Kompakte Agenten-Anleitung für dieses Repository.

## Projekt

**PC Steuerung** – Lokale Heimnetz-Steuerung: Android-Handy als Fernbedienung für
einen Windows-PC (Wake-on-LAN, Herunterfahren, Abbrechen, Ruhezustand, Energieplan,
Status).

- `PC Agent Rust/` – Windows-Agent als einzelne Rust-EXE (einzige PC-Version).
- `Android App/` – Kotlin/Jetpack Compose-Client, läuft auf dem Handy.
- Kein GUI-Launcher mehr: Start über `start-agent.bat` bzw. Tray/Autostart.
- Die frühere Python-Version liegt archiviert unter
  https://github.com/ege16tan/pc-steuerung-python und wird nicht mehr gepflegt.

## Befehle

- Standardstart: `start-agent.bat` (Agent + Tray, alles im Hintergrund).
  Debug: `start-agent-debug.bat` (sichtbare Konsole + `agent.log` live).
  Nur Tray: `start-tray.bat`. Web-UI: `http://127.0.0.1:8000/`.
- Agent startet nur mit `PC_AGENT_TOKEN` (>= 24 Zeichen); leer = Open-Modus (nur lokaler Test).
- `PC_AGENT_SIMULATE` (Default `1`) = Simulation (nur Loggen); `0` = echte Aktion.
  Sicherheitsschalter: nicht ohne explizite Anweisung auf `0` setzen.
- `PC_WOL_BROADCAST` (Default `192.168.1.255`) = Broadcast-Ziel für `/wake`.
- Tray-Steuerung: `start-tray.bat` (Web-UI öffnen, Agent starten/stoppen).
  Nur eine Tray-Instanz gleichzeitig (Mutex `Local\PCSteuerungTray`).
- Push-Schutz: `scripts/check-sensitive.sh` (Pre-Push-Hook in `.githooks/`,
  aktivieren mit `scripts/install-hooks.ps1`); GitHub Actions prüft dasselbe.
- **Entwicklungsversion:** öffentlich, Simulationsmodus als Default. Vor einer
  Veröffentlichung die Checkliste in `SECURITY.md` beachten.

## Architektur / Wichtige Fakten

- Endpunkte: `GET /status`, `POST /shutdown?delay=`, `POST /abort`, `POST /hibernate`,
  `POST /power-plan`, `POST /wake?mac=&port=`, `GET /events`, `GET /log?lines=`,
  `GET /ping`, `GET /setup-status`, `GET /pairing-info`, `GET /pairing-qr.svg`,
  `POST /setup`, `POST /mode?simulate=`, `GET /`.
- Datenordner: `%LOCALAPPDATA%\PC Steuerung` (per `PC_AGENT_DATA` überschreibbar).
  Dort liegen `credentials.json`, `token.json`, `device.json`, `agent.log`,
  `agent-manager.log`. Alte Daten aus `PC App/` oder neben der EXE werden beim
  Start einmalig übernommen.
- Logdatei: `agent.log` im Datenordner (Datum/Uhrzeit, Rotation nach `agent.log.1`,
  gitignored).
- Auth: Basic Auth (Benutzername/Passwort, PBKDF2-Hash in `credentials.json`)
  für LAN-Zugriffe; localhost ohne Login. Ersteinrichtung über die Web-UI
  (`POST /setup`, nur localhost). Alter `PC_AGENT_TOKEN`/`X-Token` bleibt als Fallback.
  `credentials.json` ist gitignored – nie committen.
- Status: TCP-Verbindungsprüfung + `GET /status` (kein Ping).
- Aktionen via `std::process::Command` (`shutdown /s /t`, `shutdown /a`,
  `shutdown /h`, `powercfg /setactive`).
- WoL: Die Android-App sendet das Magic Packet direkt per `DatagramSocket`
  (Broadcast-Adresse in den Einstellungen, Default `255.255.255.255`); der Agent
  bietet zusätzlich `/wake` für Web-UI/Tests (`PC_WOL_BROADCAST`). Im
  Simulationsmodus loggt der Agent nur.
- Plan-/Status-Quelle: `docs/planung.md`.
- Android-Marktplatz (ab v0.6.0): Basis (An/Aus, Status, Ruhezustand, Energieplan)
  bleibt immer drin; Zusatz-Features sind Module (`Modules.kt`: Datenmodell, Katalog-Parsing,
  `ModuleStore`, `SUPPORTED_MODULES`). Remote-Katalog:
  `module-catalog/modules.json` im Repo (raw.githubusercontent.com), lokaler Fallback
  bei offline. Installierte Modul-IDs: SharedPreferences `"settings"`,
  Key `installed_modules` (JSON-Array).

## Gotchas

- Deutsche UI-Strings/Kommentare (teilweise ASCII-Transliteration, z. B. „Pruefung", „fuer").
- Windows: **kein GUI-Launcher** mehr – der Agent läuft im Hintergrund, bedient wird
  er über Tray-Symbol und Web-UI (`http://127.0.0.1:8000/`).
- Tray startet automatisch: `agent-manager.ps1 -Action start` ruft `Start-TrayIfNeeded`
  auf; der Mutex `Local\PCSteuerungTray` verhindert doppelte Symbole.
- Debug: `start-agent-debug.bat` startet den Agenten in einer sichtbaren Konsole und
  zeigt `agent.log` live (Zwischeninfos, kein Hardcore-Debugging).
- Credits: genutzte Fremdprojekte/Skills in `CREDITS.md` und im Vault (`Credits`) pflegen.
- Android: minSdk 26, compileSdk/targetSdk 36, Compose BOM `2025.09.00`, Kotlin 2.3.21,
  AGP 8.13.2, JVM-Target 17. Kein Homescreen-Widget mehr (bewusst entfernt).
- Android-Texte liegen in `res/values/strings.xml`; neue UI-Texte dort ergänzen.
- Android-Build: `cd "Android App"` → `gradlew.bat assembleDebug`
  (APK: `app/build/outputs/apk/debug/app-debug.apk`).
- Windows-Installer: Rust-Build + Launcher + Inno Setup mit
  `installer/build-installer.ps1`; Installation pro Benutzer, ohne Admin-Rechte.
- Android-Ersteinrichtung: PC-Web-UI zeigt einen QR-Code mit PC-Name/IP/Port und
  optional der WoL-MAC; Android scannt ihn. Zugangsdaten werden nie im QR-Code abgelegt.
- WoL: nur zuverlässig mit Ethernet; BIOS/UEFI WoL-Einstellungen nötig.

## Testen

- Agent per `curl` testen (`/status`, `/shutdown?delay=60`, `/abort`, `/power-plan`,
  `/wake?mac=AA:BB:CC:DD:EE:FF&port=9`, …); Simulationsmodus ist Default.
