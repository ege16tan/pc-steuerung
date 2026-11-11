$ErrorActionPreference = 'Stop'
$installerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $installerDir
$rustDir = Join-Path $projectRoot 'PC Agent Rust'
$issFile = Join-Path $installerDir 'pc-steuerung.iss'

$cargoCommand = Get-Command cargo -ErrorAction SilentlyContinue
$cargoPath = if ($cargoCommand) { $cargoCommand.Source } else { Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe' }
if (-not (Test-Path -LiteralPath $cargoPath)) { throw 'Rust/Cargo wurde nicht gefunden. Installiere Rust und starte den Installer-Build erneut.' }

Push-Location $rustDir
try {
    & $cargoPath build --release
    if ($LASTEXITCODE -ne 0) { throw "Rust-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

# Kein GUI-Launcher mehr: Der Agent laeuft vollstaendig im Hintergrund,
# bedient wird er ueber Tray-Symbol und Web-UI (http://127.0.0.1:8000/).

$compiler = Get-Command ISCC.exe -ErrorAction SilentlyContinue
if (-not $compiler) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 7\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 7\ISCC.exe')
    )
    $compilerPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
} else {
    $compilerPath = $compiler.Source
}
if (-not $compilerPath) {
    throw 'Inno Setup 6 oder 7 wurde nicht gefunden. Installiere Inno Setup und führe installer\build-installer.ps1 erneut aus.'
}

$outputDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
& $compilerPath $issFile
if ($LASTEXITCODE -ne 0) { throw "Inno-Setup-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)." }
Write-Host "Installer erstellt: $(Join-Path $projectRoot 'dist\PC-Steuerung-Setup-0.9.5.exe')"
