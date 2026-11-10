# PC-Steuerung per Handy: Übersicht

**Ziel:** Eine Android-App, mit der man den PC im Heimnetz einschalten, herunterfahren, in den Ruhezustand versetzen und den Energieplan ändern kann. Dazu eine Statusanzeige, ein Bestätigungsdialog vor dem Herunterfahren, ein Timer und ein Widget für Ein/Aus.

**Geltungsbereich:** Nur lokal im Heimnetz. Kein Zugriff von unterwegs, kein Port im Router, kein VPN.

---

## 1. Entschieden

### Funktionen
| Funktion | Umsetzung |
|---|---|
| Einschalten | Wake on LAN: Magic Packet per UDP-Broadcast, direkt vom Handy |
| Herunterfahren | Agent-Endpunkt `POST /shutdown` (mit Verzögerung) |
| Abbrechen | Agent-Endpunkt `POST /abort` (`shutdown /a`) |
| Ruhezustand | Agent-Endpunkt `POST /hibernate` |
| Energieplan wechseln | Agent-Endpunkt `POST /power-plan` (`powercfg /setactive`) |
| Status | Agent-Endpunkt `GET /status`: Online-Zustand und aktiver Plan |

### Energiepläne (GUIDs, sprachunabhängig)
- Höchstleistung: `8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c`
- Ausbalanciert: `381b4222-f694-41f0-9685-ff5bb260df2e`
- Energiesparmodus: `a1841308-3541-4fab-bc81-f71556f20b4a`

### PC vorbereiten
- **BIOS/UEFI:** "Wake on LAN" bzw. "Power On by PCI-E" aktivieren.
- **Netzwerkkarte** (Geräte-Manager): Energieverwaltung "Gerät kann den Computer aus dem Ruhezustand aktivieren" an, Erweitert: "Wake on Magic Packet" an.
- **Schnellstart ausschalten:** Systemsteuerung → Energieoptionen → "Auswählen, was die Ein/Aus-Tasten tun" → Schnellstart deaktivieren. **Nicht** `powercfg /h off` verwenden, das schaltet auch den Ruhezustand ab.
- **Ethernet:** WoL funktioniert zuverlässig nur über Kabel, nicht über WLAN.
- **Router:** Feste IP per DHCP-Reservierung für den PC.

### Agent auf dem PC
- **Rust**, eine einzelne EXE in `PC Agent Rust/` (keine Python-Installation).
- Basic Auth mit Benutzername/Passwort (PBKDF2-Hash in `credentials.json`);
  localhost ohne Login. Alter Token-Schutz über `X-Token` bleibt als Fallback.
- Datenordner: `%LOCALAPPDATA%\PC Steuerung`.
- **Firewall:** Port 8000 nur für `LocalSubnet` erlaubt.
- **Autostart:** Aufgabenplanung, Auslöser "Bei Anmeldung", Start von `pc-agent.exe`.
- Eingebaute Tools, die aufgerufen werden: `shutdown`, `powercfg`.

### Android-App
- **Kotlin + Jetpack Compose + Material 3**
- **Netzwerk:** HTTP-Aufrufe an den Agenten (Basic Auth)
- **Speicher:** SharedPreferences für Profile (IP, Port, Login, MAC)
- **WoL:** `DatagramSocket` aus der Java-Standardbibliothek, keine extra Bibliothek
- **Berechtigungen:** `INTERNET`, `ACCESS_WIFI_STATE`
- **Entwicklung:** Android Studio (VS Code geht, ist aber aufwendiger)

### Statusanzeige (drei Zustände)
Kein Ping, da Windows Ping oft blockiert. Stattdessen Verbindungsversuch auf Port 8000 und Abfrage von `/status`.

| Zustand | Bedeutung | Mögliche Aktion |
|---|---|---|
| Grün | PC an, Agent läuft | Herunterfahren, Ruhezustand, Energieplan |
| Gelb | PC an, Agent antwortet nicht | Hinweis, Agent neu starten |
| Grau | PC aus | Einschalten (WoL) |

---

## 2. Gewünschte Verbesserungen

| Feature | Status |
|---|---|
| Statusanzeige mit drei Zuständen | Gewünscht, Konzept steht |
| Android-Widget für Ein/Aus | Gewünscht, später |
| Bestätigungsdialog vor Herunterfahren | Gewünscht, in der App |
| Timer "in X Stunden herunterfahren" | Gewünscht, Endpunkt `/shutdown?delay=` ist vorbereitet, Timer-Logik offen |
| Abbrechen (`/abort`) | Umgesetzt im Agenten |
| PWA-Weboberfläche auf dem PC | Verworfen |
| Zugriff von unterwegs (VPN/Tailscale) | Verschoben, nicht nötig für den Heimnetz-Betrieb |

---

## 3. Testplan

1. **WoL testen** mit einer fertigen App aus dem Play Store, bevor eigener Code entsteht.
2. **Agent starten** und mit `curl` prüfen: `/status`, `/abort`.
3. **Herunterfahren testen** mit `/shutdown?delay=60`, danach mit `/abort` abbrechen.
4. **Energieplan testen** mit `/power-plan`, danach `powercfg /getactivescheme` prüfen.
5. **Ruhezustand testen** mit `/hibernate`, vorher `powercfg /a` prüfen, ob "Ruhezustand" aufgeführt ist.
6. **Android-App** bauen und gegen den Agenten testen.

---

## 4. Offen

- [ ] Windows-Version des PCs
- [ ] PC per Ethernet verbunden?
- [ ] Rust-Agent gebaut bzw. Installer installiert
- [ ] Feste IP im Router vergeben
- [ ] MAC-Adresse der Netzwerkkarte ermittelt
- [ ] Broadcast-Adresse des Heimnetzes festgelegt (z. B. `192.168.1.255`)
- [ ] WoL mit fertiger App getestet
- [ ] Autostart über Aufgabenplanung eingerichtet
- [ ] Energiepläne auf dem PC geprüft (`powercfg /list`), falls ein Plan fehlt
- [ ] Android-App: Ein/Aus, Status, Energieplan, Bestätigungsdialog
- [ ] Timer serverseitig umgesetzt
- [ ] Widget umgesetzt
- [ ] Name und Design der App

---

## 5. Bekannte Einschränkungen

- **WLAN:** WoL meist unzuverlässig, Ethernet nutzen.
- **Laptops:** WoL aus dem Ausschaltzustand oft unzuverlässig, je nach Hersteller.
- **Energiesparmodus der Netzwerkkarte** kann WoL aus dem Standby verhindern.
- **Energieplan ändern:** `powercfg` braucht teils Admin-Rechte. Dann den Dienst als Admin starten.
- **OEM-Geräte:** Manche Hersteller entfernen den Energiesparmodus. Dann schlägt der Wechsel fehl.
- **Android 12+:** Hintergrund-Einschränkungen. Aufrufe aus dem Vordergrund sind meist unkritisch.
- **Code:** Die Beispiele sind noch nicht getestet.
- **Lokal only:** Außerhalb des WLANs ist die App nicht nutzbar. Das ist bewusst so gewählt.

---

## 6. Änderungsverlauf

- Korrektur: Der Schnellstart wird in den Energieoptionen deaktiviert, nicht über `powercfg /h off`, da sonst der Ruhezustand wegfällt.
- Statusprüfung über Port-Verbindung und `/status`, statt Ping.
- Verbindung nur im Heimnetz, VPN aus dem Plan entfernt.
- Weboberfläche auf dem PC verworfen.
