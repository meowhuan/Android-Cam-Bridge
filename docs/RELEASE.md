# Release Automation

仓库当前包含两个 GitHub Actions 工作流：

- `ci.yml`
- `release.yml`

## `ci.yml`

触发条件：

- Push 到 `main`
- Push 到 `master`
- Push 到 `develop`
- Push 到 `dev`
- Push 到 `dev/**`
- Push 到 `feature/**`
- Pull Request
- `workflow_dispatch`

主要步骤：

1. 检出仓库
2. 检出并缓存 OBS Studio 32.0.4 SDK 源码
3. 安装 Java 17、.NET 10、Gradle
4. 构建 Windows 原生目标
5. 构建 Android Debug APK
6. 生成 `dist/acb-win-x64`
7. 校验关键产物
8. 上传 CI artifacts

当前校验的新增产物包括：

- `dist/acb-win-x64/virtualcam-bridge/acb-virtualcam-bridge.exe`
- `dist/acb-win-x64/virtualcam-driver/acb-virtualcam.dll`
- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.inf`
- `dist/acb-win-x64/drivers/aoa-winusb/install-driver.ps1`

如果配置了 AOA 测试证书 secrets，CI 还会生成并校验：

- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.cat`
- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.cer`

CI 上传物：

- `acb-win-x64-ci`
- `acb-installer-ci`
- `acb-android-debug-apk`

## `release.yml`

触发条件：

- Push tag `v*`
- `workflow_dispatch` with `version`

主要步骤：

1. 解析版本号
2. 构建 OBS SDK
3. 构建 Windows 原生目标
4. 构建 Android Release APK
5. 生成 `dist/acb-win-x64`
6. 生成 `ACB-Setup-<version>.exe`
7. 校验 release payload
8. 打包 zip、生成校验和、可选 GPG 签名
9. 发布 GitHub Release

发布资产：

- `ACB-Setup-<version>.exe`
- `ACB-win-x64-<version>.zip`
- `ACB-Android-<version>.apk`
- `SHA256SUMS.txt`
- `*.asc`（启用 GPG 时）

其中新增的 AOA/VirtualCam 文件会进入 `ACB-win-x64-<version>.zip`。

### Optional AOA test driver signing secrets

To let CI/Release bundle the AOA test certificate and signed catalog, configure:

- `AOA_TEST_CERT_PFX_BASE64`
- `AOA_TEST_CERT_PASSWORD`
- `AOA_TEST_CERT_CER_BASE64` (optional but recommended)

With these secrets present, workflow will run `scripts/sign-aoa-driver.ps1` before packaging.

## 发布步骤

```powershell
git tag v0.2.4
git push origin v0.2.4
```

然后等待 `release.yml` 完成并检查 GitHub Release 页面。
