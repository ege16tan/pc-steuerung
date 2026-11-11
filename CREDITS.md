# Credits

Dieses Projekt nutzt fremde Bibliotheken, Tools und Agent-Skills. Danke an alle
Autorinnen und Autoren. Die Lizenzen der jeweiligen Projekte gelten zusätzlich zu
unserer MIT-Lizenz.

## Agent-Skills (Entwicklungshilfen, nicht Teil der App)

Diese Skills liegen global unter `~/.agents/skills` und werden über die
[Skills-CLI](https://skills.sh) gepflegt (`cline skill list -g`).

| Skill-Paket | Verwendete Skills | Zweck |
|---|---|---|
| [android/skills](https://github.com/android/skills) | `r8-analyzer`, `testing-setup`, `camerax`, `anr-audit-and-fix`, `edge-to-edge` | Offizielle Android-Guidance (R8, Tests, CameraX, Edge-to-Edge) |
| [chrisbanes/skills](https://github.com/chrisbanes/skills) | `compose-performance`, `compose-state-and-effects`, `compose-ui-testing-patterns`, `gradle-run` | Jetpack-Compose-Qualität und Gradle-Workflow |
| [obsidianmd/obsidian-skills](https://github.com/obsidianmd/obsidian-skills) | `obsidian-markdown`, `obsidian-bases`, `obsidian-cli` | Vault-Dokumentation (Wikilinks, Properties, Bases) |
| [apollographql/skills](https://github.com/apollographql/skills) | `rust-best-practices` | Idiomatisches Rust (basiert auf dem Rust-Best-Practices-Handbuch) |
| [wshobson/agents](https://github.com/wshobson/agents) | `rust-async-patterns` | Tokio-/Axum-Muster |
| [trailofbits/skills](https://github.com/trailofbits/skills) | `rust-review` | Security-Review für Rust-Code |
| [mattpocock/skills](https://github.com/mattpocock/skills) | `code-review`, `diagnosing-bugs` | Review- und Debug-Arbeitsweise |
| [vercel-labs/skills](https://github.com/vercel-labs/skills) | `find-skills` | Skills im Katalog finden |

## PC-Agent (Rust)

| Crate | Zweck |
|---|---|
| [tokio](https://crates.io/crates/tokio) | Async-Runtime |
| [axum](https://crates.io/crates/axum) | HTTP-Server und Routing |
| [serde](https://crates.io/crates/serde) / [serde_json](https://crates.io/crates/serde_json) | JSON |
| [pbkdf2](https://crates.io/crates/pbkdf2), [sha2](https://crates.io/crates/sha2), [subtle](https://crates.io/crates/subtle) | Passwort-Hashing (PBKDF2-SHA256) und Konstantzeit-Vergleich |
| [base64](https://crates.io/crates/base64) | Token-/Hash-Kodierung |
| [getrandom](https://crates.io/crates/getrandom) | Zufall für Salt/Tokens |
| [chrono](https://crates.io/crates/chrono) | Zeitstempel im Log |
| [patches/windows-link](patches/windows-link) (eigenes Patch-Crate) | Bauen mit GNU-Toolchain ohne `dlltool` |

## Android-App

| Bibliothek / Tool | Zweck |
|---|---|
| [Kotlin](https://kotlinlang.org/) + [Jetpack Compose](https://developer.android.com/jetpack/compose) (BOM 2025.09.00) | UI |
| [CameraX](https://developer.android.com/media/camera/camerax) | QR-Scanner-Kamera |
| [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning) | QR-Dekodierung (offline) |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Optionale APK-Installation ohne Systemdialog |
| [Android Gradle Plugin](https://developer.android.com/build) / [R8](https://developer.android.com/build/shrink-code) | Build, Minifizierung |

## Windows-Seite

| Tool | Zweck |
|---|---|
| [Inno Setup](https://jrsoftware.org/isinfo.php) | Installer |
| [Rust](https://www.rust-lang.org/) (stable, x86_64-pc-windows-gnu) | Agent-Build |
| PowerShell / WinForms (`System.Windows.Forms.NotifyIcon`) | Tray-Symbol |

## Frühere Version

Die archivierte Python-Version (FastAPI + Tkinter-Tray) liegt unter
[ege16tan/pc-steuerung-python](https://github.com/ege16tan/pc-steuerung-python);
sie diente als Verhaltensreferenz für den Rust-Agenten.
