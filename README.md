# Android Cam Bridge (ACB)

面向 `Android 采集端 + Windows 接收端` 的摄像头桥接项目。当前 `dev` 分支已经包含以下链路与集成能力：

- Android Camera2/MediaCodec 采集、H.264/AAC 编码与推流
- Windows Receiver（HTTP 控制面 + v2 媒体面）
- GUI 控制台（WinUI 3）
- OBS Source 插件
- `usb-adb`、`usb-native`、`usb-aoa` 三种传输模式
- VirtualCam Bridge + DirectShow 虚拟摄像头驱动
- 统一打包、安装器与 GitHub Actions 发布流程

## 本次 PR 合并后的重点能力

- `usb-aoa`：Windows 端通过 WinUSB + Android Open Accessory 发起原生 USB bulk 传输，不依赖 ADB reverse。
- `usb-native`：保留无 ADB 的 USB 网络链路，适合手机可通过 USB 网络访问 Receiver 的场景。
- `acb-virtualcam-bridge`：持续从 Receiver 拉取 `GET /api/v2/frame.bgra`，写入共享内存。
- `acb-virtualcam.dll`：DirectShow 虚拟摄像头驱动，可直接把共享内存中的 BGRA 帧暴露为系统摄像头。
- `drivers/aoa-winusb/`：随仓库提供 AOA WinUSB INF 与安装脚本，方便本地调试和打包发布。

## 组件总览

| 组件 | 路径 | 作用 |
| --- | --- | --- |
| Android App | `android/` | 采集、编码、会话控制、USB accessory 接入 |
| Receiver | `windows/receiver/` | HTTP API、v2 媒体处理、USB AOA 接收 |
| GUI | `windows/gui/Acb.Gui/` | Receiver 管理、ADB 设置、USB 状态面板 |
| OBS Plugin | `windows/obs-plugin/` | 在 OBS 中提供 `android_cam_source` |
| VirtualCam Bridge | `windows/virtualcam-bridge/` | 把 Receiver 帧发布到共享内存 |
| VirtualCam Driver | `windows/virtualcam-driver/` | DirectShow 虚拟摄像头驱动 |
| AOA Driver Files | `drivers/aoa-winusb/` | WinUSB INF 与安装脚本 |
| Protocol Docs | `protocol/` | 信令与 v2 媒体帧说明 |

## 架构速览

### `usb-adb`

1. GUI 或用户执行 `POST /api/v2/adb/setup`
2. Android 通过 `transport=usb-adb` 发起会话
3. 媒体走 Receiver 的 v2 WebSocket / 兼容 HTTP 路径

### `usb-native`

1. Android 通过 USB 网络访问 Receiver 地址
2. `POST /api/v2/session/start` 使用 `transport=usb-native`
3. Receiver 提供 `/api/v2/usb-native/*` 调试与握手接口
4. Android 继续通过 HTTP 请求发送 packet

说明：`usb-native` 当前仍是“USB 网络上的原生模式”，不是 raw USB bulk。

### `usb-aoa`

1. Windows 端执行 `POST /api/v2/usb-aoa/connect`
2. Receiver 通过 WinUSB 与手机协商 AOA accessory 模式
3. Android App 在 accessory 模式下打开 `UsbAccessory`
4. 媒体帧通过 AOA bulk 通道直接写入 Receiver

### 虚拟摄像头

1. `acb-virtualcam-bridge` 拉取 `GET /api/v2/frame.bgra`
2. Bridge 将 BGRA 帧写入 `Local\acb_virtualcam_frame`
3. `acb-virtualcam.dll` 从共享内存读取最新帧
4. DirectShow 将 `Android Cam Bridge` 暴露给系统摄像头枚举

## 快速开始

### 1. 构建 Windows 目标

```powershell
pwsh .\scripts\build.ps1 -Config Release
```

如需构建可加载的 OBS 插件，请额外提供 OBS SDK 路径：

```powershell
pwsh .\scripts\build.ps1 -Config Release `
  -ObsIncludeDir "C:/path/to/obs-studio/libobs" `
  -ObsGeneratedIncludeDir "C:/path/to/obs-studio/build_x64/config" `
  -ObsLibDir "C:/path/to/obs-studio/build_x64/libobs/Release"
