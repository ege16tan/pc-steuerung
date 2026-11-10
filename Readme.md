# PC Steuerung

> Steuere deinen Windows-PC bequem vom Android-Handy im Heimnetz – Einschalten,
> Herunterfahren, Ruhezustand, Energieplan und Status.

![Platform](https://img.shields.io/badge/platform-Windows-0078D6)
![Rust](https://img.shields.io/badge/rust-1.75%2B-000000)
![Kotlin](https://img.shields.io/badge/kotlin-2.3-7F52FF)
![Android](https://img.shields.io/badge/android-8.0%2B-3DDC84)
![License](https://img.shields.io/badge/license-MIT-green)

## Features

| Funktion | Beschreibung |
|---|---|
| 🔌 **Einschalten** | Wake-on-LAN per Magic Packet – direkt vom Handy |
| ⏻ **Herunterfahren** | Mit einstellbarer Verzögerung und Bestätigungsdialog |
| ❌ **Abbrechen** | Ein geplantes Herunterfahren stoppen |
| 🌙 **Ruhezustand** | PC in den Hibernate-Modus schicken |
| ⚡ **Energieplan** | Höchstleistung, Ausbalanciert oder Energiesparen |
| 📊 **Status** | PC an/aus, aktiver Plan und Live-Countdown (grün/gelb/grau) |
| ⏱️ **Timer** | Herunterfahren in 1, 2, 4 oder 8 Stunden – oder eigene Zeit |
| 🧩 **Homescreen-Widget** | Einschalten direkt, Herunterfahren mit Bestätigung in der App |
| 🚀 **App-Shortcuts** | Einschalten/Herunterfahren per Long-Press aufs App-Symbol |
| 🎨 **Darstellung** | Hell, Dunkel oder System – plus einstellbares Status-Intervall |
| ⬆️ **App-Update** | Neue Version in den Einstellungen über GitHub Releases laden und per Android oder Shizuku installieren |
| 🖥️ **Web-Oberfläche** | Status, Steuerung und Ereignis-Log im Browser |
| 📝 **Verlauf** | Dauerhafte Logdatei mit Datum/Uhrzeit – einsehbar in App und Web |
| 🐞 **Debug-Modus** | Simulation ↔ Echtzeit umschalten (für Tests) |
| 🪟 **Tray-Steuerung** | Agent starten/stoppen, Quick-Action-Popup unter Windows |
| 🔐 **Login** | Benutzername/Passwort (Basic Auth) für LAN-Zugriffe |
| 📦 **Windows-Installer** | Rust-Agent pro Benutzer installieren, Autostart/Tray auswählen |
| 📷 **QR-Einrichtung** | PC-Adresse auf dem Bildschirm zeigen und in Android scannen |

## Architektur

```
┌──────────────────┐        HTTP + Basic Auth        ┌──────────────────────┐
│   Android-App    │ ──────────────────────────────► │   PC-Agent           │
│  (Kotlin/Compose)│                                 │  Rust (einzelne EXE) │
└────────┬─────────┘                                 └──────────┬───────────┘
         │                                                      │
         │ Wake-on-LAN Magic Packet (UDP-Broadcast)             │ localhost
         ▼                                                      ▼
   Netzwerkkarte des PCs                              Web-UI & Tray (PC)
```

## Voraussetzungen

- **Windows 10/11** (PC)
- **Android 8.0+** (minSdk 26)
- PC und Handy im **selben Heimnetz**
- Für Wake-on-LAN: **Ethernet** empfohlen, WoL im BIOS/UEFI und in der
  Netzwerkkarte aktiviert

## Schnellstart

### 1. PC-Agent einrichten

```powershell
start-agent.bat
```

Oder `PC Steuerung.exe` starten: Der Launcher bietet Agent, Tray und Web-UI
ohne Konsole an. Danach **`http://localhost:8000/`** öffnen und einmalig
**Benutzername + Passwort** festlegen.

Der Installer wird mit Inno Setup 6/7 und `installer\build-installer.ps1`
gebaut. Er installiert pro Benutzer, kann Agent und Tray bei der Anmeldung
starten und öffnet danach die PC-Einrichtung. In Android „QR-Code vom PC
scannen“ wählen. Der QR-Code enthält PC-Name, IP, Port und – falls im
PC-Setup hinterlegt – die WoL-MAC; Zugangsdaten werden nie übertragen.

Optional:

```powershell
# Firewall (als Administrator)
netsh advfirewall firewall add rule name="PC Agent 8000" dir=in action=allow protocol=TCP localport=8000 remoteip=localsubnet profile=private

# Tray-Symbol starten
start-tray.bat

# Autostart (inkl. Tray)
powershell -ExecutionPolicy Bypass -File ".\PC Agent Rust\install-autostart.ps1" -WithTray
```

Mehr Details: [`PC Agent Rust/README.md`](PC%20Agent%20Rust/README.md)

Die Python-Version wurde eingestellt und liegt nur noch im
[Archiv-Repo](https://github.com/ege16tan/pc-steuerung-python).

### 2. Android-App installieren

```powershell
cd "Android App"
gradlew.bat assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

In der App eintragen:

- IP des PCs (z. B. `192.168.1.100`)
- Port (`8000`)
- Benutzername + Passwort (wie in der Web-Oberfläche angelegt)
- Wake-on-LAN ist optional: Die MAC-Adresse kann aus dem QR-Code übernommen,
  automatisch vom PC geholt oder manuell eingetragen werden.
- Broadcast-Adresse (optional, Standard `255.255.255.255`)

### App aktualisieren

Unter Einstellungen → App-Update kann die App das neueste stabile GitHub
Release prüfen und die APK laden. Zur Installation stehen der Android-
Systemdialog (mit einmaliger Freigabe für unbekannte Apps) und Shizuku bereit.
Shizuku muss laufen und der App ausdrücklich autorisiert werden.

## API (Auszug)

| Methode | Pfad | Wirkung |
|---|---|---|
| GET | `/status` | online, Energieplan, laufendes Herunterfahren |
| POST | `/shutdown?delay=60` | Herunterfahren in `delay` Sekunden |
| POST | `/abort` | laufendes Herunterfahren abbrechen |
| POST | `/hibernate` | Ruhezustand |
| POST | `/power-plan?name=high\|balanced\|saver` | Energieplan wechseln |
| POST | `/wake?mac=AA:BB:CC:DD:EE:FF&port=9` | Magic Packet senden |
| GET | `/events` | letzte 50 Kommandos (mit Datum/Uhrzeit) |
| GET | `/log?lines=200` | letzte Zeilen der Logdatei (Klartext) |
| GET | `/setup-status` | ob Zugangsdaten noch fehlen |
| GET | `/pairing-info` | PC-Name, LAN-IP und Port für die Einrichtung |
| GET | `/pairing-qr.svg` | QR-Code ohne Zugangsdaten |
| POST | `/setup` | Zugangsdaten anlegen (nur localhost) |
| POST | `/mode?simulate=true\|false` | Simulation/Echtzeit umschalten |

## Sicherheit

- **Basic Auth** für alle LAN-Zugriffe; Passwort nur als **PBKDF2-Hash**
  (`%LOCALAPPDATA%\PC Steuerung\credentials.json`, wird nicht committet)
- **Localhost** (PC selbst) ist ohne Login erreichbar – für Tray und Web-UI
- Firewall-Regel auf `LocalSubnet` beschränken
- **Nicht ins Internet freigeben** (HTTP, nur fürs Heimnetz gedacht)
- **Simulationsmodus** `PC_AGENT_SIMULATE=1` (Standard) führt nichts aus,
  sondern loggt nur

## Projektstruktur

```
Android App/     Kotlin-/Jetpack-Compose-App inkl. Homescreen-Widget
PC Agent Rust/   Rust-Agent (einzelne EXE), Web-UI und Autostart-Skripte
launcher/        Quelle und Build des manuellen Launchers
installer/       Inno-Setup-Skript und Build-/Setup-Helfer
docs/            Planung und Hintergründe
```

## Roadmap

Geplante Erweiterungen stehen in [ROADMAP.md](ROADMAP.md).

## Entwicklungsstand & Sicherheit

Dies ist eine **Entwicklungsversion** (öffentlich, Simulationsmodus als Default).
Vor einer Veröffentlichung oder dem Echtbetrieb bitte die Checkliste in
[SECURITY.md](SECURITY.md) beachten.

## Sicherheit vor dem Push

Ein **Pre-Push-Hook** und eine **GitHub Action** prüfen bei jedem Push:

- keine Zugangsdaten (`credentials.json`, `.env`, Keys/Zertifikate)
- keine Build-Artefakte (`build/`, `.gradle/`, `target/`, APKs)
- keine offensichtlichen Secrets (GitHub-Tokens, AWS-Keys, Private Keys)
- keine Dateien über 10 MB

Einmalig aktivieren:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-hooks.ps1
```

Manuell prüfen: `bash scripts/check-sensitive.sh`  
Umgehen (nicht empfohlen): `git push --no-verify`

## Lizenz

[MIT](LICENSE) – frei nutzbar, änderbar und teilbar.

## Haftungsausschluss

Nutzung auf eigene Gefahr. Das Projekt ist für den Betrieb im eigenen Heimnetz
gedacht und nicht für den Zugriff aus dem Internet ausgelegt.
