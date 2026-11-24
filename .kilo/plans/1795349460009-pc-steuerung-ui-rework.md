# PC Steuerung – UI/Feature-Rework Plan

## Scope
Android-Kachelraster-Feeling für Web-UI + Agent; Phasen A–G aus Auftrag. Web-UI (page.html/app.js/app.css) spiegelt Android-Verhalten.

## Phase 0: Baseline
- Build prüfen: `cargo test`, `gradlew assembleDebug`, build.ps1.
- Sicherstellen, dass bestehende WIP-Änderungen nicht verloren gehen.

## Phase 1: A – Startseite ohne Plugin-Inhalte
- Home zeigt Kacheln: Steuerung, Systemwerte, Energie, Routinen, Plugins (Einstieg), Verlauf, Einstellungen.
- Plugin-Kachel öffnet Plugin-Raster.
- Tests: Route-States, keine Plugin-Widgets auf Home.

## Phase 2: A2 – Systemwerte-Kachel
- Neue Tile "Systemwerte" mit Unterkacheln CPU, RAM, GPU, Netzwerk, Datenträger, Lüfter/Temperatur (falls verfügbar).
- Agent: Sensor-Abstraktion `src/sensors.rs` mit Fallbacks; Endpunkt `GET /sensors` (oder erweitern /metrics).
- Nicht verfügbare Sensoren -> "nicht verfügbar".
- Polling nur bei offener Anzeige / Warnregel (D1).

## Phase 3: B – Plugins als Kachelraster
- Plugin-Seite: Kacheln mit Status aktiv/bereit/fehlt; Tippen -> Detail.
- Steam-Detail: aktiv/an, Downloads (Name, %, Tempo, Restzeit), sonst "Keine Downloads" aus GET /steam.
- Game-Optimization-Detail: Scan Steam/Epic/GOG/eigene Ordner; manuell hinzufügen; löschen/ausblenden (ausgeblendete bleiben im Scan, re-einblendbar); Kill-Liste verwalten.
- Option "Manager offen lassen"/"automatisch schließen" pro Plugin (Persistenz in plugin.json oder settings).

## Phase 4: C – Systemwerte-Warnungen
- Warnregeln beim Agenten, Persistenz `warnings.json`.
- Schwellen mit Dauer; eine Benachrichtigung je Ereignis.
- App zeigt Regeln/Status nur an.
- Endpunkte: GET/POST/DELETE warnings (admin schreibend).

## Phase 5: D – Dauerprüfung vermeiden
- Sensoren nur bei offener Anzeige oder Warnregel (lang Takt 30s).
- Plugins Timeout bei Inaktivität.
- Android: lange Intervalle im Hintergrund.

## Phase 6: E – Routinen verbessern
- Auslöser: Uhrzeit (bestehend) + Ereignisse (Spiel beendet, Download fertig, PC inaktiv X min).
- Bedingungen: Wochentag, Plugin aktiv, Energieplan.
- Aktionskette mit Verzögerung.
- Vorschau "nächster Lauf", Test-Knopf (Simulation), Verlauf pro Routine.
- Vorlagen (Feierabend, Nachts sparen).
- Keine Einschalt-Routinen.

## Phase 7: F – Verlauf
- Filter Kategorie, Suche, Fehler hervorgehoben, Zeitgruppen.
- Kopieren + Export als Text.
- Aufbewahrung in Einstellungen.

## Phase 8: G – Einstellungen
- Gruppiert + Suche, sofort wirksam.
- Zurücksetzen pro Bereich.
- Export/Import JSON ohne Passwörter/Tokens.
- Plugin-Einstellungen aus Manifest-Schema generiert (Text, Zahl, Schalter, Auswahl).

## Validation
- `cargo test`, `gradlew assembleDebug`, `build.ps1`, check-sensitive.sh.
- Neue Logik mit Tests.

## Open questions (max 3)
1. Soll GPU/Lüfter/Temperatur im ersten Schritt nur lesend (nvidia-smi/WMI) oder via LibreHardwareMonitor als optionale Abhängigkeit?
2. Warn-Benachrichtigungen auf Android: bestehender LiveStatus-Service oder separater Notification-Kanal?
3. Plugin-Timeout-Default (z. B. 5 min Inaktivität) – gewünscht?
PY