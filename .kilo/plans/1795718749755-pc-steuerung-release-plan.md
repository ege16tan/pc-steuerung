# Plan: PC Steuerung – Release v0.16.0 (Security & Robustheit)

## Kontext

Alle Sicherheits- und Robustheits-Fixes aus dem vorherigen Plan sind im Code implementiert:
- auth.rs: Rate-Limit + konstantzeit-Vergleich
- jsplugins.rs: SHA-256 + Arc<HostCtx>
- optimize.rs: Kill-Liste-Blocklist
- warnings.rs: DURATION_MAX_S = 3600
- routes.rs: spawn_blocking für plugin_catalog_ep, games_ep
- Alle `.expect()` auf Mutex-Locks ersetzt
- Android: saveWarningsRules, optIntOrNull, LiveStatusService-Credentials
- Desktop: Installer-Autostart entfernt, build-installer.ps1 bereinigt
- Web-UI: CSS-Variablen definiert
- CI-Workflow: checks.yml erweitert

**Was vor einem Release noch fehlt:**
1. Release-Builds (Rust release EXE, Android release APK, Desktop release EXE, Installer)
2. Integrationstests gegen die gebauten Artefakte
3. Versionskonsistenz prüfen (Cargo 0.1.0, Desktop 0.13.0, Android 0.15.8, Installer 0.13.0)
4. GitHub-Release erstellen und Artefakte hochladen

## Entscheidungen (binding)

| # | Entscheidung | Begründung |
|---|-------------|------------|
| R1 | Release-Version: `0.16.0` für alle Komponenten vereinheitlichen | Aktuelle Versionsnummern sind verteilt (0.1.0 / 0.13.0 / 0.15.8 / 0.13.0) |
| R2 | Simulationsmodus bleibt Default (`PC_AGENT_SIMULATE=1` im Code) | SECURITY.md verspricht Simulation als Default |
| R3 | Android APK muss `app-release.apk` heißen | In-App-Updater sucht exakt diesen Namen |
| R4 | Kein Autostart im Installer | ADR-001 sagt „kein Autostart" |
| R5 | CI muss grün sein vor Release | `cargo test`, `cargo clippy -D warnings`, `./gradlew testDebugUnitTest`, `check-sensitive.sh` |

## Aufgaben-Verteilung an Sub-Agenten

Jeder Sub-Agent übernimmt eine eigenständige Build-/Test-Spur und meldet Ergebnis + Artefakt-Pfad zurück.

### Sub-Agent A: Rust-Agent Release-Build & Test
**Zuständig:** `PC Agent Rust/`

1. `cargo build --release` → `target/release/pc-agent.exe`
2. `cargo test` (alle Unit- + Integrationstests)
3. `cargo clippy -D warnings`
4. Simulationsmodus per `curl` testen: `/status`, `/shutdown?delay=60`, `/abort`, `/warnings`
5. Rate-Limit verifizieren: 5 falsche Logins → 60s Sperre (HTTP 429)
6. Plugin-Download mit SHA-256 prüfen: `/plugins/system-monitor/install` mit korrektem/falschem Hash
7. Ergebnis: Pfad zu `pc-agent.exe` + Testergebnis melden

### Sub-Agent B: Android-App Release-Build & Test
**Zuständig:** `Android App/`

1. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
2. `./gradlew testDebugUnitTest` (alle Unit-Tests)
3. `saveWarningsRules`-Bug verifizieren: genau ein POST mit Body an `/warnings`
4. `optIntOrNull(0)` verifiziert: `duration_s = 0` bleibt erhalten
5. LiveStatusService lädt Profil aus ProfileStore (keine Klartext-Credentials in Intent)
6. Ergebnis: Pfad zu `app-release.apk` + Testergebnis melden

### Sub-Agent C: Desktop-App Release-Build & Test
**Zuständig:** `Desktop App/`