```

### 2. 启动 Receiver

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

### 3. 运行 GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

GUI 当前支持：

- `usb-adb`、`usb-native`、`usb-aoa`、`lan`
- `USB Native` 设备列表、状态与链路刷新
- `USB AOA` 连接/断开与状态查看
- 本地 Receiver 自动拉起

### 4. 构建 Android App

```powershell
cd android
gradle :app:assembleDebug
```

## USB 使用说明

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

适合最稳定的开发期联调。

### `usb-native`

- 不依赖 ADB reverse
- 需要手机通过 USB 网络可访问 Receiver
- 默认仍走 Receiver 的 HTTP/v2 packet 接口

详细说明见 [`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)。

### `usb-aoa`

1. 先构建并启动 Receiver
2. 以管理员身份安装 WinUSB 驱动：

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

3. 打开 GUI，选择 `USB (AOA Direct)`
4. 点击 `AOA Connect`
5. 启动 v2 会话

详细说明见 [`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)。

## Virtual Camera

构建产物：

- `build/windows/virtualcam-bridge/Release/acb-virtualcam-bridge.exe`
- `build/windows/virtualcam-driver/Release/acb-virtualcam.dll`

注册虚拟摄像头驱动：

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

启动桥接进程：

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

卸载驱动：

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

详细说明见 [`docs/VIRTUALCAM_BRIDGE.md`](docs/VIRTUALCAM_BRIDGE.md)。

## Receiver API 摘要

### v2 会话

- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`
- `POST /api/v2/adb/setup`
- `GET /api/v2/frame.bgra`
- `WS /ws/v2/media`

### USB 调试接口

- `GET /api/v2/usb-native/devices`
- `GET /api/v2/usb-native/status`
- `GET /api/v2/usb-native/link`
- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`
- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`

## 打包与发布

### 生成 Windows payload

```powershell
pwsh .\scripts\package.ps1 -Version 0.2.4
```

输出目录 `dist/acb-win-x64/` 当前包含：

- `receiver/`
- `gui/`
- `obs-plugin/`（真实 OBS SDK 构建时）
- `virtualcam-bridge/`
- `virtualcam-driver/`
- `drivers/aoa-winusb/`
- `prereqs/`

### 生成安装器

```powershell
pwsh .\scripts\build-installer.ps1 -Version 0.2.4
```

输出：`dist/installer/ACB-Setup-0.2.4.exe`

安装器当前支持的组件：

- `Receiver`
- `GUI`
- `OBS Plugin`
- `DirectShow Virtual Camera`
- `AOA WinUSB Driver Files`

其中：

- 选择虚拟摄像头组件时，安装器会自动注册 `acb-virtualcam.dll`
- 选择 AOA 驱动组件后，可在安装向导里勾选“立即安装 AOA WinUSB 驱动”
- 如需手动处理，仍可按文档执行 `regsvr32` 与 `pnputil/install-driver.ps1`

### GitHub Actions

- CI：`.github/workflows/ci.yml`
- Release：`.github/workflows/release.yml`

CI/Release 现在会校验这些新增产物是否进入 payload：

- `virtualcam-bridge/acb-virtualcam-bridge.exe`
- `virtualcam-driver/acb-virtualcam.dll`
- `drivers/aoa-winusb/acb-aoa.inf`
- `drivers/aoa-winusb/install-driver.ps1`

## 文档导航

- GUI 使用说明：[`docs/GUI.md`](docs/GUI.md)
- USB 模式说明：[`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)
- 虚拟摄像头：[`docs/VIRTUALCAM_BRIDGE.md`](docs/VIRTUALCAM_BRIDGE.md)
- 安装与打包：[`docs/INSTALLER.md`](docs/INSTALLER.md)
- 环境与接入（EN）：[`docs/SETUP.md`](docs/SETUP.md)
- 环境与接入（中文）：[`docs/SETUP.zh-CN.md`](docs/SETUP.zh-CN.md)
- 发布流程：[`docs/RELEASE.md`](docs/RELEASE.md)
- 发布记录：[`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)
- 协议说明：[`protocol/README.md`](protocol/README.md)
