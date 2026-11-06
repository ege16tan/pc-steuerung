# Rust-Migration (Branch `rust`)

Ziel: Den Python-Agenten (`PC App/`) durch eine **Rust-Version** ersetzen, ohne
die Android-App zu ändern. Die bestehende APK muss unverändert weiterlaufen.

## Warum kompatibel?

Die Android-App spricht ausschließlich HTTP mit dem Agenten. Solange die
Rust-Version dieselben Endpunkte, JSON-Felder und Header bereitstellt, bleibt
die APK kompatibel.

## Geplanter Stack

- **Tokio + Axum** (HTTP-Server)
- **Serde / serde_json**
- **PBKDF2 + SHA-2 + HMAC** (Passwort-Hash)
- **base64, rand, chrono**
- Windows-Befehle: `std::process::Command` mit `CREATE_NO_WINDOW` (0x08000000)
- Wake-on-LAN: `std::net::UdpSocket` (Broadcast)

## Kompatible API (1:1 erhalten)

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/` | Web-Oberfläche |
| GET | `/status` | online, simulate, power_plan, pending_shutdown, setup_required |
| POST | `/shutdown?delay=` | Herunterfahren |
| POST | `/abort` | Abbrechen |
| POST | `/hibernate` | Ruhezustand |
| POST | `/power-plan?name=` | Energieplan |
| POST | `/wake?mac=&port=` | Magic Packet |
| GET | `/ping` | Verbindungstest |
| GET | `/events` | letzte 50 Ereignisse |
| GET | `/log?lines=` | Logdatei (Klartext) |
| GET | `/setup-status` | Setup nötig? |
| POST | `/setup` | Zugangsdaten (nur localhost) |
| POST | `/mode?simulate=` | Simulation/Echtzeit |

- Auth: `X-Token` (Fallback), **Basic Auth**, **localhost-Bypass**
- Setup: PBKDF2-Hash in `credentials.json`, nur von localhost
- Log: `agent.log` mit Datum/Uhrzeit, Rotation nach `agent.log.1`
- Simulation: Standard an, Laufzeit-Umschaltung über `/mode`
- Web-UI: HTML/CSS/JS 1:1 übernehmen (z. B. `include_str!`)

## Schritte

1. Cargo-Projekt `rust-agent/` anlegen
2. Konfiguration, Credentials, Auth (Basic + localhost)
3. Endpunkte inkl. Simulation/Echtzeit
4. Windows-Befehle (`shutdown`, `powercfg`) + WoL
5. Logdatei + Web-Oberfläche
6. Vergleichstests gegen Python (curl-Matrix)
7. Tray/Autostart prüfen (Rust oder weiter PowerShell)
8. Python nach `legacy/` verschieben, wenn die Rust-Version stabil ist
9. CI/Release anpassen

## Kompatibilitätstest (curl)

- `GET /status` → gleiche Felder
- `POST /shutdown?delay=60` → Simulation/ECHT
- `POST /abort`, `/hibernate`, `/power-plan`
- `POST /wake?mac=...`
- `GET /events`, `/log?lines=200`
- `POST /setup` (localhost), Auth von LAN (401/200)
- `POST /mode?simulate=false|true`

## Hinweis

- Branch `rust` ist für die Migration reserviert.
- `main` bleibt vorerst bei Python, bis die Rust-Version getestet ist.
- Die Android-APK wird **nicht** geändert – sie muss mit beiden Agenten laufen.
