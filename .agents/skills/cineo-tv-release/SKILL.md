---
name: cineo-tv-release
description: 发布 Cineo TV OS Android TV 版本：确认版本号、生成并提交更新日志、同步 versionName/versionCode、打 tag 推送、触发 GitHub Actions 构建签名 APK，并创建或更新 GitHub Release。当用户说“打包”“构建”“发布”“出 APK”“发到 GitHub Releases”“发新版”“重建某个版本”时使用。
---

# Cineo TV OS 发布编排

这个 skill 是发布流程的**编排者**。更新日志的取证与写作由通用 skill
`release-notes` 完成；本 skill 负责确认版本、改版本号、提交、打 tag、触发
GitHub Actions，并在 run 结束后逐项验证。

## 项目契约

| 项 | 值 |
| --- | --- |
| 项目 | Cineo TV OS |
| 仓库 | `benson-singapore/cineo-tv-os` |
| 平台 | Android TV |
| 默认分支 | `main` |
| 版本来源 | `app/build.gradle.kts` 的 `versionName` 与 `versionCode` |
| Git tag / Release | `vX.Y.Z` |
| 发布产物 | 签名 APK：`CineoTV-vX.Y.Z.apk` |
| 更新日志 | `docs/update/vX.Y.Z.md` |
| 工作流 | `.github/workflows/release.yml` |
| 工作流触发 | push tag `v*`，或手动 `workflow_dispatch` |

版本号必须满足：`versionName == tag 去掉前缀 v`（例如 tag `v1.2.3`
对应 `versionName = "1.2.3"`）。`versionCode` 是 Android 的单调递增整数，
新版本必须大于上一版本。

## 依赖的 skill

- 取证脚本：`.agents/skills/release-notes/scripts/git-release-context.sh`
- 写作规范：`.agents/skills/release-notes/SKILL.md`
- 工作流模板：`.agents/skills/cineo-tv-release/assets/release.yml`

---

## Step 0 — 硬性规则：先确认版本

改任何文件、commit、tag、push 之前，必须：

1. 读取当前版本：

   ```bash
   sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -n 1
   sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -n 1
   ```

2. 读取最新已有 tag：

   ```bash
   git tag --sort=-v:refname | head -n 5
   ```

3. 检查工作区：

   ```bash
   git status --short
   ```

4. 向用户展示“当前 `versionName` / `versionCode` / 最新 tag”，并让用户在
   两者之间选择：

   - **新版本 X.Y.Z**：用户指定版本号。可以建议 patch +1，但只是建议，
     **绝不自动递增**。
   - **重建当前版本**：同一个 tag 重新指向新 commit，并替换 Release 资产。
     这是移动已发布的 tag，属于**外部突变**，必须单独获得用户明确确认。

5. 如果 `git status` 有与本次发布无关的改动，先要求用户 commit 或 stash。
   不要把无关改动混进发布 commit。

> 用户没有明确选择版本号之前，不要进入下一步。

---

## Step 1 — 版本契约

编辑 `app/build.gradle.kts`：

```kotlin
defaultConfig {
    applicationId = "com.nova.tv"
    minSdk = 36
    targetSdk = 37
    versionCode = 2          // 必须递增
    versionName = "1.0.1"    // 与 tag 去掉 v 后一致
}
```

规则：

- 只改 `versionName` 和 `versionCode`，不要顺手改 `applicationId`
  （当前是 `com.nova.tv`，改名会导致用户无法覆盖安装）。
- 新版本 `versionCode` 必须大于当前值。
- 仓库没有其他 `package.json` / `Cargo.toml` 版本契约；不要虚构同步文件。
- 如果 README 的版本表仍写着旧版本，可以一并更新，但它是文档，不是构建依据。
- 改完用 Step 0 的命令复核。

---

## Step 2 — 确认工作流与签名

### 2.1 工作流存在

如果 `.github/workflows/release.yml` 不存在，复制模板：

```bash
mkdir -p .github/workflows
cp .agents/skills/cineo-tv-release/assets/release.yml .github/workflows/release.yml
```

