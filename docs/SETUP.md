# Setup Guide

## Components

- Android app (`android/`)
- Windows receiver (`windows/receiver/`)
- WinUI GUI (`windows/gui/Acb.Gui/`)
- OBS plugin (`windows/obs-plugin/`)
- Virtual camera bridge (`windows/virtualcam-bridge/`)
- DirectShow virtual camera driver (`windows/virtualcam-driver/`)
- AOA WinUSB driver files (`drivers/aoa-winusb/`)

## Windows prerequisites

- Visual Studio 2022 with Desktop C++
- CMake 3.22+
- PowerShell 7+
- .NET 10 SDK
- Java 17
- Android Platform Tools (ADB)
- OBS Studio and OBS SDK sources when building the real OBS plugin
- Administrator privileges for WinUSB driver install and `regsvr32`

## Build native targets

```powershell
pwsh .\scripts\build.ps1 -Config Release
```

This builds:

- `acb-receiver`
- `acb-virtualcam-bridge`
- `acb-virtualcam`
- `acb-obs-plugin` (real or stub, depending on OBS SDK inputs)

## Start receiver

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

## Run GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

## Build Android app

```powershell
cd android
gradle :app:assembleDebug
```

## Transport modes

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

### `usb-native`

Use this when the phone can reach Receiver over USB networking. No `adb reverse` is required.

### `usb-aoa`

Install the bundled WinUSB driver first:

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

Then in GUI:

1. Select `USB (AOA Direct)`
2. Click `AOA Connect`
3. Start the v2 session

## OBS plugin

Build with OBS SDK paths:

```powershell
pwsh .\scripts\build.ps1 -Config Release `
  -ObsIncludeDir "C:/path/to/obs-studio/libobs" `
  -ObsGeneratedIncludeDir "C:/path/to/obs-studio/build_x64/config" `
  -ObsLibDir "C:/path/to/obs-studio/build_x64/libobs/Release"
```

Install plugin files:

```powershell
pwsh .\scripts\install-obs-plugin.ps1
```

## DirectShow virtual camera

Register:

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

Run bridge:

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

Unregister:

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```
