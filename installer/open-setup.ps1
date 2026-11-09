param([switch]$SkipStart)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$manager = Join-Path $root 'agent-manager.ps1'
$url = 'http://127.0.0.1:8000/'

Add-Type -AssemblyName System.Windows.Forms

try {
    if (-not $SkipStart) {
        & $manager -Action start -Port 8000 | Out-Null
    }

    $ready = $false
    $deadline = (Get-Date).AddSeconds(15)
    while (-not $ready -and (Get-Date) -lt $deadline) {
        try {
            $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/setup-status' -TimeoutSec 2
            $ready = $true
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    if (-not $ready) {
        throw 'Der PC-Agent antwortet nicht auf Port 8000.'
    }

    $trayTask = Get-ScheduledTask -TaskName 'PC Steuerung Tray' -ErrorAction SilentlyContinue
    if ($trayTask) {
        Start-Process -FilePath (Join-Path $root 'start-tray.bat') -WorkingDirectory $root -WindowStyle Hidden
    }
    Start-Process $url
} catch {
    [System.Windows.Forms.MessageBox]::Show(
        "PC Steuerung konnte nicht gestartet werden.`n`n$($_.Exception.Message)",
        'PC Steuerung',
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error
    ) | Out-Null
    exit 1
}