已有工作流时，检查它是否仍匹配当前构建方式（Gradle、JDK 25、Android SDK
37.0、APK 输出路径）。只有构建布局变化时才需要改。

### 2.2 签名 secrets 必须存在

云构建使用仓库 secrets 注入发布密钥。工作流在缺少 secrets 时会**直接失败**，
不会生成无法升级的临时签名 APK。

| Secret | 内容 |
| --- | --- |
| `CINEO_KEYSTORE_BASE64` | `.jks` 文件的 base64（单行） |
| `CINEO_KEYSTORE_PASSWORD` | keystore 密码 |
| `CINEO_KEY_ALIAS` | key alias |
| `CINEO_KEY_PASSWORD` | key 密码 |

检查：

```bash
gh secret list
```

缺少时，先让用户生成并配置。参考命令（**不要把 keystore 或密码提交进仓库**）：

```bash
keytool -genkeypair -v \
  -keystore cineo-release.jks \
  -alias cineo \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '替换为强密码' \
  -keypass '替换为强密码' \
  -dname "CN=Cineo TV OS, OU=Release, O=Cineo, C=SG"

# macOS
base64 -i cineo-release.jks | gh secret set CINEO_KEYSTORE_BASE64
# Linux
# base64 -w0 cineo-release.jks | gh secret set CINEO_KEYSTORE_BASE64

gh secret set CINEO_KEYSTORE_PASSWORD --body '替换为强密码'
gh secret set CINEO_KEY_ALIAS --body 'cineo'
gh secret set CINEO_KEY_PASSWORD --body '替换为强密码'
```

把 `cineo-release.jks` 存到仓库外的安全位置。**丢失密钥后，已安装用户必须
先卸载旧版才能安装新签名 APK**（包名仍是 `com.nova.tv`）。

`app/build.gradle.kts` 已通过环境变量读取签名材料：

```kotlin
val releaseKeystorePath = providers.environmentVariable("CINEO_KEYSTORE_PATH").orNull
// ...
signingConfigs {
    if (hasReleaseSigning) {
        create("release") { /* storeFile / passwords / alias */ }
    }
}
```

工作流会把 `CINEO_KEYSTORE_BASE64` 解码到 `$RUNNER_TEMP`，并设置
`CINEO_KEYSTORE_PATH`。本地没有这些环境变量时，`assembleRelease` 产出
unsigned APK 是正常的；正式发布以 CI 为准。

---

## Step 3 — 先写更新日志（Release-note-first）

先读 `.agents/skills/release-notes/SKILL.md`，再取证。

已有 tag 时：

```bash
.agents/skills/release-notes/scripts/git-release-context.sh \
  --target-tag "vX.Y.Z" \
  --from-tag   "$(git tag --merged HEAD --sort=-v:refname | head -n 1)"
```

仓库完全没有 tag 时（首发）：

```bash
.agents/skills/release-notes/scripts/git-release-context.sh \
  --target-tag "vX.Y.Z"
```

然后写 `docs/update/vX.Y.Z.md`（中文，章节见 release-notes 模板）。
必须包含：

- 版本、发布时间、上一版本、覆盖范围；
- 概述；
- 新功能 / 变更与优化 / 修复（按 commit 规范分类）；
- 构建与发布说明；
- 来源范围与 commit hash。

Android TV 的“构建与发布说明”建议写：

```markdown
## 构建与发布说明

- 产物：GitHub Actions 构建的签名 APK `CineoTV-vX.Y.Z.apk`
- 安装：电视开启 ADB 调试后执行 `adb install -r CineoTV-vX.Y.Z.apk`
- 如旧版由不同签名密钥安装，需先 `adb uninstall com.nova.tv`
- 具体构建结果以本次 GitHub Actions run 为准
```

措辞红线：

- Actions 确认成功前，不得写“构建已通过”“发布成功”“已验证可安装”。
- 不得编造历史中没有的功能、修复或测试结果。
- Release 正文必须与仓库里的 `docs/update/vX.Y.Z.md` 一致；不要只在
  GitHub 网页上手写正文。

---

## Step 4 — 提交与推送

