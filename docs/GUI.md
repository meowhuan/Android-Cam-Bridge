# Windows GUI (WinUI 3)

Project path: `windows/gui/Acb.Gui`.

## Features
- Single-page minimal control panel
- UI language: `Auto / 简体中文 / English`
- Receiver address (`http://127.0.0.1:39393` by default)
- Connection mode:
  - `managed`: GUI handles ADB setup + session start/stop
  - `attach`: no new session, only attach-style monitoring intent
- Transport: `usb-adb` / `usb-native` (experimental) / `lan`
- Quality preset: `balanced` / `high` / `ultra` (maps to bitrate defaults)
- Fit mode semantic selector: `letterbox` / `crop` / `stretch` (for semantic parity with OBS plugin)
- Stream settings: resolution / fps / bitrate / audio enabled
- Android app supports optional background streaming mode via foreground service (toggle in app UI)
- Session actions:
  - `Setup ADB` -> `/api/v2/adb/setup`
  - `Start v2 Session` -> `/api/v2/session/start`
  - `Stop v2 Session` -> `/api/v2/session/stop`
  - `Fetch v2 Stats` -> `/api/v2/session/{id}/stats`
  - `Auto Refresh Stats (5s)`
- Lifecycle:
  - Auto-start local `acb-receiver.exe` when Receiver address is local
  - Optional auto-stop managed receiver on GUI exit

## Build (from GUI project directory)
```powershell
dotnet build -c Release
```

## Run
From repo root:
```powershell
pwsh .\scripts\run-gui.ps1
```

## Publish Single EXE
From repo root:
```powershell
pwsh .\scripts\publish-gui.ps1
```
Output directory:
`windows/gui/Acb.Gui/bin/Release/net10.0-windows10.0.19041.0/win-x64/publish`

Publish flow now includes:
1. Build `acb-receiver`
2. Embed receiver binary into GUI at build time
3. Publish single-file GUI with `ACB_EMBED_RECEIVER` define

## Notes
- GUI is intentionally minimal and uses local Receiver HTTP API.
- OBS plugin can run in managed mode without opening GUI.
- In embedded build, GUI can extract bundled receiver to:
  `%LOCALAPPDATA%\AcbGui\bundled\acb-receiver.exe`
- GUI runtime logs:
  `%LOCALAPPDATA%\AcbGui\logs\acb-gui-YYYYMMDD.log`
