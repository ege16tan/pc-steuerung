# PC-Agent (Rust)

Bevorzugte Rust-Version des PC-Agenten. Python bleibt als Alternative in
`PC App/` erhalten. Ziel: **eine einzelne .exe** ohne Python-Installation, kompatibel zur
Android-App und zur Web-Oberflaeche.

## Verhaeltnis zum Python-Agent

| | Python (`PC App/`) | Rust (dieser Ordner) |
|---|---|---|
| Start | `python -m uvicorn main:app` | `pc-agent.exe` |
| Abhaengigkeiten | Python + pip (requirements.txt) | keine (alles im Binary) |
| Bindung | 0.0.0.0:8000 | 0.0.0.0:8000 (`PC_AGENT_PORT` optional) |
| Zugangsdaten | `PC App/credentials.json` | `credentials.json` neben der EXE |
| Log | `PC App/agent.log` (+Rotation .log.1) | `agent.log` neben der EXE (+Rotation) |
| PBKDF2 | sha256, 200000 Iterationen | identisch -> **bestehendes `credentials.json` bleibt gueltig** |
| Web-Oberflaeche | `PAGE` in `main.py` | `src/page.html` (1:1 identisch) |
| Tray | gemeinsame Tray-Steuerung im Projektstamm | gemeinsame Tray-Steuerung im Projektstamm |
| Autostart | `install-autostart.ps1` | `install-autostart.ps1` |

## Endpunkte (identisch wie bisher)

`GET /`, `GET /setup-status`, `POST /setup`, `POST /mode?simulate=`,
`GET /status`, `POST /shutdown?delay=`, `POST /abort`, `POST /hibernate`,
`POST /power-plan?name=`, `POST /wake?mac=&port=`, `POST /ping`,
`GET /events`, `GET /log?lines=`.
Zusätzlich bieten beide Varianten `GET /agent-version` und
`POST /agent-version?version=rust|python` zum Anzeigen und Wechseln der aktiven
Agent-Version.

Anmeldung wie beim Python-Agent: Basic Auth fuer LAN-Zugriffe, localhost
ohne Login, `X-Token`/`PC_AGENT_TOKEN` als Fallback, Ersteinrichtung nur
am PC (`POST /setup`, nur localhost).

## Bauen

Erfordert Rust mit der GNU-Toolchain (keine Visual-Studio-Bauwerkzeuge
noetig):

```
cd "PC Agent Rust"
cargo test          # Einheitstests
cargo build --release
```

Ergebnis: `target\release\pc-agent.exe`. `start.bat` kopiert die EXE
zusaetzlich als `pc-agent.exe` in diesen Ordner und startet sie von dort.

Die von rustup mitgelieferte GNU-Toolchain enthaelt keinen GNU-Assembler
(`as.exe`). `windows-sys` wuerde fuer `raw-dylib` aber `dlltool` benoetigen.
Deshalb patcht `patches/windows-link` die Link-Deklarationen auf klassische
Import-Bibliotheken; `build.rs` legt dafuer die passenden Alias-Dateien im
Build-Ordner an. Das ist nur fuer `x86_64-pc-windows-gnu` aktiv.

## Starten

- `start.bat` wählt Rust als bevorzugte Version. Wenn Cargo verfügbar ist,
  baut der gemeinsame Manager den aktuellen Quellstand (Cargo überspringt
  unveränderte Builds), ersetzt danach sicher die EXE und startet sie. Ohne
  Cargo nutzt er eine vorhandene `pc-agent.exe`. Der Standardstart aus dem
  Projektstamm ist `start-agent.bat`;
  in der Web-UI oder im Tray kann zwischen Rust und Python gewechselt werden.
- Bestehende Zugangsdaten werden beim ersten Start automatisch aus
  `PC App\credentials.json` uebernommen. Sonst einmalig Ersteinrichtung unter
  <http://localhost:8000/>.
- Wer die EXE direkt startet (z. B. aus `target\release\`), bekommt die
  Datendateien neben genau dieser EXE.

## Tray-Steuerung (Taskleiste)

`start-tray.bat` öffnet das gemeinsame Tray-Symbol. Doppelklick öffnet die
Web-UI; das Kontextmenü kann den bevorzugten Agenten starten oder stoppen,
zwischen Rust und Python wechseln und den Browser öffnen. Rust ist grün,
Python blau, gestoppt grau.

## Autostart

```
powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1
powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1 -WithTray
```

Die gemeinsame Aufgabe heisst `PC Steuerung Agent` und (mit `-WithTray`)
`PC Steuerung Tray`. Eine vorhandene getrennte Python-/Rust-Aufgabe wird bei
der Installation entfernt, damit nicht zwei Agenten Port 8000 beanspruchen.
Entfernen:

```
powershell -ExecutionPolicy Bypass -File .\uninstall-autostart.ps1 -WithTray
```

## Umgebungsvariablen (wie bisher)

- `PC_AGENT_TOKEN`           Fallback-Schutz, wenn keine Zugangsdaten existieren.
- `PC_AGENT_SIMULATE=1`      Standard: Simulation (nur Log). `0` = echt.
- `PC_WOL_BROADCAST`         Broadcast-Ziel fuer `/wake` (Standard 192.168.1.255).
- `PC_AGENT_PORT`            Optionaler Port (Standard 8000), z. B. fuer einen
                             Parallellauf mit dem Python-Agenten.

## Hinweise

- Windows-Firewall: beim ersten Start einmalig Netzwerkzugriff erlauben
  (private Netzwerke), damit das Handy den Agent erreicht.
- Sicherheitsschalter: `PC_AGENT_SIMULATE` bleibt standardmaessig `1`.
  Erst mit `0` bzw. `POST /mode?simulate=false` werden Befehle ausgefuehrt.
- `target/`, `agent.log*` und `credentials.json` sind ignoriert und duerfen
  nicht committet werden.

## Status

- Einheitstests: 20 Tests (PBKDF2/Python-Kompatibilitaetsvektor, Basic Auth,
  MAC-Parsing, WoL-Paket, Log-Format/Rotation, Query-Validierung, Ringpuffer).
- Live-Test im Simulationsmodus: Setup, Auth (401/403/200), Status,
  Shutdown/Abort/Hibernate, Energieplan, Wake (auch ungueltige MAC),
  Ping, Events und Log wurden gegen die laufende EXE geprueft.
