# 故障排查

## Receiver 无法监听 39393
- 检查是否有进程占用 `127.0.0.1:39393`。
- 执行 `netstat -ano | findstr 39393`，结束冲突 PID。

## USB 无法连接
- 确认 `adb devices` 能看到设备。
- 重新执行 `adb reverse tcp:39393 tcp:39393`。
- 重新插拔数据线并允许 USB 调试弹窗。

## OBS 没有看到源
- 确认插件二进制在 OBS 插件目录。
- 确认插件编译工具链与 OBS 版本兼容。
- 若构建日志出现 `OBS SDK not configured`，说明生成的是占位 stub，无法被 OBS 加载。
- 在 OBS 日志（`Help -> Log Files -> View Current Log`）中检查 `MODULE_MISSING_EXPORTS` 或 `Failed to load module`。

## 延迟高或掉帧
- 降低码率/分辨率（例如 720p30，3 Mbps）。
- Wi-Fi 不稳定时优先 USB ADB。
- 检查主机 CPU 占用是否超标。
