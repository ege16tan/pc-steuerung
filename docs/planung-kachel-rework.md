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

- [ ] **B1** Plugin-Seite: Kacheln mit Status (aktiv/bereit/fehlt); Tippen oeffnet eine
      Detailseite (keine Knopf-Liste).
- [ ] **B2** Steam-Detail: „aktiv“/„an“, laufende Downloads mit Name, Prozent, Tempo,
      Restzeit; ohne Downloads „Keine Downloads“ (Quelle `GET /steam`).
- [ ] **B3** Game-Optimization-Detail: Scan (Steam/Epic/GOG/eigene Ordner), manuell
      hinzufuegen (EXE/Pfad), Eintraege loeschen oder ausblenden (ausgeblendete bleiben
      im Scan, wieder einblendbar), Kill-Liste verwalten.
- [ ] **B4** Pro Plugin: „Manager offen lassen“ oder „automatisch schliessen“.

## C) Systemwerte-Warnungen

- [ ] **C1** Schwellen mit Dauer („CPU > 90 % fuer 30 s“) → Benachrichtigung auf dem
      Handy, eine je Ereignis.
- [ ] **C2** Regeln liegen im Agenten (`warnings.json`), Auswertung auch bei
      geschlossener App; die App zeigt sie nur an.

## D) Dauerpruefung vermeiden (Pflichtregel)

- [ ] **D1** Sensoren nur lesen, wenn eine Anzeige offen ist oder eine Warnregel sie
      braucht (dann Takt 30 s).
- [ ] **D2** Plugins nur auf Anfrage, Stopp nach Inaktivitaets-Timeout.
- [ ] **D3** Android: lange Intervalle, wenn die App im Hintergrund ist.

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
