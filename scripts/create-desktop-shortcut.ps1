# create-desktop-shortcut.ps1
#
# Legt (oder erneuert) die Desktop-Verknuepfung "PC Steuerung" an.
# Die Verknuepfung startet open-web-ui.ps1: Agent + Tray sicherstellen und die
# Web-Oberflaeche im Browser oeffnen. Das Icon kommt aus scripts\build-icon.ps1.
#
# Ausfuehren:  powershell -ExecutionPolicy Bypass -File scripts\create-desktop-shortcut.ps1

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$icon = Join-Path $root 'PC Steuerung.ico'
$launcher = Join-Path $root 'open-web-ui.ps1'

if (-not (Test-Path -LiteralPath $icon)) {
    & (Join-Path $here 'build-icon.ps1')
}
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "open-web-ui.ps1 fehlt: $launcher"
}

$desktop = [Environment]::GetFolderPath('Desktop')
$lnk = Join-Path $desktop 'PC Steuerung.lnk'

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($lnk)
$shortcut.TargetPath = 'powershell.exe'
$shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $launcher + '"'
$shortcut.WorkingDirectory = $root
$shortcut.IconLocation = "$icon,0"
$shortcut.Description = 'PC Steuerung - Agent starten und Web-Oberflaeche oeffnen'
$shortcut.WindowStyle = 7
$shortcut.Save()

Write-Host "Desktop-Verknuepfung erstellt: $lnk"
Write-Host "  Ziel:  powershell.exe ... -File `"$launcher`""
Write-Host "  Icon:  $icon"
