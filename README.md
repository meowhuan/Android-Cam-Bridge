# Android Cam Bridge (ACB)

Android 手机采集端与 Windows 接收端之间的摄像头桥接项目。当前 `dev` 分支已经落地：

- Android Camera2 + MediaCodec 采集、H.264/AAC 编码与推流
- Windows Receiver（HTTP 控制面、v2 媒体面、Media Foundation 解码）
- WinUI 3 GUI
- OBS Source 插件
- `usb-adb`、`usb-native`、`usb-aoa`、`lan` 四种链路
- `acb-virtualcam-bridge` + `acb-virtualcam.dll` DirectShow 虚拟摄像头
- Windows payload、安装器、GitHub Actions CI/Release

当前预发行版基线：`v1.2.0-beta.1`

## 仓库结构

| 组件 | 路径 | 说明 |
| --- | --- | --- |
| Android App | `android/` | 手机端采集、编码、前台服务、USB accessory 接入 |
| Receiver | `windows/receiver/` | 本地 HTTP API、v2 媒体接收、H.264 解码、USB AOA/Native 状态管理 |
| GUI | `windows/gui/Acb.Gui/` | Receiver 管理、会话控制、USB Native/AOA 面板 |
| OBS Plugin | `windows/obs-plugin/` | OBS 中的 `android_cam_source` |
| VirtualCam Bridge | `windows/virtualcam-bridge/` | 轮询 `GET /api/v2/frame.bgra` 并写入共享内存 |
| VirtualCam Driver | `windows/virtualcam-driver/` | DirectShow 虚拟摄像头驱动 |
| AOA Driver Files | `drivers/aoa-winusb/` | WinUSB INF、catalog/cert、驱动安装脚本 |
| Protocol Docs | `protocol/` | v1/v2 控制面、媒体帧和封包说明 |

## 链路概览

### `usb-adb`

1. Receiver 执行 `adb reverse`
2. Android 使用 `transport=usb-adb` 发起 `/api/v2/session/start`
3. 媒体走 `WS /ws/v2/media`

适合最稳的开发联调路径。

### `usb-native`

1. Android 能通过 USB 网络访问 Receiver
2. `transport=usb-native` 发起 `/api/v2/session/start`
3. Android 通过 `/api/v2/usb-native/handshake` 建链
4. 媒体 packet 通过 `/api/v2/usb-native/packet` 发送

说明：`usb-native` 仍然是基于 USB 网络的 HTTP 链路，不是 raw USB bulk。

### `usb-aoa`

1. Windows 端执行 `POST /api/v2/usb-aoa/connect`
2. Receiver 通过 WinUSB 和设备协商 Android Open Accessory
3. Android App 打开 `UsbAccessory`
4. v2 媒体帧通过 AOA bulk 直传到 Receiver

这是当前真正的无 ADB raw USB 直连链路。

### Virtual Camera

1. `acb-virtualcam-bridge` 拉取 `GET /api/v2/frame.bgra`
2. Bridge 写入 `Local\acb_virtualcam_frame`
3. `acb-virtualcam.dll` 将共享内存中的帧暴露成系统摄像头

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

默认监听 `http://127.0.0.1:39393`。

### 3. 运行 GUI

```powershell
pwsh .\scripts\run-gui.ps1
```

GUI 当前支持：

- `managed` / `attach` 两种连接模式
- `usb-adb`、`usb-native`、`usb-aoa`、`lan`
- USB Native 设备列表、状态、链路、手动握手
- USB AOA 连接、断开、状态轮询
- 本地 Receiver 自动拉起和退出时自动停止

### 4. 构建 Android App

```powershell
cd android
gradle :app:assembleDebug
```

Android 端当前支持：

- 前台服务后台持续推流
- 自动重连
- 调试日志覆盖层
- 锁定横屏 / 防息屏
- `USB ADB`、`USB Native`、`USB AOA`、`Wi-Fi`

## USB 与驱动

### `usb-adb`

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

### `usb-native`

- 不依赖 `adb reverse`
- 手机需能通过 USB 网络访问 Receiver
- Receiver 暴露 `/api/v2/usb-native/*` 供 GUI 和 Android 调试/握手

