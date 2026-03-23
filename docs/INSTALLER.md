# ACB Packaging And Installer

当前 `dev` 分支推荐把 Windows 交付物分成两层：

- `dist/acb-win-x64/`：可直接解压使用的 payload
- `dist/installer/ACB-Setup-<version>.exe`：Inno Setup 安装器

当前预发行版基线：`v1.2.0-beta.1`

## Build payload

从仓库根目录：

```powershell
pwsh .\scripts\package.ps1 -Version "1.2.0-beta.1"
```

输出目录：

```text
dist/acb-win-x64
```

当前 payload 包含：

- `receiver/acb-receiver.exe`
- `gui/`
- `virtualcam-bridge/acb-virtualcam-bridge.exe`
- `virtualcam-driver/acb-virtualcam.dll`
- `drivers/aoa-winusb/acb-aoa.inf`
- `drivers/aoa-winusb/install-driver.ps1`
- `prereqs/vc_redist.x64.exe`
- `obs-plugin/`，仅在真实 OBS SDK 构建时生成

如果你已经执行过：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 -CertStoreScope CurrentUser
```

payload 还会附带：

- `drivers/aoa-winusb/acb-aoa.cat`
- `drivers/aoa-winusb/acb-aoa.cer`

## Build EXE installer

依赖：Inno Setup 6

```powershell
pwsh .\scripts\build-installer.ps1 -Version "1.2.0-beta.1"
```

输出：

```text
dist/installer/ACB-Setup-1.2.0-beta.1.exe
```

如果 payload 已经准备好，也可以跳过重新打包：

```powershell
pwsh .\scripts\build-installer.ps1 -Version "1.2.0-beta.1" -SkipPackage
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

- 选择 `DirectShow Virtual Camera` 时自动注册 `acb-virtualcam.dll`
- 卸载时自动反注册虚拟摄像头
- 选择 `AOA WinUSB Driver Files` 时复制 `INF/install-driver.ps1`
- 如果 payload 内存在 `.cat/.cer`，安装器也会一并复制
- 如果勾选 `Install AOA test certificate now`，会把 `acb-aoa.cer` 导入 `LocalMachine\Root` 和 `LocalMachine\TrustedPublisher`
- 如果勾选 `Install AOA WinUSB driver now`，会调用 `pnputil /add-driver ... /install`

说明：安装器本身已配置 `PrivilegesRequired=admin`，因此这些动作都在提权后执行。

## Local install script

payload 根目录自带：

```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1
```

它负责：

- 复制 Receiver、GUI、VirtualCam、AOA 驱动文件
- 自动注册 `virtualcam-driver\acb-virtualcam.dll`
- 自动调用 `drivers\aoa-winusb\install-driver.ps1`

注意：本地安装脚本不会自动导入 `acb-aoa.cer`。如果目标机需要测试证书，请手动导入，或优先使用 Inno 安装器中的证书安装任务。

如果只想复制文件、不做系统注册：

```powershell
pwsh .\dist\acb-win-x64\install-acb.ps1 `
  -RegisterVirtualCam:$false `
  -InstallAoaDriver:$false
```

## Manual steps after install

### Import AOA test certificate

如果 payload 中存在 `acb-aoa.cer`，推荐先导入：

```powershell
certutil -addstore -f Root .\dist\acb-win-x64\drivers\aoa-winusb\acb-aoa.cer
certutil -addstore -f TrustedPublisher .\dist\acb-win-x64\drivers\aoa-winusb\acb-aoa.cer
```

### Install AOA WinUSB driver

```powershell
pwsh .\dist\acb-win-x64\drivers\aoa-winusb\install-driver.ps1
```

如果设备仍因测试签名策略被拒绝，再把 `TESTSIGNING` 作为兜底：

```powershell
bcdedit /set testsigning on
```

### Register DirectShow virtual camera

```powershell
regsvr32 /s .\dist\acb-win-x64\virtualcam-driver\acb-virtualcam.dll
```

### Unregister DirectShow virtual camera

```powershell
regsvr32 /u /s .\dist\acb-win-x64\virtualcam-driver\acb-virtualcam.dll
```

## CI And Release

### CI

`ci.yml` 会：

- 构建真实 OBS 插件
- 生成 `dist/acb-win-x64`
- 生成 `dist/installer/ACB-Setup-0.0.0-ci.exe`
- 上传 `acb-win-x64-ci`、`acb-installer-ci`、`acb-android-debug-apk`

如果 AOA 测试证书 secrets 配置正确，CI 还会把 `.cat/.cer` 带进 payload；若签名失败，CI 当前会降级继续。

### Release

`release.yml` 会生成：

- `ACB-Setup-<version>.exe`
- `ACB-win-x64-<version>.zip`
- `ACB-Android-<version>.apk`

如果显式启用了 AOA 签名 secrets，则 release 期望签名成功并打包 `.cat/.cer`。
