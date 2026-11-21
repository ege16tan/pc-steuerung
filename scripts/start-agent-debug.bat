@echo off
rem Debug-Start: Agent in sichtbarer Konsole plus Live-Anzeige des Agent-Logs.
rem Fuer den Normalbetrieb reicht start-agent.bat (alles im Hintergrund + Tray).
setlocal
cd /d "%~dp0"

echo === PC Steuerung: Debug-Start ===
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0agent-manager.ps1" -Action start -ShowConsole

echo.
echo === agent.log (live, Strg+C beendet nur diese Anzeige) ===
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$log = Join-Path $env:LOCALAPPDATA 'PC Steuerung\agent.log'; if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 40 -Wait } else { Write-Host 'Noch keine Logdatei vorhanden.' }"

endlocal