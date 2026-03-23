# USB Transport Guide

本文档覆盖仓库当前三条 USB 相关链路：

- `usb-adb`
- `usb-native`
- `usb-aoa`

其中真正“不依赖 ADB 且不走 USB 网络”的是 `usb-aoa`。

## 1. `usb-adb`

### 定位

最稳的开发链路。Receiver 会负责：

- `adb reverse tcp:39393 tcp:39393`
- `adb forward tcp:39394 tcp:39394`

Android 随后按普通 v2 会话走 `WS /ws/v2/media`。

### 典型用法

```powershell
adb devices
adb reverse tcp:39393 tcp:39393
```

## 2. `usb-native`

### 定位

`usb-native` 适用于手机已经能通过 USB 网络访问 Windows Receiver 的场景。当前实现会：

- 用 `transport=usb-native` 发起 `POST /api/v2/session/start`
- 通过 `/api/v2/usb-native/handshake` 建立逻辑链路
- 通过 `/api/v2/usb-native/packet` 发送 Base64 封装的 v2 packet
- 通过 `/api/v2/usb-native/status`、`/api/v2/usb-native/link` 查看状态

它不依赖 ADB，但也不是 raw USB bulk。

### 相关接口

- `GET /api/v2/usb-native/devices`
- `GET /api/v2/usb-native/status`
- `GET /api/v2/usb-native/link`
- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`

### 当前实现细节

- GUI 会定时轮询 `devices/status/link`
- GUI 手动握手默认会传 `sessionId`、`devicePath` 和 `mtu`
- Android 端在接收地址为空时，会尝试扫描常见 `192.168.x.x` USB 网络段做自动发现

### 测试步骤

1. 确认手机通过 USB 网络能访问 PC 上的 Receiver。
2. 启动 Receiver：

```powershell
.\build\windows\receiver\Release\acb-receiver.exe
```

3. 打开 GUI，选择 `USB (Native)`。
4. 启动会话后查看：
   - `USB Native Panel`
   - `GET /api/v2/usb-native/status`
   - `GET /api/v2/usb-native/link`
5. 如有需要，再手动执行 `Handshake`。

### 典型场景

- USB 共享网络 / RNDIS 已经可用
- 需要避开 `adb reverse`
- 还不想切到 AOA 驱动链路

## 3. `usb-aoa`

### 定位

`usb-aoa` 是当前真正的 USB accessory/bulk 链路。Windows 端会：

- 枚举 USB 设备
- 尝试用 WinUSB 发起 AOA 握手
- 发送 accessory strings 并等待设备重枚举
- 打开 bulk IN/OUT 端点
- 按 ACB 自定义封包格式接收完整 v2 packet

Android 端则通过 `UsbManager.openAccessory(...)` 打开 accessory，并使用 `UsbAccessoryTransport` 发送封包。

### 相关接口

- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`
- `POST /api/v2/session/start` with `transport=usb-aoa`

`POST /api/v2/usb-aoa/connect` 支持可选 `devicePath`，不传时会自动选择候选 Android 设备。

### Windows 端准备

#### 安装 WinUSB 驱动

```powershell
pwsh .\drivers\aoa-winusb\install-driver.ps1
```

如果设备已连接，安装后通常需要重新插拔一次。

#### 生成测试签名

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
```

没有 WDK 时：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser -UseMakeCatFallback
```

脚本会：

- 基于 `acb-aoa.inf` 生成 `acb-aoa.cat`
- 复用或新建测试代码签名证书
- 导出 `acb-aoa.cer`
- 导出 `acb-aoa-test.pfx`
- 对 `acb-aoa.cat` 进行签名

#### 推荐的小范围测试机流程

优先导入测试证书，再安装驱动：

```powershell
certutil -addstore -f Root .\drivers\aoa-winusb\acb-aoa.cer
certutil -addstore -f TrustedPublisher .\drivers\aoa-winusb\acb-aoa.cer
pnputil /add-driver .\drivers\aoa-winusb\acb-aoa.inf /install
```

如果仍被系统策略拒绝，再把 `TESTSIGNING` 作为兜底：

```powershell
bcdedit /set testsigning on
```

### 安装器支持

如果使用 ACB Inno 安装器，并且 payload 已包含 `acb-aoa.cer`，可以在安装向导中勾选：

- `Install AOA test certificate now`
- `Install AOA WinUSB driver now`

安装器会先导入测试证书，再执行 `pnputil`。

### ADB fallback

如果 Windows 无法直接占用当前设备，Receiver 会尝试：

```text
adb shell am broadcast -a com.acb.androidcam.ACTION_ENTER_AOA
```

让 Android 侧主动进入 accessory 模式，再等待设备重枚举。

### 测试步骤

1. 启动 Receiver。
2. 确保 AOA WinUSB 驱动已安装。
3. 打开 GUI，选择 `USB (AOA Direct)`。
4. 点击 `AOA Connect`。
5. 等手机进入 accessory 模式并拉起 ACB。
6. 点击 `Start v2 Session`。
7. 观察 `USB AOA Panel` 或：

```text
GET /api/v2/usb-aoa/status
```

### 代码位置

- Android accessory 发送：`android/app/src/main/java/com/acb/androidcam/UsbAccessoryTransport.kt`
- Receiver WinUSB/AOA 实现：`windows/receiver/src/usb_aoa_transport.cpp`
- GUI 面板与按钮：`windows/gui/Acb.Gui/MainWindow.xaml` / `MainWindow.xaml.cs`
- WinUSB 驱动文件：`drivers/aoa-winusb/`

## 4. 如何选择

- 优先求稳：`usb-adb`
- 已有 USB 网络：`usb-native`
- 追求真正无 ADB 的直连链路：`usb-aoa`

## 5. 常见问题

### `usb_native_device_not_found`

说明 Receiver 没找到合适的 USB 候选设备。先看：

- `GET /api/v2/usb-native/devices`
- 线缆是否只供电
- 手机当前 USB 模式

### `usb_aoa_not_connected`

说明还没先执行 `POST /api/v2/usb-aoa/connect`，或 accessory 模式未成功建立。

### `WinUsb_Initialize` 失败

优先检查：

- 是否已安装 `drivers/aoa-winusb/acb-aoa.inf`
- 是否已生成并复制 `acb-aoa.cat`
- 是否已导入 `acb-aoa.cer`
- 设备是否重新插拔
- 设备当前是否已经切到 accessory 模式
- 必要时再检查是否需要 `TESTSIGNING`

### `failed to open AOA device after 8 attempts`

通常说明设备已重枚举，但 Windows 还没有把它绑定到 WinUSB。优先检查：

- 驱动是否真的安装到 `VID_18D1&PID_2D00` / `2D01`
- 证书链是否受信任
- 是否需要先卸载旧绑定后重新插拔
- 本地开发时可用 Zadig 手动绑 `WinUSB`
