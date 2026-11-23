# Kachel-Rework (Phasen A–G)

Stand: 22.11.2026 · Quelle: Auftrag des Nutzers (Startseite, Systemwerte, Plugins als
Kachelraster, Warnungen, Dauerpruefung, Routinen, Verlauf, Einstellungen).

Diese Datei ist die **verbindliche Checkliste**. Abgehakt wird erst, wenn es
nachweisbar laeuft: `cargo test`, `gradlew.bat assembleDebug` und ein Live-Test gegen
den lokalen Agenten (`http://127.0.0.1:8000/`).

## Entscheidungen (statt offener Fragen)

1. **Sensoren:** CPU/RAM aus `metrics.rs`; Datentraeger ueber `GetDiskFreeSpaceEx`,
   Netzwerk ueber `GetIfTable2` (iphlpapi), GPU ueber `nvidia-smi`, nur wenn vorhanden.
   Luefter und Temperaturen bleiben ohne Zusatzbibliothek `available: false` mit
   Begruendung – **niemals Fantasiewerte**. Keine neue Crate.
2. **Warnungen:** laufen im Agenten (`warnings.json`, Takt 30 s), die Android-App
   zeigt sie ueber den bestehenden `LiveStatusService` (ein Hinweis je Ereignis,
   keine Dauerbenachrichtigung).
3. **Plugin-Timeout:** 5 Minuten ohne Aktivitaet → Plugin stoppt; pro Manifest mit
   `idle_timeout_s` ueberschreibbar.

## A) Startseite + Systemwerte

- [x] **A1** Kacheln der Startseite: Steuerung, **Systemwerte**, Energie, Routinen,
      Plugins (Einstieg ins Plugin-Raster), Markt+, Verlauf, Einstellungen. Auf der
      Startseite stehen **keine** Plugin-Inhalte, nur Kurzstatus. Steam hat dort
      bewusst keine Kachel mehr; der Einstieg liegt auf der Plugin-Seite (bis das
      Plugin-Raster aus B steht).
- [x] **A2** `GET /sensors` im Agenten + Seite „Systemwerte“ in der Android-App
      (Unterkacheln CPU, RAM, GPU, Netzwerk, Datentraeger, Luefter, Temperaturen).
      Nicht messbare Werte zeigen „nicht verfügbar“.

**JSON-Vertrag `GET /sensors`** (Lese-Endpunkt, `viewer` erlaubt):

```json
{
  "cpu": { "available": true, "percent": 12.3, "cores": 16 },
  "ram": { "available": true, "used_mb": 9011, "total_mb": 32768, "percent": 27.5 },
  "gpu": {
    "available": true, "name": "NVIDIA GeForce RTX 3070",
    "percent": 34.0, "memory_used_mb": 2048, "memory_total_mb": 8192
  },
  "network": { "available": true, "name": "Ethernet", "rx_bps": 12000.0, "tx_bps": 3000.0 },
  "disks": [{ "name": "C:", "total_gb": 931.5, "free_gb": 250.3, "percent": 73.1 }],
  "fans": { "available": false, "reason": "kein Sensor gefunden" },
  "temperatures": { "available": false, "reason": "kein Sensor gefunden" }
}
```

Regeln: `available: false` immer mit `reason`; fehlende Einzelwerte als `null`
(z. B. `percent` bei der ersten CPU-Stichprobe).

## B) Plugins als Kachelraster

- [x] **B1** Plugin-Seite: Kacheln mit Status (aktiv/bereit/fehlt); Tippen oeffnet eine
      Detailseite (keine Knopf-Liste).
- [x] **B2** Steam-Detail: „aktiv“/„an“, laufende Downloads mit Name, Prozent, Tempo,
      Restzeit; ohne Downloads „Keine Downloads“ (Quelle `GET /steam`).
- [x] **B3** Game-Optimization-Detail: Scan (Steam/Epic/GOG/eigene Ordner), manuell
      hinzufuegen (EXE/Pfad), Eintraege loeschen oder ausblenden (ausgeblendete bleiben
      im Scan, wieder einblendbar), Kill-Liste verwalten.
