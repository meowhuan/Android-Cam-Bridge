# 环境与接入说明

当前基线版本：`v1.2.0-beta.1`

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
- Android Platform Tools（`adb`）
- 构建真实 OBS 插件时需要 OBS Studio SDK 源码
- 使用官方 `Inf2Cat.exe` 时需要 Windows SDK / WDK
- 安装 WinUSB 驱动、导入证书、注册虚拟摄像头时需要管理员权限

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

默认监听 `127.0.0.1:39393`。

## 运行 GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

GUI 当前支持：

- 连接模式：`managed` / `attach`
- 传输方式：`usb-adb` / `usb-native` / `usb-aoa` / `lan`
- USB Native 设备、状态、链路、握手
- USB AOA 连接、断开、状态轮询
- 本地 Receiver 自动拉起

## 构建 Android App

```powershell
cd android
gradle :app:assembleDebug
```

Android 端当前能力：

- 相机预览
- 麦克风开关
- 推流时防息屏
- 后台前台服务持续推流
- 自动重连
- 调试日志覆盖层
- USB AOA accessory 接入

## 传输模式

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

适合最稳定的开发联调。

### `usb-native`

适用于手机已经能通过 USB 网络访问 Receiver 的场景，不需要 `adb reverse`。

如果 Android 端接收地址留空，应用会尝试扫描常见 `192.168.x.x` USB 网络地址做自动发现。

### `usb-aoa`

先安装仓库自带 WinUSB 驱动：

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

如果 Windows 因“缺少数字签名”拒绝安装，先生成测试签名：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
```

如果本机没装 WDK：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser -UseMakeCatFallback
```

小范围测试建议优先这样处理证书与驱动：

```powershell
certutil -addstore -f Root .\drivers\aoa-winusb\acb-aoa.cer
certutil -addstore -f TrustedPublisher .\drivers\aoa-winusb\acb-aoa.cer
pnputil /add-driver .\drivers\aoa-winusb\acb-aoa.inf /install
```

如果目标机仍拒绝测试签名，再把 `TESTSIGNING` 作为兜底：

```powershell
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

如果构建输出是 `stub-no-obs-sdk`，说明当前 DLL 只是占位文件，不能被真实 OBS 加载。

## DirectShow 虚拟摄像头

注册：

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

启动桥接：

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

反注册：

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```
