# Aktiviert die Git-Hooks aus .githooks (einmalig pro Klon).
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

git config core.hooksPath .githooks

Write-Host "Git-Hooks aktiviert: core.hooksPath = .githooks"
Write-Host "Vor jedem Push wird scripts/check-sensitive.sh ausgeführt."
Write-Host ""
Write-Host "Manuell prüfen:   bash scripts/check-sensitive.sh"
Write-Host "Umgehen (nicht empfohlen): git push --no-verify"
