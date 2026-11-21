# PC Steuerung -- Empfohlene Projekt- und Dokumentationsstruktur

> **Stand 2026-11-21:** teilweise umgesetzt. Erledigt: Start-/Notfallskripte in
> `scripts/`, Icons in `assets/`, technische Doku in `docs/architecture/` und
> `docs/decisions/` (siehe `docs/README.md`). Offen: Installer-Umbau, README-Großschreibung.

Die aktuelle Projektstruktur ist bereits ordentlich. Sie muss nicht
komplett umgebaut werden. Sinnvoll ist vor allem eine klare Trennung
zwischen **Projektdateien, Entwicklerdokumentation und persönlichen
Notizen**.

Die folgende Struktur ist eine mögliche Zielstruktur und keine Pflicht.
Startdateien können weiterhin im Hauptverzeichnis liegen, wenn das für
den Projektstart sinnvoll ist.

## 1. Empfohlene Projektstruktur

``` text
PC Steuerung/
│
├── AGENTS.md
├── README.md
├── ROADMAP.md
├── SECURITY.md
├── CREDITS.md
├── LICENSE
├── opencode.json
├── .gitignore
├── .gitattributes
│
├── scripts/
│   ├── agent-manager.ps1
│   ├── open-web-ui.ps1
│   ├── tray.ps1
│   ├── start-agent.bat
│   ├── start-agent-debug.bat
│   └── start-tray.bat
│
├── docs/
│   ├── architecture/
│   │   ├── OVERVIEW.md
│   │   ├── PLUGIN-SYSTEM.md
│   │   └── SYSTEM-COMPONENTS.md
│   │
│   ├── api/
│   │   ├── HTTP-API.md
│   │   └── PLUGIN-API.md
│   │
│   ├── development/
│   │   ├── SETUP.md
│   │   ├── BUILD.md
│   │   ├── TESTING.md
│   │   └── DEBUGGING.md
│   │
│   └── decisions/
│       ├── ADR-001-ARCHITECTURE.md
│       └── ADR-002-PLUGIN-SYSTEM.md
│
└── assets/
    └── PC Steuerung.ico
```

Weitere Projektgrafiken können ebenfalls in `assets/` abgelegt werden.

## 2. Was gehört in welche Datei?

  -----------------------------------------------------------------------
  Datei oder Ordner                   Inhalt
  ----------------------------------- -----------------------------------
  `AGENTS.md`                         Regeln und Kontext für
                                      Coding-Agents, Architekturüberblick
                                      und wichtige Entwicklungsregeln

  `README.md`                         Projektbeschreibung und Einstieg:
                                      Was ist PC Steuerung und wie wird
                                      es gestartet?

  `ROADMAP.md`                        Geplante Funktionen, offene
                                      Aufgaben und
                                      Entwicklungsfortschritt

  `SECURITY.md`                       Sicherheitskonzept und bekannte
                                      Einschränkungen

  `CREDITS.md`                        Verwendete Projekte,
                                      Abhängigkeiten, Lizenzen und
                                      Credits

  `LICENSE`                           Projektlizenz

  `opencode.json`                     OpenCode-Konfiguration

  `.gitignore` / `.gitattributes`     Git-Konfiguration

  `scripts/`                          Start-, Verwaltungs- und
                                      Hilfsskripte

  `docs/architecture/`                Erklärung, wie das System intern
                                      aufgebaut ist und funktioniert

  `docs/api/`                         Schnittstellen, HTTP-Endpunkte,
                                      Plugin-API und Datenformate

  `docs/development/`                 Installation, Build, Tests und
                                      Debugging

  `docs/decisions/`                   Begründungen für wichtige
                                      technische Entscheidungen
  -----------------------------------------------------------------------

### Warum ein Ordner für technische Entscheidungen?

Im Ordner `docs/decisions/` können wichtige Architekturentscheidungen
dokumentiert werden. Beispielsweise, warum eine bestimmte
Plugin-Architektur oder Kommunikationsmethode gewählt wurde.

Das hilft auch Coding-Agents: Sie können nachvollziehen, warum etwas auf
eine bestimmte Weise umgesetzt wurde, statt eine bestehende Lösung ohne
Kenntnis der ursprünglichen Gründe umzubauen.

## 3. Empfohlene Obsidian-Vault-Struktur

Der Obsidian Vault sollte als internes Entwickler-Wiki dienen. Dort
können längerfristige Planung, technische Recherche, Ideen und
persönliche Entwicklungsnotizen gesammelt werden.

