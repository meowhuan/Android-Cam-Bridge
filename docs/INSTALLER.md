# ACB Packaging And Installer

## Build payload

从仓库根目录：

```powershell
pwsh .\scripts\package.ps1 -Version 0.2.4
```

输出目录：

```text
dist/acb-win-x64
```

当前 payload 包含：

- `receiver/acb-receiver.exe`
- `gui/`
- `obs-plugin/`（真实 OBS SDK 构建时）
- `virtualcam-bridge/acb-virtualcam-bridge.exe`
- `virtualcam-driver/acb-virtualcam.dll`
- `drivers/aoa-winusb/acb-aoa.inf`
- `drivers/aoa-winusb/install-driver.ps1`
- `prereqs/vc_redist.x64.exe`

## Build EXE installer

依赖：Inno Setup 6

```powershell
pwsh .\scripts\build-installer.ps1 -Version 0.2.4
```

输出：

```text
dist/installer/ACB-Setup-0.2.4.exe
```

## Installer coverage

当前 Inno Setup 安装器覆盖：

- Receiver
- GUI
- OBS Plugin
- DirectShow Virtual Camera
- AOA WinUSB Driver Files
- VC++ Runtime prerequisite

安装行为：

- 选择 `DirectShow Virtual Camera` 组件时，安装器会自动注册 `acb-virtualcam.dll`
- 卸载时会自动调用对应的反注册
- 选择 `AOA WinUSB Driver Files` 组件时，会把 INF 和安装脚本复制到 `{app}\drivers\aoa-winusb`
- 如果同时勾选 `Install AOA WinUSB driver now` 任务，安装器会调用 `pnputil` 立即安装驱动

也就是说，完整能力建议优先使用 release 里的 `ACB-win-x64-<version>.zip`，其中会包含所有新增文件。

## Local install script

```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1
```

该脚本当前主要负责：

- Receiver
- GUI
- OBS Plugin
- VirtualCam Bridge
- DirectShow Virtual Camera
- AOA driver files

默认还会：

- 自动注册 `virtualcam-driver\acb-virtualcam.dll`
- 自动执行 `drivers\aoa-winusb\install-driver.ps1`

如果只想复制文件、不做系统注册，可显式关闭：

```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1 `
  -RegisterVirtualCam:$false `
  -InstallAoaDriver:$false
```

## Manual steps after install

### Install AOA WinUSB driver

```powershell
pwsh .\dist\acb-win-x64\drivers\aoa-winusb\install-driver.ps1
```

### Register DirectShow virtual camera

```powershell
regsvr32 /s .\dist\acb-win-x64\virtualcam-driver\acb-virtualcam.dll
```

### Unregister DirectShow virtual camera

```powershell
regsvr32 /u /s .\dist\acb-win-x64\virtualcam-driver\acb-virtualcam.dll
```
