# Web-UI: Phasen und offene Punkte

Stand: 23.11.2026 · **aktuelle Checkliste** (die älteren Phasenlisten in
`docs/architecture/webui-konzept.md` §6–§11 und `docs/planung.md` §8–§10 bleiben als
Historie stehen und sind abgeschlossen).

Dateien der Web-UI: `PC Agent Rust/src/page.html`, `src/app.css`, `src/app.js`
(per `include_str!` im Binary, Routen `/`, `/app.css`, `/app.js`).
Prüfbefehle: `node --check "PC Agent Rust/src/app.js"`, `cargo build --release`,
danach `dist\pc-agent.exe` ersetzen und Service neu starten.

## Erledigt

- [x] **Phase 1–4** (13./14.11.2026): Layout/Kacheln, `/metrics`, `pending_seconds`,
      Hell/System, SSE (`GET /events/stream`), Rollen `admin`/`viewer`, Geräteliste
      über `PC_AGENT_CORS`, Routinen, eigener `<dialog>`-Bestätigungsdialog.
- [x] **Phase 5 – Kachel-Rework gespiegelt** (23.11.2026, Commit `80822ec`):
  - [x] Tab **Systemwerte** (`GET /sensors`, Takt 5 s nur bei offenem Tab, „Nicht
        verfügbar" mit Begründung statt Fantasiewerten)
  - [x] Tab **Warnungen** (`GET /warnings` lesen, Regelsatz per `POST /warnings`
        bearbeiten, aktive und ausgelöste Warnungen, Takt 30 s)
  - [x] **Plugins** als Kachelraster, Detailansicht mit Ausgabe (3 s, nur offen),
        Start/Stop/Entfernen, **Einstellungen aus dem Manifest-Schema**
        (`GET/POST /plugins/{id}/settings`), Steam-Detail, Spiel-Verwaltung
        (Ausblenden/Einblenden, eigene Spiele, Kill-Liste)
  - [x] **Routinen** mit Ereignis-Auslösern, Bedingungen, Schrittkette samt
        Wartezeit, `next_run`, Verlauf, Vorlagen (`GET /routine-templates`)

## Offen – Phase W1: Plugin-Katalog (Installieren/Entfernen)

Heute zeigt die Web-UI nur **installierte** Plugins. Es fehlt der Marktplatz.

- [x] Kachel/Abschnitt „Marktplatz" im Plugins-Tab: `GET /plugin-catalog`
      (Einträge mit `id`, `name`, `description`, `version`)
- [x] „Installieren" → `POST /plugins/{id}/install` (nur `admin`), danach Liste neu
      laden; Fehlertext aus `detail` anzeigen
- [x] „Entfernen" gibt es schon in der Detailansicht (`DELETE /plugins/{id}`) –
      im Katalog installierte Einträge als „installiert" markieren
- [ ] Abnahme: Katalog lädt offline aus dem lokalen Fallback, Installation und
      Entfernen wirken ohne Neuladen der Seite

## Offen – Phase W2: Log wie in der Android-App (Phase F)

Heute: einfache Ereignisliste + Logdatei-Text, keine Filter.

- [x] Filter-Chips: Steuerung, Routine, Plugin, Warnung, Fehler (`ok === false`)
- [x] Suchfeld über Aktion + Text
- [x] Zeitgruppen „Heute / Gestern / Älter"
- [x] Fehler hervorheben (Farbe + Text „Fehler"), Leerzustand je Filter
- [x] „Exportieren" als Textdatei (`Blob` + `URL.createObjectURL`, Dateiname
      `verlauf-YYYY-MM-DD.txt`, gefilterter Inhalt)
- [ ] Aufbewahrung: Auswahl der geladenen Logzeilen (200/500/1000,
      `GET /log?lines=`), gespeichert in `localStorage`
- [ ] Kein Hintergrund-Takt (nur bei offenem Log-Tab), `document.hidden` beachten

## Offen – Phase W3: Spiele- und Plugin-Feinschliff

- [ ] Eigene **Suchordner** (`scan_paths`) im Spiele-Detail hinzufügen/entfernen
      (Teil von `POST /game-config`, heute nur über die API möglich)
- [ ] Anzeige, ob ein Plugin per Idle-Timeout gestoppt wurde (`idle_timeout_s`,
      letzte Ausgabezeile/Status reicht)
- [ ] „Automatisch"/`auto`-Schalter der Spiel-Optimierung sichtbar machen
      (`game-config.auto`)

## Offen – Phase W4: Feinschliff, Barrierefreiheit, Bilder

- [ ] Sichtprüfung Handy (≤ 520 px) / Tablet / Desktop 1440 px, Abstände der neuen
      Kacheln (Systemwerte, Warnungen, Plugin-Detail)
- [ ] `aria`-Attribute für neue Schalter (`role="switch"` geprüft), Fokus-Reihenfolge,
      Kontrast der Statusfarben
- [ ] Screenshots fürs README (Übersicht, Systemwerte, Warnungen, Plugin-Detail)
- [ ] Fußzeile/Version aktualisieren (`Phase 4` steht noch im `page.html`)

## Offen – Phase W5 (optional): Live statt Polling

- [ ] Sensoren über SSE mitliefern (`state`-Ereignis um `sensors` erweitern) statt
      5-s-Polling – nur wenn das Polling im Alltag stört
- [ ] Plugin-Ausgabe per SSE statt 3-s-Polling (gleiche Bedingung)

## Kleine Fixes am Rand

- [ ] `warnings.rs`: `DURATION_MAX_S` ist **3600 s**, das UI erlaubt 86400 s →
      entweder Konstante auf 86400 anheben (dann `AGENTS.md` nachziehen) oder das
      Eingabefeld auf 3600 begrenzen
- [ ] `.pluginRow` in `app.css` ist ungenutzt (aufräumen oder wiederverwenden)

## Nicht geplant (bewusst)

- Eigener Hub für mehrere Agenten (Geräteliste bleibt Browser-localStorage + CORS)
- Login-Formular im Browser (Basic Auth bleibt)
- Build-Kette/Framework (reines HTML/CSS/JS, `include_str!`)
