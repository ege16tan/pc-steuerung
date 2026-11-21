$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$tray = Join-Path (Join-Path $root 'scripts') 'tray.ps1'
Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-STA','-WindowStyle','Hidden','-File',('"{0}"' -f $tray)) -WindowStyle Hidden | Out-Null