- [x] **B4** Pro Plugin: „Manager offen lassen“ oder „automatisch schliessen“.

**Vertrag `GET /games`** (neu: `hidden`, manuelle Spiele in der Liste):

```json
{ "scanned_at": "22.11.2026 13:16",
  "games": [ { "name": "Team Fortress 2", "exe": "D:\\Steam\\...\\hl2.exe",
               "source": "steam", "running": false, "hidden": false } ] }
```

`hidden` ist true, wenn EXE-Pfad oder Name in `game-config.json` → `hidden` steht.
`POST /game-config` schreibt den **kompletten** Zustand
(`kill_list`, `scan_paths`, `auto`, `hidden`, `custom`); manuelle Spiele haben
`source: "manuell"`. Ausgeblendete Spiele bleiben in der Antwort, die Frontends
filtern selbst.

## C) Systemwerte-Warnungen

- [x] **C1** Schwellen mit Dauer („CPU > 90 % fuer 30 s“) → Benachrichtigung auf dem
      Handy, eine je Ereignis.
- [x] **C2** Regeln liegen im Agenten (`warnings.json`), Auswertung auch bei
      geschlossener App; die App zeigt sie nur an.

**Umgesetzt (23.11.2026):**

- `src/warnings.rs`: Modell (`WarningRule`), Standardregeln (`cpu-high` 90 %/30 s,
  `ram-high` 90 %/60 s, `disk-low` < 10 % frei/60 s), Validierung
  (max. 20 Regeln, `>`/`<`, Dauer 0–3600 s, eindeutige IDs, fester Messwert-Katalog),
  reine Auswertung `evaluate()` und die Schleife `run_loop()` (Takt 30 s).
- Gemessen wird nur, was eine eingeschaltete Regel braucht: CPU/RAM aus `metrics`,
  Datentraeger aus `sensors::disks()` (Minimum = knappste Platte), GPU nur bei
  einer GPU-Regel (`nvidia-smi`, 5 s zwischengespeichert).
- Ausgeloeste Warnungen landen **genau einmal** im Journal (`warnung`) und im
  Verlauf `triggered` (30 Eintraege, neueste zuerst); beim Rueckfall in den
  Normalbereich folgt ein `warnung`-Eintrag „wieder im Normalbereich“.
- `GET /warnings` (auch `viewer`) liefert Regeln, aktive Warnungen, Verlauf,
  `checked_at` und `interval_s`; `POST /warnings` ersetzt den kompletten Regelsatz
  (nur `admin`, 422 bei ungueltigen Regeln, setzt Laufzeit-Zustand zurueck).
- Android: `Warnings.kt` (Parser, Einmal-Meldung, Begrenzung) und
  `LiveStatusService` fragt `/warnings` alle 30 s; je neuem Ereignis eine eigene
  Benachrichtigung im Kanal „PC Steuerung Warnungen“ (IMPORTANCE_HIGH, antippbar,
  kein Dauerlauf). Gemeldete Zeitpunkte liegen in SharedPreferences
  (`pc_warnings`), ein Nachlauf nach Pause meldet hoechstens die 5 neuesten.
  Hinweis: Die Meldungen kommen ueber den `LiveStatusService`. Ist die Dauerkarte
  in den Einstellungen abgeschaltet und laeuft gerade nichts, beendet sich der
  Dienst - dann werden auch keine Warnungen gemeldet (bewusst kein zweiter
  Dauerlauf).
- Tests: 13 neue Rust-Tests (10 in `warnings.rs`, 1 in `sensors.rs`, 2 in
  `jsplugins.rs`) und 7 Kotlin-Tests (`WarningsTest.kt`).

**JSON-Vertrag `GET /warnings`:**

