# PC-Agent (Rust)

Windows-Agent des Projekts **PC Steuerung** – eine einzelne EXE ohne
Python-Installation, kompatibel zur Android-App und zur Web-Oberfläche.
Die frühere Python-Version ist archiviert und wird nicht mehr gepflegt.

## Daten und Ablage

| | Rust-Agent |
|---|---|
| Start | `pc-agent.exe` |
| Abhängigkeiten | keine (alles im Binary) |
| Bindung | 0.0.0.0:8000 (`PC_AGENT_PORT` optional) |
| Datenordner | `%LOCALAPPDATA%\PC Steuerung` (`PC_AGENT_DATA` optional) |
| Zugangsdaten | `credentials.json` im Datenordner |
| Log | `agent.log` (+Rotation `.log.1`) im Datenordner |
| PBKDF2 | sha256, 200000 Iterationen |
| Web-Oberfläche | `src/page.html` |
| Tray | gemeinsame Tray-Steuerung im Projektstamm |

Alte Daten aus `PC App/` oder neben der EXE werden beim Start einmalig
übernommen; vorhandene Zieldateien werden nur ersetzt, wenn die Quelle neuer ist.

## API-Endpunkte

`GET /`, `GET /setup-status`, `GET /pairing-info`, `GET /pairing-qr.svg`,
`POST /setup`, `POST /mode?simulate=`,
`GET /status`, `POST /shutdown?delay=`, `POST /abort`, `POST /hibernate`,
`POST /power-plan?name=`, `POST /wake?mac=&port=`, `POST /ping`,
`GET /events`, `GET /log?lines=`.

Die Routen und Erfolgsantworten entsprechen der Android-App. Anmeldung wie
bisher: Basic Auth für LAN-Zugriffe, localhost ohne Login,
`X-Token`/`PC_AGENT_TOKEN` als Fallback, Ersteinrichtung nur am PC
(`POST /setup`, nur localhost).

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

- `start.bat` baut mit Cargo den aktuellen Quellstand (Cargo ueberspringt
  unveraenderte Builds), ersetzt danach sicher die EXE und startet sie.
  Ohne Cargo wird eine vorhandene `pc-agent.exe` verwendet.
- Standardstart aus dem Projektstamm: `start-agent.bat` oder der Launcher
  `PC Steuerung.exe` (Agent, Tray und Web-UI ohne Konsole).
- Der Agent schreibt Zugangsdaten, Token, Geraeteeinstellungen und Log nach
  `%LOCALAPPDATA%\PC Steuerung`.

## Installer und Handy-Setup

Der Inno-Setup-Installer wird pro Benutzer installiert und braucht keine
Administratorrechte. Er kann Agent und Tray bei der Windows-Anmeldung starten.

Mit Inno Setup 6/7:

```
powershell -ExecutionPolicy Bypass -File .\installer\build-installer.ps1
```

Der Build erstellt `dist\PC-Steuerung-Setup-0.9.4.exe`. Nach der Installation
oeffnet der Assistent die Web-Einrichtung. Der QR-Code uebertraegt PC-Name,
LAN-IP, Port und – falls im PC-Setup hinterlegt – die WoL-MAC; Zugangsdaten
bleiben ausserhalb des Codes. In der Android-App `QR-Code vom PC scannen`
waehlen und danach das Login eintragen.

## Tray-Steuerung (Taskleiste)

`start-tray.bat` oeffnet das Tray-Symbol. Doppelklick oeffnet die Web-UI;
das Kontextmenue kann den Agenten starten oder stoppen und den Browser
oeffnen. Gruen = laeuft, grau = gestoppt, orange = Port belegt.

## Autostart

```
powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1
powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1 -WithTray
```

Die Aufgabe heisst `PC Steuerung Agent` und (mit `-WithTray`)
`PC Steuerung Tray`. Entfernen:

```
powershell -ExecutionPolicy Bypass -File .\uninstall-autostart.ps1 -WithTray
```

## Umgebungsvariablen

- `PC_AGENT_TOKEN`           Fallback-Schutz, wenn keine Zugangsdaten existieren.
- `PC_AGENT_SIMULATE=1`      Standard: Simulation (nur Log). `0` = echt.
- `PC_WOL_BROADCAST`         Broadcast-Ziel fuer `/wake` (Standard 192.168.1.255).
- `PC_AGENT_PORT`            Optionaler Port (Standard 8000), z. B. fuer Tests.
- `PC_AGENT_DATA`            Optionaler Datenordner (absolut oder relativ zur EXE).

## Hinweise

- Windows-Firewall: beim ersten Start einmalig Netzwerkzugriff erlauben
  (private Netzwerke), damit das Handy den Agent erreicht.
- Sicherheitsschalter: `PC_AGENT_SIMULATE` bleibt standardmaessig `1`.
  Erst mit `0` bzw. `POST /mode?simulate=false` werden Befehle ausgefuehrt.
- `target/`, `agent.log*`, `token.json`, `device.json` und `credentials.json`
  sind ignoriert und duerfen nicht committet werden.

## Status

- Einheitstests: PBKDF2-Kompatibilitaetsvektor, Basic Auth, MAC-Parsing,
  WoL-Paket, Log-Format/Rotation, Query-Validierung, Ringpuffer.
- Live-Test im Simulationsmodus: Setup, Auth (401/403/200), Status,
  Shutdown/Abort/Hibernate, Energieplan, Wake (auch ungueltige MAC),
  Ping, Events und Log wurden gegen die laufende EXE geprueft.
