<#
.SYNOPSIS
    Legt ein neues Plugin-Paket fuer den Markt+ des PC-Agenten an.

.DESCRIPTION
    Erzeugt plugin-catalog\<id>.json (Manifest plus leeres main.js-Geruest) und
    traegt den Eintrag in plugin-catalog\plugins.json ein. Bestehende Eintraege
    bleiben erhalten, eine doppelte ID wird abgelehnt.

.PARAMETER Id
    Kennung des Plugins: a-z, 0-9, '-', '_' und '.' (nicht am Anfang).
    Beispiel: "mein-plugin".

.PARAMETER Name
    Anzeigename im Markt+.

.PARAMETER Description
    Kurzbeschreibung in einem Satz.

.PARAMETER Permissions
    Freigegebene Host-Funktionen, z. B. "log","status","steam".
    Moeglich: log, status, metrics, power, games, process, steam.

.PARAMETER IntervalMs
    Takt fuer tick() in Millisekunden; 0 bedeutet kein Takt.

.EXAMPLE
    .\scripts\new-plugin.ps1 -Id "mein-plugin" -Name "Mein Plugin" `
        -Description "Macht etwas Sinnvolles." -Permissions "log","status" -IntervalMs 2000

.NOTES
    Das Skript schreibt bewusst UTF-8 ohne BOM - der Agent liest die Pakete mit
    serde_json, das eine Byte Order Mark nicht akzeptiert.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Description = "",
    [string[]]$Permissions = @("log"),
    [int]$IntervalMs = 1000
)

$ErrorActionPreference = "Stop"

# UTF-8 ohne BOM schreiben.
function Write-Utf8NoBom([string]$Path, [string]$Text) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

# PowerShell 5.1 maskiert <, >, & und ' als \uXXXX; fuer lesbare Dateien zurueck.
function Clear-MaskedChars([string]$Json) {
    $Json.Replace('\u003c', '<').Replace('\u003e', '>').Replace('\u0026', '&').Replace('\u0027', "'")
}

# Reihenfolge der Felder eines bestehenden Katalogeintrags erhalten.
function Copy-Entry($Entry) {
    $copy = [ordered]@{}
    foreach ($property in $Entry.PSObject.Properties) {
        $copy[$property.Name] = $property.Value
    }
    $copy
}

$id = $Id.Trim()
if ($id -notmatch '^[a-z0-9][a-z0-9._-]*$') {
    throw "Ungueltige ID '$Id': erlaubt sind a-z, 0-9, '-', '_' und '.' (nicht am Anfang)."
}
$name = $Name.Trim()
if ($name.Length -eq 0) {
    throw "Der Name darf nicht leer sein."
}
if ($IntervalMs -lt 0) {
    throw "IntervalMs muss 0 oder groesser sein."
}

$catalogDir = Join-Path (Split-Path -Parent $PSScriptRoot) "plugin-catalog"
if (-not (Test-Path -LiteralPath $catalogDir)) {
    throw "Ordner fehlt: $catalogDir"
}
$packageFile = Join-Path $catalogDir "$id.json"
$catalogFile = Join-Path $catalogDir "plugins.json"
if (Test-Path -LiteralPath $packageFile) {
    throw "Paket existiert schon: $packageFile"
}
if (-not (Test-Path -LiteralPath $catalogFile)) {
    throw "Katalog fehlt: $catalogFile"
}

$catalog = [System.IO.File]::ReadAllText($catalogFile) | ConvertFrom-Json
$bekannt = @($catalog.plugins) | Where-Object { $null -ne $_ -and $_.id -eq $id }
if ($bekannt) {
    throw "ID '$id' steht schon im Katalog ($catalogFile)."
}

# --- Paket schreiben ---
$js = @"
// $name - Geruest fuer ein neues Plugin.
// activate() laeuft beim Start, tick() alle $IntervalMs ms, stop() beim Beenden.

function activate() {
  pc.log("$name gestartet");
}

function tick() {
  // Hier die regelmaessige Arbeit eintragen.
}

function stop() {
  pc.log("$name beendet");
}
"@
# Zeilenenden vereinheitlichen und mit einer Leerzeile beginnen (wie im Katalog).
$js = ($js -replace "`r`n", "`n")
if (-not $js.StartsWith("`n")) {
    $js = "`n" + $js
}

$bundle = [ordered]@{
    id          = $id
    name        = $name
    description = $Description
    version     = "1"
    entry       = "main.js"
    permissions = @($Permissions)
    interval_ms = $IntervalMs
    files       = [ordered]@{ "main.js" = $js }
}
$bundleJson = Clear-MaskedChars ($bundle | ConvertTo-Json -Depth 5 -Compress)
Write-Utf8NoBom $packageFile $bundleJson

# --- Katalogeintrag ergaenzen ---
$entries = New-Object System.Collections.ArrayList
foreach ($eintrag in @($catalog.plugins)) {
    if ($null -eq $eintrag) { continue }
    [void]$entries.Add((Copy-Entry $eintrag))
}
$url = "https://raw.githubusercontent.com/ege16tan/pc-steuerung/main/plugin-catalog/$id.json"
[void]$entries.Add([ordered]@{
    id          = $id
    name        = $name
    description = $Description
    version     = "1"
    url         = $url
})

$newCatalog = [ordered]@{}
foreach ($property in $catalog.PSObject.Properties) {
    if ($property.Name -eq "plugins") { continue }
    $newCatalog[$property.Name] = $property.Value
}
$newCatalog["plugins"] = @($entries.ToArray())
$catalogJson = Clear-MaskedChars ($newCatalog | ConvertTo-Json -Depth 6)
Write-Utf8NoBom $catalogFile ($catalogJson + "`r`n")

Write-Host "Paket angelegt:      plugin-catalog\$id.json"
Write-Host "Katalog ergaenzt:    plugin-catalog\plugins.json"
Write-Host "Rechte:              $($Permissions -join ', ')"
Write-Host "Takt:                $IntervalMs ms"
Write-Host ""
Write-Host "Naechster Schritt: main.js im Paket fuellen, dann committen und pushen."
Write-Host "Der Agent findet das Paket ueber CATALOG_URL (raw.githubusercontent.com)."
