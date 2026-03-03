# Android Cam Bridge (ACB)

## English
Android camera streaming project for **Windows host + Android sender**, with OBS integration and v2 H.264 path.

### Features
- Android app capture and stream (USB ADB / LAN)
- Windows receiver (`acb-receiver`) with v1 compatibility + v2 media path
- OBS plugin (`acb-obs-plugin`) attaching to receiver APIs
- Optional WinUI GUI (`Acb.Gui`) for session management and diagnostics

### Repository Layout
- `android/` Android app (Kotlin, CameraX + MediaCodec)
- `windows/receiver/` Windows receiver core (C++17)
- `windows/obs-plugin/` OBS source plugin (C++17)
- `windows/gui/Acb.Gui/` WinUI 3 control panel
- `windows/virtualcam-bridge/` virtual camera bridge (planned/optional)
- `protocol/` signaling and media protocol docs
- `installer/` installer scripts
- `docs/` setup and troubleshooting (English + zh-CN)
- `scripts/` build/test/package scripts

### APIs
Receiver default address: `http://127.0.0.1:39393`

- v1 (compatibility):
  - `POST /api/v1/session/start`
  - `POST /api/v1/session/answer`
  - `POST /api/v1/session/stop`
  - `GET /api/v1/devices`
  - `GET /api/v1/stats/{sessionId}`
- v2 (current):
  - `POST /api/v2/session/start`
  - `POST /api/v2/session/stop`
  - `GET /api/v2/session/{sessionId}/stats`
  - `POST /api/v2/adb/setup`

More details: [`protocol/v2/README.md`](protocol/v2/README.md).

### OBS Plugin Compatibility
- `acb-obs-plugin` is built against **OBS SDK/libobs 32.0.4** by default.
- If your OBS runtime is newer or older (for example 32.1+), OBS may reject loading the plugin due to ABI mismatch (`compiled with newer libobs`).
- Recommendation: keep plugin build SDK and installed OBS on the same minor version.

### Build
```powershell
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```

Android build (from `android/`):
```powershell
gradle :app:assembleDebug
```

### Release Checklist
1. Build receiver and OBS plugin in `Release`.
2. Build Android APK and verify USB ADB + LAN streaming.
3. Verify OBS source attach/start/stop and aspect-ratio behavior.
4. Build/package installer from `installer/` scripts.
5. Run uninstall check (no orphan process/service).
6. For GitHub publishing, use automated workflow in [`docs/RELEASE.md`](docs/RELEASE.md).

### Docs
- Setup (EN): [`docs/SETUP.md`](docs/SETUP.md)
- Setup (zh-CN): [`docs/SETUP.zh-CN.md`](docs/SETUP.zh-CN.md)
- Troubleshooting (EN): [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)
- Troubleshooting (zh-CN): [`docs/TROUBLESHOOTING.zh-CN.md`](docs/TROUBLESHOOTING.zh-CN.md)
- Release automation: [`docs/RELEASE.md`](docs/RELEASE.md)

---

## 中文
面向 **Windows 主机 + Android 采集端** 的摄像头串流项目，支持 OBS 集成与 v2 H.264 主链路。

### 功能概览
- Android 端采集与推流（USB ADB / 局域网）
- Windows 接收端（`acb-receiver`），兼容 v1 并支持 v2 媒体链路
- OBS 插件（`acb-obs-plugin`），通过 receiver 接口工作
- 可选 WinUI GUI（`Acb.Gui`），用于会话管理和诊断

### 仓库结构
- `android/` Android 应用（Kotlin, CameraX + MediaCodec）
- `windows/receiver/` Windows 接收核心（C++17）
- `windows/obs-plugin/` OBS Source 插件（C++17）
- `windows/gui/Acb.Gui/` WinUI 3 控制面板
- `windows/virtualcam-bridge/` 虚拟摄像头桥接（规划/可选）
- `protocol/` 协议文档（信令与媒体）
- `installer/` 安装器脚本
- `docs/` 文档（英文 + 中文）
- `scripts/` 构建/测试/打包脚本

### 接口
Receiver 默认地址：`http://127.0.0.1:39393`

- v1（兼容）
  - `POST /api/v1/session/start`
  - `POST /api/v1/session/answer`
  - `POST /api/v1/session/stop`
  - `GET /api/v1/devices`
  - `GET /api/v1/stats/{sessionId}`
- v2（当前主链路）
  - `POST /api/v2/session/start`
  - `POST /api/v2/session/stop`
  - `GET /api/v2/session/{sessionId}/stats`
  - `POST /api/v2/adb/setup`

详细说明见：[`protocol/v2/README.md`](protocol/v2/README.md)。

### OBS 插件兼容性
- `acb-obs-plugin` 默认按 **OBS SDK/libobs 32.0.4** 编译。
- 如果本机 OBS 版本高于或低于该小版本（例如 32.1+），可能因 ABI 不匹配被拒绝加载（`compiled with newer libobs`）。
- 建议：插件编译所用 SDK 与已安装 OBS 保持同一小版本。

### 构建
```powershell
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```

Android 构建（在 `android/` 目录）：
```powershell
gradle :app:assembleDebug
```

### 发布检查清单
1. 以 `Release` 构建 receiver 与 OBS 插件。
2. 构建 Android APK，验证 USB ADB 与 LAN 推流。
3. 验证 OBS 插件 Attach / Start / Stop 与画面比例策略。
4. 使用 `installer/` 脚本生成安装包。
5. 验证卸载流程（无残留进程/服务）。
6. GitHub 发布使用自动化流程，见 [`docs/RELEASE.md`](docs/RELEASE.md)。

