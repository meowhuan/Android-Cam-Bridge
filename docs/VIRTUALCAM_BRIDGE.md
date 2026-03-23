# Virtual Camera Guide

当前仓库里的虚拟摄像头由两部分组成：

- `acb-virtualcam-bridge.exe`
  - 从 Receiver 拉取 `GET /api/v2/frame.bgra`
  - 将 BGRA 帧写入共享内存 `Local\acb_virtualcam_frame`
- `acb-virtualcam.dll`
  - DirectShow 虚拟摄像头驱动
  - 从共享内存读取最新帧并向系统暴露 `Android Cam Bridge`

## 共享内存契约

- 映射名：`Local\acb_virtualcam_frame`
- Header：`SharedFrameHeader`
- slot 数：`3`
- 每 slot 最大容量：`3840 * 2160 * 4`
- 像素格式：`BGRA`

实现位置：

- Bridge：`windows/virtualcam-bridge/src/main.cpp`
- Driver：`windows/virtualcam-driver/src/`
- 共享头：`windows/virtualcam-driver/src/shared_frame.h`

## 构建

```powershell
pwsh .\scripts\build.ps1 -Config Release
```

输出文件：

- `build/windows/virtualcam-bridge/Release/acb-virtualcam-bridge.exe`
- `build/windows/virtualcam-driver/Release/acb-virtualcam.dll`

## 注册 DirectShow 虚拟摄像头

驱动 DLL 导出了 `DllRegisterServer` / `DllUnregisterServer`，可以直接用 `regsvr32`：

```powershell
regsvr32 /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

卸载：

```powershell
regsvr32 /u /s .\build\windows\virtualcam-driver\Release\acb-virtualcam.dll
```

注册后，支持 DirectShow 摄像头枚举的应用会看到 `Android Cam Bridge`。

## 启动桥接进程

```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

该脚本会：

1. 检查并构建 `acb-virtualcam-bridge`
2. 启动桥接进程
3. 启动 Python consumer（保留兼容路径）

停止桥接：

```powershell
pwsh .\scripts\stop-virtualcam.ps1
```

手动发送控制命令：

```powershell
pwsh .\scripts\send-vcam-command.ps1 -Command "STATUS"
pwsh .\scripts\send-vcam-command.ps1 -Command "SET_RECEIVER 127.0.0.1:39393"
pwsh .\scripts\send-vcam-command.ps1 -Command "START"
```

## 命名管道命令

桥接进程监听：

- `START`
- `STOP`
- `SET_RECEIVER <host:port>`
- `SET_INTERVAL <ms>`
- `STATUS`
- `EXIT`

命名管道：

```text
\\.\pipe\acb-virtualcam-control
```

## 当前推荐用法

推荐把它理解为“两段式”方案：

1. Receiver 负责采集/解码
2. Bridge 负责本地共享内存发布
3. DirectShow 虚拟摄像头负责系统摄像头暴露

仓库里保留的 `scripts/virtualcam_consumer.py` 仍然可用于 `pyvirtualcam` / UnityCapture 兼容测试，但不再是唯一方案。
