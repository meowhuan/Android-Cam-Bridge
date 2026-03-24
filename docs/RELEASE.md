# Release Automation

仓库当前维护两条 GitHub Actions 工作流：

- `ci.yml`
- `release.yml`

当前预发行版基线：`v1.2.0-beta.1`
当前候选预发行版：`v1.2.0-beta.4`

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
git tag v1.2.0-beta.4
git push origin v1.2.0-beta.4
```

后续正式版或新的 beta/rc，请按同样规则推送 `v*` tag。

## v1.2.0-beta.4 regression checklist

Android manual regression:

- [ ] 横屏下空闲预览与推流预览方向、裁切和视野保持一致。
- [ ] 竖屏下预览区不会被控制区完全挤占，至少保留可用取景区域。
- [ ] 前台推流时切换手电筒能够稳定生效，且不会导致推流中断。
- [ ] 后台持续采集推流时切换手电筒后，服务能按新参数重新进入稳定推流状态。
- [ ] USB AOA 模式下，从未连接、已连接、断开重连三个状态切换后仍可正常启动推流。
- [ ] 低光 / 60 FPS / 标准 30 FPS 三类 profile 的 `actual profile` 显示与实际行为一致。
- [ ] 推流过程中修改分辨率 / 模式时，界面状态与真实流参数不会出现误导性不一致。

Windows GUI manual regression:

- [ ] GUI 退出时不会卡死，托管的 receiver / virtualcam bridge 能被正确回收。
- [ ] 顶部标题栏文本、版本 chip、渠道 chip 与系统窗口按钮保持稳定对齐。
- [ ] 虚拟摄像头控制面板可以完成启动、停止、状态刷新与 receiver override。
- [ ] 结构化日志视图可以正常追加、筛选、清空且不会阻塞 UI。
- [ ] 版本号与渠道显示能正确区分 `-beta/-preview/-rc` 和正式版。

Packaging / release regression:

- [ ] `scripts/package.ps1` 在当前机器上能复用已有 CMake 生成器并成功完成 payload 打包。
- [ ] `scripts/build-local.ps1 -Mode installer` 能完成本地安装器构建。
- [ ] `release.yml` 构建出的 GUI 版本号与 tag 一致。
- [ ] 安装器内包含 receiver、virtualcam bridge、virtual camera driver、AOA driver 以及 GUI payload。

Current known regression items before finalizing beta.4:

- [ ] `scripts/build-local.ps1 -AllowStubPlugin` 目前仍会在 OBS SDK 路径检查阶段提前失败，stub fallback 没有真正生效。
- [ ] WinUI 自定义标题栏的左右 inset 目前只在初次激活时计算一次，窗口度量变化后可能继续出现对齐漂移。
- [ ] Android 在“正在推流”时修改分辨率 / 模式，界面文案会先变化，但真实流参数不会同步重配。
- [ ] Android 竖屏布局仍使用 `previewStage minHeight=220dp` 和 `controlsPanel height=320dp` 的硬编码尺寸，需要继续做响应式收口。
