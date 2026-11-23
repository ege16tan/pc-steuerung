# build.ps1
#
# Baut die Desktop-App als EINE EXE nach ..\dist\PC Steuerung.exe
#
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -SelfContained   # ohne .NET-Runtime
#
# Standard ist framework-abhaengig (kleine EXE, braucht .NET 8 Desktop-Runtime).

param([switch]$SelfContained)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here
$dist = Join-Path $repo 'dist'
$agentSrc = Join-Path $repo 'PC Agent Rust'

# Rust-Agent bauen (falls noetig), damit pc-agent.exe in dist\ liegt.
$agentExe = Join-Path $agentSrc 'target\release\pc-agent.exe'
if (-not (Test-Path -LiteralPath $agentExe)) {
    Write-Host "Baue Rust-Agent..."
    Push-Location $agentSrc
    try { & cargo build --release } finally { Pop-Location }
}
if (Test-Path -LiteralPath $agentExe) {
    Copy-Item -LiteralPath $agentExe -Destination (Join-Path $dist 'pc-agent.exe') -Force
    Write-Host "pc-agent.exe nach dist\ kopiert."
}

Push-Location $here
try {
    $selfContained = $SelfContained.IsPresent
    $arguments = @(
        'publish', '.\PcSteuerung.App.csproj'
        '-c', 'Release'
        '-r', 'win-x64'
        '--self-contained', $selfContained.ToString().ToLowerInvariant()
        '-p:PublishSingleFile=true'
        '-p:DebugType=none'
        '-p:DebugSymbols=false'
        '-o', $dist
    )
    # Kompression gibt es nur beim eigenstaendigen Buendel (NETSDK1176).
    if ($selfContained) {
        $arguments += '-p:EnableCompressionInSingleFile=true'
    }

    & dotnet @arguments
}
finally {
    Pop-Location
}

$exe = Join-Path $dist 'PC Steuerung.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "Build hat keine EXE erzeugt: $exe" }
$size = [math]::Round((Get-Item -LiteralPath $exe).Length / 1MB, 2)
Write-Host "Fertig: $exe ($size MB)"
