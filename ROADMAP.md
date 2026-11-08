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

## Geplant

- [ ] Agent-Auswahl Python/Rust in der Web-Oberfläche (gemeinsamer Launcher, wirksam nach Neustart)
- [ ] Module: Katalog künftig per Release erweitern (z. B. Screenshots, Dateien)

- [ ] Finales Design (Restpolitur)
- [ ] Automatisierte Tests für den FastAPI-Agent
- [ ] CI über GitHub Actions
- [ ] Screenshots fürs README
- [ ] Zeitplan (z. B. täglich 23:00 herunterfahren)
- [ ] Home-Assistant-/Tasker-Integration
