# Roadmap

## Erledigt

- [x] Android-App: Status, Shutdown, Abort, Ruhezustand, Energieplan, Timer
- [x] Wake-on-LAN direkt vom Handy (Magic Packet per UDP-Broadcast)
- [x] Homescreen-Widget (Ein/Aus + Status)
- [x] Benutzername/Passwort (Basic Auth) statt Token
- [x] Web-Oberfläche mit Ersteinrichtung
- [x] Tray-Steuerung mit Quick-Action-Popup
- [x] Autostart-Skripte (Agent + Tray)
- [x] Agent-Endpunkte im Simulationsmodus getestet
- [x] Dauerhafte Logs mit Datum/Uhrzeit + Verlauf in App und Web
- [x] UI-Redesign: Bottom-Navigation, kombinierter Power-Button, Timer im Shutdown-Dialog
- [x] Default-Timer (Sofort/10/30/60 s) + Custom-Timer in der App
- [x] Debug-Umschalter Simulation/Echtzeit (App, Web-UI, API)
- [x] Widget-Bestätigung + Standard-Timer (öffnet die App)
- [x] Buttons je nach PC-Zustand (online/offline/login)
- [x] Snackbar-Feedback + rote ECHT-Modus-Warnung
- [x] Umlaute in der App-Oberfläche
- [x] Lade-/Sperrzustand (Busy) + Fortschrittsanzeige
- [x] Verbindungstest in der App
- [x] Countdown-Fix (Sekunden statt „0 min") + Duplikat entfernt
- [x] Log-Dialog aktualisierbar + Text auswählbar
- [x] Eingabevalidierung (Port/MAC/Passwort) + Passwort-Sichtbarkeit
- [x] Widget-Status wird von der App aktualisiert
- [x] Mehrere PCs (Profile): verwalten, umschalten, hinzufügen, löschen
- [x] App-Icon (adaptiv, Power-Symbol auf Teal/Navy-Verlauf)
- [x] Unit-Tests: Magic-Packet, MAC-Parsing, Status-Auswertung, Dauer
- [x] Animationen: Statuspuls, weiche Übergänge, Kaskaden-Einblendung, plan- und Kartenfarben
- [x] Fortschrittsbalken als Overlay am oberen Rand (kein Layout-Verschieben mehr), dezenter Statuspuls
- [x] Marktplatz (v0.6.0): 4. Tab „Markt+“ – Basis (An/Aus, Status, Ruhezustand, Energieplan) immer gratis, Timer + Verlauf als installierbare Module
- [x] Remote-Modulkatalog: `module-catalog/modules.json` im Repo, Katalog-Refresh per GitHub + lokaler Fallback offline, 8 neue Unit-Tests
- [x] App-Shortcuts (Long-Press aufs Icon): Einschalten und Herunterfahren
- [x] v0.8.0-preview: Live-Countdown, Pull-to-Refresh, Hell/Dunkel/System, einstellbares Status-Intervall, Wake-Fortschritt, Verlauf kopieren
- [x] In-App-Updater über GitHub Releases mit Android-Systemdialog oder optionaler Shizuku-Installation
- [x] PC-Agent in Rust (1:1 zum Python-Agent, einzelne EXE)
- [x] Rust-only: Python-Agent ins Archiv-Repo ausgelagert, Web-UI/Tray/Installer ohne Versionswechsel
- [x] Android-Einstellungen als gruppierte Kartenübersicht mit Detailseiten für PC-Profile, Darstellung, Updates und App-Info
- [x] QR-Scanner auf CameraX + ML Kit umgestellt (Android 17), Scan auch im Profil-Editor
- [x] Optionaler WoL-Schritt im Android-Assistenten inkl. MAC aus QR und automatischer Suche
- [x] Manueller Launcher `PC Steuerung.exe` für Agent, Tray und Web-UI
- [x] **v0.9.5 (11.11.2026):** Homescreen-Widget entfernt (war nie gewollt), GUI-Launcher ersetzt (Agent läuft rein im Hintergrund), Tray startet automatisch mit dem Agenten, Debug-Konsole `start-agent-debug.bat`, Simulationsmodus ist jetzt die Ausnahme (Banner zeigt Simulation, Echt ist Standard), Statuspunkt bei „PC aus" rot, Setup lässt sich verlassen (Zurück-Button), Markt+ mit Basis-Chips und Versions-Badges, Energieplan mit animierter Aktiv-Leiste, weicher Theme-Wechsel, UI-Texte nach `strings.xml`, `CREDITS.md`
- [x] **v0.9.6 (12.11.2026):** Setup ohne Schritt 0 (Einstieg direkt bei „Verbindung" inkl. QR-Scan, Felder werden automatisch gefüllt), echter Theme-Crossfade (alle Farben animiert) statt Abblend-Hack, Markt+ ist ehrlich (kein Fake-Download: Module sind enthalten, Katalog steuert nur das Angebot), Agent läuft dauerhaft (Autostart-Aufgaben + Tray-Watchdog), Echtbetrieb ist Default (`PC_AGENT_SIMULATE=0`)
- [x] **Web-UI Phase 4 (14.11.2026):** Routinen (Uhrzeit/Wochentage oder manuell, Herunterfahren/Ruhezustand/Energieplan, `routines.json`, Scheduler alle 10 s) inkl. Kachel „Routinen"; eigener Bestätigungsdialog statt `window.confirm()`; gemeinsame Aktionsschicht `actions.rs`
- [x] **Plugin-System neu (15.11.2026):** JavaScript-Plugins laufen direkt im Agenten (Engine Boa, reines Rust – kein C-Toolchain nötig). Manifest + `main.js` im Datenordner, Freigaben pro Plugin (`log`, `status`, `metrics`, `power`, `games`, `process`), Host-API `pc.*`, `activate()`/`tick()`/`stop()`, Ausgabe im UI. Endpunkte `/plugins`, `/plugin-catalog`, `/plugins/{id}/install`, `DELETE /plugins/{id}`, `/plugins/{id}/bundle`. Eingebaute Plugins `game-optimizer` und `system-monitor`, Markt+ zeigt sie in der Android-App
- [x] **Build-Härtung (15.11.2026):** `windows-link`-Patch erzeugt Alias-Importbibliotheken (`lib*.dll.a`), damit GNU-Toolchain und `cdylib` (boa_engine) ohne `dlltool` linken; `getrandom` fährt den Legacy-Backend (`advapi32`)

## Geplant

> **Neu ab 18.11.2026:** Steam-Plugin (Millennium), Kachel-Startbildschirm und
> Now-Bar-Live-Benachrichtigungen. Verbindliche Checkliste:
> [`docs/steam-plugin-und-kacheln.md`](docs/steam-plugin-und-kacheln.md)

- [x] Steam-Plugin `steam-millennium`: Release **v0.11.0**, Katalog live
      (Markt+ findet es)
- [x] Agent: `GET /steam` + `GET /downloads` (read-only), Plugin-Freigabe `steam`
- [x] Katalog-Infrastruktur: `plugin-catalog/README.md` + `scripts/new-plugin.ps1`
- [x] App: Home als Kachel-Raster mit eigenen Aktionsseiten
- [x] Live-Notification/Now Bar: Steam-Download, Shutdown-Countdown, Startphase
- [x] **Live-Updates + Steam-Feinschliff (19.11.2026):** Tempo und Restzeit in
      Benachrichtigung und Kachel (`speed_bps`/`eta_seconds` je Download),
      Android-16-`Notification.ProgressStyle` mit Statusleisten-Chip
      (`POST_PROMOTED_NOTIFICATIONS`), Countdown tickt lokal im Sekundentakt,
      Steam-Plugin **v2** (Start-/Fortschritts-/Fertig-Meldungen statt Spam)

- [x] Routinen (ersetzt den Zeitplan): Auslöser (Uhrzeit, App-Knopf) + Aktionen
  (Herunterfahren, Ruhezustand, Energieplan); PC muss dafür wach/entsperrt bleiben
- [ ] Routinen erweitern: Ereignis-Auslöser, Android-Kachel
- [ ] Plugin-Katalog per GitHub Release befüllen (Pakete liegen unter `plugin-catalog/`)
- [ ] Web-UI: Plugin-Liste für alle Plugins (statt nur Game-Optimization-Kachel)

- [ ] Finales Design (Restpolitur)
- [ ] Automatisierte Tests für den Rust-Agenten (HTTP-Endpunkte)
- [ ] CI über GitHub Actions
- [ ] Screenshots fürs README
- [ ] Home-Assistant-/Tasker-Integration
