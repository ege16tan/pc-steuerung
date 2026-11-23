# Plugin-Katalog

Hier liegen die Pakete fuer den Markt+ des PC-Agenten. Ein Paket ist **eine
JSON-Datei** mit Manifest und Quelltext – kein ZIP, keine zusaetzliche Crate.
Beispiel im Ordner: `game-optimizer.json`, `system-monitor.json`,
`steam-millennium.json`.

## Aufbau eines Pakets

Datei: `plugin-catalog/<id>.json`

```json
{
  "id": "mein-plugin",
  "name": "Mein Plugin",
  "description": "Was das Plugin macht (ein Satz).",
  "version": "1",
  "entry": "main.js",
  "permissions": ["log", "status"],
  "interval_ms": 2000,
  "idle_timeout_s": 300,
  "files": {
    "main.js": "\n// Code hier\nfunction tick() { pc.log(\"hallo\"); }\n"
  }
}
```

| Feld           | Bedeutung                                                        |
| -------------- | ---------------------------------------------------------------- |
| `id`           | Kleingeschrieben, ohne Pfad (`a-z`, `0-9`, `-`, `_`, `.`).       |
| `name`         | Anzeigename im Markt+.                                           |
| `description`  | Kurzbeschreibung.                                                |
| `version`      | Text, z. B. `1`. Wird beim Installieren ins Manifest uebernommen.|
| `entry`        | Einstiegsdatei, Standard `main.js` (muss in `files` stehen).     |
| `permissions`  | Freigegebene Host-Funktionen, siehe unten.                       |
| `interval_ms`  | Takt fuer `tick()`; `0` = kein Takt.                             |
| `idle_timeout_s`| Ohne Abruf der Ausgabe nach so vielen Sekunden beenden; `0` = nie. Ohne Angabe gelten 300 s (Pflichtregel D2). |
| `files`        | Dateiname -> Inhalt (alles, was das Plugin braucht).             |

Neue Plugins bestehen also **nur aus Manifest und `files`** – der Agent legt
daraus den Ordner `<Datenordner>\plugins\<id>` mit `plugin.json` und den
Dateien an. Es gibt keinen Installer und keine EXE.

## Rechte (`permissions`)

| Recht     | Funktionen im Plugin                                  |
| --------- | ----------------------------------------------------- |
| `log`     | `pc.log(text)` (Zeilen mit `>` landen im Journal)     |
| `status`  | `pc.status()`, `pc.events()`, `pc.gameConfig()`       |
| `metrics` | `pc.metrics()` (CPU/RAM)                              |
| `power`   | `pc.powerPlan()`, `pc.setPowerPlan()`, `pc.shutdown()`, `pc.abort()`, `pc.hibernate()` |
| `games`   | `pc.games()`, `pc.scanGames()`                        |
| `process` | `pc.killProcesses()`, `pc.boost()`, `pc.cleanMemory()`, `pc.isAdmin()` |
| `steam`   | `pc.steam()` (Steam-Pfad, Millennium, Downloads)       |

Ohne passendes Recht wirft der Aufruf einen Fehler. Zusaetzlich gibt es immer
`pc.setStatus(text)` und `pc.now()`.

## Eintrag in `plugins.json`

Damit der Markt+ das Paket findet, muss es im Katalog stehen:

```json
{
  "id": "mein-plugin",
  "name": "Mein Plugin",
  "description": "Was das Plugin macht (ein Satz).",
  "version": "1",
  "url": "https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/plugin-catalog/mein-plugin.json"
}
```

Das Feld `url` darf fehlen – dann bildet der Agent sie aus `id` und
`CATALOG_URL`. Bestehende Eintraege nicht umbenennen: die `id` ist der
Schluessel fuer Installation und Deinstallation.

## Wie der Agent laedt

1. `GET /plugin-catalog` liest `CATALOG_URL` (Konstante in `jsplugins.rs`,
   `https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/plugin-catalog/plugins.json`).
2. Der Download laeuft ueber `curl.exe` (Windows-Bordmittel, `--max-time 20`);
   schlaegt er fehl, bleibt der eingebaute Ersatz-Katalog leer.
3. `POST /plugins/<id>/install` laedt `<id>.json`, prueft `id`, `entry` und die
   Dateinamen (`..`, Pfadtrenner und `:` sind verboten) und schreibt den Ordner
   in den Datenordner. Start erst danach per `POST /plugins/<id>/start`.

## Neues Paket anlegen

Nicht von Hand kopieren, sondern:

```powershell
.\scripts\new-plugin.ps1 -Id "mein-plugin" -Name "Mein Plugin" `
  -Description "Was es macht." -Permissions "log","status" -IntervalMs 2000
```

Das Skript legt `plugin-catalog/<id>.json` mit einem leeren `main.js`-Geruest
an und traegt den Eintrag in `plugins.json` ein. Doppelte IDs werden
abgelehnt; bestehende Eintraege bleiben erhalten.
