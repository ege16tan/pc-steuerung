$ErrorActionPreference = 'Stop'
$installerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $installerDir
$rustDir = Join-Path $projectRoot 'PC Agent Rust'
$desktopDir = Join-Path $projectRoot 'Desktop App'
$issFile = Join-Path $installerDir 'pc-steuerung.iss'

# Desktop-App bauen (produziert dist\PC Steuerung.exe)
Write-Host "Baue Desktop-App..."
Push-Location $desktopDir
try {
    & powershell -ExecutionPolicy Bypass -File .\build.ps1
    if ($LASTEXITCODE -ne 0) { throw "Desktop-App-Build fehlgeschlagen." }
} finally {
    Pop-Location
}

$cargoCommand = Get-Command cargo -ErrorAction SilentlyContinue
$cargoPath = if ($cargoCommand) { $cargoCommand.Source } else { Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe' }
if (-not (Test-Path -LiteralPath $cargoPath)) { throw 'Rust/Cargo wurde nicht gefunden.' }

Push-Location $rustDir
try {
    & $cargoPath build --release
    if ($LASTEXITCODE -ne 0) { throw "Rust-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

$compiler = Get-Command ISCC.exe -ErrorAction SilentlyContinue
if (-not $compiler) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    )
    $compilerPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
} else {
    $compilerPath = $compiler.Source
}
if (-not $compilerPath) {
    throw 'Inno Setup 6 oder 7 wurde nicht gefunden.'
}

$outputDir = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
& $compilerPath $issFile
if ($LASTEXITCODE -ne 0) { throw "Inno-Setup-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)." }
Write-Host "Installer erstellt in $outputDir"
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
