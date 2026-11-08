Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$manager = Join-Path $root 'agent-manager.ps1'
$port = 8000

function Get-ManagerStatus {
    try {
        $json = & $manager -Action status -Port $port
        return ($json | ConvertFrom-Json)
    } catch {
        return [PSCustomObject]@{ selected = 'rust'; active = $null; port = $port }
    }
}

function Invoke-Manager([string]$action, [string]$version) {
    $versionArgument = if ($version) { " -Version $version" } else { '' }
    $arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$manager`" -Action $action -Port $port$versionArgument"
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden | Out-Null
}

function Open-WebUi {
    Start-Process "http://127.0.0.1:$port/"
}

function Invoke-AgentPost([string]$path, [string]$success) {
    try {
        Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port$path" -TimeoutSec 3 | Out-Null
        $notify.ShowBalloonTip(3000, 'PC Steuerung', $success, [System.Windows.Forms.ToolTipIcon]::Info)
    } catch {
        $notify.ShowBalloonTip(3000, 'PC Steuerung', 'Agent nicht erreichbar oder Aktion fehlgeschlagen.', [System.Windows.Forms.ToolTipIcon]::Warning)
    }
}

function New-StateIcon([System.Drawing.Color]$color, [ref]$bitmapRef) {
    $bitmap = New-Object System.Drawing.Bitmap 16, 16
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $brush = New-Object System.Drawing.SolidBrush $color
    $graphics.FillEllipse($brush, 1, 1, 14, 14)
    $graphics.Dispose()
    $brush.Dispose()
    $bitmapRef.Value = $bitmap
    return [System.Drawing.Icon]::FromHandle($bitmap.GetHicon())
}

$bitmapRust = $null
$bitmapPython = $null
$bitmapStopped = $null
$bitmapConflict = $null
$iconRust = New-StateIcon ([System.Drawing.Color]::FromArgb(46, 204, 113)) ([ref]$bitmapRust)
$iconPython = New-StateIcon ([System.Drawing.Color]::FromArgb(59, 130, 246)) ([ref]$bitmapPython)
$iconStopped = New-StateIcon ([System.Drawing.Color]::FromArgb(145, 153, 166)) ([ref]$bitmapStopped)
$iconConflict = New-StateIcon ([System.Drawing.Color]::FromArgb(245, 158, 11)) ([ref]$bitmapConflict)

$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Text = 'PC Steuerung'
$notify.Icon = $iconStopped
$notify.Visible = $true
$menu = New-Object System.Windows.Forms.ContextMenuStrip

$statusItem = New-Object System.Windows.Forms.ToolStripMenuItem
$statusItem.Text = 'Agent: wird geprüft …'
$statusItem.Enabled = $false
[void]$menu.Items.Add($statusItem)
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))

$startItem = New-Object System.Windows.Forms.ToolStripMenuItem
$startItem.Text = 'Agent starten (bevorzugte Version)'
$startItem.Add_Click({ Invoke-Manager 'start' ''; Update-Ui })
[void]$menu.Items.Add($startItem)

$stopItem = New-Object System.Windows.Forms.ToolStripMenuItem
$stopItem.Text = 'Agent stoppen'
$stopItem.Add_Click({ Invoke-Manager 'stop' ''; Update-Ui })
[void]$menu.Items.Add($stopItem)

$versionMenu = New-Object System.Windows.Forms.ToolStripMenuItem
$versionMenu.Text = 'Version auswählen'
$pythonItem = New-Object System.Windows.Forms.ToolStripMenuItem
$pythonItem.Text = 'Python verwenden'
$pythonItem.Add_Click({ Invoke-Manager 'switch' 'python'; Update-Ui })
$rustItem = New-Object System.Windows.Forms.ToolStripMenuItem
$rustItem.Text = 'Rust verwenden (empfohlen)'
$rustItem.Add_Click({ Invoke-Manager 'switch' 'rust'; Update-Ui })
[void]$versionMenu.DropDownItems.Add($rustItem)
[void]$versionMenu.DropDownItems.Add($pythonItem)
[void]$menu.Items.Add($versionMenu)

$openItem = New-Object System.Windows.Forms.ToolStripMenuItem
$openItem.Text = 'Web-UI im Browser öffnen'
$openItem.Add_Click({ Open-WebUi })
[void]$menu.Items.Add($openItem)

$shutdownItem = New-Object System.Windows.Forms.ToolStripMenuItem
$shutdownItem.Text = 'PC herunterfahren (60 Sekunden)'
$shutdownItem.Add_Click({ Invoke-AgentPost '/shutdown?delay=60' 'Herunterfahren in 60 s geplant.' })
[void]$menu.Items.Add($shutdownItem)

$abortItem = New-Object System.Windows.Forms.ToolStripMenuItem
$abortItem.Text = 'Herunterfahren abbrechen'
$abortItem.Add_Click({ Invoke-AgentPost '/abort' 'Herunterfahren abgebrochen.' })
[void]$menu.Items.Add($abortItem)

$refreshItem = New-Object System.Windows.Forms.ToolStripMenuItem
$refreshItem.Text = 'Status aktualisieren'
$refreshItem.Add_Click({ Update-Ui })
[void]$menu.Items.Add($refreshItem)
[void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))

$exitItem = New-Object System.Windows.Forms.ToolStripMenuItem
$exitItem.Text = 'Tray beenden'
$exitItem.Add_Click({
    $notify.Visible = $false
    $notify.Dispose()
    [System.Windows.Forms.Application]::Exit()
})
[void]$menu.Items.Add($exitItem)
$notify.ContextMenuStrip = $menu
$notify.Add_DoubleClick({ Open-WebUi })

$panel = New-Object System.Windows.Forms.Form
$panel.Text = 'PC Steuerung'
$panel.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedToolWindow
$panel.StartPosition = [System.Windows.Forms.FormStartPosition]::Manual
$panel.TopMost = $true
$panel.ShowInTaskbar = $false
$panel.ClientSize = New-Object System.Drawing.Size(260, 350)
$panelStatus = New-Object System.Windows.Forms.Label
$panelStatus.Dock = 'Top'
$panelStatus.Height = 30
$panelStatus.TextAlign = 'MiddleLeft'
$panelStatus.Padding = New-Object System.Windows.Forms.Padding(10, 0, 10, 0)
$panelStatus.Text = 'Agent wird geprüft …'
[void]$panel.Controls.Add($panelStatus)
$panelFlow = New-Object System.Windows.Forms.FlowLayoutPanel
$panelFlow.Dock = 'Fill'
$panelFlow.FlowDirection = 'TopDown'
$panelFlow.WrapContents = $false
$panelFlow.Padding = New-Object System.Windows.Forms.Padding(10)
[void]$panel.Controls.Add($panelFlow)

function Add-PanelButton([string]$label, [scriptblock]$onClick) {
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $label
    $button.Width = 234
    $button.Height = 34
    $button.Margin = New-Object System.Windows.Forms.Padding(0, 0, 0, 5)
    $button.Add_Click($onClick)
    [void]$panelFlow.Controls.Add($button)
    return $button
}

$panelStart = Add-PanelButton 'Agent starten (bevorzugt)' { Invoke-Manager 'start' ''; Update-Ui }
$panelStop = Add-PanelButton 'Agent stoppen' { Invoke-Manager 'stop' ''; Update-Ui }
$panelRust = Add-PanelButton 'Rust verwenden (empfohlen)' { Invoke-Manager 'switch' 'rust'; Update-Ui }
$panelPython = Add-PanelButton 'Python verwenden' { Invoke-Manager 'switch' 'python'; Update-Ui }
$panelShutdown = Add-PanelButton 'PC herunterfahren (60 s)' { Invoke-AgentPost '/shutdown?delay=60' 'Herunterfahren in 60 s geplant.' }
$panelAbort = Add-PanelButton 'Herunterfahren abbrechen' { Invoke-AgentPost '/abort' 'Herunterfahren abgebrochen.' }
$panelOpen = Add-PanelButton 'Web-UI im Browser öffnen' { Open-WebUi }
$panel.Add_Deactivate({ $panel.Hide() })
$notify.Add_MouseClick({
    param($sender, $eventArgs)
    if ($eventArgs.Button -eq [System.Windows.Forms.MouseButtons]::Left) {
        Update-Ui
        $position = [System.Windows.Forms.Cursor]::Position
        $x = [Math]::Max(0, $position.X - [int]($panel.Width / 2))
        $y = [Math]::Max(0, $position.Y - $panel.Height - 10)
        $panel.Location = New-Object System.Drawing.Point($x, $y)
        $panel.Show()
        $panel.Activate()
    }
})

function Update-Ui {
    $state = Get-ManagerStatus
    $versionMenu.Text = if ($state.selected -eq 'python') { 'Version auswählen (Python bevorzugt)' } else { 'Version auswählen (Rust bevorzugt)' }
    if ($state.active -eq 'rust') {
        $notify.Icon = $iconRust
        $notify.Text = "PC Steuerung - Rust aktiv (Port $port)"
        $statusItem.Text = 'Agent: Rust läuft'
        $startItem.Enabled = $false
        $stopItem.Enabled = $true
        $shutdownItem.Enabled = $true
        $abortItem.Enabled = $true
        $panelStatus.Text = 'Agent: Rust läuft'
        $panelStart.Enabled = $false
        $panelStop.Enabled = $true
        $panelRust.Enabled = $false
        $panelPython.Enabled = $true
        $panelShutdown.Enabled = $true
        $panelAbort.Enabled = $true
    } elseif ($state.active -eq 'python') {
        $notify.Icon = $iconPython
        $notify.Text = "PC Steuerung - Python aktiv (Port $port)"
        $statusItem.Text = 'Agent: Python läuft'
        $startItem.Enabled = $false
        $stopItem.Enabled = $true
        $shutdownItem.Enabled = $true
        $abortItem.Enabled = $true
        $panelStatus.Text = 'Agent: Python läuft'
        $panelStart.Enabled = $false
        $panelStop.Enabled = $true
        $panelRust.Enabled = $true
        $panelPython.Enabled = $false
        $panelShutdown.Enabled = $true
        $panelAbort.Enabled = $true
    } else {
        $notify.Icon = $iconStopped
        $notify.Text = 'PC Steuerung - Agent gestoppt'
        $statusItem.Text = "Agent: gestoppt (bevorzugt: $($state.selected))"
        $startItem.Enabled = $true
        $stopItem.Enabled = $false
        $shutdownItem.Enabled = $false
        $abortItem.Enabled = $false
        $panelStatus.Text = "Agent gestoppt (bevorzugt: $($state.selected))"
        $panelStart.Enabled = $true
        $panelStop.Enabled = $false
        $panelRust.Enabled = $true
        $panelPython.Enabled = $true
        $panelShutdown.Enabled = $false
        $panelAbort.Enabled = $false
    }
    $pythonItem.Checked = $state.selected -eq 'python'
    $rustItem.Checked = $state.selected -eq 'rust'
    if ($state.active -eq 'unknown') {
        $notify.Icon = $iconConflict
        $notify.Text = "PC Steuerung - Port $port ist belegt"
        $statusItem.Text = "Port $port wird von einem anderen Dienst verwendet"
        $startItem.Enabled = $false
        $stopItem.Enabled = $false
        $versionMenu.Enabled = $false
        $openItem.Enabled = $false
        $shutdownItem.Enabled = $false
        $abortItem.Enabled = $false
        $panelStatus.Text = "Port $port wird von einem anderen Dienst verwendet"
        $panelStart.Enabled = $false
        $panelStop.Enabled = $false
        $panelRust.Enabled = $false
        $panelPython.Enabled = $false
        $panelShutdown.Enabled = $false
        $panelAbort.Enabled = $false
        $panelOpen.Enabled = $false
    } else {
        $versionMenu.Enabled = $true
        $openItem.Enabled = $true
        $panelOpen.Enabled = $true
    }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 2500
$timer.Add_Tick({ Update-Ui })
$timer.Start()
Update-Ui
[System.Windows.Forms.Application]::Run()
