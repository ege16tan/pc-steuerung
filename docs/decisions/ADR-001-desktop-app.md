# ADR-001: Desktop-App (Fenster + Tray in einer EXE), kein Autostart

- **Datum:** 2026-11-21
- **Status:** umgesetzt (uncommitted), Release offen
- **Betroffen:** `Desktop App/`, `scripts/`, `installer/`, `Android App/` (Dauerkarte)

## Kontext

Der Rust-Agent war über `.bat`-Dateien, ein PowerShell-Tray und drei Aufgaben in der
Aufgabenplanung angebunden (`\PC Agent` mit Altlast auf `PC App\start.bat`,
`\PC Steuerung Agent`, `\PC Steuerung Tray`). Beim Anmelden erschienen dadurch zwei
Konsolenfenster, es existierten mehrere Startpfade (`.bat`, Tray, Autostart), und die
Bedienung war auf PowerShell-Menüs verteilt.

## Entscheidung

1. **Eine EXE** (WinForms, .NET 8, Ziel `dist\PC Steuerung.exe`) zeigt Fenster **und**
   Tray-Symbol und steuert den Agenten per HTTP (`/status`, `/shutdown?delay=`,
   `/abort`, `/hibernate`) mit der Kopfzeile `X-PC-Steuerung: 1`.
2. **Sichtbare Texte sagen „Service"**; der technische Name `pc-agent.exe` und die API
   bleiben unverändert.
3. **Kein Autostart.** Start über das Desktop-Symbol; die drei PowerShell-Aufgaben sind
   deaktiviert. Der Watchdog in der App startet den Dienst, solange die App läuft
   (pausiert nach manuellem Stopp).
4. **Ein kombinierter Knopf** für Herunterfahren/Abbrechen, gesteuert über
   `pending_shutdown` aus `/status`.
5. **Ablage:** Start-/Notfallskripte nach `scripts/`, Icons nach `assets/`, technische
   Doku nach `docs/architecture/` und `docs/decisions/`.

## Alternativen

- **Fenster direkt in Rust** (z. B. `eframe`): eigenes GUI-Toolkit und zweiter Stack für
  ein Fenster mit acht Knöpfen – verworfen, zu viel Fläche für den Nutzen.
- **Weiter PowerShell-Tray**: Konsolenfenster, zwei Startpfade, kein echtes
  Fenster-Verhalten (X versteckt nicht, keine Einzelinstanz) – verworfen.
- **Windows-Dienst für den Agenten**: bräuchte Admin-Rechte und hätte keinen Tray/
  kein Fenster im Benutzerkontext – für einen Heimnetz-PC unpassend.

## Folgen

- Die App braucht die **.NET 8 Desktop-Runtime** (framework-abhängig, 0,26 MB);
  `build.ps1 -SelfContained` erzeugt eine EXE ohne Runtime-Abhängigkeit.
- Der Agent bleibt unabhängig startbar (Debug-Skript, Web-UI, später Handy/WoL).
- `.bat`/`tray.ps1` bleiben als Notfallweg. Der Installer (`installer\pc-steuerung.iss`)
  ist auf die neue Ablage umgestellt, installiert aber weiterhin den `.bat`-Weg.
