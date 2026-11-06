# AGENTS.md

Kompakte Agenten-Anleitung für dieses Repository.

## Projekt

**PC Steuerung** – Lokale Heimnetz-Steuerung: Android-Handy als Fernbedienung für
einen Windows-PC (Wake-on-LAN, Herunterfahren, Abbrechen, Ruhezustand, Energieplan,
Status).

- `PC App/` – FastAPI-Agent (Python), läuft auf dem Windows-PC.
- `Android App/` – Kotlin/Jetpack Compose-Client, läuft auf dem Handy.

## Befehle

- Agent (PC): `python -m pip install -r requirements.txt`
- Agent (PC): `python -m uvicorn main:app --host 0.0.0.0 --port 8000` (oder `start.bat`)
- Agent startet nur mit `PC_AGENT_TOKEN` (>= 24 Zeichen); leer = Open-Modus (nur lokaler Test).
- `PC_AGENT_SIMULATE` (Default `1`) = Simulation (nur Loggen); `0` = echte Aktion.
  Sicherheitsschalter: nicht ohne explizite Anweisung auf `0` setzen.
- `PC_WOL_BROADCAST` (Default `192.168.1.255`) = Broadcast-Ziel für `/wake`.
- Tray-Steuerung (Taskleiste): `PC App/start-tray.bat` (Agent starten/stoppen,
  Status, Web-UI öffnen).

## Architektur / Wichtige Fakten

- Endpunkte: `GET /status`, `POST /shutdown?delay=`, `POST /abort`, `POST /hibernate`,
  `POST /power-plan`, `POST /wake?mac=&port=`, `GET /events`, `GET /ping`,
  `GET /setup-status`, `POST /setup`, `GET /`.
- Auth: Basic Auth (Benutzername/Passwort, PBKDF2-Hash in `PC App/credentials.json`)
  für LAN-Zugriffe; localhost ohne Login. Ersteinrichtung über die Web-UI
  (`POST /setup`, nur localhost). Alter `PC_AGENT_TOKEN`/`X-Token` bleibt als Fallback.
  `credentials.json` ist gitignored – nie committen.
- Status: TCP-Verbindungsprüfung + `GET /status` (kein Ping).
- Aktionen via `subprocess` (`shutdown /s /t`, `shutdown /a`, `shutdown /h`, `powercfg /setactive`).
- WoL: Die Android-App sendet das Magic Packet direkt per `DatagramSocket`
  (Broadcast-Adresse in den Einstellungen, Default `255.255.255.255`); der Agent
  bietet zusätzlich `/wake` für Web-UI/Tests (`PC_WOL_BROADCAST`). Im
  Simulationsmodus loggt der Agent nur.
- Plan-/Status-Quelle: `docs/planung.md`.

## Gotchas

- Deutsche UI-Strings/Kommentare (teilweise ASCII-Transliteration, z. B. „Pruefung", „fuer").
- Android: minSdk 26, compileSdk/targetSdk 36, Compose BOM `2025.09.00`, Kotlin 2.3.21,
  AGP 8.13.2, JVM-Target 17.
- Android: Homescreen-Widget (`WidgetProvider.kt`, `res/layout/widget.xml`,
  Manifest-Receiver) nutzt RemoteViews und `goAsync()` – kein Compose im Widget.
- Android-Build: `cd "Android App"` → `gradlew.bat assembleDebug`
  (APK: `app/build/outputs/apk/debug/app-debug.apk`).
- WoL: nur zuverlässig mit Ethernet; BIOS/UEFI WoL-Einstellungen nötig.

## Testen

- Agent per `curl` testen (`/status`, `/shutdown?delay=60`, `/abort`, `/power-plan`,
  `/wake?mac=AA:BB:CC:DD:EE:FF&port=9`, …); Simulationsmodus ist Default.
