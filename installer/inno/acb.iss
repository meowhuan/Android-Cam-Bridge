#define MyAppName "ACB"
#ifndef AppVersion
  #define AppVersion "0.2.2"
#endif

[Setup]
AppId={{5F0C9B1D-9A14-4E53-A783-EA7716D5A5B1}
AppName={#MyAppName}
AppVersion={#AppVersion}
AppPublisher=ACB
DefaultDirName={autopf}\ACB
DefaultGroupName=ACB
OutputDir=..\..\dist\installer
OutputBaseFilename=ACB-Setup-{#AppVersion}
Compression=lzma
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
WizardStyle=modern
UninstallDisplayIcon={app}\gui\Acb.Gui.exe
AllowNoIcons=no
SetupLogging=yes

[Types]
Name: "full"; Description: "Full installation"
Name: "custom"; Description: "Custom installation"; Flags: iscustom

[Components]
Name: "core"; Description: "Core"; Types: full custom; Flags: fixed
Name: "core\receiver"; Description: "Receiver"; Types: full custom; Flags: fixed
Name: "core\gui"; Description: "GUI"; Types: full custom; Flags: fixed
Name: "obs"; Description: "OBS Integration"; Types: full custom
Name: "obs\plugin"; Description: "OBS Plugin"; Types: full custom
Name: "extras"; Description: "Extra Integrations"; Types: full custom
Name: "extras\virtualcam"; Description: "DirectShow Virtual Camera"; Types: full custom
Name: "extras\usbdriver"; Description: "AOA WinUSB Driver Files"; Types: full custom

[Tasks]
Name: "desktopicon"; Description: "Create desktop shortcut"; GroupDescription: "Additional icons:"; Components: core\gui
Name: "desktopuninstall"; Description: "Create desktop uninstall shortcut"; GroupDescription: "Additional icons:"
Name: "installaoacert"; Description: "Install AOA test certificate now"; GroupDescription: "Device setup:"; Components: extras\usbdriver
Name: "installaoadriver"; Description: "Install AOA WinUSB driver now"; GroupDescription: "Device setup:"; Components: extras\usbdriver

[InstallDelete]
Type: filesandordirs; Name: "{app}\gui\*"; Components: core\gui

[Files]
Source: "..\..\dist\acb-win-x64\receiver\acb-receiver.exe"; DestDir: "{app}\receiver"; Flags: ignoreversion; Components: core\receiver
Source: "..\..\dist\acb-win-x64\gui\*"; DestDir: "{app}\gui"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: core\gui
Source: "..\..\dist\acb-win-x64\prereqs\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Components: core\gui
Source: "..\..\dist\acb-win-x64\obs-plugin\acb-obs-plugin.dll"; DestDir: "{code:GetObsPlugin64Dir}"; Flags: ignoreversion skipifsourcedoesntexist; Components: obs\plugin
Source: "..\..\dist\acb-win-x64\obs-plugin\locale\en-US.ini"; DestDir: "{code:GetObsPluginLocaleDir}"; Flags: ignoreversion skipifsourcedoesntexist; Components: obs\plugin
Source: "..\..\dist\acb-win-x64\obs-plugin\locale\zh-CN.ini"; DestDir: "{code:GetObsPluginLocaleDir}"; Flags: ignoreversion skipifsourcedoesntexist; Components: obs\plugin
Source: "..\..\dist\acb-win-x64\virtualcam-bridge\acb-virtualcam-bridge.exe"; DestDir: "{app}\virtualcam-bridge"; Flags: ignoreversion; Components: extras\virtualcam
Source: "..\..\dist\acb-win-x64\virtualcam-driver\acb-virtualcam.dll"; DestDir: "{app}\virtualcam-driver"; Flags: ignoreversion regserver; Components: extras\virtualcam
Source: "..\..\dist\acb-win-x64\drivers\aoa-winusb\acb-aoa.inf"; DestDir: "{app}\drivers\aoa-winusb"; Flags: ignoreversion; Components: extras\usbdriver
Source: "..\..\dist\acb-win-x64\drivers\aoa-winusb\acb-aoa.cat"; DestDir: "{app}\drivers\aoa-winusb"; Flags: ignoreversion skipifsourcedoesntexist; Components: extras\usbdriver
Source: "..\..\dist\acb-win-x64\drivers\aoa-winusb\acb-aoa.cer"; DestDir: "{app}\drivers\aoa-winusb"; Flags: ignoreversion skipifsourcedoesntexist; Components: extras\usbdriver
Source: "..\..\dist\acb-win-x64\drivers\aoa-winusb\install-driver.ps1"; DestDir: "{app}\drivers\aoa-winusb"; Flags: ignoreversion; Components: extras\usbdriver

[Icons]
Name: "{group}\ACB GUI"; Filename: "{app}\gui\Acb.Gui.exe"; Components: core\gui
Name: "{group}\Uninstall ACB"; Filename: "{uninstallexe}"
Name: "{autodesktop}\ACB GUI"; Filename: "{app}\gui\Acb.Gui.exe"; Tasks: desktopicon; Components: core\gui
Name: "{autodesktop}\Uninstall ACB"; Filename: "{uninstallexe}"; Tasks: desktopuninstall

[Run]
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; Flags: runhidden waituntilterminated; Check: NeedInstallVCRedist; Components: core\gui
Filename: "{sys}\certutil.exe"; Parameters: "-addstore -f Root ""{app}\drivers\aoa-winusb\acb-aoa.cer"""; StatusMsg: "Installing AOA test certificate to LocalMachine\\Root..."; Flags: runhidden waituntilterminated; Components: extras\usbdriver; Tasks: installaoacert; Check: AoaCertExists
Filename: "{sys}\certutil.exe"; Parameters: "-addstore -f TrustedPublisher ""{app}\drivers\aoa-winusb\acb-aoa.cer"""; StatusMsg: "Installing AOA test certificate to LocalMachine\\TrustedPublisher..."; Flags: runhidden waituntilterminated; Components: extras\usbdriver; Tasks: installaoacert; Check: AoaCertExists
Filename: "{sys}\pnputil.exe"; Parameters: "/add-driver ""{app}\drivers\aoa-winusb\acb-aoa.inf"" /install"; StatusMsg: "Installing AOA WinUSB driver..."; Flags: runhidden waituntilterminated; Components: extras\usbdriver; Tasks: installaoadriver
Filename: "{app}\gui\Acb.Gui.exe"; Description: "Launch ACB GUI"; Flags: nowait postinstall skipifsilent; Components: core\gui

[Code]
var
  ObsPage: TInputDirWizardPage;

function CandidateObsRoot(const Index: Integer): string;
begin
  case Index of
    0: Result := 'C:\Program Files (x86)\Steam\steamapps\common\OBS Studio';
    1: Result := 'C:\Program Files\Steam\steamapps\common\OBS Studio';
    2: Result := 'D:\SteamLibrary\steamapps\common\OBS Studio';
    3: Result := 'E:\SteamLibrary\steamapps\common\OBS Studio';
    4: Result := 'F:\SteamLibrary\steamapps\common\OBS Studio';
  else
    Result := '';
  end;
end;

function DetectObsRoot: string;
var
  I: Integer;
  Candidate: string;
begin
  Result := '';
  for I := 0 to 8 do begin
    Candidate := CandidateObsRoot(I);
    if (Candidate <> '') and DirExists(Candidate) then begin
      Result := Candidate;
      Exit;
    end;
  end;
end;

procedure InitializeWizard;
var
  ObsRootParam: string;
  ObsRootDefault: string;
begin
  ObsPage := CreateInputDirPage(
    wpSelectComponents,
    'OBS Plugin Settings',
    'Select OBS Studio directory',
    'Set OBS Studio root path. Plugin files will be copied into obs-plugins and data folders.',
    False,
    ''
  );
  ObsPage.Add('');
  ObsRootParam := ExpandConstant('{param:ObsRoot|}');
  if ObsRootParam <> '' then
    ObsRootDefault := ObsRootParam
  else
    ObsRootDefault := DetectObsRoot;
  if ObsRootDefault = '' then
    ObsRootDefault := ExpandConstant('{pf}\obs-studio');
  ObsPage.Values[0] := ObsRootDefault;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if (PageID = ObsPage.ID) and (not WizardIsComponentSelected('obs\plugin')) then
    Result := True;
end;

function IsValidObsRoot(const Root: string): Boolean;
var
  ExePath: string;
begin
  ExePath := Root + '\bin\64bit\obs64.exe';
  Result := DirExists(Root) and FileExists(ExePath);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = ObsPage.ID then begin
    if not IsValidObsRoot(ObsPage.Values[0]) then begin
      MsgBox('Invalid OBS Studio root (missing bin\\64bit\\obs64.exe): ' + ObsPage.Values[0], mbError, MB_OK);
      Result := False;
    end;
  end;
end;

function GetObsPlugin64Dir(const Param: string): string;
begin
  Result := ObsPage.Values[0] + '\obs-plugins\64bit';
end;

function GetObsPluginLocaleDir(const Param: string): string;
begin
  Result := ObsPage.Values[0] + '\data\obs-plugins\acb-obs-plugin\locale';
end;

function NeedInstallVCRedist: Boolean;
var
  Installed: Cardinal;
begin
  Result := True;
  if RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', Installed) then
    if Installed = 1 then
      Result := False;
end;

function AoaCertExists: Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\drivers\aoa-winusb\acb-aoa.cer'));
end;

