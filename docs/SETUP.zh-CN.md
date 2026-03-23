# 环境与接入说明

## 组件

- Android 应用（`android/`）
- Windows Receiver（`windows/receiver/`）
- WinUI GUI（`windows/gui/Acb.Gui/`）
- OBS 插件（`windows/obs-plugin/`）
- VirtualCam Bridge（`windows/virtualcam-bridge/`）
- DirectShow 虚拟摄像头驱动（`windows/virtualcam-driver/`）
- AOA WinUSB 驱动文件（`drivers/aoa-winusb/`）

## Windows 环境要求

- Visual Studio 2022（Desktop C++）
- CMake 3.22+
- PowerShell 7+
- .NET 10 SDK
- Java 17
- Android Platform Tools（ADB）
- 构建真实 OBS 插件时需要 OBS Studio SDK 源码
- 安装 WinUSB 驱动与注册虚拟摄像头时需要管理员权限

## 构建 Windows 目标

```powershell
pwsh .\scripts\build.ps1 -Config Release
```

会构建：

- `acb-receiver`
- `acb-virtualcam-bridge`
- `acb-virtualcam`
- `acb-obs-plugin`（是否为真实插件取决于是否提供 OBS SDK）

## 启动 Receiver

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

## 运行 GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

## 构建 Android App

```powershell
cd android
gradle :app:assembleDebug
```

## 三种 USB 模式

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

### `usb-native`

适用于手机已能通过 USB 网络访问 Receiver 的场景，不需要 `adb reverse`。

### `usb-aoa`

先安装仓库自带 WinUSB 驱动：

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

如果 Windows 因“缺少数字签名”拒绝安装，先生成测试签名：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
bcdedit /set testsigning on
```

然后在 GUI 中：

1. 选择 `USB（AOA 直连）`
2. 点击 `AOA Connect`
3. 启动 v2 会话

## OBS 插件

带 OBS SDK 路径构建：

```powershell
pwsh .\scripts\build.ps1 -Config Release `
  -ObsIncludeDir "C:/path/to/obs-studio/libobs" `
  -ObsGeneratedIncludeDir "C:/path/to/obs-studio/build_x64/config" `
  -ObsLibDir "C:/path/to/obs-studio/build_x64/libobs/Release"
```

安装插件：

```powershell
pwsh .\scripts\install-obs-plugin.ps1
```

## DirectShow 虚拟摄像头

注册：

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

启动桥接：

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

卸载：

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```
