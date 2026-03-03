# 安装说明

## 组件
- Android 端应用（`android/`）
- Windows Receiver（`windows/receiver`）
- OBS 插件（`windows/obs-plugin`）

## Windows 依赖
- Visual Studio 2022（Desktop C++）
- CMake 3.22+
- OBS Studio（用于插件测试）
- Android Platform Tools（ADB）

## 快速开始
1. 构建 Windows 组件：
   ```powershell
   cmake -S . -B build -G "Visual Studio 17 2022" -A x64
   cmake --build build --config Release
   ```
2. 启动 Receiver：
   ```powershell
   .\build\windows\receiver\Release\acb-receiver.exe
   ```
3. 校验 API：
   ```powershell
   curl http://127.0.0.1:39393/api/v1/devices
   ```
4. 用 Android Studio 构建并安装 Android App。

## v0.1 端到端（当前实现）
1. 启动 Receiver：
   ```powershell
   .\build\windows\receiver\Release\acb-receiver.exe
   ```
2. OBS 中添加 `android_cam_source`：
   - USB：`Transport=USB`，`Receiver IP:Port=127.0.0.1:39393`
   - Wi-Fi：`Transport=Wi-Fi (IP)`，填运行 Receiver 的电脑 `IP:39393`
   - 连接模式：`托管直连`（默认）或 `附加到 Receiver`
   - 适配模式：`黑边保比例`（默认）/`裁切填满`/`强制拉伸`
   - 画质预设：`平衡`/`高质量`/`超高质量`
3. Android App：
   - USB 模式填 `127.0.0.1:39393`
   - Wi-Fi 模式填电脑局域网 `IP:39393`
   - 可在 App 内直接预览画面，支持横竖屏。
   - 可选择分辨率（640x480 / 1280x720 / 1920x1080）。
   - 可勾选麦克风推流（推送到 Receiver 的 `/api/v1/audio`）。
4. 如 USB 首次不通，手动执行：
   ```powershell
   adb reverse tcp:39393 tcp:39393
   ```

## v2 控制面接口（新增）
- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`
- `POST /api/v2/adb/setup`

## USB ADB 模式（v0.1）
```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

## OBS 接入
- 使用 OBS SDK 路径构建，确保模块导出完整：
  ```powershell
  cmake -S . -B build -G "Visual Studio 17 2022" -A x64 `
    -DOBS_INCLUDE_DIR="C:/path/to/obs-studio/libobs" `
    -DOBS_GENERATED_INCLUDE_DIR="C:/path/to/obs-studio/build_x64/config" `
    -DOBS_LIB_DIR="C:/path/to/obs-studio/build/libobs/Release"
  cmake --build build --config Release --target acb-obs-plugin
  ```
- 安装插件文件（DLL + locale）：
  ```powershell
  .\scripts\install-obs-plugin.ps1
  ```
- 目标目录结构：
  - `C:\ProgramData\obs-studio\plugins\acb-obs-plugin\bin\64bit\acb-obs-plugin.dll`
  - `C:\ProgramData\obs-studio\plugins\acb-obs-plugin\data\locale\en-US.ini`
- 在 OBS 添加 `android_cam_source`。
- 对外输出时可直接开启 OBS 虚拟摄像头。
