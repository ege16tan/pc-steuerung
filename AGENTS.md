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
- Desktop-Symbol: `scripts\create-desktop-shortcut.ps1` legt „PC Steuerung" auf dem
  Desktop an (startet Agent/Tray und öffnet die Web-UI). Icon erzeugen:
  `scripts\build-icon.ps1` (rendert `PC Steuerung.ico` aus den Android-Vektoren).
- Agent startet nur mit `PC_AGENT_TOKEN` (>= 24 Zeichen); leer = Open-Modus (nur lokaler Test).
- `PC_AGENT_SIMULATE` (Default `0` im Manager) = echte Aktion; `1` = Simulation (nur Loggen).
  Simulation nur bewusst einschalten; destruktive Aufrufe (Shutdown) immer mit Countdown testen.
- `PC_WOL_BROADCAST` (Default `192.168.1.255`) = Broadcast-Ziel für `/wake`.
- `PC_AGENT_CORS` (Default leer) = Komma-getrennte Origins, die die Geräteliste im
  Browser lesen dürfen (z. B. `http://192.168.1.10:8000`). Leer = kein CORS; `*`
  gibt es bewusst nicht.
- Tray-Steuerung: `start-tray.bat` (Web-UI öffnen, Agent starten/stoppen).
  Nur eine Tray-Instanz gleichzeitig (Mutex `Local\PCSteuerungTray`).
- Push-Schutz: `scripts/check-sensitive.sh` (Pre-Push-Hook in `.githooks/`,
  aktivieren mit `scripts/install-hooks.ps1`); GitHub Actions prüft dasselbe.
- **Entwicklungsversion:** öffentlich, Simulationsmodus als Default. Vor einer
  Veröffentlichung die Checkliste in `SECURITY.md` beachten.

## Architektur / Wichtige Fakten

- Endpunkte: `GET /status`, `GET /metrics`, `POST /shutdown?delay=`, `POST /abort`, `POST /hibernate`,
  `POST /power-plan`, `POST /wake?mac=&port=`, `GET /events`, `GET /log?lines=`,
  `GET /events/stream` (SSE), `GET /users`, `POST /users`, `DELETE /users/{user}`,
  `GET /routines`, `POST /routines`, `DELETE /routines/{id}`, `POST /routines/{id}/run`,
  `GET /ping`, `GET /setup-status`, `GET /pairing-info`, `GET /pairing-qr.svg`,
  `POST /setup`, `POST /mode?simulate=`, `GET /` (Web-UI-Gerüst),
  `GET /app.css`, `GET /app.js`,
  `GET /plugins`, `POST /plugins/{id}/start?game=&auto=`, `POST /plugins/{id}/stop`,
  `GET /plugins/{id}/output?lines=`, `GET /plugins/{id}/bundle`,
  `GET /plugin-catalog`, `POST /plugins/{id}/install`, `DELETE /plugins/{id}`,
  `GET /games`, `POST /games/scan`, `GET /game-config`, `POST /game-config`,
  `GET /steam`, `GET /downloads`.
- **Plugins (neu, 15.11.2026):** `src/jsplugins.rs` betreibt JavaScript-Plugins mit
  der eingebetteten Engine **Boa** (reines Rust, kein C-Toolchain nötig). Ein Plugin
  ist ein Ordner im Datenordner: `<Datenordner>\plugins\<id>\plugin.json` (Manifest:
  `id`, `name`, `entry`, `permissions`, `interval_ms`, `builtin`) plus `main.js`.
  Der Agent startet Plugins nur auf Anforderung, jeder Thread bekommt eine eigene
  `boa_engine::Context` und die freigegebenen Host-Funktionen (`pc.log`, `pc.metrics`,
  `pc.status`, `pc.powerPlan`/`setPowerPlan`, `pc.shutdown`/`abort`/`hibernate`,
  `pc.games`/`scanGames`/`gameConfig`, `pc.killProcesses`/`boost`/`cleanMemory`,
  `pc.setStatus`, `pc.now`, `pc.isAdmin`) als `pc.*`; Dateien, Netzwerk und Prozesse
  bleiben unzugänglich. Aufrufe sind JSON über die Brücke `__pc_host(name, json)`.
  Lebenszyklus: `activate()` beim Start, `tick()` alle `interval_ms`, `stop()` beim
  Beenden; der vorherige Energieplan wird von `game-optimizer` zurückgesetzt.
  Verzeichnis: `<Datenordner>\plugins\<id>` (Datenordner hat Vorrang vor `<EXE>\plugins`).
  Eingebaute Plugins (`game-optimizer`, `system-monitor`) werden beim ersten Start
  in den Datenordner geschrieben und sind danach normal bearbeitbar/deinstallierbar.
  Pakete für den Marktplatz sind JSON (`plugin.json` + Dateien), Katalog:
  `plugin-catalog/plugins.json` im Repo (URL `CATALOG_URL` in `jsplugins.rs`),
  Download per `curl.exe`. Ausgabe-Ringpuffer 300 Zeilen, Ergebniszeilen mit `>`
  landen zusätzlich im Journal.
