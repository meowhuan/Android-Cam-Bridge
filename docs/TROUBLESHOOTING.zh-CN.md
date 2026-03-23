# 故障排查

## Receiver 无法监听 39393

- 检查是否有进程占用 `127.0.0.1:39393`
- 执行 `netstat -ano | findstr 39393`，结束冲突 PID

## `usb-adb` 无法连接

- 确认 `adb devices` 能看到设备
- 重新执行 `adb reverse tcp:39393 tcp:39393`
- 重新插拔数据线并允许 USB 调试弹窗

## `usb-native` 无法启动

- 查看 `GET /api/v2/usb-native/devices`
- 确认手机确实能通过 USB 网络访问 Receiver
- 如果 Android 端接收地址留空，注意自动发现只会扫描常见 `192.168.x.x` 网段

## `usb-aoa` 报未签名驱动或证书错误

- 先执行 `pwsh .\scripts\sign-aoa-driver.ps1`
- 将 `acb-aoa.cer` 导入 `Root` 和 `TrustedPublisher`
- 重新执行 `pnputil /add-driver ... /install`
- 只有在目标机仍拒绝安装时，再把 `TESTSIGNING` 作为兜底

## `failed to open AOA device after 8 attempts`

- 通常表示设备已经重枚举，但还没成功绑定到 WinUSB
- 重新安装 AOA 驱动并重新插拔设备
- 检查 `VID_18D1&PID_2D00` 或 `VID_18D1&PID_2D01` 是否确实绑定到 WinUSB
- 本地开发可使用 Zadig 手动绑定 WinUSB

## OBS 看不到源

- 确认插件文件已复制到 OBS 插件目录
- 确认插件构建工具链与 OBS 版本兼容
- 如果构建输出出现 `OBS SDK not configured` 或 `stub-no-obs-sdk`，说明当前 DLL 只是占位文件，不能被真实 OBS 加载
- 在 OBS 日志（`Help -> Log Files -> View Current Log`）中检查 `MODULE_MISSING_EXPORTS` 或 `Failed to load module`

## 虚拟摄像头没有出现

- 确认已用 `regsvr32` 注册 `acb-virtualcam.dll`
- 确认 `acb-virtualcam-bridge` 正在运行
- 检查 `GET /api/v2/frame.bgra` 返回的是否是实际帧，而不是 `{"error":"no_frame"}` 或 `{"error":"stale_frame"}`

## 延迟高或掉帧

- 降低码率和 FPS，例如 `720p30`、`3-4 Mbps`
- Wi-Fi 不稳定时优先 `usb-adb`
- `usb-aoa` 适合驱动链路已经稳定后的场景
- 检查主机 CPU 占用是否超标
