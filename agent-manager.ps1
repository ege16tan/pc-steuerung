param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',
    [ValidateRange(1, 65535)]
    [int]$Port = 8000,
    # Debug: Agent in einem sichtbaren Konsolenfenster starten.
    [switch]$ShowConsole
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$rustDir = Join-Path $projectRoot 'PC Agent Rust'
$stateDir = Join-Path $env:LOCALAPPDATA 'PC Steuerung'
$managerLog = Join-Path $stateDir 'agent-manager.log'
$script:stagedRustExe = $null

function Write-ManagerLog([string]$message) {
    if (-not (Test-Path -LiteralPath $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }
    Add-Content -LiteralPath $managerLog -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message)
}

<#
    Startet das Tray-Symbol, falls noch keins laeuft.

    Grund: Bisher startete nur start-tray.bat das Tray. Wer den Agenten ueber
    start-agent.bat, den Autostart oder die Aufgabenplanung startete, hatte
    kein Symbol im Infobereich. Das Tray-Skript selbst sichert sich ueber den
    Mutex 'Local\PCSteuerungTray' gegen doppelte Symbole - hier wird derselbe
    Mutex nur kurz getestet, danach wird das Skript gestartet.
#>
function Start-TrayIfNeeded {
    $trayScript = Join-Path $projectRoot 'tray.ps1'
    if (-not (Test-Path -LiteralPath $trayScript -PathType Leaf)) { return $false }

    $trayMutex = $null
    try {
        $trayMutex = New-Object System.Threading.Mutex($false, 'Local\PCSteuerungTray')
        if (-not $trayMutex.WaitOne(0)) { return $false }
        $trayMutex.ReleaseMutex()
    } catch [System.Threading.AbandonedMutexException] {
        # Ein abgestuerztes Tray hat den Mutex hinterlassen: neu starten.
    } finally {
        if ($trayMutex) { $trayMutex.Dispose() }
    }

    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-STA', '-WindowStyle', 'Hidden',
        '-File', ('"' + $trayScript + '"')
    ) -WindowStyle Hidden | Out-Null
    return $true
}

