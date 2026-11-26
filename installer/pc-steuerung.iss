#define AppName "PC Steuerung"
#define AppVersion "0.16.0"
#define AppPublisher "PC Steuerung"
#define AppExeName "PC Steuerung.exe"
#define AgentExeName "pc-agent.exe"

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
SetupIconFile=..\assets\PC Steuerung.ico

[Tasks]
Name: "desktopicon"; Description: "Desktop-Symbol anlegen"; GroupDescription: "Symbole:"

[Files]
Source: "..\dist\PC Steuerung.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\PC Agent Rust\target\release\pc-agent.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\assets\PC Steuerung.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\PC Steuerung"; Filename: "{app}\{#AppExeName}"; IconFilename: "{app}\PC Steuerung.ico"
Name: "{commondesktop}\PC Steuerung"; Filename: "{app}\{#AppExeName}"; IconFilename: "{app}\PC Steuerung.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "PC Steuerung starten"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\{#AppExeName}"; Parameters: ""; Flags: runhidden; RunOnceId: "StopService"
