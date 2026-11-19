# Steam-Plugin, Kachel-Design und Now Bar

Stand: 18.11.2026 · Quelle: Wunschliste des Nutzers (eine Nachricht, hier
zerlegt). Diese Datei ist die **verbindliche Checkliste** – erst abhaken, wenn
es nachweisbar laeuft (`cargo test`, `gradlew assembleBeta`/`assembleDebug`,
Live-Test gegen den Agenten).

## 1. Zielbild

Die App hoert auf, nur „PC an/aus" zu sein. Das **Home-Menue wird ein
Kachel-Raster**; jede Kachel fuehrt in eine eigene Aktion/Detailseite
(Steuerung, Steam, Plugins, Markt+, Routinen, ...). Neue Funktionen kommen als
**Plugin** vom Markt+ – die App bleibt schlank.

## 2. Steam + Millennium (Plugin)

- [ ] **Plugin `steam-millennium`** im Markt+ ladbar (kein eingebautes Plugin,
      es soll bewusst ueber den Katalog kommen).
- [ ] Erkennt **Steam-Installation** und **aktive Downloads**:
      `steamapps\\appmanifest_<appid>.acf` liefert `StateFlags`,
      `BytesDownloaded`, `BytesToDownload`, `LastUpdated`; Bibliotheken ueber
      `steamapps\\libraryfolders.vdf`.
- [ ] **Millennium** (Steam-Client-Modding) erkennen: Ordner/Hinweis im
      Steam-Verzeichnis (`millennium`/`plugins`), plus Klartext-Status
      „Millennium erkannt / nicht erkannt" – keine Installation, nur Erkennung.
- [ ] Fortschritt als `pc.setStatus(...)` und Logzeilen mit `>` (landen im
      Journal), damit App und Web-UI es sehen.
- [ ] **Keine** Dateisystem-Freigabe fuer Plugins: der lesende Teil gehoert in
      den Rust-Agenten (`src/steam.rs`), das Plugin bekommt nur `pc.steam()`.

### Technischer Vertrag (gilt fuer alle drei Bausteine)

```text
GET /steam                      -> SteamStatus (siehe unten)
GET /downloads                  -> Downloads-Liste (read-only, MVP)
```

```json
{
  "detected": true,
  "steam_path": "D:\\Steam",
  "millennium": false,
  "running": true,
  "downloading": true,
  "progress_percent": 42.5,
  "bytes_downloaded": 12345678,
  "bytes_total": 29000000,
  "speed_bps": 4200000,
  "eta_seconds": 300,
  "app_id": "730",
  "app_name": "Counter-Strike 2",
  "downloads": [
    { "app_id": "730", "name": "Counter-Strike 2", "percent": 42.5,
      "percent_staged": 55.0,
      "bytes_downloaded": 12345678, "bytes_total": 29000000,
      "bytes_staged": 15950000, "bytes_to_stage": 29000000,
      "speed_bps": 4200000, "eta_seconds": 300 }
  ]
}
```

`percent` = `BytesDownloaded / BytesToDownload` (Download-Wert), `percent_staged`
= `BytesStaged / BytesToStage` (Platte). Beide weichen bei Updates bewusst ab;
`speed_bps`/`eta_seconds` sind beim ersten Aufruf 0 (zwei Messungen noetig).
Die Kopfzeile (`app_*`, `progress_percent`, `speed_bps`, `eta_seconds`) beschreibt
den **am weitesten fortgeschrittenen** Download.

Neue Plugin-Freigabe: `steam` → `pc.steam()`. Neue Datei
`PC Agent Rust/src/steam.rs`, Route in `routes.rs`, Host-Funktion in
`jsplugins.rs` (Vorspann `PRELUDE` + `host_call`).

## 3. Repo/Katalog, damit neue Plugins leicht dazukommen

- [ ] `plugin-catalog/README.md`: wie ein Plugin-Paket aufgebaut ist
      (`id`, `name`, `version`, `permissions`, `entry`, `files{...}`) und wie
      man es in `plugins.json` eintraegt.
