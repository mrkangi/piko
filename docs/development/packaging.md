# 打包与测试包发布

本文整理「从一份源码到能装到设备上的包」的全部通道。正式发版的细节以根 `AGENTS.md`
为准，这里补上日常最常用的 **test 包**、手动构建、Actions 故障排查与产物命名。

## 通道一览

| 用途 | 触发方式 | 工作流 | 产物去向 |
| --- | --- | --- | --- |
| 正式发版 | 推 `vMAJOR.MINOR.PATCH` tag | `release.yml` | 草稿 Release，人工核对后公开 |
| 日常 test 包 | 推 `test-*` tag | `build.yml` | 自动建 **Pre-release**，带全平台安装包 |
| 只拿产物 | 手动 `workflow_dispatch` | `build.yml` | Actions Artifacts，保留 7 天 |
| 全量测试 | push / PR / 手动 | `test.yml` | 无安装包，只有结论 |
| 本机调试 | 本地 Gradle | — | `app/build/outputs/...` |

test 包与正式包的关键区别：**不跑安装/更新冒烟**，只保证能编出来；版本号带后缀、
Release 标记为 Pre-release，不会进入应用内更新的 `/releases/latest` 通道。

## 1. 用 git 打 test 包（推荐）

一条命令，四个平台并行打包，完成后自动创建 Pre-release：

```bash
git tag test-20261111-vr
git push origin test-20261111-vr
```

tag 约定 `test-<日期>-<说明>`：

- 版本号 = 基座 `9.9.9` + tag 后缀 → `9.9.9-20261111-vr`（基座可用手动触发时的
  `version` 输入改，见下一节）。
- Release 标题自动变成 `Piko 测试构建 2026-11-11（vr）`。
- 后缀只允许字母、数字、点、下划线和短横线；`versionCode` 只由基座决定
  （`9.9.9` → `9009009`），同一基座的测试包签名一致时可直接覆盖安装。
- `MSI` / `DMG` 的**包内版本**固定是纯数字基座（jpackage 不接受后缀），后缀只出现在
  文件名与 Release 标题里；Android 的 `versionName` 则带完整后缀。

产物（都挂在同一个 Pre-release 下）：

| 平台 | 文件 |
| --- | --- |
| Android | `piko-<版本>-arm64-v8a.apk`、`-armeabi-v7a.apk`、`-x86_64.apk`、`-universal.apk`、`-mapping.txt` |
| Windows | `piko-windows-x64-<版本>.7z` / `.msi`，`piko-windows-arm64-<版本>.7z` / `.msi` |
| macOS | `piko-macos-arm64-<版本>.dmg` |
| Linux | `piko-linux-x64-<版本>.AppImage` |
| 校验 | `SHA256SUMS.txt` |

Quest / 手机装 Android 的 `arm64-v8a` 或 `universal` 即可。

下载后校验：`sha256sum -c SHA256SUMS.txt`（Windows 可用 `Get-FileHash` 对照）。

失败重跑：在 Actions 页面只 Rerun failed jobs。`publish` job 会先查 Release 是否已存在，
存在就补传并覆盖附件，不会重复建。

删除一个测试包：

```bash
gh release delete test-20261111-vr --cleanup-tag
```

## 2. 只出产物、不建 Release

只想拿某个平台、或先确认能编过：

```bash
gh workflow run build.yml --ref feature/remote-download \
  -f version=9.9.9 -f suffix=vr
gh run watch
gh run download <run-id> -D dist
```

手动触发不会跑 `publish` job；产物在 run 的 Artifacts 里保留 7 天。

## 3. 正式发版（摘要）

1. SDK 已发布到 Maven Central，并确认 `repo1.maven.org` 可解析；
2. 更新 Piko 依赖版本，写 `.github/changelogs/<version>.md` 五种语言；
3. 先演练：`gh workflow run release.yml -f version=9.9.9`；
4. 推 `v<version>` tag，等测试与四平台构建跑完；
5. 核对草稿 Release 的附件与正文，再 `gh release edit <tag> --draft=false`。

完整门控与顺序见根 `AGENTS.md` 的「发版与多语言更新日志」。

## 4. 本机构建

```bash
# Android debug（Quest 3 用 arm64-v8a）
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-<abi>-debug.apk

# Android release（需要本机 keystore 与对应的 Gradle 属性）
./gradlew :app:assembleRelease

# 桌面 Release 镜像（ProGuard + AOT，能提前暴露 keep 规则遗漏）
./gradlew :desktopApp:createReleaseDistributable
```

特定开发容器的 JDK、Android SDK、Gradle 镜像与 SDK 复合构建限制见
`docs/development/local-build-notes.md`；不要把这些绝对路径写进构建脚本。

## 5. Actions 报 422 / 无法触发

现象：`gh workflow run ...` 返回 `HTTP 422`，页面上 workflow 显示为 disabled 或列表为空。

常见原因与处理：

1. **Fork 仓库默认不运行 Actions**。打开仓库页面 → Actions →
   「I understand my workflows, go ahead and enable them」；
   或 Settings → Actions → General → Allow all actions。
   启用后 `gh workflow list -R <owner>/<repo>` 应能看到 `Build` / `Release` / `Test` 且为 `active`。
2. **token 缺 `workflow` scope**：`gh auth refresh -s workflow`。
3. **引用了不存在的 workflow 文件或分支**：确认文件名（`build.yml`）与 `--ref` 的分支存在。
4. **Release 创建 403**：Settings → Actions → General → Workflow permissions 改成
   「Read and write permissions」；或在仓库里配一个带 `contents: write` 的
   `RELEASE_TOKEN` secret，`build.yml` 会自动优先使用它。

快速自检：

```bash
gh api repos/{owner}/{repo}/actions/permissions --jq '{enabled,allowed_actions}'
gh api repos/{owner}/{repo}/actions/permissions/workflow --jq .default_workflow_permissions
gh run list -L 5
```

## 6. 备用通道：`piko-ci` 镜像仓库

`mrkangi/piko-ci` 是同源码的镜像仓库，配置了同一套构建 Secret，用于 fork 的 Actions
不可用时出包。本地 remote 名为 `ci`：

```bash
git push ci feature/remote-download
gh workflow run build.yml -R mrkangi/piko-ci --ref feature/remote-download -f version=9.9.9
```

镜像仓库只负责「编译出产物」；要把产物挂到 `mrkangi/piko` 的 Release 上，需要带写权限的
token。日常优先用第 1 节的 `test-*` tag，镜像仓库仅在 fork Actions 出问题时使用。

镜像仓库里还留着一个旧的 `publish` 分支与 `Publish test build` 工作流：当年的做法是把某次
Build 的 `RUN_ID` 和 `TAG` 直接改在 `publish.yml` 里再 push。现在 `build.yml` 已支持
`test-*` tag 自动发 Pre-release，这套权宜流程只在 fork Actions 再次不可用时作为备用。

## 7. 版本号与产物命名

- 版本名：正式包 `MAJOR.MINOR.PATCH`；test 包为 `<基座>-<后缀>`。
- `versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH`，上限 `2000.999.999`。
- 安装包文件名统一带版本号，便于多版本共存时辨认；Artifacts 只保留 7 天，
  Release 附件长期保留。
- 同一签名、同一 `versionCode` 可直接覆盖安装；换了签名必须先卸载。
