# Windows GUI (WinUI 3)

项目路径：`windows/gui/Acb.Gui`

当前 GUI 是面向 Receiver 的本地控制台，已经覆盖 `usb-native`、`usb-aoa` 和本地 Receiver 托管流程。

## 功能

- Receiver 地址管理，默认 `http://127.0.0.1:39393`
- 语言切换：`Auto / 简体中文 / English`
- 连接模式：
  - `managed`
  - `attach`
- 传输模式：
  - `usb-adb`
  - `usb-native`
  - `usb-aoa`
  - `lan`
- 画质预设、适配模式、分辨率、FPS、码率、音频开关
- 本地 Receiver 自动拉起
- 退出时自动停止由 GUI 托管的 Receiver
- v2 会话操作：
  - `Setup ADB`
  - `Start v2 Session`
  - `Stop v2 Session`
  - `Fetch v2 Stats`
  - `Auto Refresh Stats (5s)`
- `USB Native Panel`
  - 设备列表
  - 当前状态
  - 当前链路
  - 手动握手
- `USB AOA Panel`
  - `AOA Connect`
  - `AOA Disconnect`
  - 状态自动轮询

## 当前行为

### `managed`

如果 Receiver 地址是本地地址，GUI 会在需要时自动启动 `acb-receiver.exe`。

### `attach`

只做 UI 附加，不会发起新的 `/api/v2/session/start`。

### `usb-adb`

`Setup ADB` 或 `Start v2 Session` 时会调用：

- `POST /api/v2/adb/setup`

### `usb-native`

不会做 ADB 配置，而是：

- 直接用 `transport=usb-native` 发起会话
- 定时刷新：
  - `GET /api/v2/usb-native/devices`
  - `GET /api/v2/usb-native/status`
  - `GET /api/v2/usb-native/link`
- 可手动发起：
  - `POST /api/v2/usb-native/handshake`

### `usb-aoa`

不会做 ADB 配置，而是：

- 先调用 `POST /api/v2/usb-aoa/connect`
- 然后用 `transport=usb-aoa` 发起会话
- 定时刷新 `GET /api/v2/usb-aoa/status`

如果设备被别的驱动占用，Receiver 还会尝试 ADB fallback 广播，让 Android 侧主动进入 AOA accessory 模式。

## 运行

从仓库根目录：

```powershell
pwsh .\scripts\run-gui.ps1
```

如果本地没有现成的 Release GUI，可先自动 `dotnet build -c Release` 再启动。

## 发布

从仓库根目录：

```powershell
pwsh .\scripts\publish-gui.ps1
```

发布目录：

```text
windows/gui/Acb.Gui/bin/Release/net10.0-windows10.0.19041.0/win-x64/publish
```

## 打包行为

GUI 发布流程会：

1. 构建 `acb-receiver`
2. 将 Receiver 嵌入 GUI 资源
3. 发布 self-contained WinUI 产物

嵌入式 Receiver 会在运行时提取到：

```text
%LOCALAPPDATA%\AcbGui\bundled\acb-receiver.exe
```

GUI 日志目录：

```text
%LOCALAPPDATA%\AcbGui\logs\
```

## 使用建议

- 本地开发优先 `usb-adb`
- 已经具备 USB 网络时再切 `usb-native`
- 已安装 WinUSB 驱动并完成证书准备后，再使用 `usb-aoa`
