# Plan: PC Steuerung – Sicherheit & Robustheit

## Kontext

Projekt: PC Steuerung (Windows-Rust-Agent, Android-App, WinForms-Desktop-App, Skripte).
Der Nutzer hat eine detaillierte Liste von Verbesserungsvorschlägen in 6 Kategorien erstellt und Top-5 Prioritäten definiert.

**Top-5 (reihenfolge):**
1. Rate-Limit + konstanter Zeitaufwand in `auth.rs`
2. Hash-/Signaturprüfung für Plugin-Downloads in `jsplugins.rs`
3. `saveWarningsRules`-Bug + `optIntOrNull`-Problem in Android (`MainActivity.kt`, `Warnings.kt`)
4. Blockierende Handler (`plugin_catalog_ep`, `games_ep`) in `spawn_blocking` + Mutex-Poisoning abfangen
5. `MainActivity.kt` in ViewModel + Screen-Dateien zerlegen

Weitere wichtige Punkte: `.expect()`-Ersetzung, Code-Deduplizierung, `DURATION_MAX_S`-Inkonsistenz, Simulationsmodus-Default, Kill-Liste-Blocklist, LiveStatusService-Credentials, SSE statt Polling, strings.xml, Updater-Signatur, Desktop/Legacy-Aufräumung, Installer, Web-UI-Modularisierung, CI.

---

## Entscheidungen (binding)

| # | Entscheidung | Begründung |
|---|-------------|------------|
| D1 | Rate-Limit: 5 fehlgeschlagene Login-Versuche pro IP → 60s Sperre | Einfach, ausreichend für LAN |
| D2 | Konstantzeit-Vergleich: Immer PBKDF2 rechnen (auch bei unbekanntem User), Dummy-Hash für nicht-existente User | Verhindert Timing-Attacke auf Usernamen |
| D3 | Plugin-Katalog-Einträge bekommen `sha256`-Feld; Download wird vor Installation geprüft | Manipulationsschutz |
| D4 | `host_ptr` (`usize` + `Box::from_raw`) → `Arc<HostCtx>` in Closure, kein `unsafe` | Memory-Sicherheit, kein Leak bei Fehlerpfaden |
| D5 | `DURATION_MAX_S = 3600` (1 Stunde) | Doku (`planung-kachel-rework.md`, `planung-webui.md`) und `app.js` sagen 3600; Code hat 86400 → Code an Doku anpassen |
| D6 | Simulationsmodus Default: `1` (Simulation) laut `SECURITY.md` und `main.rs` Kommentar; Code-Default `true` = `1` | Einheitlich: Simulation ist Default, Echt nur bewusst |
| D7 | Kill-Liste-Blocklist: `explorer.exe`, `winlogon.exe`, `csrss.exe`, `smss.exe`, `wininit.exe`, `services.exe`, `lsass.exe` | Verhindert Fußschuss mit Admin-Rechten |
| D8 | LiveStatusService bekommt nur Profil-ID, lädt User/Pass aus `ProfileStore` (Keystore) | Keine Klartext-Credentials in Intent-Extras |
| D9 | `saveWarningsRules`: Einmaliger POST mit Body, nicht doppelter Aufruf | Behebt den doppelten/leeren POST |
| D10 | `optIntOrNull`: `0` ist ein gültiger Wert, nicht "fehlt" | Behebt `duration_s = 0` → `30s` Bug |

---

## Aufgaben-Verteilung an Sub-Agenten

### Sub-Agent A: Rust-Agent Sicherheit (`PC Agent Rust/src/`)
**Zuständig:** auth.rs, jsplugins.rs, optimize.rs, routes.rs (kill_list), warnings.rs (DURATION_MAX_S)

1. **auth.rs – Rate-Limit pro IP**
   - `AuthDenied`-Struktur um `ip`-Sperre erweitzen (HashMap<IP, (fail_count, locked_until)>)
   - 5 Fehlversuche → 60s Sperre; nach Erfolg Zähler zurücksetzen
   - Sperre im `App`-Zustand oder separatem `Arc<Mutex<...>>` speichern

2. **auth.rs – Konstantzeit-Username-Vergleich**
   - `verify_password` nie vorzeitig bei `user != expected_user` returnen
   - Immer PBKDF2-Hash rechnen (Dummy-Salt/Hash bei unbekanntem User)
   - Nur das finales Hash-Ergebnis vergleichen

3. **jsplugins.rs – SHA-256 für Plugin-Downloads**
   - `CatalogEntry` um `sha256: Option<String>` erweitern
   - `plugins.json` Einträge um `sha256` ergänzen
   - `download()` gibt `(text, sha256_hex)` zurück; `install_from_url` prüft Hash vor `serde_json::from_str`
   - Bei Hash-Abweichung: Installation abbrechen, Fehler loggen

