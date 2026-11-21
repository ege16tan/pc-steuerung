# create-desktop-shortcut.ps1
#
# Legt (oder erneuert) die Desktop-Verknuepfung "PC Steuerung" an.
# Ziel ist die Desktop-App: eine EXE mit Fenster und Tray-Symbol.
# Gebaut wird sie mit:  powershell -ExecutionPolicy Bypass -File "Desktop App\build.ps1"
#
# Ausfuehren:  powershell -ExecutionPolicy Bypass -File scripts\create-desktop-shortcut.ps1
# Optional:    -Exe "D:\Pfad\PC Steuerung.exe"

param([string]$Exe)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here

if (-not $Exe) {
    $Exe = Join-Path $root 'dist\PC Steuerung.exe'
}
if (-not (Test-Path -LiteralPath $Exe -PathType Leaf)) {
    throw "Desktop-App fehlt: $Exe`nBitte zuerst 'powershell -ExecutionPolicy Bypass -File `"$root\Desktop App\build.ps1`"' ausfuehren."
}

$exePath = (Resolve-Path -LiteralPath $Exe).Path
$desktop = [Environment]::GetFolderPath('Desktop')
$lnk = Join-Path $desktop 'PC Steuerung.lnk'

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($lnk)
$shortcut.TargetPath = $exePath
$shortcut.WorkingDirectory = Split-Path -Parent $exePath
# Kein Autostart, keine PowerShell: die App selbst zeigt Fenster und Tray.
$shortcut.Arguments = ''
$shortcut.IconLocation = "$exePath,0"
$shortcut.Description = 'PC Steuerung - Fenster und Tray-Symbol'
$shortcut.WindowStyle = 1
$shortcut.Save()

Write-Host "Desktop-Verknuepfung erstellt: $lnk"
Write-Host "  Ziel: $exePath"
