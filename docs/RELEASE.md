# Release Automation

仓库当前维护两条 GitHub Actions 工作流：

- `ci.yml`
- `release.yml`

当前预发行版基线：`v1.2.0-beta.1`

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
2. 检出并缓存 OBS Studio `32.0.4`
3. 安装 Java 17、.NET 10、Gradle
4. 构建 Windows 原生目标
5. 可选准备 AOA 测试签名文件
6. 构建 Android Debug APK
7. 生成 `dist/acb-win-x64`
8. 构建 `dist/installer/ACB-Setup-0.0.0-ci.exe`
9. 校验关键产物
10. 上传 artifacts

当前校验的关键产物包括：

- `dist/acb-win-x64/package.json`
- `dist/acb-win-x64/receiver/acb-receiver.exe`
- `dist/acb-win-x64/virtualcam-bridge/acb-virtualcam-bridge.exe`
- `dist/acb-win-x64/virtualcam-driver/acb-virtualcam.dll`
- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.inf`
- `dist/acb-win-x64/drivers/aoa-winusb/install-driver.ps1`
- `dist/acb-win-x64/prereqs/vc_redist.x64.exe`
- `dist/installer/ACB-Setup-0.0.0-ci.exe`

如果 AOA 测试签名成功，还会额外校验：

- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.cat`
- `dist/acb-win-x64/drivers/aoa-winusb/acb-aoa.cer`

CI 上传物：

- `acb-win-x64-ci`
- `acb-installer-ci`
- `acb-android-debug-apk`

说明：CI 中 AOA 签名失败当前是非阻塞的，workflow 会继续打出“不含签名产物”的 payload/installer。

## `release.yml`

触发条件：

- Push tag `v*`
- `workflow_dispatch` with `version`

主要步骤：

1. 解析版本号
2. 检出并缓存 OBS SDK
3. 构建 Windows 原生目标
4. 可选准备 AOA 测试签名文件
5. 可选准备 Android release keystore
6. 构建 Android Release APK
7. 生成 payload 和 Inno 安装器
8. 校验 release 产物
9. 打包 zip、生成 SHA256、可选 GPG 签名
10. 发布 GitHub Release

发布资产：

- `ACB-Setup-<version>.exe`
- `ACB-win-x64-<version>.zip`
- `ACB-Android-<version>.apk`
- `SHA256SUMS.txt`
- `*.asc`，启用 GPG 时

## Optional AOA test driver signing secrets

要让 CI/Release 自动生成并打包 AOA 测试证书相关文件，请配置：

- `AOA_TEST_CERT_PFX_BASE64`
- `AOA_TEST_CERT_PASSWORD`
- `AOA_TEST_CERT_CER_BASE64`

工作流会在构建前调用：

```powershell
pwsh .\scripts\sign-aoa-driver.ps1 `
  -ExistingPfxPath <runner-temp-pfx> `
  -PfxPassword <secret> `
  -InstallCertificate:$false `
  -UseMakeCatFallback
```

补充说明：

- `AOA_TEST_CERT_CER_BASE64` 虽然是可选，但建议配置，这样安装器可直接导入固定 `.cer`
- 如果 PFX 是用当前脚本默认生成的，默认密码是 `acb-test-driver`，除非你导出时显式改过
- CI 里签名失败会降级继续
- Release 里如果已经启用了这些 secrets，签名失败会让发布失败

## Optional Android signing secrets

如果要在 `release.yml` 中生成正式签名 APK，请配置：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

未配置时，workflow 仍会构建 release APK，但不会启用自定义 keystore。

## Optional GPG secrets

如果要给发布资产附加 `.asc` 签名，请配置：

- `GPG_PRIVATE_KEY` 或 `GPG_PRIVATE_KEY_B64`
- `GPG_PASSPHRASE`

## Recommended tag flow

当前预发行版可直接使用：

```powershell
git tag v1.2.0-beta.1
git push origin v1.2.0-beta.1
```

后续正式版或新的 beta/rc，请按同样规则推送 `v*` tag。
