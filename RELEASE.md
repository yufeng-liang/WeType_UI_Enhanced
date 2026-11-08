# RELEASE.md — 发版流程

> 当用户说「发布 / release / 打 tag / 发版」时，按本文件执行（2026-11-07 起生效）。

## 发版方式：GitHub Actions CI 构建（不再本机打包）

本仓库配置了 `.github/workflows/release.yml`：

1. **打 tag 触发**：`git tag v<版本> && git push origin v<版本>` → CI 自动构建 `:app:assembleRelease`，签名后创建 GitHub Release 并附 APK，同时上传同名 artifact。
2. **手动触发**：Actions 页面「Release Build」→ Run workflow（只出 artifact，不建 Release）。
3. `.github/workflows/ci.yml` 在 push/PR 到 main 时跑单元测试 + assembleDebug 冒烟。

## 版本号规则

- `versionName = <上游基线>.<fork 修订号>`：跟随 NEORUAA 上游 main 的版本（无 v 前缀），fork 在其后追加修订号，如上游 1.29.0 → fork v1.29.0.1。
- tag 一律带 `v` 前缀（CI 的触发条件是 `v*`）。
- versionCode 单调递增即可（当前 41），需大于上游同包名版本（applicationId 相同，覆盖安装要能升上去）。
- 冲突合入上游版本 bump 提交时，保留 fork 自己的版本号（解决冲突取 HEAD 侧）。

## 发版步骤

1. 版本号 bump：`app/build.gradle.kts` 改 `versionCode` / `versionName`，commit（`chore: bump version`）。
2. 写 release 说明：`.github/release-notes/v<版本>.md`（CI 读取此文件作为标题与正文，约定见下）。
3. commit + push main，打 tag `v<版本>` 并推送 tag。
4. 盯 Actions：`gh run watch` 或 `gh run list --workflow=release.yml`；失败看日志修复后重打 tag。
5. 发布完成后：release 说明首条加 **gh-proxy 加速链接**（模板见下）并验证 206，然后通知用户。

## release 说明约定（.github/release-notes/v<版本>.md）

- 第一行 `# <标题>` 会被 CI 用作 release 标题，**正文自动剥掉首行**（不会在页面重复出现）；第二行起直接写 `## 小节`。
- 结构：这版做了什么 → 与上游项目的区别 → 安装与更新。
- **「安装与更新」首条必须加 gh-proxy 加速链接**（国内直连慢）：
  `[文件名（gh-proxy 加速）](https://gh-proxy.org/https://github.com/yufeng-liang/WeType_UI_Enhanced/releases/download/v<版本>/WeType_UI_Enhanced-<版本>_release.apk)`
  模板 = GitHub 原下载 URL 前拼 `https://gh-proxy.org/`；用 `curl -sIL -r 0-1023` 验证返回 206。

## CI 签名配置（已配置，勿改动）

- secrets：`ANDROID_KEYSTORE_BASE64`（keystore/release.jks 的 base64）、`ANDROID_KEYSTORE_PROPS_BASE64`（keystore.properties 的 base64）。CI 上解码到仓库根，Gradle 读 `keystore.properties` 完成签名。
- 本地凭据 `keystore.properties`、`keystore/` 已被 .gitignore 忽略，绝不提交。
- 更换签名密钥时：`gh secret set ANDROID_KEYSTORE_BASE64 --body "$(base64 -w0 keystore/release.jks)"` 等，密码不回显。

## 上游合入

- remote：`origin`=yufeng-liang/WeType_UI_Enhanced（本 fork），`upstream`=NEORUAA/WeType_UI_Enhanced，`costben`=Costben/WeType-Enhance（历史不相关，只能手工移植文件，不能 merge）。
- 合入上游：`git fetch upstream && git log HEAD..upstream/main --oneline` 评估后 `git merge upstream/main --no-edit`，重点检查 MainHook.kt / WeTypeSettings.kt / MainActivity.kt 的 fork 侧改动（剪贴板 hook 链、HostDexKit、Profiles 剪贴板字段、寄主预览返回等）是否被覆盖。