``` text
PC Steuerung Vault/
│
├── 00 - Dashboard/
├── 01 - Ideen/
├── 02 - Planung/
├── 03 - Architektur/
├── 04 - Recherche/
├── 05 - Entwicklung/
├── 06 - Plugins/
├── 07 - UI-UX/
├── 08 - Entscheidungen/
├── 09 - Changelog/
└── 10 - Archiv/
```

  -----------------------------------------------------------------------
  Ordner                              Inhalt
  ----------------------------------- -----------------------------------
  `00 - Dashboard`                    Startseite, Projektstatus und
                                      wichtige Links

  `01 - Ideen`                        Neue Funktionen und spontane
                                      Einfälle

  `02 - Planung`                      Featureplanung, Aufgaben und
                                      Meilensteine

  `03 - Architektur`                  Systemdesign, Diagramme und
                                      technische Zusammenhänge

  `04 - Recherche`                    Vergleiche, Bibliotheken, APIs und
                                      Technologien

  `05 - Entwicklung`                  Technische Notizen, Fehler und
                                      Lösungen

  `06 - Plugins`                      Plugin-Ideen, Konzepte und
                                      Integrationen

  `07 - UI-UX`                        Designideen, Screenshots und
                                      Bedienkonzepte

  `08 - Entscheidungen`               Technische Entscheidungen und deren
                                      Begründungen

  `09 - Changelog`                    Entwicklungsverlauf und wichtige
                                      Änderungen

  `10 - Archiv`                       Veraltete Ideen und abgeschlossene
                                      Themen
  -----------------------------------------------------------------------

### Beispiele für Notizen im Vault

-   `Plugin-Ideen.md` -- Welche Programme und Dienste soll PC Steuerung
    steuern können?
-   `Plugin-Lebenszyklus.md` -- Wie werden Plugins geladen, gestartet
    und beendet?
-   `System-Kommunikation.md` -- Wie kommunizieren Rust, Web-UI und
    Android miteinander?
-   `Fehler-Sammlung.md` -- Bekannte Bugs und bereits getestete
    Lösungen.
-   `UI-Ideen.md` -- Funktionen, Layouts und Designinspirationen.
-   `Technologie-Vergleich.md` -- Vergleiche von Bibliotheken und
    Frameworks.
-   `Offene-Fragen.md` -- Technische und konzeptionelle Punkte, die noch
    nicht entschieden wurden.

## 4. Git-Projekt und Obsidian voneinander trennen

Die beiden Bereiche sollten unterschiedliche Aufgaben haben:

### Git-Projekt

Alles, was für den Code, den Build, die Zusammenarbeit und Coding-Agents
verbindlich ist:

-   Projekt- und Entwicklungsregeln
-   Aktuelle Architektur
-   API- und Plugin-Dokumentation
-   Build- und Testanleitungen
-   Sicherheitsregeln
-   Verbindliche technische Entscheidungen

### Obsidian

Alles, was zur internen Wissenssammlung und längerfristigen Planung
gehört:

-   Ideen und mögliche Funktionen
-   Recherche und Technologievergleiche
-   Persönliche Entwicklungsnotizen
-   Erste Konzepte und Entwürfe
-   Offene Fragen
-   Planung und Wissenssammlung

Wenn eine Idee später umgesetzt wird, werden die verbindlichen
Informationen in die passenden Projektdateien übernommen, beispielsweise
in `AGENTS.md`, `ROADMAP.md` oder die Dokumentation unter `docs/`.

Dadurch entstehen nicht zwei unterschiedliche Dokumentationen, die sich
mit der Zeit widersprechen.

## 5. Wichtigste Empfehlungen

1.  **`AGENTS.md` im Hauptverzeichnis behalten.** Die Datei ist der
    zentrale Einstiegspunkt für Coding-Agents. Sie sollte Regeln,
    wichtige Architekturinformationen und Hinweise auf weitere
    Dokumentation enthalten.
2.  **Technische Details nach `docs/` verschieben.** Lange API-Listen
    und ausführliche Erklärungen einzelner Komponenten gehören in eigene
    Dokumentationsdateien.
3.  **Obsidian als internes Entwickler-Wiki nutzen.** Ideen, Recherche
    und frühe Konzepte müssen nicht sofort Teil des Git-Repositories
    werden.
4.  **Entscheidungen dokumentieren.** Haltet fest, warum wichtige
    technische Lösungen gewählt wurden.
5.  **Informationen nur einmal verbindlich pflegen.** Sobald eine Idee
    umgesetzt oder eine Entscheidung getroffen wurde, sollte klar sein,
    welche Datei im Git-Projekt dafür maßgeblich ist.

**Ziel:** `AGENTS.md` bleibt der zentrale Einstiegspunkt, `docs/`
enthält die technische Dokumentation und Obsidian dient als internes
Entwickler-Wiki.
