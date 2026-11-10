$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $csc)) {
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
}
if (-not (Test-Path -LiteralPath $csc)) {
    throw 'csc.exe (.NET Framework) wurde nicht gefunden.'
}

$output = Join-Path $root 'PC Steuerung.exe'
& $csc /nologo /target:winexe /optimize+ /out:"$output" `
    /reference:System.Windows.Forms.dll `
    /reference:System.Drawing.dll `
    (Join-Path $here 'PC-Steuerung-Launcher.cs')

if ($LASTEXITCODE -ne 0) {
    throw "Launcher-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)."
}
Write-Host "Launcher erstellt: $output"
