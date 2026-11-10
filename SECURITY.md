# Sicherheit

> **Entwicklungsversion:** Dieses Projekt befindet sich in Entwicklung. Das
> Repository ist privat, der Agent läuft standardmäßig im **Simulationsmodus**.
> Es ist **nicht** für eine öffentliche Bereitstellung oder den Zugriff aus dem
> Internet ausgelegt.

## Vor einer Veröffentlichung (z. B. GitHub public)

- [ ] **Zugangsdaten einrichten** (Benutzername/Passwort in der Web-Oberfläche)
- [ ] `PC_AGENT_SIMULATE=0` nur bewusst und nach Tests setzen
- [ ] **Firewall** auf `LocalSubnet` beschränken, **kein** Port-Forwarding
- [ ] **Persönliche Daten entfernen** (LAN-IPs, Hostnamen, Pfade, Namen)
- [ ] `credentials.json`, `token.json`, `device.json` und `agent.log` aus
      `%LOCALAPPDATA%\PC Steuerung` sind **nicht** im Repo
- [ ] **Git-Historie prüfen** (keine Secrets in alten Commits)
- [ ] Pre-Push-Check aktiv (`scripts/install-hooks.ps1`)
- [ ] `AGENTS.md`/Doku auf sensible Angaben prüfen
- [ ] README/Repo-Beschreibung auf den Entwicklungsstand hinweisen

## Melden von Sicherheitsproblemen

Da es sich um ein privates Entwicklungsprojekt handelt, bitte **keine
öffentlichen Issues** mit Sicherheitsdetails erstellen. Kontakt: Repository-Inhaber.

## Sicherheitsmodell (Kurzfassung)

- **Basic Auth** (PBKDF2-Hash) für LAN-Zugriffe; localhost ohne Login
- Nur für das **eigene Heimnetz** gedacht
- **Kein** Internet-Zugriff, kein VPN, kein Port-Forwarding
- Simulationsmodus schützt vor versehentlichen echten Aktionen
- Der Pre-Push-Hook und die GitHub Action blockieren Zugangsdaten, Secrets
  und Build-Artefakte
