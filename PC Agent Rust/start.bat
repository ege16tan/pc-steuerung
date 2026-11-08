@echo off
rem PC-Agent in Rust: baut die Release-EXE, legt sie neben start.bat ab und startet sie.
rem Dadurch bleiben credentials.json und agent.log im stabilen Ordner (nicht in target\).
cd /d "%~dp0"

if exist "%USERPROFILE%\.cargo\bin\cargo.exe" (
    set "CARGO=%USERPROFILE%\.cargo\bin\cargo.exe"
) else (
    set "CARGO=cargo"
)

if "%PC_AGENT_TOKEN%"=="" (
  echo Hinweis: PC_AGENT_TOKEN ist nicht gesetzt - der Agent laeuft ohne Token.
)
if "%PC_AGENT_SIMULATE%"=="" (
  set "PC_AGENT_SIMULATE=1"
)
set "PORT=8000"
if not "%PC_AGENT_PORT%"=="" set "PORT=%PC_AGENT_PORT%"
echo PC-Agent startet auf http://0.0.0.0:%PORT%  (Simulation=%PC_AGENT_SIMULATE%)
echo Erststart: Web-Oberflaeche am PC oeffnen und Zugangsdaten einrichten.

echo Baue Agent ...
"%CARGO%" build --release
if errorlevel 1 (
    echo Bauen fehlgeschlagen.
    pause
    exit /b 1
)

copy /y "target\release\pc-agent.exe" "pc-agent.exe" >nul
if errorlevel 1 (
    echo Kopieren der EXE fehlgeschlagen.
    pause
    exit /b 1
)

rem Bestehende Zugangsdaten einmalig uebernehmen, damit der Umstieg
rem vom Python-Agenten ohne neue Ersteinrichtung klappt.
if not exist "credentials.json" (
    if exist "..\PC App\credentials.json" (
        copy /y "..\PC App\credentials.json" "credentials.json" >nul
        echo Zugangsdaten aus ..\PC App uebernommen.
    )
)

"pc-agent.exe"
pause
