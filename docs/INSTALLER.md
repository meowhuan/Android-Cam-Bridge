# ACB Unified Installer (Script-based)

This project now includes a unified install flow for:
- Receiver
- GUI
- OBS Plugin

## Build Package
From repo root:
```powershell
pwsh .\scripts\package.ps1 -Version 0.2.2
```

Output:
`dist/acb-win-x64`

## Build EXE Installer (Inno Setup)
Prerequisite: Inno Setup 6 (`ISCC.exe`)

From repo root:
```powershell
pwsh .\scripts\build-installer.ps1 -Version 0.2.2
```

Output:
`dist/installer/ACB-Setup-0.2.2.exe`

Installer supports components:
- Receiver
- GUI
- OBS Plugin (with OBS root selection page)

Notes:
- GUI is installed from full publish directory (not exe-only) to avoid side-by-side runtime issues.
- Start menu includes `Uninstall ACB`.
- Installer includes Visual C++ Redistributable x64 prerequisite and installs it when missing.
- EXE installer logging is enabled (`SetupLogging=yes`).

## Install
Run as Administrator:
```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1
```

Optional parameters:
```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1 `
  -InstallRoot "C:\Program Files\ACB" `
  -InstallReceiver:$true `
  -InstallGui:$true `
  -InstallObsPlugin:$true `
  -ObsPath "E:\SteamLibrary\steamapps\common\OBS Studio"
```

## Uninstall
```powershell
pwsh "C:\Program Files\ACB\uninstall-acb.ps1"
```

## OBS Path Detection
Installer will auto-detect these OBS roots:
- `C:\Program Files (x86)\Steam\steamapps\common\OBS Studio`
- `C:\Program Files\Steam\steamapps\common\OBS Studio`
- `D:\SteamLibrary\steamapps\common\OBS Studio`
- `E:\SteamLibrary\steamapps\common\OBS Studio`
- `F:\SteamLibrary\steamapps\common\OBS Studio`

You can override with `-ObsPath`.