详细说明见 [`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)。

### `usb-aoa`

1. 先生成并安装 WinUSB 驱动文件：

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

2. 如果 Windows 拒绝未签名驱动，先生成测试签名：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
```

脚本会优先使用 WDK `Inf2Cat.exe`；没有 WDK 时可改用：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser -UseMakeCatFallback
```

3. 小范围分发/测试机推荐优先导入测试证书，再安装驱动：

```powershell
certutil -addstore -f Root .\drivers\aoa-winusb\acb-aoa.cer
certutil -addstore -f TrustedPublisher .\drivers\aoa-winusb\acb-aoa.cer
pnputil /add-driver .\drivers\aoa-winusb\acb-aoa.inf /install
```

4. 如果目标机仍拒绝测试签名，再把 `TESTSIGNING` 作为兜底：

```powershell
bcdedit /set testsigning on
```

重启后重新安装驱动。

5. GUI 中选择 `USB (AOA Direct)`，点击 `AOA Connect`，再启动 v2 会话。

详细说明见 [`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)。

## Virtual Camera

构建产物：

- `build/windows/virtualcam-bridge/Release/acb-virtualcam-bridge.exe`
- `build/windows/virtualcam-driver/Release/acb-virtualcam.dll`

注册虚拟摄像头：

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

启动桥接：

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

卸载驱动：

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

详细说明见 [`docs/VIRTUALCAM_BRIDGE.md`](docs/VIRTUALCAM_BRIDGE.md)。

## Receiver API 摘要

### v2 会话与媒体

- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`
- `POST /api/v2/adb/setup`
- `GET /api/v2/frame.bgra`
- `WS /ws/v2/media`

### USB Native

- `GET /api/v2/usb-native/devices`
- `GET /api/v2/usb-native/status`
- `GET /api/v2/usb-native/link`
- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`

### USB AOA

- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`

## 打包、安装器与发布

### 生成 Windows payload

```powershell
pwsh .\scripts\package.ps1 -Version "1.2.0-beta.1"
```

输出目录 `dist/acb-win-x64/` 包含：

- `receiver/`
- `gui/`
- `virtualcam-bridge/`
- `virtualcam-driver/`
- `drivers/aoa-winusb/`
- `prereqs/`
- `obs-plugin/`，仅在真实 OBS SDK 构建时生成

若事先执行过 `scripts/sign-aoa-driver.ps1`，payload 还会额外带上：

- `drivers/aoa-winusb/acb-aoa.cat`
- `drivers/aoa-winusb/acb-aoa.cer`

### 生成安装器

```powershell
pwsh .\scripts\build-installer.ps1 -Version "1.2.0-beta.1"
```

输出：`dist/installer/ACB-Setup-1.2.0-beta.1.exe`

安装器支持：

- Receiver
- GUI
- OBS Plugin
- DirectShow Virtual Camera
- AOA WinUSB Driver Files

安装器行为：

- 选择虚拟摄像头组件时自动注册 `acb-virtualcam.dll`
- 若 payload 中存在 `acb-aoa.cer`，可勾选 `Install AOA test certificate now`
- 若勾选 `Install AOA WinUSB driver now`，会调用 `pnputil`

### GitHub Actions

- CI：`.github/workflows/ci.yml`
- Release：`.github/workflows/release.yml`

当前工作流会校验并打包：

- `virtualcam-bridge/acb-virtualcam-bridge.exe`
- `virtualcam-driver/acb-virtualcam.dll`
- `drivers/aoa-winusb/acb-aoa.inf`
- `drivers/aoa-winusb/install-driver.ps1`

若配置了：

- `AOA_TEST_CERT_PFX_BASE64`
- `AOA_TEST_CERT_PASSWORD`
- `AOA_TEST_CERT_CER_BASE64`（可选，建议配置）

则还会尝试生成并打包：

- `drivers/aoa-winusb/acb-aoa.cat`
- `drivers/aoa-winusb/acb-aoa.cer`

说明：

- `ci.yml` 中 AOA 签名失败会降级继续，不阻塞 CI
- `release.yml` 中若显式启用了 AOA 签名 secrets，则签名失败会让发布失败

## 文档导航

- GUI 使用说明：[`docs/GUI.md`](docs/GUI.md)
- USB 模式与 AOA：[`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)
- 虚拟摄像头：[`docs/VIRTUALCAM_BRIDGE.md`](docs/VIRTUALCAM_BRIDGE.md)
- 打包与安装器：[`docs/INSTALLER.md`](docs/INSTALLER.md)
- 环境与接入（EN）：[`docs/SETUP.md`](docs/SETUP.md)
- 环境与接入（中文）：[`docs/SETUP.zh-CN.md`](docs/SETUP.zh-CN.md)
- 发布流程：[`docs/RELEASE.md`](docs/RELEASE.md)
- 发布记录：[`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)
- 协议说明：[`protocol/README.md`](protocol/README.md)
- 故障排查：[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)、[`docs/TROUBLESHOOTING.zh-CN.md`](docs/TROUBLESHOOTING.zh-CN.md)