- **Steam + Millennium (18.11.2026, Feinschliff 19.11.2026):** `src/steam.rs` liest
  read-only die Steam-Installation (Registry `HKCU\Software\Valve\Steam`, Fallback-Pfade),
  `steamapps\libraryfolders.vdf` und `steamapps\appmanifest_*.acf`
  (`StateFlags`, `BytesDownloaded`/`BytesToDownload`, `BytesStaged`/`BytesToStage`,
  Tempo und Restzeit **je Download** aus zwei Messungen; `tasklist`-Prüfung 10 s
  gecacht). `percent` bleibt der Download-Wert, `percent_staged` steht getrennt im JSON.
  Millennium wird nur **erkannt** (`<Steam>\millennium` bzw. `<Steam>\plugins\*.json`),
  nichts installiert. Neue Endpunkte `GET /steam`, `GET /downloads`; neue
  Plugin-Freigabe `steam` → `pc.steam()`. Marktplatz-Paket
  `plugin-catalog/steam-millennium.json` (v2, bewusst nicht eingebaut), Doku
  `plugin-catalog/README.md`, Vorlage `scripts/new-plugin.ps1`.
- **Android-UI (18.11.2026):** Start ist ein Kachel-Raster (`HomeScreen`,
  `LazyVerticalGrid` 2 Spalten), jede Kachel öffnet eine Aktionsseite (eigener
  `Route`-State + `BackHandler`, keine Navigation-Bibliothek). `LiveStatus.kt`
  bringt den Foreground-Service `LiveStatusService` (ONGOING-Progress-Notification
  für Steam-Download mit Tempo/Restzeit, Shutdown-Countdown mit „Abbrechen“,
  Startphase); der Countdown tickt lokal im Sekundentakt, der Agent wird alle 3 s
  gefragt. Android 16 nutzt `Notification.ProgressStyle` + `setShortCriticalText`
  mit `POST_PROMOTED_NOTIFICATIONS` (Now Bar), ältere Versionen bekommen denselben
  Inhalt als normale Progress-Notification. Texte in
  `res/values/strings_live.xml`. Samsung Now Bar kann die Karte zeigen – abhängig
  von One UI/„Live-Benachrichtigungen“, nicht erzwingbar.
- **Plugins (alt):** `src/plugins.rs` startet weiter EXE-Hilfsprogramme nur auf
  Anforderung (Manifeste `<EXE>\plugins\*.json` oder `<Datenordner>\plugins\*.json`,
  `bin` + `args`). Das frühere eingebaute `game-optimizer.exe` ist ersatzlos
  entfallen – die Logik liegt jetzt im JavaScript-Plugin.
- **Web-UI (Phase 1+2, 13.11.2026; Phase 3, 14.11.2026; Phase 4, 14.11.2026):** `src/page.html` (Gerüst),
  `src/app.css`, `src/app.js` – per `include_str!` im Binary, ohne Login unter `/`,
  `/app.css`, `/app.js`. **Phase 3:** SSE unter `GET /events/stream` (Rundruf alle 2 s
  als `state`, neue Log-Einträge sofort als `event`; UI fällt bei Fehler auf Polling
  zurück), Geräteliste im Browser (localStorage `pcDevices`/`pcActiveDevice`, fremde
  Agenten brauchen `PC_AGENT_CORS`), Rollen `admin`/`viewer` in `credentials.json`
  (`users`-Liste, `WriteAuth` → 403 für viewer; Kachel „Benutzer & Rechte“).
  Polling alle 2 s (Live-Modus aus), Status/Plan/Timer/Ereignisse/Verlauf und die
  Game-Kachel (Auswahl, Start, Stop, Scan).
  CPU/RAM kommen aus `GET /metrics` (erste Antwort `cpu_percent: null`, weil zwei
  Stichproben nötig sind). Timer-Ring nutzt `pending_seconds` aus `/status`.
  Darstellung: System/Hell/Dunkel über `data-theme` (localStorage `pcTheme`).
