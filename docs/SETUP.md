# Setup Guide

## Components
- Android app (`android/`)
- Windows receiver (`windows/receiver`)
- OBS plugin (`windows/obs-plugin`)

## Windows prerequisites
- Visual Studio 2022 (Desktop C++)
- CMake 3.22+
- OBS Studio (for plugin testing)
- Android Platform Tools (ADB)

## Quick start
1. Build Windows targets:
   ```powershell
   cmake -S . -B build -G "Visual Studio 17 2022" -A x64
   cmake --build build --config Release
   ```
2. Start receiver:
   ```powershell
   .\build\windows\receiver\Release\acb-receiver.exe
   ```
3. Verify API:
   ```powershell
   curl http://127.0.0.1:39393/api/v1/devices
   ```
4. Build/install Android app from Android Studio.

## USB ADB mode (v0.1)
```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

## OBS plugin integration
- Build with OBS SDK paths so module exports are present:
  ```powershell
  cmake -S . -B build -G "Visual Studio 17 2022" -A x64 `
    -DOBS_INCLUDE_DIR="C:/path/to/obs-studio/libobs" `
    -DOBS_GENERATED_INCLUDE_DIR="C:/path/to/obs-studio/build_x64/config" `
    -DOBS_LIB_DIR="C:/path/to/obs-studio/build/libobs/Release"
  cmake --build build --config Release --target acb-obs-plugin
  ```
- Install plugin files (DLL + locale):
  ```powershell
  .\scripts\install-obs-plugin.ps1
  ```
- Result layout:
  - `C:\ProgramData\obs-studio\plugins\acb-obs-plugin\bin\64bit\acb-obs-plugin.dll`
  - `C:\ProgramData\obs-studio\plugins\acb-obs-plugin\data\locale\en-US.ini`
- Add source `android_cam_source` in OBS.
- Use OBS Virtual Camera when needed by external apps.
