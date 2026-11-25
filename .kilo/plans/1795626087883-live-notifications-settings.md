# Plan: v0.15.5 App-Verbesserungen (Version, Updater, About)

## Ziel
1. Versionsnummer auf 0.15.5 (versionCode bump).
2. In-App-Updater sichtbar: aktuelle Version anzeigen + "Update prüfen"/"Jetzt aktualisieren"-Button.
3. "Über die App"-Bereich: PC-Web-UI-Link entfernen; Links zu GitHub, Releases, Credits; Lizenz-Hinweis.
4. Version in Settings-Suche und About konsistent.

## Betroffene Dateien
- `app/build.gradle.kts` — versionCode/versionName
- `MainActivity.kt` — About-Screen (Links, Updater-Button, Version)
- `AppUpdater.kt` — Updater-UI-Integration (besteht bereits, einbinden)
- `strings.xml` / `strings_live.xml` — neue Strings (About, Links, Updater)

## Schritte
1. versionName = "0.15.5", versionCode = 24.
2. About-Screen: entferne PC-Web-UI-Link; füge GitHub-Repo-Link, Releases-Link, Credits-Link hinzu.
3. Updater: Button "Update prüfen" ruft AppUpdater.checkForUpdate() auf; zeigt Status (aktuell / Update verfügbar / Fehler).
4. Version-Anzeige: "Version 0.15.5 (24)" im About-Bereich.

## Risiken
- AppUpdater nutzt GitHub Releases API; Rate-Limit beachten.
- Links müssen im Browser geöffnet werden (Intent.ACTION_VIEW).

## Validierung
- Build debug erfolgreich.
- About-Screen zeigt Version + Links + Updater-Button.
- Updater findet Release 0.15.5 (oder neueres).
"@
filePath">
D:\Goodnight my pc\.kilo\plans\1795626087883-live-notifications-settings.md