- [ ] Hilfsskript `scripts/new-plugin.ps1`: legt Paket-JSON + Katalogeintrag
      aus Vorlage an (kein Copy-Paste-Fehler).
- [ ] Katalog-URL bleibt `CATALOG_URL` in `jsplugins.rs`; Pakete liegen unter
      `plugin-catalog/<id>.json`, Auslieferung per `raw.githubusercontent.com`.

## 4. App: Kachel-Design (Home)

- [ ] Start-Tab wird ein **Kachel-Raster** (2 Spalten, grosse Touch-Flaechen,
      Material-3-Farben wie bisher).
- [ ] Kacheln: **Steuerung** (an/aus, Status), **Steam** (Downloads),
      **Plugins**, **Markt+**, **Routinen**, **Verlauf**.
- [ ] Tippen auf eine Kachel oeffnet die **Aktionsseite** (eigener Screen mit
      Zurueck-Pfeil), nicht mehr alles auf einer Seite.
- [ ] Bestehende Funktionen bleiben erreichbar (keine Regression): Timer,
      Dialoge, Einstellungen, Profilwechsel.
- [ ] Texte in `res/values/strings.xml`.

## 5. Samsung Now Bar / Live-Notification

- [ ] Laufender Steam-Download als **Live-Notification** (ProgressBar,
      Prozent, „PC laedt CS2 – 42 %"), aktualisiert im Takt.
- [ ] Beim **Herunterfahren**: Countdown als Live-Notification
      (`pending_seconds` aus `/status`), Abbrechen direkt aus der Notification.
- [ ] Beim **Einschalten**: Statuszeile „PC startet ...", bis `/status`
      antwortet (WoL braucht 20–60 s).
- [ ] Umsetzung: Foreground-Service + `NotificationCompat`-Progress
      (`setOngoing`, `setOnlyAlertOnce`), Kanal „PC Steuerung live".
      Samsung zeigt das automatisch in der Now Bar, wenn es eine laufende
      Progress-Notification ist.
- [ ] Eigene Textdatei `res/values/strings_live.xml` (haelt `strings.xml` frei).

## 6. Timer / Countdown

- [ ] Herunterfahren mit Timer: Restzeit sichtbar (Ring + Notification).
- [ ] Einschalten: Fortschritt „warte auf PC", Abbruch nach Timeout.
- [ ] `pending_seconds` ist die einzige Quelle (kein Client-Raten).

## 7. Definition of Done

- [ ] `cargo test` gruen, `cargo build --release` ohne Warnungen.
- [ ] `gradlew.bat assembleDebug` gruen.
- [ ] Live-Test: `/steam` liefert plausible Werte, Plugin startet/stoppt,
      Markt+ findet `steam-millennium`.
- [ ] `ROADMAP.md` + Vault (`PC Steuerung/Neue Ideen - gleiche App.md`) aktuell.

---

## 8. Umsetzungsstand 18.11.2026

**Fertig und live geprueft**

- [x] Agent: `src/steam.rs` (Registry + Fallback-Pfade, `libraryfolders.vdf`,
      `appmanifest_*.acf`, `StateFlags`-Auswertung, Millennium-Erkennung,
      Tempo aus zwei Messungen), `GET /steam` und `GET /downloads`,
      Plugin-Freigabe `steam` → `pc.steam()`.
- [x] Marktplatz-Paket `plugin-catalog/steam-millennium.json` (bewusst **nicht**
      eingebaut), Katalogeintrag, `plugin-catalog/README.md`,
      `scripts/new-plugin.ps1`.
- [x] App: Kachel-Raster als Start (`HomeScreen`), Aktionsseiten je Kachel
      (`Route`-State + `BackHandler`, keine neue Bibliothek).
- [x] Live-Notification: `LiveStatusService` (ONGOING-Progress) fuer
      Steam-Download, Shutdown-Countdown mit „Abbrechen", Startphase;
      Texte in `strings_live.xml`.
- [x] `cargo test` 78 gruen, `cargo build --release` ohne Warnungen,
      `gradlew assembleDebug` gruen.

**Live-Messung (18.11.2026, echter Agent, Echtmodus)**

```text
GET /steam -> detected:true, steam_path:"c:\\steam", millennium:true,
              running:true, downloading:true,
              311210 "Call of Duty: Black Ops III"  0.0 %
              1985810 "Call of Duty(R): Black Ops Cold War" 3.2 %
```

**Offen**

- [x] `git push` + Release **v0.11.0** (18.11.2026): Katalog ist live,
      `GET /plugin-catalog` listet `steam-millennium`, Markt+ findet es.
- [ ] `speed_bps` beim ersten Aufruf 0 (zwei Messungen noetig) – erwartet.
- [ ] Now Bar: Karte erscheint nur, wenn One UI „Live-Benachrichtigungen"
      erlaubt; technisch sind alle Voraussetzungen erfuellt (ongoing, silent,
      Progress, `FOREGROUND_SERVICE_IMMEDIATE`).
- [ ] Routinen-Formular (Anlegen) bleibt vorerst der Web-UI.

## 9. Feinschliff 19.11.2026 (Benachrichtigung + Steam)

**Agent (`src/steam.rs`)**

- [x] `BytesStaged`/`BytesToStage` werden gelesen; `percent_staged` steht
      getrennt neben `percent` (Download-Wert). Grund fuer die Differenz
      29 %/33 %: Steam schreibt die `.acf` in Abstaenden und staged in Bloecken.
- [x] Tempo **je Download** (`speed_bps`) aus zwei Messungen, dazu
      `eta_seconds` (Restzeit). Der erste Aufruf liefert 0 – erwartet.
- [x] `tasklist`-Pruefung (`steam_running`) 10 s gecacht: App, Plugin und
      Benachrichtigung fragen im Sekundentakt, das sparte viele Prozessstarts.
- [x] Kopfzeile von `GET /steam` ist der **am weitesten fortgeschrittene**
      Download (vorher alphabetisch); `eta_seconds` auch auf oberster Ebene.
- [x] Tests: Staging-Parsing, `percent_staged`, `eta_seconds`, Felder in
      `downloads` (zusaetzlich zu den bisherigen 78 Tests).

**Android (`LiveStatus.kt`, `MainActivity.kt`)**

- [x] Textzeile: `42.5 % · 4.2 MB/s · noch 12 Min.` (Tempo dezimal wie Steam,
      Restzeit gestaffelt s/min/Std.), Groesse nur solange kein Tempo bekannt ist.
- [x] Android 16: `Notification.ProgressStyle` (Segment-Gewicht 1000 =
      Promille), `setShortCriticalText("42 %")`, `canPostPromotedNotifications()`
      einmal im Log; `POST_PROMOTED_NOTIFICATIONS` im Manifest.
- [x] Aeltere Androids: derselbe Inhalt als normale Progress-Notification
      (`setProgress(1000, …)`), keine zweite Textquelle.
- [x] Countdown: Balken fuellt sich, Text/Chip zeigen die Restzeit, lokal jede
      Sekunde neu gezeichnet; Netzabfrage alle 3 s (Akku).
- [x] Mehrere Downloads: Prozent/Groesse/Tempo summiert, Restzeit aus
      Restbytes ÷ Summe der Tempi.
- [x] Kachel „Steam": Spiel + Prozent + Tempo und ein duenner Fortschrittsbalken;
      Detailseite zeigt Tempo, Restzeit und (bei Updates) den Plattenstand.

**Plugin `steam-millennium` (v2)**

- [x] Kein Log-Spam: Statuszeile nur bei Aenderung, Fortschritt in
      10-%-Schritten, Start/Fertig/Beendet als Ergebniszeile (`>`).
- [x] Statuszeile mit Tempo und Restzeit, bei mehreren Downloads Anzahl +
      fortgeschrittenster Download; Steam-Start/-Ende einmalig gemeldet.
- [x] Marktplatz-Paket (`plugin-catalog/steam-millennium.json`, Version 2) und
      `plugins.json` aktualisiert; Verhalten mit `node` gegen einen `pc`-Stub
      geprueft.
