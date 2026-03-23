# Windows GUI (WinUI 3)

项目路径：`windows/gui/Acb.Gui`

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
- 画质、分辨率、FPS、码率、音频开关
- 本地 Receiver 自动拉起与退出自动停止
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
  - 实时状态查看

## 传输模式说明

### `usb-adb`

GUI 会先调用 `/api/v2/adb/setup`，再发起会话。

### `usb-native`

GUI 不再做 ADB 映射，直接按 `transport=usb-native` 发起会话，并定时刷新：

- `/api/v2/usb-native/devices`
- `/api/v2/usb-native/status`
- `/api/v2/usb-native/link`

### `usb-aoa`

GUI 会先提示使用 AOA 链路，再调用：

- `POST /api/v2/usb-aoa/connect`
- `GET /api/v2/usb-aoa/status`

随后按 `transport=usb-aoa` 发起 v2 会话。

## 运行

从仓库根目录：

```powershell
pwsh .\scripts\run-gui.ps1
```

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
2. 把 Receiver 嵌入 GUI 资源
3. 发布 self-contained WinUI 产物

嵌入式 Receiver 会在运行时被提取到：

```text
%LOCALAPPDATA%\AcbGui\bundled\acb-receiver.exe
```

GUI 日志目录：

```text
%LOCALAPPDATA%\AcbGui\logs\
```
