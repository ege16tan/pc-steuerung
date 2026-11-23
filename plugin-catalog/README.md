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
| `settings`     | Optionales Einstellungs-Schema, siehe unten. Ohne den Block gibt es keine Einstellungen. |
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
| `settings`| `pc.settings()` (die eigenen Einstellungen als Objekt)  |

Ohne passendes Recht wirft der Aufruf einen Fehler. Zusaetzlich gibt es immer
`pc.setStatus(text)` und `pc.now()`. Ausnahme: `pc.settings()` ohne Freigabe
`settings` liefert ein **leeres Objekt** statt eines Fehlers.

## Einstellungen (`settings`)

Ein Plugin darf Felder beschreiben, die die App einstellen kann. Das Schema
steht im Manifest/Paket, die Werte liegen im Plugin-Ordner in `settings.json`
(flaches Objekt `{"key": wert}`) – diese Datei gehoert dem Nutzer, sie wird
**nicht** mit `GET /plugins/<id>/bundle` exportiert und beim Deinstallieren
mit geloescht.

```json
"settings": [
  {"key": "refresh_s", "label": "Aktualisierung (s)", "type": "number", "default": 5, "min": 1, "max": 600},
  {"key": "mode", "label": "Modus", "type": "select", "default": "auto", "options": ["auto", "manuell"]},
  {"key": "notify", "label": "Benachrichtigen", "type": "switch", "default": true},
  {"key": "name", "label": "Name", "type": "text", "default": ""}
]
```

| Feld      | Bedeutung                                                        |
| --------- | ---------------------------------------------------------------- |
| `key`     | Schluessel in `settings.json` und `pc.settings()`; `[a-z0-9_]+`, eindeutig, hoechstens 20 Felder. |
| `label`   | Beschriftung in der Oberflaeche, darf nicht leer sein.           |
| `type`    | `text`, `number`, `switch` oder `select`.                        |
| `default` | Standardwert; fehlt er, gilt `""`, `min`/`0`, `false` bzw. die erste Option. |
| `min`/`max`| Nur bei `number` erlaubt.                                       |
| `options` | Nur bei `select`, mindestens eine Option.                        |

Ungueltige Felder (unbekannter Typ, doppelter Key, `select` ohne Option,
`min`/`max` bei `text`, Standardwert ausserhalb der Grenzen) werden
**verworfen**, nicht das ganze Plugin: der Agent schreibt den Grund beim
Laden ins Journal. Manifeste ohne `settings` verhalten sich wie bisher.

Der Code liest die Werte so:

```js
function tick() {
  var settings = pc.settings();          // {} ohne Freigabe "settings"
  pc.log("Aktualisierung: " + settings.refresh_s + " s, Modus " + settings.mode);
}
```

Endpunkte (Agent): `GET /plugins/<id>/settings` liefert
`{"schema": [...], "values": {...}}`, `POST /plugins/<id>/settings` mit
`{"values": {...}}` prueft und speichert (nur `admin`; 400 bei unbekanntem
Key, 422 bei ungueltigem Wert, 404 bei unbekanntem Plugin).

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