4. **jsplugins.rs – `host_ptr` entfernen**
   - `register_host` und `run_plugin` nehmen `Arc<HostCtx>` statt `usize`
   - Kein `Box::into_raw` / `Box::from_raw` mehr, kein `unsafe`
   - Bei `spawn`-Fehler kein Leak (Arc wird automatisch dropped)

5. **optimize.rs / routes.rs – Kill-Liste-Blocklist**
   - `kill_list` prüft gegen Blocklist (`explorer.exe`, `winlogon.exe`, `csrss.exe`, `smss.exe`, `wininit.exe`, `services.exe`, `lsass.exe`)
   - Blockierte Einträge werden übersprungen und geloggt

6. **warnings.rs – DURATION_MAX_S**
   - `DURATION_MAX_S` von `86400` auf `3600` ändern
   - Validierungs-Fehlertext entsprechend anpassen

**Validierung:** `cargo test` im `PC Agent Rust`-Ordner, `cargo clippy -D warnings`

---

### Sub-Agent B: Rust-Agent Robustheit (`PC Agent Rust/src/`)
**Zuständig:** routes.rs, main.rs, app.rs, commands.rs, games.rs, jsplugins.rs

1. **Mutex-Poisoning abfangen**
   - Alle `.expect("...Sperre")` auf `Mutex::lock()` ersetzen durch `lock().unwrap_or_else(|e| e.into_inner())` oder Migration zu `parking_lot::Mutex`
   - Besonders: `app.inner`, `app.jsplugins`, `app.plugins`, `app.warnings`, `app.routines`, `app.device_settings`, `app.metrics`, `app.sensors`

2. **Blockierende Handler in `spawn_blocking`**
   - `plugin_catalog_ep`: `jsplugins::catalog(&app)` (curl, bis 20s) → `spawn_blocking`
   - `games_ep`: `running_exes()` (= `tasklist`) → `spawn_blocking`
   - `ping_ep`: Log-Schreiben ist schnell, kann bleiben (oder `spawn_blocking` für Konsistenz)

3. **Code-Deduplizierung**
   - `run()` existiert in `commands.rs` und `optimize.rs` → einheitlich in `commands.rs`
   - `task_list()` existiert in `optimize.rs` und `games.rs` → einheitlich in `games.rs` (oder `commands.rs`)
   - `quoted_value()` / VDF-Parser steht in `games.rs` dreimal → einmal
   - Alte `src/bin/game-optimizer.rs` löschen (Referenz entfällt, Logik ist im JS-Plugin)

4. **Simulationsmodus-Default vereinheitlichen**
   - `main.rs` Zeile 46-48: `.unwrap_or(true)` bleibt (Simulation Default)
   - Desktop-App `AgentController.cs` Zeile 132: `PC_AGENT_SIMULATE=0` explizit setzen – absichtlich für Echtbetrieb bei Desktop-Start
   - Doku (`AGENTS.md`, `SECURITY.md`, `Readme.md`) konsistent halten: Simulation Default, Echt nur bewusst

**Validierung:** `cargo test`, `cargo clippy -D warnings`, manueller Test der Endpunkte `/plugin-catalog`, `/games`, `/shutdown`

---

### Sub-Agent C: Android-App – Warnings-Bug + Core-Fixes (`Android App/app/src/main/java/`)
**Zuständig:** MainActivity.kt, Warnings.kt, Core.kt

1. **`saveWarningsRules` beheben (MainActivity.kt Zeile ~1785)**
   - Aktuell: `pluginAction("/warnings","POST",...) { httpCall(...) }` sendet erst POST ohne Body, dann POST mit Body im Callback
   - Fix: Einmaliger `httpCall(active.base, "/warnings", auth, "POST", body)` direkt, ohne `pluginAction`-Wrapper

2. **`optIntOrNull` beheben (Warnings.kt Zeile 61-63)**
   - `takeIf { it != 0 }` entfernen; `0` ist ein gültiger Wert
   - `duration_s = 0` bleibt `0` (sofort auslösen), wird nicht zu `30s`

3. **Warnings-Editor: `metric`/`op` als Auswahl**
   - `metric`: Auswahl aus `cpu_percent`, `ram_percent`, `gpu_percent`, `disk_free_percent` (statt Freitext)
   - `op`: Auswahl `>` / `<` (statt Freitext)
   - `label`: nicht zufällig, sondern auswahlbasiert oder vorbefüllt

