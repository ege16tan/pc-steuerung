param(
    [ValidateSet('start', 'stop', 'switch', 'status')]
    [string]$Action = 'start',
    [ValidateSet('python', 'rust')]
    [string]$Version,
    [ValidateRange(1, 65535)]
    [int]$Port = 8000
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonDir = Join-Path $projectRoot 'PC App'
$rustDir = Join-Path $projectRoot 'PC Agent Rust'
$stateDir = Join-Path $env:LOCALAPPDATA 'PC Steuerung'
$versionFile = Join-Path $stateDir 'agent-version.txt'
$managerLog = Join-Path $stateDir 'agent-manager.log'
$script:stagedRustExe = $null

function Write-ManagerLog([string]$message) {
    if (-not (Test-Path -LiteralPath $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }
    Add-Content -LiteralPath $managerLog -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message)
}

function Get-PreferredVersion {
    if (Test-Path -LiteralPath $versionFile) {
        $saved = (Get-Content -LiteralPath $versionFile -Raw).Trim().ToLowerInvariant()
        if ($saved -in @('python', 'rust')) { return $saved }
    }
    return 'rust'
}

function Set-PreferredVersion([string]$value) {
    if (-not (Test-Path -LiteralPath $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($versionFile, $value, [System.Text.Encoding]::UTF8)
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
        } elseif ($process.Name -match '^python(w)?\.exe$' -and
            $process.CommandLine -match 'uvicorn' -and
            $process.CommandLine -match 'main:app' -and
            $process.CommandLine -match "--port\s+$Port") {
            $kind = 'python'
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
    Write-ManagerLog "Stopped $($current.version) (PID $($current.processId))."
    return "Agent ($($current.version)) gestoppt."
}

function Sync-Credentials([string]$targetDir) {
    # In einem eigenstaendigen Rust-Repo gibt es keinen Python-Datenordner;
    # dort bleiben Rust-Zugangsdaten bei der EXE.
    if (-not (Test-Path -LiteralPath $targetDir -PathType Container)) { return }
    $pythonCredentials = Join-Path $pythonDir 'credentials.json'
    $rustCredentials = Join-Path $rustDir 'credentials.json'
    $sources = @($pythonCredentials, $rustCredentials) |
        Where-Object { Test-Path -LiteralPath $_ } |
        ForEach-Object { Get-Item -LiteralPath $_ } |
        Sort-Object LastWriteTimeUtc -Descending
    if ($sources.Count -eq 0) { return }
    $source = $sources[0]
    $target = Join-Path $targetDir 'credentials.json'
    if (-not (Test-Path -LiteralPath $target) -or
        (Get-Item -LiteralPath $target).LastWriteTimeUtc -lt $source.LastWriteTimeUtc) {
        Copy-Item -LiteralPath $source.FullName -Destination $target -Force
    }
}

function Prepare-Agent([string]$targetVersion) {
    if ($targetVersion -eq 'python') {
        if (-not (Test-Path -LiteralPath (Join-Path $pythonDir 'main.py') -PathType Leaf)) {
            throw 'Python-Agentdateien wurden nicht installiert. Installiere die optionale Python-Komponente oder waehle Rust.'
        }
        if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
            throw 'Python wurde nicht gefunden. Installiere Python oder waehle Rust.'
        }
        Sync-Credentials $pythonDir
        return
    }

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
            & $cargo build --release *>> $managerLog
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
    # Python und Rust verwenden im gemeinsamen Repo PC App/credentials.json.
    # Dies uebernimmt auch eine vorhandene, neuere Rust-Datei vor dem Wechsel.
    Sync-Credentials $pythonDir
}

function Start-Agent([string]$targetVersion) {
    $running = Get-AgentProcess
    $replaceRust = $false
    $exe = Join-Path $rustDir 'pc-agent.exe'
    if ($targetVersion -eq 'rust' -and $script:stagedRustExe) {
        $replaceRust = -not (Test-Path -LiteralPath $exe -PathType Leaf)
        if (-not $replaceRust) {
            $deployedHash = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
            $builtHash = (Get-FileHash -LiteralPath $script:stagedRustExe -Algorithm SHA256).Hash
            $replaceRust = $deployedHash -ne $builtHash
        }
    }
    if ($running) {
        if ($running.version -eq $targetVersion -and -not $replaceRust) {
            return "Agent ($targetVersion) laeuft bereits."
        }
        if ($running.version -eq 'unknown') { throw "Port $Port gehoert zu einem anderen Dienst." }
        Stop-Agent | Out-Null
    } elseif (Get-ListenerIds) {
        throw "Port $Port gehoert nicht zum PC-Agenten; Prozess bleibt unangetastet."
    }

    if ($targetVersion -eq 'python') {
        $arguments = "-m uvicorn main:app --host 0.0.0.0 --port $Port --app-dir `"$pythonDir`""
        $oldPort = $env:PC_AGENT_PORT
        try {
            $env:PC_AGENT_PORT = "$Port"
            Start-Process -FilePath 'python' -ArgumentList $arguments -WorkingDirectory $pythonDir -WindowStyle Hidden | Out-Null
        } finally {
            if ($null -eq $oldPort) { Remove-Item Env:PC_AGENT_PORT -ErrorAction SilentlyContinue }
            else { $env:PC_AGENT_PORT = $oldPort }
        }
    } else {
        if ($script:stagedRustExe -and $replaceRust) {
            Copy-Item -LiteralPath $script:stagedRustExe -Destination $exe -Force
        }
        $oldPort = $env:PC_AGENT_PORT
        $oldSimulation = $env:PC_AGENT_SIMULATE
        try {
            $env:PC_AGENT_PORT = "$Port"
            if (-not $env:PC_AGENT_SIMULATE) { $env:PC_AGENT_SIMULATE = '1' }
            Start-Process -FilePath $exe -WorkingDirectory $rustDir -WindowStyle Hidden | Out-Null
        } finally {
            if ($null -eq $oldPort) { Remove-Item Env:PC_AGENT_PORT -ErrorAction SilentlyContinue }
            else { $env:PC_AGENT_PORT = $oldPort }
            if ($null -eq $oldSimulation) { Remove-Item Env:PC_AGENT_SIMULATE -ErrorAction SilentlyContinue }
            else { $env:PC_AGENT_SIMULATE = $oldSimulation }
        }
    }

    $deadline = (Get-Date).AddSeconds(15)
    while (-not (Get-ListenerIds) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 250 }
    $running = Get-AgentProcess
    if (-not $running -or $running.version -ne $targetVersion) {
        throw "Agent ($targetVersion) wurde gestartet, lauscht aber noch nicht auf Port $Port."
    }
    Write-ManagerLog "Started $targetVersion (PID $($running.processId)) on port $Port."
    return "Agent ($targetVersion) gestartet."
}

switch ($Action) {
    'status' {
        $running = Get-AgentProcess
        [PSCustomObject]@{
            selected = Get-PreferredVersion
            active = if ($running) { $running.version } else { $null }
            port = $Port
        } | ConvertTo-Json -Compress
    }
    'stop' {
        Stop-Agent
    }
    'start' {
        $desired = if ($Version) { $Version } else { Get-PreferredVersion }
        Prepare-Agent $desired
        $result = Start-Agent $desired
        Set-PreferredVersion $desired
        $result
    }
    'switch' {
        if (-not $Version) { throw 'Version python oder rust fehlt.' }
        Prepare-Agent $Version
        $result = Start-Agent $Version
        Set-PreferredVersion $Version
        $result
    }
}