- **Routinen (Phase 4, 14.11.2026):** `src/routines.rs` speichert
  `routines.json` (Auslöser `time` mit `HH:MM` + Wochentagen 1–7 oder `manual`,
  1–3 Aktionen `shutdown`/`hibernate`/`power_plan`). Die Schleife prüft alle 10 s;
  `last_run` wird **vor** dem Ausführen gespeichert, verpasste Zeiten werden nicht
  nachgeholt. `src/actions.rs` bündelt die Aktionsausführung für HTTP und Routinen
  (gleiche Simulation/Journal/pending-Werte). Web-UI-Kachel „Routinen“ mit
  An/Aus, „Jetzt“, Löschen und Formular (nur `admin`); eigener
  `<dialog>`-Bestätigungsdialog statt `window.confirm()`.
- **Einschalten per Routine gibt es bewusst nicht:** Der Agent läuft nur, wenn der
  PC an ist; WoL bräuchte ein zweites Gerät oder einen RTC-Wake-Timer.
- **Game Optimization:** JavaScript-Plugin `game-optimizer` (siehe oben), Quelltext
  im Agenten (`GAME_OPTIMIZER_JS` in `jsplugins.rs`), Windows-Helfer in
  `src/optimize.rs`. Energieplan -> Hoechstleistung, Kill-Liste schliessen,
  Arbeitsspeicher bereinigen (Working Sets + Standby-Liste, Admin fuer Standby),
  Spiel-Prioritaet; auf `stop` wird der vorherige Energieplan zurueckgesetzt.
  Das alte EXE-Programm `src/bin/game-optimizer.rs` bleibt als Referenz/Notfall.
  Web-UI-Tab „Game Optimization“. Spiele-Scan (`src/games.rs`) liest Steam, Epic,
  GOG und eigene Ordner.
- Datenordner: `%LOCALAPPDATA%\PC Steuerung` (per `PC_AGENT_DATA` überschreibbar).
  Dort liegen `credentials.json`, `token.json`, `device.json`, `routines.json`,
  `agent.log`, `agent-manager.log`. Alte Daten aus `PC App/` oder neben der EXE
  werden beim Start einmalig übernommen.
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
- Agent läuft dauerhaft: Autostart über `PC Agent Rust\install-autostart.ps1 -WithTray`
  (Aufgabenplanung, „bei Anmeldung") **und** Tray-Watchdog (`tray.ps1` startet den Agenten
  automatisch, wenn er nicht läuft; abschaltbar im Tray-Menü).
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
- **Plugins:** `curl http://127.0.0.1:8000/plugins`,
  `curl -X POST -H "X-PC-Steuerung: 1" http://127.0.0.1:8000/plugins/system-monitor/start`,
  danach `curl "http://127.0.0.1:8000/plugins/system-monitor/output?lines=20"`;
  `GET /plugins/{id}/bundle` liefert das Paket, `DELETE /plugins/{id}` entfernt es.
  Eigene Plugins: Ordner im Datenordner anlegen, Manifest schreiben, Agent neu starten
  (das Manifest wird beim Start gelesen, der Code erst beim Start des Plugins).
- **CSRF:** schreibende Aufrufe von `localhost` brauchen die Kopfzeile
  `X-PC-Steuerung: 1`, sonst antwortet der Agent mit 403 (Schutz gegen fremde
  Webseiten, weil localhost ohne Login erreichbar ist):
  `curl -X POST -H "X-PC-Steuerung: 1" "http://127.0.0.1:8000/shutdown?delay=60"`.
  Zugriffe aus dem LAN bleiben unverändert (dort greift Basic Auth);
  Tray und Web-UI senden die Kopfzeile selbst.