确认待提交文件只有本次发布相关内容：

```bash
git status --short
git diff --stat
```

典型文件：

- `app/build.gradle.kts`（版本号）
- `docs/update/vX.Y.Z.md`（更新日志）
- `.github/workflows/release.yml`（首次发布时新增）
- `.gitignore`（首次加入签名忽略规则时）

提交并推送：

```bash
git add app/build.gradle.kts docs/update/vX.Y.Z.md .github/workflows/release.yml
git commit -m "chore(release): vX.Y.Z"
git push origin main
```

打 tag 并推送（tag push 是 CI 触发点）：

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

注意：

- 默认分支是 `main`，不是 `master`。
- 工作流使用仓库自带的 `github.token`，**不要**为它配置 `GH_TOKEN` secret。
- tag 名必须是 `vX.Y.Z`，与 `versionName` 去掉 `v` 后一致。
- 不要在 tag 推送后继续 amend 发布 commit；需要修复时走新版本。

### 重建当前版本（仅在用户明确确认后）

```bash
git tag -d vX.Y.Z
git push origin :refs/tags/vX.Y.Z
git tag vX.Y.Z
git push origin vX.Y.Z
```

重建会移动已发布 tag 并替换 Release 资产。执行前必须再次向用户确认；
不要静默重建。

---

## Step 5 — 监控与验证

```bash
gh run list --workflow=release.yml --limit 3
gh run watch <run-id> --exit-status
```

只有 run 成功结束后，才允许宣称发布完成。逐项核对：

- [ ] 该 tag 的 `Release Android TV APK` workflow run 成功
- [ ] GitHub Release `vX.Y.Z` 存在，且不是 draft（除非用户明确要求 draft）
- [ ] Release 正文与 `docs/update/vX.Y.Z.md` 一致
- [ ] Release 资产包含 `CineoTV-vX.Y.Z.apk`，大小不为 0
- [ ] APK 已签名：`apksigner verify --print-certs CineoTV-vX.Y.Z.apk`
- [ ] APK 的 `versionName` 与 tag 一致：
      `aapt dump badging CineoTV-vX.Y.Z.apk | head`
- [ ] tag 指向预期 commit：`git rev-list -n 1 vX.Y.Z`
- [ ] 本地 `main` 与 `origin/main` 同步

辅助命令：

```bash
gh release view vX.Y.Z --json isDraft,tagName,body,assets
gh release download vX.Y.Z --pattern 'CineoTV-vX.Y.Z.apk' --dir /tmp/cineo-release
apksigner verify --print-certs /tmp/cineo-release/CineoTV-vX.Y.Z.apk
git fetch origin --tags
git status -sb
```

### 失败处理

1. 看失败日志：

   ```bash
   gh run view <run-id> --log-failed
   ```

2. 修复仓库中的问题，提交后打**新版本 tag** 重新发布。
3. 只有在用户明确确认后，才按“重建当前版本”流程删除并重推同一个 tag。
4. 如果失败原因是缺少 secrets，先配置 secrets，再重跑；不要在缺少签名材料时
   把 unsigned APK 当作正式版本发布。

---

## Android TV 安装提示

构建成功后，用户可以通过 ADB 安装：

```bash
adb connect <电视IP>:5555
adb install -r CineoTV-vX.Y.Z.apk
adb shell am start -n com.nova.tv/.MainActivity
```

电视需要开启开发者选项和 ADB 调试。若提示签名不一致：

```bash
adb uninstall com.nova.tv
adb install CineoTV-vX.Y.Z.apk
```

卸载会清除应用本地数据（视频源、收藏、观看记录等），执行前提醒用户。

---

## 红线

- 版本号永远来自用户，绝不自动递增。
- 移动已发布 tag 视为外部突变，必须单独确认。
- 更新日志只写有证据的内容；先提交日志，再打 tag。
- 不在 Actions run 结束前宣称构建成功。
- 不把 keystore、密码、`local.properties` 或签名材料提交进仓库。
- 不夸大验证结果：未实际安装到设备时，只写“APK 已构建并上传”，不要写
  “已在电视上验证”。
