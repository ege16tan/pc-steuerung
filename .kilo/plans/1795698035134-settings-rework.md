# Plan: Android-Einstellungen neu ordnen und erweitern

## Kontext
Android-App (`Android App/`), Einstellungen in `AppSettings.kt` + `MainActivity.kt` (SettingsTab/SettingsOverview). Aktuell: Aussehen, Steuerung, Verlauf, Live-Benachrichtigungen, Plugin-Auto-Close, Zurücksetzen in einem Tab.

## Ziele
1. PC-Profile: beibehalten, kleine Verbesserungen (z. B. Verbindungstest prominent, Reihenfolge).
2. Verbindungstest aus dem Profilbereich in einen eigenen "Diagnose"/"Verbindung"-Bereich verschieben (oder als eigener Tab), mit Statusanzeige (online/offline, Latenz).
3. Darstellung: mehr Optionen (z. B. Schriftgröße, Akzentfarbe, Kachelgröße), Hell/Dunkel-Schalter verbessern (Segmented Control statt Toggle, Smooth-Transition/Fade beibehalten). Status-Aktualisierungsintervall dort erklären/verschieben.
4. Zurücksetzen: eigener Tab "Zurücksetzen" mit Einzel-Reset pro Bereich (Aussehen, Steuerung, Live, Verlauf, Profile) statt eines globalen Buttons.
5. Dauerkarte ("PC ist an"-Benachrichtigung): eigener Tab "Live-Karte" mit Erklärung + Schaltern.
6. Simulation entfernen (Agent-Seite; Android-Settings-Referenz entfernen).
7. PC-Web-Oberfläche aus Android-Einstellungen entfernen (gehört zum PC/Agent, nicht zur App).
8. Verlauf verbessern (Filter, Export, Zeilenwahl) – optional eigener Tab.
9. Update-Info in "Über die App" verschieben (Version, Update prüfen).
10. Sicherung/Wiederherstellung: Export/Import der Einstellungen (JSON) in Datei/Cloud, mit Auswahl (Profile, Einstellungen, beide).

## Entscheidungen (geklärt)
- Backup: nur App-Einstellungen (keine Profile/Passwörter).
- Reset: nur Einstellungen pro Bereich (Aussehen, Steuerung, Live-Karte, Verlauf), keine Profile.
- Darstellung: Akzentfarbe, Kachelgröße (2/3 Spalten), Statusintervall-Erklärung. Schriftgröße nicht.
- Live-Karte: eigener Tab mit Erklärung + Schaltern.
- Simulation + PC-Web-Oberfläche aus Settings entfernt (bereits teilweise umgesetzt).
- History: Filter + Zeilenwahl (bereits 200/500/1000).

## Umsetzung (Reihenfolge)
1. SettingsDestination erweitern: RESET, LIVE_CARD, BACKUP.
2. SettingsOverview: Einträge für Reset, Live-Karte, Backup hinzufügen; Simulation/WebUI entfernt.
3. Live-Karte-Tab: Erklärungstext + Schalter (onlineCard, liveDownloads, liveCountdown, liveWarnings, liveStarting).
4. Reset-Tab: Buttons pro Bereich (Aussehen, Steuerung, Live-Karte, Verlauf) mit Bestätigungsdialog.
5. Backup-Tab: Export/Import JSON (nur Einstellungen) via Storage Access Framework (SAF).
6. Darstellung: Akzentfarbe-Picker, Kachelgröße-Schalter, Statusintervall-Hinweis.
7. Dark/Light: Segmented Control (System/Hell/Dunkel) statt Toggle, Fade beibehalten.

## Offene Fragen
- Akzentfarbe: welche Palette (Material You / feste Liste)?
- Kachelgröße: wo genutzt (Home-Kacheln)?