1. `powershell -ExecutionPolicy Bypass -File "Desktop App\build.ps1"` → `dist\PC Steuerung.exe`
2. `powershell -ExecutionPolicy Bypass -File "Desktop App\build.ps1" -SelfContained` (optional, ohne .NET-Runtime)
3. EXE startet, Statusanzeige funktioniert, Tray-Symbol erscheint
4. `AgentController.FindProcesses` erkennt Agent auch unter anderem Benutzer (Port-8000-Fallback)
5. Ergebnis: Pfad zu `PC Steuerung.exe` + Testergebnis melden

### Sub-Agent D: Installer & Versionskonsistenz
**Zuständig:** `installer/`, `PC Agent Rust/Cargo.toml`, `Desktop App/PcSteuerung.App.csproj`, `Android App/app/build.gradle.kts`

1. Versionsnummern auf `0.16.0` vereinheitlichen:
   - `Cargo.toml`: `version = "0.1.0"` → `"0.16.0"`
   - `PcSteuerung.App.csproj`: `<Version>0.13.0</Version>` → `<Version>0.16.0</Version>`
   - `app/build.gradle.kts`: `versionName = "0.15.8"` → `"0.16.0"`
   - `pc-steuerung.iss`: `AppVersion = "0.13.0"` → `"0.16.0"`
2. `powershell -ExecutionPolicy Bypass -File "installer\build-installer.ps1"` → `dist\PC-Steuerung-Setup-0.16.0.exe`
3. Installer enthält keine Autostart-Registry (bereits entfernt)
4. Ergebnis: Pfad zu Installer-EXE + Versionsliste melden

### Sub-Agent E: CI & Release-Verifikation
**Zuständig:** `.github/workflows/checks.yml`, `scripts/check-sensitive.sh`

1. CI-Workflow läuft grün: `cargo test`, `cargo clippy -D warnings`, `./gradlew testDebugUnitTest`, `check-sensitive.sh`
2. Integrationstests bauen `cargo build` vor Test-Erzwingen (nicht still überspringen)
3. Pre-Push-Hook prüfen: `scripts/check-sensitive.sh` findet keine Secrets
4. Release-Artikel: `dist\PC Steuerung.exe`, `dist\pc-agent.exe`, `dist\PC-Steuerung-Setup-0.16.0.exe`, `Android App\app\build\outputs\apk\release\app-release.apk`
5. Ergebnis: CI-Status + Liste aller Release-Artefakte melden

## Risiken & Rollback

| Risiko | Auswirkung | Rollback |
|--------|-----------|----------|
| Version 0.16.0 in allen Dateien nicht konsistent | Nutzer verwirrt, Updater findet falsche Version | Alle 4 Version-Dateien zurücksetzen |
| Android Release-APK kann nicht signiert werden (kein Keystore) | Kein updatefähiges APK | Debug-APK bauen, Release verschieben |
| Rust Release-Build schlägt fehl (Linker, Windows-SDK) | Kein pc-agent.exe für Release | Debug-Build als Zwischenlösung |
| CI schlägt fehl (clippy, Android-Tests) | Release blockiert | Fehler beheben, dann neu versuchen |

## Definition of Done (Release)

- [ ] `cargo build --release` erfolgreich → `target/release/pc-agent.exe`
- [ ] `cargo test`: alle Tests grün
- [ ] `cargo clippy -D warnings`: keine Warnungen
- [ ] `./gradlew assembleRelease`: `app-release.apk` erstellt
- [ ] `./gradlew testDebugUnitTest`: alle Android-Tests grün
- [ ] `build.ps1`: `dist\PC Steuerung.exe` erstellt
- [ ] `build-installer.ps1`: `dist\PC-Steuerung-Setup-0.16.0.exe` erstellt
- [ ] Alle Versionsnummern auf `0.16.0` vereinheitlicht
- [ ] CI-Workflow grün
- [ ] Rate-Limit, SHA-256, saveWarningsRules manuell verifiziert
- [ ] GitHub-Release erstellt mit allen Artefakten

## Plan-Datei

`.kilo/plans/1795718749755-pc-steuerung-release-plan.md`