**Validierung:** `./gradlew testDebugUnitTest`, manueller Test der Warnregeln-Seite

---

### Sub-Agent D: Android-App – MainActivity Refactoring (`Android App/app/src/main/java/`)
**Zuständig:** MainActivity.kt

1. **ViewModel einführen**
   - `AppViewModel` mit `StateFlow` für: Profile, Settings, Agent-State, Plugins, Warnungen, Routinen, Spiele, Steam, Sensoren, Ereignisse
   - Repository für HTTP-Calls (OkHttp statt `HttpURLConnection`)
   - UI-State pro Screen als data class

2. **Screen-Dateien aufteilen**
   - `SetupScreen`, `HomeScreen`, `ControlTab`, `WarningsPage`, `RoutinesPage`, `PluginsPage`, `SteamPage`, `SensorsPage`, `GameOptimizationPage`, `HistoryPage`, `SettingsTab`, `MarketPage` als eigene Composable-Dateien
   - `MainActivity` nur noch Navigation + ViewModel-Binding (< 500 Zeilen)

3. **OkHttp statt `HttpURLConnection`**
   - Timeouts, Retries, Connection-Pooling
   - TLS-Pinning vorbereitet (für späteres HTTPS)

**Validierung:** `./gradlew testDebugUnitTest`, App kompiliert, alle Screens funktionsfähig

---

### Sub-Agent E: Android-App – LiveStatusService Credentials + SSE
**Zuständig:** LiveStatus.kt, ProfileStore.kt, MainActivity.kt

1. **LiveStatusService bekommt nur Profil-ID**
   - `startSync(context, profileId: String)` statt `Profile`-Objekt
   - Im Service: `ProfileStore.active(context)` lädt Profil, User/Pass aus Keystore
   - Intent-Extras enthalten nur `profile_id`, kein `user`/`pass`

2. **SSE-Client statt Polling (optional, Phase 2)**
   - `/events/stream` nutzen statt eigenständiger Status-Polling-Schleifen
   - Polling als Fallback bei SSE-Fehler

**Validierung:** `./gradlew testDebugUnitTest`, Dienst startet, Profil lädt, keine Klartext-Credentials in Intents

---

### Sub-Agent F: Android-App – Strings & Updater
**Zuständig:** MainActivity.kt, res/values/strings.xml, AppUpdater.kt

1. **Fehlende `res`-Strings ergänzen**
   - "PC herunterfahren", "PC einschalten", "Timer...", "Zurück", "Weiter", "Speichern" etc. in `strings.xml`
   - Alle hartcodierten deutschen Texte in Compose durch `stringResource()` ersetzen

2. **Updater: APK-Signatur-Prüfung**
   - Nach Download: Zertifikats-Fingerabdruck der APK lesen und mit installierter Version vergleichen
   - Frühe Fehlermeldung bei Nichtübereinstimmung (vor der Installation)

**Validierung:** `./gradlew assembleDebug`, Lint, strings.xml vollständig

---

### Sub-Agent G: Desktop-App & Skripte (`Desktop App/`, `scripts/`)
**Zuständig:** MainForm.cs, AgentController.cs, PcSteuerung.App.csproj, build.ps1, installer/, scripts/

1. **Legacy-Skripte aufräumen**
   - `scripts/tray.ps1`, `start-tray.bat`, `agent-manager.ps1` in `scripts/legacy/` verschieben oder löschen
   - Autostart-Kram (Aufgabenplanung) entfernen, da WinForms-App stabil

2. **Installer widersprüchlichkeiten beheben**
   - `pc-steuerung.iss`: `[Registry]` Run-Key für autostart entfernt (ADR-001: kein Autostart)
   - `build-installer.ps1`: Doppelten Compiler-Suchblock entfernen
   - Versionsnummer zentral halten: `0.13.0` (iss), `0.13.0` (csproj), `0.15.8` (Android) – Abgleich und Dokumentation

3. **`AgentController.FindProcesses`**
   - Bei `MainModule.FileName`-Zugriffsfehler (anderer Benutzer): nicht einfach `false` zurückgeben, sondern Status "unbekannt" oder Port-8000-Prüfung als Fallback

**Validierung:** `powershell -ExecutionPolicy Bypass -File "Desktop App\build.ps1"` baut erfolgreich, EXE startet, Statusanzeige korrekt

---

### Sub-Agent H: Web-UI (`PC Agent Rust/src/`)
**Zuständig:** src/app.js, src/app.css

