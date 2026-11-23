#define AppName "CLM Studio"
#ifndef AppVersion
  #define AppVersion "0.2.1+5"
#endif
#ifndef PublishDir
  #define PublishDir "..\desktop\bin\Release\net8.0-windows\win-x64\publish"
#endif
#ifndef OutputDir
  #define OutputDir "..\artifacts"
#endif

[Setup]
AppId={{A3E2D087-75D0-4D29-B975-1C83F9DBE782}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=CLM Tools
DefaultDirName={localappdata}\Programs\CLM Studio
DefaultGroupName=CLM Studio
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=CLM-Studio-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\desktop\Assets\clm.ico
UninstallDisplayIcon={app}\CLMStudio.exe

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加快捷方式："; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb"

[Icons]
Name: "{autoprograms}\CLM Studio"; Filename: "{app}\CLMStudio.exe"
Name: "{autodesktop}\CLM Studio"; Filename: "{app}\CLMStudio.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CLMStudio.exe"; Description: "启动 CLM Studio"; Flags: postinstall nowait skipifsilent
