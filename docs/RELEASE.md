# Release Automation (GitHub Actions)

## English
This repository ships with two workflows:

- `ci.yml`: build/test pipeline for push and pull request.
- `release.yml`: full release pipeline for GitHub Releases.

### CI Workflow
Triggers:
- Push to `main/master/develop/feature/**`
- Pull request
- Manual dispatch

Outputs:
- Windows payload artifact (`acb-win-x64-ci`)
- Android debug APK artifact (`acb-android-debug-apk`)

### Release Workflow
Triggers:
- Push tag `v*` (example: `v0.2.4`)
- Manual dispatch (input: `version`)

Outputs uploaded to GitHub Release:
- `ACB-Setup-<version>.exe`
- `ACB-win-x64-<version>.zip`
- `ACB-Android-<version>.apk`
- `SHA256SUMS.txt`
- `*.asc` (GPG detached signatures, when key is configured)

### How to Publish
1. Ensure local changes are committed and pushed.
2. Create and push a tag:
   - `git tag v0.2.4`
   - `git push origin v0.2.4`
3. Wait for `release.yml` to complete.
4. Check the created GitHub Release page and attached assets.

### Local GPG signing (optional)
```powershell
pwsh ./scripts/sign-release.ps1 -AssetsDir ./dist/release-assets -KeyId "<your-key-id>" -Passphrase "<passphrase>"
```

### Notes
- Android artifact is the generated release APK from `assembleRelease` (unsigned unless signing is configured).
- Inno Setup is installed automatically on GitHub runner via Chocolatey.
- GPG signing is optional and enabled by repository secrets:
  - `GPG_PRIVATE_KEY` (ASCII armored private key)
  - `GPG_PASSPHRASE` (key passphrase)

---

## 中文
仓库内置两个自动化工作流：

- `ci.yml`：推送/PR 的构建验证流水线
- `release.yml`：用于 GitHub Release 的正式发布流水线

### CI 工作流
触发条件：
- 推送到 `main/master/develop/feature/**`
- Pull Request
- 手动触发

产物：
- Windows 打包产物（`acb-win-x64-ci`）
- Android Debug APK（`acb-android-debug-apk`）

### Release 工作流
触发条件：
- 推送 `v*` 标签（例如 `v0.2.4`）
- 手动触发（输入 `version`）

上传到 GitHub Release 的文件：
- `ACB-Setup-<version>.exe`
- `ACB-win-x64-<version>.zip`
- `ACB-Android-<version>.apk`
- `SHA256SUMS.txt`
- `*.asc`（配置密钥后自动生成的 GPG 分离签名）

### 发布步骤
1. 确认代码提交并推送到远端。
2. 创建并推送标签：
   - `git tag v0.2.4`
   - `git push origin v0.2.4`
3. 等待 `release.yml` 运行完成。
4. 在 GitHub Release 页面检查产物与说明。

### 本地 GPG 签名（可选）
```powershell
pwsh ./scripts/sign-release.ps1 -AssetsDir ./dist/release-assets -KeyId "<你的密钥ID>" -Passphrase "<口令>"
```

### 说明
- Android 包来自 `assembleRelease` 的输出（未配置签名时为未签名/测试发布用途）。
- Inno Setup 在 GitHub Runner 上通过 Chocolatey 自动安装。
- GPG 签名为可选功能，需要在仓库 Secrets 配置：
  - `GPG_PRIVATE_KEY`（ASCII 装甲格式私钥）
  - `GPG_PASSPHRASE`（私钥口令）
