# Plan: Live-Notifications Einstellungen + Dauerkarte-Fix

## Ziel
1. Live-Benachrichtigungen (Dauerkarte, Steam-Downloads, Shutdown-Countdown, Warnungen, Startphase) einzeln an/aus schaltbar in den Einstellungen.
2. Dauerkarte ("PC ist an") zuverlaessig anzeigen (Channel IMPORTANCE_DEFAULT bereits gesetzt).

## Betroffene Dateien
- `AppSettings.kt` — neue Felder `liveOnlineCard`, `liveDownloads`, `liveCountdown`, `liveWarnings`, `liveStarting`
- `AppSettingsStore` — load/save
- `LiveStatus.kt` — Filter in `poll()` und `checkWarnings()` anhand der Toggles
- `MainActivity.kt` — Settings-UI (Kategorie "Live-Benachrichtigungen")
- `Settings.kt` — Reset/Import der neuen Felder
- `SettingsTest.kt` — Tests erweitern

## Datenfluss
- Toggles in SharedPreferences `settings` (bestehender Store).
- `LiveStatusService` liest bei jedem Poll `AppSettingsStore.load(this)` und ueberspringt deaktivierte Quellen.
- Dauerkarte nutzt bestehendes `onlineCard` (bereits vorhanden).

## Dauerkarte-Debug
- Logcat Tag `PCSteuerungLive`: pruefen `Live-Updates: erlaubt=..., geeignet=...`.
- System: Benachrichtigungs-Berechtigung + "Live-Benachrichtigungen" fuer App aktiviert.
- Channel IMPORTANCE_DEFAULT (bereits geaendert).

## Risiken
- Viele Toggles erhoehen Komplexitaet; Default alle an.
- Wenn alle aus: Dienst beendet sich selbst (bestehendes Verhalten).

## Validierung
- Build debug erfolgreich.
- SettingsTest gruen.
- Manuell: Toggles umschalten, Karte verschwindet/erscheint.
"@
filePath">
D:\Goodnight my pc\.kilo\plans\1795626087883-live-notifications-settings.md