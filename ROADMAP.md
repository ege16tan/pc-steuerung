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

## Geplant

- [ ] Routinen (ersetzt den Zeitplan): Auslöser (Uhrzeit, App-Knopf, Ereignis) + Aktionen
  (Herunterfahren, Einschalten, Energieplan); PC muss dafür wach/entsperrt bleiben
- [ ] Module: Katalog künftig per Release erweitern (z. B. Screenshots, Dateien)

- [ ] Finales Design (Restpolitur)
- [ ] Automatisierte Tests für den Rust-Agenten (HTTP-Endpunkte)
- [ ] CI über GitHub Actions
- [ ] Screenshots fürs README
- [ ] Home-Assistant-/Tasker-Integration
