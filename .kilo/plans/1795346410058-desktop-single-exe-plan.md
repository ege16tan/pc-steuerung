# Plan: Android Steam-Download in Steam-Plugin-Bereich + mehr Plugin-Info

## Ziel
1. Steam-Download-Fortschritt aus der HomeScreen-Kachel in den Steam-Plugin-Bereich
   verschieben (HomeScreen zeigt nur "Steam" als Kachel ohne Fortschritt).
2. Plugin-Kacheln und Detailseiten sollen mehr Informationen zeigen (Status, Uptime,
   Log-Tail, abgerundete Karten) statt nur Start/Beenden.

## Aktueller Zustand
- HomeScreen hat eine Steam-Kachel mit `progress` (Fortschrittsbalken) und
  `steamStatus` (Prozent + Tempo).
- SteamDetails-Composable zeigt Chips (detected/running/millennium), Speed/ETA,
  Download-Liste.
- Plugin-Kacheln zeigen nur Name, Status-Chip, Version, Start/Beenden.

## Umsetzungsschritte
1. HomeScreen: Steam-Kachel vereinfachen (kein progress-Balken, nur Status-Text).
2. SteamDetails: Download-Fortschritt prominent anzeigen (Prozent-Balken, Tempo,
   Restzeit, Spielname) — bereits teilweise vorhanden, erweitern.
3. Plugin-Detailseite: mehr Info (Status, Uptime, Log-Tail, abgerundete Karten).
4. Plugin-Kacheln auf HomeScreen: Status-Text erweitern (z.B. "3 Plugins, 1 aktiv").

## Risiken
- MainActivity.kt ist sehr groß (4784 Zeilen) — Änderungen müssen sorgfältig
  eingefügt werden.
- Steam-Daten nur vorhanden, wenn Steam installiert ist.

## Validierung
- HomeScreen Steam-Kachel zeigt keinen Fortschrittsbalken mehr.
- Steam-Plugin-Bereich zeigt Download-Fortschritt.
- Plugin-Kacheln zeigen mehr Info.