1. **app.js in ES-Module aufteilen**
   - `<script type="module">` ohne Build-Kette
   - Module: `state.js`, `api.js`, `ui.js`, `tabs.js`, `plugins.js`, `warnings.js`, `routines.js`, `games.js`

2. **CSS-Variablen beheben (app.css)**
   - `--border`, `--fg`, `--primary`, `--primary-fg` definieren (bisher undefiniert, Fallback-Farben)
   - Einheitlich mit `--line`, `--text`, `--teal` oder neue Variablen einführen

3. **Duplikate entfernen**
   - Doppelte Fußzeile "Phase 4" entfernen

**Validierung:** Web-UI lädt, Tabs haben korrekte Farben, kein doppelter Footer

---

### Sub-Agent I: Prozess & Qualität (CI, Tests, Doku)
**Zuständig:** .github/, docs/, AGENTS.md, Readme.md

1. **GitHub-CI-Workflow hinzufügen**
   - `cargo test`, `cargo clippy -D warnings` (Rust)
   - `./gradlew testDebugUnitTest` (Android)
   - `bash scripts/check-sensitive.sh` (Secrets-Check)
   - Integrationstests: `cargo build` vor Test-Erzwingen (nicht still überspringen)

2. **Doku-Drift beheben**
   - README: "Homescreen-Widget" aus Roadmap entfernen (bereits entfernt)
   - AGENTS.md: "Agent startet nur mit PC_AGENT_TOKEN" korrigieren (Code macht das nicht)
   - Versionsnummern zentral dokumentieren

**Validierung:** CI-Workflow läuft grün, Doku konsistent mit Code

---

## Risiken & Rollback

| Risiko | Auswirkung | Rollback |
|--------|-----------|----------|
| Rate-Limit sperrt legitimen Nutzer aus (5 Fehlversuche) | Nutzer kann 60s nicht einloggen | Sperre ist zeitbegrenzt (60s), manueller Reset über Dateilöschung möglich |
| SHA-256 im Katalog fehlt bei alten Einträgen | Download schlägt fehl | Fallback: Download ohne Hash-Prüfung (mit Warnung im Journal) |
| `Arc<HostCtx>` statt `usize` | Keine Verhaltensänderung, nur sicherer | Rückfall auf alten Code möglich (keine API-Änderung) |
| `DURATION_MAX_S` 3600 statt 86400 | Regeln mit >1h Dauer werden abgelehnt | Validierung gibt 422, Nutzer muss Regel anpassen |
| ViewModel-Refactoring | Regression in UI-Zustand | Git-Zustand vor Refactoring verfügbar, Unit-Tests decken Logik ab |

---

## Validierung / Definition of Done

- [ ] `cargo test` im `PC Agent Rust`-Ordner: alle Tests grün
- [ ] `cargo clippy -D warnings`: keine Warnungen
- [ ] `./gradlew testDebugUnitTest` im `Android App`-Ordner: alle Tests grün
- [ ] `powershell -ExecutionPolicy Bypass -File "Desktop App\build.ps1"`: baut erfolgreich
- [ ] Auth-Rate-Limit funktioniert (5 falsche Logins → 60s Sperre)
- [ ] Plugin-Download prüft SHA-256, bricht bei Abweichung ab
- [ ] `saveWarningsRules` sendet genau einen POST mit Body
- [ ] `optIntOrNull(0)` liefert `Some(0)`, nicht `null`
- [ ] Blockierende Endpunkte blockieren Tokio-Worker nicht mehr (`spawn_blocking`)
- [ ] LiveStatusService lädt Profil aus ProfileStore (keine Klartext-Credentials in Intent)
- [ ] CI-Workflow existiert und läuft grün

---

## Offene Fragen

1. Soll der Rate-Limit-Zähler pro IP oder pro (IP, User)-Kombination geführt werden? (Empfehlung: pro IP, einfach und effektiv gegen LAN-DoS)
2. Soll `parking_lot::Mutex` statt `std::sync::Mutex` eingeführt werden? (Erfordert neue Dependency, aber kein Poisoning)
3. Soll die SSE-Migration in Android in diesem Zyklus erfolgen oder als separater Schritt? (Empfehlung: separat, nach ViewModel-Refactoring)
4. Soll `scripts/legacy/` erstellt oder die Skripte gelöscht werden? (Empfehlung: in `legacy/` verschieben, nicht löschen)
5. Welche Versionsnummer soll zentral verwaltet werden? (Empfehlung: `0.13.0` für Desktop/Rust, `0.15.8` für Android – getrennt, da unterschiedliche Release-Zyklen)

---

## Plan-Datei

`.kilo/plans/1795718749755-pc-steuerung-security-robustheit-plan.md`