function Get-ListenerIds {
    try {
        return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch {
        return @()
    }
}

function Get-AgentProcess {
    foreach ($processId in (Get-ListenerIds)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        $kind = 'unknown'
        if ($process.Name -ieq 'pc-agent.exe' -and
            $process.ExecutablePath -and
            $process.ExecutablePath.StartsWith($rustDir, [System.StringComparison]::OrdinalIgnoreCase)) {
            $kind = 'rust'
        }
        return [PSCustomObject]@{
            processId = [int]$processId
            version = $kind
            name = $process.Name
            commandLine = $process.CommandLine
        }
    }
    return $null
}

function Stop-Agent {
    $current = Get-AgentProcess
    if (-not $current) {
        if (Get-ListenerIds) { throw "Port $Port gehoert nicht zum PC-Agenten; Prozess bleibt unangetastet." }
        return 'Agent war bereits gestoppt.'
    }
    if ($current.version -eq 'unknown') {
        throw "Port $Port gehoert zu einem anderen Dienst ($($current.name)); Prozess bleibt unangetastet."
    }
    Stop-Process -Id $current.processId -Force -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(12)
    while ((Get-ListenerIds).Count -gt 0 -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if ((Get-ListenerIds).Count -gt 0) { throw "PC-Agent hat Port $Port nicht freigegeben." }
    Write-ManagerLog "Stopped rust (PID $($current.processId))."
    return 'Agent gestoppt.'
}

function Prepare-Agent {
    $exe = Join-Path $rustDir 'pc-agent.exe'
    $cargo = Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
    if (-not (Test-Path -LiteralPath $cargo)) {
        $cargoCommand = Get-Command cargo -ErrorAction SilentlyContinue
        if ($cargoCommand) { $cargo = $cargoCommand.Source }
    }
    $cargoManifest = Join-Path $rustDir 'Cargo.toml'
    if ((Test-Path -LiteralPath $cargo) -and (Test-Path -LiteralPath $cargoManifest -PathType Leaf)) {
        Write-ManagerLog 'Building Rust agent.'
        Push-Location $rustDir
        try {
            # Cargo schreibt Fortschritt auf stderr; mit ErrorActionPreference=Stop
            # wuerde PowerShell das faelschlich als Abbruch werten.
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & $cargo build --release *>> $managerLog
            } finally {
                $ErrorActionPreference = $previousPreference
            }
            if ($LASTEXITCODE -ne 0) { throw "Rust-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)." }
            $script:stagedRustExe = Join-Path $rustDir 'target\release\pc-agent.exe'
            if (-not (Test-Path -LiteralPath $script:stagedRustExe -PathType Leaf)) {
                throw 'Rust-Build hat pc-agent.exe nicht erzeugt.'
            }
        } finally {
            Pop-Location
        }
    } elseif (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        throw 'Rust-Agent ist noch nicht gebaut und Cargo wurde nicht gefunden.'
    }
}

function Start-Agent {
    $running = Get-AgentProcess
    $replaceRust = $false
    $exe = Join-Path $rustDir 'pc-agent.exe'
    if ($script:stagedRustExe) {
        $replaceRust = -not (Test-Path -LiteralPath $exe -PathType Leaf)
        if (-not $replaceRust) {
            $deployedHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
            $builtHash = (Get-FileHash -LiteralPath $script:stagedRustExe -Algorithm SHA256).Hash
            $replaceRust = $deployedHash -ne $builtHash
        }
    }
    if ($running) {
        if ($running.version -eq 'rust' -and -not $replaceRust) {
            return 'Agent laeuft bereits.'
        }
        if ($running.version -eq 'unknown') { throw "Port $Port gehoert zu einem anderen Dienst." }
        Stop-Agent | Out-Null
    } elseif (Get-ListenerIds) {
        throw "Port $Port gehoert nicht zum PC-Agenten; Prozess bleibt unangetastet."
    }

    if ($script:stagedRustExe -and $replaceRust) {
        Copy-Item -LiteralPath $script:stagedRustExe -Destination $exe -Force
    }
    $oldPort = $env:PC_AGENT_PORT
    $oldSimulation = $env:PC_AGENT_SIMULATE
    try {
        $env:PC_AGENT_PORT = "$Port"
        if (-not $env:PC_AGENT_SIMULATE) { $env:PC_AGENT_SIMULATE = '1' }
        if ($ShowConsole) {
            Write-Host "Starte Agent: $exe"
            Write-Host "Datenordner: $stateDir"
        }
        if ($ShowConsole) {
            Start-Process -FilePath $exe -WorkingDirectory $rustDir | Out-Null
        } else {
            Start-Process -FilePath $exe -WorkingDirectory $rustDir -WindowStyle Hidden | Out-Null
        }
    } finally {
        if ($null -eq $oldPort) { Remove-Item Env:PC_AGENT_PORT -ErrorAction SilentlyContinue }
        else { $env:PC_AGENT_PORT = $oldPort }
        if ($null -eq $oldSimulation) { Remove-Item Env:PC_AGENT_SIMULATE -ErrorAction SilentlyContinue }
        else { $env:PC_AGENT_SIMULATE = $oldSimulation }
    }

    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Get-ListenerIds) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 250 }
    $running = Get-AgentProcess
    if (-not $running -or $running.version -ne 'rust') {
        throw "Agent wurde gestartet, lauscht aber noch nicht auf Port $Port."
    }
    Write-ManagerLog "Started rust (PID $($running.processId)) on port $Port."
    return 'Agent gestartet.'
}

switch ($Action) {
    'status' {
        $running = Get-AgentProcess
        [PSCustomObject]@{
            active = if ($running) { $running.version } else { $null }
            port = $Port
        } | ConvertTo-Json -Compress
    }
    'stop' {
        Stop-Agent
    }
    'start' {
        Prepare-Agent
        $result = Start-Agent
        # Immer dafuer sorgen, dass ein Tray-Symbol im Infobereich existiert.
        if (Start-TrayIfNeeded) {
            Write-ManagerLog 'Tray automatic gestartet.'
            if ($ShowConsole) { Write-Host 'Tray-Symbol gestartet.' }
        }
        $result
    }
}
