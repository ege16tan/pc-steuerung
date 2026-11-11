#define AppName "PC Steuerung"
#define AppVersion "0.9.5"
#define AppPublisher "PC Steuerung"
#define AppExeName "PC Agent Rust\pc-agent.exe"

[Setup]
AppId={{A72F71C3-9DFB-4C8C-8F82-4F3A0B211C19}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\PC Steuerung
DefaultGroupName=PC Steuerung
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
OutputDir=..\dist
OutputBaseFilename=PC-Steuerung-Setup-{#AppVersion}
UninstallDisplayIcon={app}\{#AppExeName}
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
Uninstallable=yes
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "autostart"; Description: "Agent bei der Windows-Anmeldung starten"; GroupDescription: "Startverhalten:"
Name: "tray"; Description: "Tray-Symbol bei der Windows-Anmeldung starten"; Flags: unchecked; Check: WizardIsTaskSelected('autostart')

[Files]
Source: "..\agent-manager.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start-agent.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start-agent-debug.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\start-tray.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\tray.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "open-setup.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\PC Agent Rust\target\release\pc-agent.exe"; DestDir: "{app}\PC Agent Rust"; Flags: ignoreversion
Source: "..\PC Agent Rust\install-autostart.ps1"; DestDir: "{app}\PC Agent Rust"; Flags: ignoreversion
Source: "..\PC Agent Rust\uninstall-autostart.ps1"; DestDir: "{app}\PC Agent Rust"; Flags: ignoreversion
Source: "..\PC Agent Rust\README.md"; DestDir: "{app}\PC Agent Rust"; Flags: ignoreversion

[Icons]
Name: "{group}\PC Steuerung starten"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\agent-manager.ps1"" -Action start"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"
Name: "{group}\PC Steuerung öffnen"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\open-setup.ps1"""; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"
Name: "{group}\Tray-Symbol starten"; Filename: "{app}\start-tray.bat"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"
Name: "{group}\Debug-Konsole"; Filename: "{app}\start-agent-debug.bat"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"
Name: "{group}\Agent stoppen"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\agent-manager.ps1"" -Action stop"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\PC Agent Rust\install-autostart.ps1"" -WithTray"; Flags: runhidden; Tasks: tray
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\PC Agent Rust\install-autostart.ps1"""; Flags: runhidden; Tasks: autostart; Check: not WizardIsTaskSelected('tray')
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\open-setup.ps1"""; Description: "PC-Agent starten und Setup mit QR-Code öffnen"; Flags: postinstall runasoriginaluser skipifsilent

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\agent-manager.ps1"" -Action stop"; Flags: runhidden
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\PC Agent Rust\uninstall-autostart.ps1"" -WithTray"; Flags: runhidden