```json
{
  "rules": [ { "id": "cpu-high", "label": "CPU-Last", "metric": "cpu_percent",
               "op": ">", "threshold": 90.0, "duration_s": 30, "enabled": true } ],
  "active": [ { "id": "cpu-high", "label": "CPU-Last",
                "message": "CPU-Last: 91.4 % > 90.0 % seit 30 s",
                "value": 91.4, "threshold": 90.0, "op": ">", "duration_s": 30,
                "since_s": 30, "since": "2026-11-23 12:00:00",
                "since_epoch": 1763000000.0 } ],
  "triggered": [ { "...": "wie active, neueste zuerst, max. 30" } ],
  "checked_at": "2026-11-23 12:00:10",
  "interval_s": 30,
  "history_max": 30
}
```

`since_epoch` ist der Schluessel der App: pro Zeitpunkt genau eine Meldung.
Messwerte: `cpu_percent`, `ram_percent`, `gpu_percent`, `disk_free_percent`
(freier Anteil der knappsten Platte).

## D) Dauerpruefung vermeiden (Pflichtregel)

- [x] **D1** Sensoren nur lesen, wenn eine Anzeige offen ist oder eine Warnregel sie
      braucht (dann Takt 30 s).
- [x] **D2** Plugins nur auf Anfrage, Stopp nach Inaktivitaets-Timeout.
- [x] **D3** Android: lange Intervalle, wenn die App im Hintergrund ist.

**Stand (23.11.2026):**

- **D1:** `/sensors`, `/metrics` und `/warnings` lesen nur beim Aufruf. Die
  Systemwerte-Seite fragt nur solange sie offen ist (2 s, `repeatOnLifecycle`);
  die Startseite alle 15 s im Vordergrund. Die Warn-Schleife liest je Takt nur
  die Werte ihrer eingeschalteten Regeln.
- **D2:** Manifest-Feld `idle_timeout_s` (Standard 300 s, `0` = nie). Ohne Abruf
  von `GET /plugins/{id}/output` beendet der Agent das Plugin nach dieser Zeit
  (`jsplugins::idle_loop`, Takt 30 s) und schreibt die Begruendung ins Plugin-Log.
  Das eingebaute `game-optimizer` hat bewusst `0`, damit ein Timeout nicht mitten
  im Spiel den Energieplan zuruecksetzt.
- **D3:** Statusabfrage, Kachel-Auffrischung und Systemwerte haengen an
  `repeatOnLifecycle(STARTED)`; im Hintergrund laeuft nur noch der
  `LiveStatusService` (15 s im Leerlauf, 30 s fuer Warnungen).

## E) Routinen verbessern

- [ ] **E1** Ausloeser: Uhrzeit (bestehend) + Ereignisse (Spiel beendet, Download
      fertig, PC inaktiv seit X Minuten).
- [ ] **E2** Bedingungen: Wochentag, Plugin aktiv, Energieplan.
- [ ] **E3** Aktionskette mit Verzoegerung zwischen Schritten.
- [ ] **E4** Vorschau „naechster Lauf“, Test-Knopf (Simulation), Verlauf je Routine.
- [ ] **E5** Vorlagen „Feierabend“ und „Nachts sparen“.
- [ ] **E6** Keine Einschalt-Routinen (der Agent laeuft nur bei eingeschaltetem PC).

## F) Verlauf

- [ ] **F1** Filter nach Kategorie (Steuerung, Routine, Plugin, Fehler), Suche, Fehler
      hervorgehoben, Zeitgruppen (heute, gestern).
- [ ] **F2** Kopieren und Export als Textdatei.
- [ ] **F3** Aufbewahrung in den Einstellungen (Rotation bleibt).

## G) Einstellungen

- [ ] **G1** Gruppiert mit Suche; Aenderungen wirken sofort, wo moeglich.
- [ ] **G2** Zuruecksetzen je Bereich.
- [ ] **G3** Export/Import als JSON, ohne Passwoerter und Tokens.
- [ ] **G4** Plugin-Einstellungen aus dem Manifest-Schema (Text, Zahl, Schalter, Auswahl).
