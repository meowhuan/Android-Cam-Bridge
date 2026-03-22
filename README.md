# Android Cam Bridge (ACB)

面向 **Android 采集端 + Windows 接收端** 的摄像头桥接项目，支持：
- Android CameraX 采集
- Windows Receiver（HTTP 控制面 + v2 WebSocket 媒体面）
- OBS Source 插件接入
- WinUI GUI 控制台
- 一键打包与安装器发布流程

## 当前仓库包含的部件

| 部件 | 路径 | 作用 | 当前状态 |
| --- | --- | --- | --- |
| Android App | `android/` | CameraX 采集、H.264/AAC 编码、v1+v2 推流 | 可用 |
| Receiver | `windows/receiver/` | 会话控制、帧接收、v2 H.264 解码、统计接口 | 可用 |
| OBS Plugin | `windows/obs-plugin/` | 在 OBS 中提供 `android_cam_source` 源 | 可用 |
| WinUI GUI | `windows/gui/Acb.Gui/` | Receiver 地址配置、会话管理、统计与日志 | 可用 |
| VirtualCam Bridge | `windows/virtualcam-bridge/` | 虚拟摄像头桥接实验入口 | 预留/实验 |
| Protocol Docs | `protocol/` | 信令、v2 媒体帧格式说明 | 可用 |
| Installer | `installer/` + `scripts/` | Payload 打包、Inno Setup 安装器 | 可用 |
| CI/Release | `.github/workflows/` | CI 构建 + Tag 发布资产 | 可用 |

## 架构速览

1. Android 启动后请求 Receiver `POST /api/v2/session/start`
2. Receiver 返回 `wsUrl`，Android 通过 `ws://.../ws/v2/media` 发送二进制媒体帧
3. Receiver 解析并累计统计，视频帧可通过 `GET /api/v2/frame.bgra` 拉取
4. OBS 插件优先拉取 `/api/v2/frame.bgra`，失败时回退 `/api/v1/frame.jpg`
5. GUI 通过 Receiver HTTP API 做 ADB 设置、会话管理和统计查看

## API 概览

Receiver 默认地址：`http://127.0.0.1:39393`

### v1（兼容路径）
- `POST /api/v1/session/start`
- `POST /api/v1/session/answer`
- `POST /api/v1/session/stop`
- `POST /api/v1/frame`
- `POST /api/v1/audio`
- `GET /api/v1/frame.jpg`
- `GET /api/v1/devices`
- `GET /api/v1/stats/{sessionId}`

### v2（主链路）
- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `POST /api/v2/adb/setup`
- `GET /api/v2/session/{sessionId}/stats`
- `GET /api/v2/frame.bgra`
- `WS /ws/v2/media`

详细协议见：
- [`protocol/README.md`](protocol/README.md)
- [`protocol/v2/README.md`](protocol/v2/README.md)
- [`protocol/v2/media_frame.md`](protocol/v2/media_frame.md)

## 开发环境要求

### Windows
- Visual Studio 2022（Desktop C++）
- CMake 3.22+
- PowerShell 7+
- .NET 10 SDK（GUI）
- Java 17 + Gradle（Android 构建）
- Android Platform Tools（ADB）
- OBS Studio（用于插件联调）

### OBS 兼容性注意
- 当前 CI/Release 以 **OBS 32.0.4** SDK 构建插件。
- 建议本机 OBS 版本与插件构建使用的 libobs 小版本保持一致，避免 ABI 不匹配导致加载失败。

## 快速开始

### 1) 构建 Windows 目标
```powershell
pwsh ./scripts/build.ps1 -Config Release
```

若需要构建可加载的真实 OBS 插件，请提供 OBS SDK 路径：
```powershell
pwsh ./scripts/build.ps1 -Config Release `
  -ObsIncludeDir "C:/path/to/obs-studio/libobs" `
  -ObsGeneratedIncludeDir "C:/path/to/obs-studio/build_x64/config" `
  -ObsLibDir "C:/path/to/obs-studio/build_x64/libobs/Release"
```

### 2) 启动 Receiver
```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

### 3) 运行 GUI（可选）
```powershell
pwsh ./scripts/run-gui.ps1
```

### 4) Android 端构建
```powershell
cd android
gradle :app:assembleDebug
```

## 打包与安装

### 生成 payload 目录
```powershell
pwsh ./scripts/package.ps1 -Version 0.2.4
```
输出：`dist/acb-win-x64`

### 生成安装器 EXE（Inno Setup）
```powershell
pwsh ./scripts/build-installer.ps1 -Version 0.2.4
```
输出：`dist/installer/ACB-Setup-0.2.4.exe`

### 本地安装 / 卸载
```powershell
pwsh ./dist/acb-win-x64/install-acb.ps1
pwsh "C:\Program Files\ACB\uninstall-acb.ps1"
```

安装器细节见 [`docs/INSTALLER.md`](docs/INSTALLER.md)。

## 自动化发布

- CI：`.github/workflows/ci.yml`
- Release：`.github/workflows/release.yml`

Tag 发布示例：
```powershell
git tag v0.2.4
git push origin v0.2.4
```

发布文档：
- [`docs/RELEASE.md`](docs/RELEASE.md)
- [`docs/RELEASE_NOTES.md`](docs/RELEASE_NOTES.md)

## 文档导航

- GUI 使用说明：[`docs/GUI.md`](docs/GUI.md)
- USB Native 说明：[`docs/USB_NATIVE.md`](docs/USB_NATIVE.md)
- VirtualCam Bridge：[`docs/VIRTUALCAM_BRIDGE.md`](docs/VIRTUALCAM_BRIDGE.md)
- 安装说明：[`docs/INSTALLER.md`](docs/INSTALLER.md)
- 环境与接入（EN）：[`docs/SETUP.md`](docs/SETUP.md)
- 环境与接入（中文）：[`docs/SETUP.zh-CN.md`](docs/SETUP.zh-CN.md)
- 故障排查（EN）：[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
- 故障排查（中文）：[`docs/TROUBLESHOOTING.zh-CN.md`](docs/TROUBLESHOOTING.zh-CN.md)

## 目录结构

```text
android/                    Android 采集端
windows/receiver/           Windows 接收端
windows/obs-plugin/         OBS 插件
windows/gui/Acb.Gui/        WinUI GUI
windows/virtualcam-bridge/  虚拟摄像头桥接（实验）
protocol/                   协议定义与文档
installer/                  安装器模板（Inno/WiX）
scripts/                    构建/打包/安装脚本
docs/                       使用、发布、排障文档
.github/workflows/          CI 与发布流水线
```
