# Setup Guide

Current baseline: `v1.2.0-beta.1`

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
- Android Platform Tools (`adb`)
- OBS Studio SDK sources when building the real OBS plugin
- Windows SDK / WDK when using official `Inf2Cat.exe`
- Administrator privileges for:
  - WinUSB driver install
  - certificate import
  - `regsvr32`

## Build native targets

```powershell
pwsh .\scripts\build.ps1 -Config Release
```

This builds:

- `acb-receiver`
- `acb-virtualcam-bridge`
- `acb-virtualcam`
- `acb-obs-plugin` (real or stub, depending on OBS SDK inputs)

## Start Receiver

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

Receiver listens on `127.0.0.1:39393` by default.

## Run GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

The GUI can auto-start a local Receiver and exposes:

- connection mode: `managed` / `attach`
- transport: `usb-adb` / `usb-native` / `usb-aoa` / `lan`
- USB Native devices, status, link, handshake
- USB AOA connect, disconnect, status

## Build and run Android app

```powershell
cd android
gradle :app:assembleDebug
```

Android app capabilities currently include:

- camera preview
- microphone toggle
- keep screen awake while streaming
- background foreground-service streaming
- auto reconnect
- debug log overlay
- USB AOA accessory handling

## Transport modes

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

Use when you want the most stable debug flow.

### `usb-native`

Use this when the phone can reach Receiver over USB networking. No `adb reverse` is required.

If the receiver address is left blank on Android, the app will try a small auto-detection scan for common `192.168.x.x` USB networking ranges.

### `usb-aoa`

Install the bundled WinUSB driver first:

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

If Windows rejects the package as unsigned, generate a test-signed catalog first:

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
```

If WDK is not installed:

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser -UseMakeCatFallback
```

Recommended small-scale test flow:

```powershell
certutil -addstore -f Root .\drivers\aoa-winusb\acb-aoa.cer
certutil -addstore -f TrustedPublisher .\drivers\aoa-winusb\acb-aoa.cer
pnputil /add-driver .\drivers\aoa-winusb\acb-aoa.inf /install
```

If the target machine still rejects the package, use:

```powershell
bcdedit /set testsigning on
```

Then in GUI:

1. Select `USB (AOA Direct)`.
2. Click `AOA Connect`.
3. Start the v2 session.

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

If build output says `stub-no-obs-sdk`, the produced DLL is only a placeholder and will not load in OBS.

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
