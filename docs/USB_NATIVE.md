# USB Transport Guide

本文档覆盖仓库当前的两条无 ADB 链路：

- `usb-native`：USB 网络模式，不依赖 `adb reverse`
- `usb-aoa`：Android Open Accessory + WinUSB 原生 bulk 传输

## 1. `usb-native`

### 定位

`usb-native` 适用于手机已经能通过 USB 网络访问 Windows Receiver 的场景。它会：

- 用 `transport=usb-native` 发起 `POST /api/v2/session/start`
- 使用 Receiver 的 `/api/v2/usb-native/*` 接口做设备发现、握手与链路状态查看
- 继续通过 HTTP 请求发送媒体 packet

它不再依赖 ADB，但目前仍不是 raw USB bulk 传输。

### 相关接口

- `GET /api/v2/usb-native/devices`
- `GET /api/v2/usb-native/status`
- `GET /api/v2/usb-native/link`
- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`

### 测试步骤

1. 手机通过 USB 网络能访问 PC 上的 Receiver 地址。
2. 启动 Receiver：

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

3. 打开 GUI，选择 `USB (Native)`。
4. 启动会话后查看：
   - `USB Native Panel`
   - `GET /api/v2/usb-native/status`
   - `GET /api/v2/usb-native/link`

### 典型场景

- USB 共享网络 / RNDIS 已经可用
- 需要避开 `adb reverse`
- 还不想切到 AOA 驱动链路

## 2. `usb-aoa`

### 定位

`usb-aoa` 是本次 PR 引入的真正 USB accessory/bulk 链路。Windows 端由 Receiver 负责：

- 枚举 Android 设备
- 通过 WinUSB 发送 AOA 握手与 accessory strings
- 等待手机重枚举为 accessory
- 直接接收 Android App 通过 `UsbAccessoryTransport` 发送的 v2 packet

Android 端则通过 `UsbManager.openAccessory(...)` 打开 accessory 并直接写入媒体帧。

### 相关接口

- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`
- `POST /api/v2/session/start` with `transport=usb-aoa`

### Windows 端准备

以管理员身份安装仓库附带的 WinUSB 驱动：

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

如果设备已连接，安装后通常需要重新插拔一次。

### 测试步骤

1. 启动 Receiver。
2. 打开 GUI，选择 `USB (AOA Direct)`。
3. 点击 `AOA Connect`。
4. 等手机进入 accessory 模式并弹起 ACB。
5. 点击 `Start v2 Session`。
6. 观察 `USB AOA Panel` 或：

```text
GET /api/v2/usb-aoa/status
```

### 代码位置

- Android accessory 发送：`android/app/src/main/java/com/acb/androidcam/UsbAccessoryTransport.kt`
- Receiver WinUSB/AOA 实现：`windows/receiver/src/usb_aoa_transport.cpp`
- GUI 面板与按钮：`windows/gui/Acb.Gui/MainWindow.xaml` / `MainWindow.xaml.cs`
- WinUSB 驱动文件：`drivers/aoa-winusb/`

## 3. 如何选择

- 优先求稳：`usb-adb`
- 已有 USB 网络：`usb-native`
- 追求真正无 ADB 的直连链路：`usb-aoa`

## 4. 常见问题

### `usb-native_device_not_found`

说明 Receiver 没找到合适的 USB 候选设备。先看：

- `GET /api/v2/usb-native/devices`
- 线缆是否只供电
- 手机当前 USB 模式

### `usb_aoa_not_connected`

说明还没先执行 `POST /api/v2/usb-aoa/connect`，或者设备重枚举后没有成功建立 accessory 通道。

### `WinUsb_Initialize` 失败

优先检查：

- 是否已安装 `drivers/aoa-winusb/acb-aoa.inf`
- 是否重新插拔设备
- 设备当前是否已经切换到 accessory 模式
