# Web-UI-Konzept „Dashboard" – Abgleich mit dem Agenten

**Stand:** 2026-11-13 · **Status:** geprüft · **Phase 1 umgesetzt** (siehe §8)
**Vorlage:** `docs/webui-konzept.html` (das gelieferte Mockup, reines HTML/CSS/JS)

## 1. Was das Konzept zeigt

- **Sidebar:** Geräte-Liste (Wohnzimmer-PC, Büro-PC) mit Online-Punkt, „＋ Gerät hinzufügen",
  Benutzer-Bereich (Admin, „Benutzer einladen (bald)")
- **Header:** Gerätename, Online/Offline-Pill, klickbare **Simulation/Echt**-Pill
- **Kacheln:** Status (2×2) mit Herunterfahren/Ruhezustand bzw. „PC einschalten",
  CPU mit Sparkline, RAM mit Sparkline, Timer mit Fortschrittsring + Abbrechen + 1 h/2 h,
  Energieplan als Segmente, Game Optimization (Optimieren / Spiele scannen),
  Ereignisse (Logliste), „Benutzer & Rechte (später)"
- **Layout:** Handy zuerst (1 Spalte), ab 700 px 4 Spalten, ab 900 px Sidebar links;
  dunkles Design, Einblend-Animation
- **Wichtig:** Alle Zahlen sind Attrappen – `act()` schreibt nur ins lokale Log,
  `wake()` setzt nach 2,5 s „online", CPU/RAM sind Zufall. Es gibt noch keinen API-Aufruf.

## 2. Element → Endpunkt: was heute schon geht

| UI-Element | Endpunkt heute | Status | Anmerkung |
|---|---|---|---|
| Online/Offline-Pill | `GET /status` | ✅ | inkl. `host`, `simulate`, `setup_required` |
| Herunterfahren | `POST /shutdown?delay=` | ✅ | 0–86400 s |
| Ruhezustand | `POST /hibernate` | ✅ | |
| Abbrechen | `POST /abort` | ✅ | |
| PC einschalten (WoL) | `POST /wake?mac=&port=` | ⚠️ teilweise | **ohne `mac` → 422**; die MAC liegt in `device.json` und kommt über `/pairing-info` |
| Energieplan-Segmente | `POST /power-plan?name=` | ✅ | `high`/`balanced`/`saver` |
| Simulation/Echt umschalten | `POST /mode?simulate=` | ✅ | |
| Ereignisse | `GET /events` | ✅ | Ringpuffer 50, neueste zuerst |
| Timer-Ring | `GET /status` → `pending_shutdown` | ⚠️ teilweise | liefert nur **Restzeit**, nicht die Gesamtdauer → Ring startet falsch |
| CPU-Kachel + Sparkline | – | ❌ **fehlt** | Agent liefert keine CPU-Auslastung |
| RAM-Kachel + Sparkline | – | ❌ **fehlt** | Agent liefert keinen Speicherverbrauch |
| Game Optimization | `GET /plugins`, `GET /games`, `POST /plugins/game-optimizer/start|stop`, `GET /game-config` | ✅ teilweise | Konzept hat **keine Spielauswahl**, keinen Stop, keinen Status |
| Geräte-Liste (mehrere PCs) | – | ❌ **fehlt** | ein Agent = ein PC |
| Benutzer & Rollen | – | ❌ **fehlt** | nur ein Zugangsdaten-Paar (Basic Auth) |
| Gerät hinzufügen (QR) | `GET /pairing-info`, `GET /pairing-qr.svg` | ⚠️ anders | QR dient der **Android**-Einrichtung, nicht dem Hinzufügen weiterer PCs |

## 3. Was im Konzept fehlt (muss dazu, sonst bricht Betrieb)

1. **Ersteinrichtung** – `GET /setup-status` + `POST /setup` (Zugangsdaten) und der
   Setup-Assistent inkl. QR. Ohne sie kommt ein frischer PC nie zu Zugangsdaten.
2. **Verlauf/Logdatei** – `GET /log?lines=` (heute in der Web-UI vorhanden,
   zeigt die Datei `agent.log`; das Konzept hat nur die 50 Ereignisse).
3. **Verbindungsfehler-Zustand** – heute gibt es „Keine Verbindung / Login fehlt".
   Das Konzept kennt nur online/offline; bei 401/Timeout muss es einen klaren Hinweis
   + Retry geben (nicht einfach „Offline" behaupten).
4. **Spielauswahl in der Game-Kachel** – Liste aus `/games` (mit „läuft"-Marker),
   Auswahl oder Auto-Modus, **Status + Stop** des Plugins.
5. **Eigene Verzögerung** – Konzept hat fest 60 s + 1 h/2 h; das Sekundenfeld
   (0–86400) sollte bleiben.
6. **CSRF-Schutz** – Header `X-PC-Steuerung: 1` bei allen `POST`, sonst 403
   (sonst kann jede fremde Website `localhost:8000` steuern; Kimi-Punkt 5).
7. **Barrierefreiheit** – 11-px-Labels, Status nur über Farbe (grün/rot), keine
   `aria`-Attribute, `<i>⏻</i>` ohne Text, Einblend-Animation mit `dataset.done`-Hack.
8. **Dark-only** – die Android-App kann hell/dunkel/System; die Web-UI ist fest dunkel.
   Entweder bewusst dunkel lassen (Entscheidung) oder `prefers-color-scheme` ergänzen.
9. **`confirm()`** – erfüllt unsere Regel „Bestätigung vor destruktiven Aktionen",
   wirkt aber neben dem neuen Design fremd; später eigener Dialog.

## 4. Was im Backend fehlen würde (klein gehalten)

| Ergänzung | Aufwand | Nutzen |
|---|---|---|
| `GET /metrics` → `{cpu_percent, ram_used_mb, ram_total_mb, ram_percent, uptime_s}` | klein (~60 Zeilen FFI, keine neue Crate: `GetSystemTimes` + `GlobalMemoryStatusEx`, letzteres nutzt das Plugin schon) | CPU-/RAM-Kacheln echt statt Attrappe |
| `/status` erweitern: `pending_seconds` (Gesamtdauer), `device_name` | klein | Timer-Ring stimmt, Kopfzeile zeigt den PC-Namen |
| `/wake` ohne `mac` → konfigurierte MAC aus `device.json` | sehr klein | „PC einschalten" braucht keine MAC-Eingabe |
| `X-PC-Steuerung`-Header prüfen (nur localhost-POSTs) | klein | CSRF / Fremdseiten-Schutz |
| `GET /events/stream` (SSE) | mittel | Live-Updates ohne 2-s-Polling (optional) |
| Peer-Liste für mehrere PCs | groß | Sidebar „mehrere Geräte" (siehe Entscheidung 1) |

## 5. Entscheidungen, die **vor** dem Einbau nötig sind

1. **Mehrere PCs in der Sidebar?** Der Agent kennt nur seinen eigenen PC.
   - (a) Nur ein Gerät → Sidebar entfällt / zeigt nur diesen PC
   - (b) Liste im Browser (localStorage) und direkte Aufrufe an andere Agenten
     → braucht CORS + Basic Auth pro Gerät
   - (c) Eigener „Hub"-Dienst, der mehrere Agenten bündelt (großer Umbau)
   → **Empfehlung:** (a) jetzt, (b) später bewusst entscheiden.
2. **Benutzer & Rollen?** Braucht User-Store, Geräte-Token, Rechte-Matrix.
   → **Empfehlung:** erst nach dem Geräte-Token aus Kimis Review, nicht jetzt.
3. **Live-Updates:** Polling alle 2 s (heute, einfach) oder SSE (sauber).
   → **Empfehlung:** Polling behalten, SSE erst wenn es stört.
4. **Frontend-Technik:** Das Konzept ist reines HTML/CSS/JS ohne Build-Kette – passt
   perfekt zu `include_str!`. **Kein React/Vite nötig.**
   → **Empfehlung:** Konzept als Vorlage in `page.html` übernehmen; wenn die Datei zu
   groß wird, in `ui.css` + `ui.js` + `page.html` splitten (3 × `include_str!`).
5. **Auth im Browser:** Basic Auth (Browser-Dialog) bzw. localhost ohne Login.
   Das neue UI muss 401 (Login) und `setup_required` (Assistent) sauber abdecken.

## 6. Vorschlag Reihenfolge

**Phase 1 – UI mit vorhandenen Endpunkten (kein Backend-Eingriff)**
- Layout/Kacheln aus dem Konzept übernehmen (Grid, Farben, Handy zuerst)
- echte Aufrufe: `/status`, `/shutdown`, `/abort`, `/hibernate`, `/power-plan`,
  `/mode`, `/events`, `/plugins`, `/games`
- MAC für WoL aus `/pairing-info` holen
- Setup-Assistent und Verlauf/Log behalten
- Fehlerzustand (401/Timeout) + Retry
- Game-Kachel mit Spielauswahl, Status und Stop

**Phase 2 – kleine Backend-Ergänzungen**
- `GET /metrics`, `pending_seconds` in `/status`, Wake-Fallback-MAC,
  `X-PC-Steuerung`-Header

**Phase 3 – optional**
- SSE, mehrere PCs, Rollen/Rechte, eigenes Dialog-Design

## 7. Dateien

| Datei | Zweck |
|---|---|
| `docs/webui-konzept.html` | gelieferte Vorlage (Referenz, wird nicht ausgeliefert) |
| `docs/webui-konzept.md` | diese Abgleich-Liste |
| `PC Agent Rust/src/page.html` | heute ausgelieferte UI (~28 KB, per `include_str!`) |

## 8. Umsetzung Phase 1 (13.11.2026)

**Entschieden und gebaut:**

- **Ein Gerät:** keine Geräte-Sidebar; Kopfzeile mit PC-Name, Status-Pill und
  klickbarer Modus-Pill. Eine Geräteliste im Browser kommt später (braucht CORS).
- **Keine Benutzer/Rollen-Kachel.** `credentials.json` bleibt ein Benutzer; das lässt
  sich später zu einer Liste erweitern, ohne heute etwas zu verbauen.
- **Polling alle 2 s**, pausiert bei verstecktem Tab (`visibilitychange`).
- **Nur dunkel**, aber alle Farben als CSS-Variablen → Hell/System sind in Phase 2 klein.
- **Barrierefreiheit:** Labels ab 12 px, Status mit Text **und** Symbol
  (`● Online` / `○ Keine Verbindung`), `aria-label` an Ring und Timer-Feld.
- **Dateien getrennt:** `src/page.html` (Gerüst), `src/app.css`, `src/app.js`,
  jeweils `include_str!`; Routen `/`, `/app.css`, `/app.js` ohne Login.
- **Raus:** Zufalls-CPU/RAM (Kachel zeigt `–`), Benutzer-Kachel, Geräteliste.
- **Rein:** Ersteinrichtung (Name → optional WoL → Zugangsdaten → QR), Verlauf
  (`/log?lines=200`), Fehlerzustand bei 401/Timeout („Keine Verbindung" + Retry),
  Game-Kachel mit Spielauswahl, „läuft"-Marker, Start/Auto/Stop/Scan und Plugin-Log.
- **Timer-Ring:** Gesamtdauer wird im Browser gemerkt (nach Start/Planen); nach einem
  Neuladen zeigt die Kachel nur Text, bis `pending_seconds` aus dem Agenten kommt.
- **CSRF:** Kopfzeile `X-PC-Steuerung: 1` bei allen schreibenden Aufrufen; der Agent
  prüft sie für `localhost` (Middleware `csrf_guard`, 403 ohne Header). LAN-Zugriffe
  bleiben bei Basic Auth. Tray sendet die Kopfzeile ebenfalls.

**Im Agenten ergänzt:** `GET /app.css`, `GET /app.js`, `csrf_guard`,
`src/app.css`, `src/app.js`.

**Bewusst offen (Phase 2):** `/metrics` (CPU/RAM), `pending_seconds` in `/status`,
Wake-Fallback-MAC, SSE, mehrere PCs, Rollen/Rechte.

## 9. Umsetzung Phase 2 (13.11.2026)

**Erledigt:**

- **`GET /metrics`** (`src/metrics.rs`): `cpu_percent`, `cpu_cores`, `ram_used_mb`,
  `ram_total_mb`, `ram_percent`, `uptime_s`. CPU aus zwei `GetSystemTimes`-Stichproben
  (erste Antwort `null`), RAM über `GlobalMemoryStatusEx`. Keine neue Crate.
- **`/status`**: `pending_seconds` (Gesamtdauer), `device_name`, `wol_enabled`.
  Der Timer-Ring kennt die Gesamtdauer jetzt auch nach einem Neuladen; die im Browser
  gemerkte Zeit ist nur noch Rückfall.
- **`POST /wake` ohne `mac`**: nutzt die eingerichtete MAC aus `device.json`
  (422, wenn Wake-on-LAN aus ist). Das UI sendet keinen MAC-Parameter mehr.
- **Hell/System**: Palette als CSS-Variablen, Umschalter in der Kopfzeile
  (System → Hell → Dunkel, gespeichert unter `localStorage.pcTheme`); „System"
  folgt `prefers-color-scheme` und reagiert live auf Systemwechsel.
- **CPU-/RAM-Kacheln** zeigen echte Werte plus Sparkline der letzten 30 Messungen.

**Verifiziert:** `cargo test` 41 grün; `/metrics` liefert zuerst `cpu_percent: null`,
danach z. B. `8.8` bei 8 Kernen und 32 % RAM; `/status` enthält `pending_seconds`;
`/wake` ohne MAC → 422 ohne WoL, 200 mit eingerichteter MAC; `node --check` sauber.

**Offen (Phase 3):** SSE, Geräteliste über mehrere Agenten (CORS), Rollen/Rechte.


