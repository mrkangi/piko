# 开发验证踩坑记录

记录最近一次远程下载页、应用锁与生物识别改动中实际遇到的问题。适用于当前 Orca 开发容器；换机器时以实际 JDK/SDK 路径为准。

## 环境与构建

- **默认 Java 是 8，Gradle 直接拒绝运行**：执行构建前设置
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`。否则会报
  `Gradle requires JVM 17 or later`。
- **当前环境访问 `dl.google.com` 会超时**：不要反复等待 Google Maven。
  可在验证时用临时 init script 将 `pluginManagement`、`dependencyResolutionManagement`
  和 `buildscript` 的仓库指向可用镜像；临时脚本放在 `/tmp`，不要提交。
  只改 `settings.gradle.kts` 的仓库不够，根 `build.gradle.kts` 的 `buildscript`
  仍会走原仓库。
- **Gradle 9.6.1 + foojay-resolver 0.9 在当前容器配置 `:desktopApp` 时会报
  `JvmVendorSpec ... IBM_SEMERU`**：验证 UI/Android 时使用
  `--configure-on-demand`，避免无关模块被提前配置。
- **不要用 `-x :shared:compileKotlinDesktop` 验证 UI**：`ui` 依赖 `shared`
  的编译产物，跳过 shared 只会得到整屏“Unresolved reference 'shared'”，没有诊断价值。
  需要先让 shared 正常编译。
- **当前分支依赖 pikpak-kotlin 的未发版 main**：`libs.versions.toml` 里的
  `pikpakSdk = "2.5.0"` 没有 `concurrentAcceleration` 和 `passwordSupplier`，
  直接用正式版会在 `PikoClientManager` 报错。验证时可在 `local.properties`
  临时配置 `pikpak.sdk.dir=<pikpak-kotlin checkout>`，并在 SDK 仓库的
  `local.properties` 写 `sdk.dir=<android-sdk>`，走 Composite Build。
  验证完成后删除本仓库临时 `local.properties`，它在 `.gitignore` 中。
- 当前 Android SDK：`/mnt/kangjin-default/tools/android-sdk`。

### 本次实际用过的验证命令

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :ui:compileKotlinDesktop \
  --init-script /tmp/piko-init.gradle \
  --configure-on-demand

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :app:compileDebugKotlin \
  --init-script /tmp/piko-init.gradle \
  --configure-on-demand

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :ui:desktopTest \
  --init-script /tmp/piko-init.gradle \
  --configure-on-demand

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ./gradlew :app:testDebugUnitTest \
  --init-script /tmp/piko-init.gradle \
  --configure-on-demand
```

本次以上命令均通过。若多个工作区的 Gradle 同时运行，Kotlin 编译可能长时间无输出；
先确认没有其他构建占用 daemon，不要误判为代码死循环。

## 远程下载页

- **不要把“远程”塞进 `TransferKind`**：`TransferKind` 表示文件任务类型，
  远程请求是独立的数据源。新增 `TransferTab`（`Kind` + `Remote`）承载顶层页签，
  远程页单独渲染，避免污染 `TransfersState.sectionsOf` 与本地任务统计。
- **`TransfersScreen` 的 phase 必须特判远程页**：本地任务为空时原逻辑会进入
  `TransfersPhase.EMPTY`，导致 pager 根本不组合远程页。远程页应强制 `CONTENT`，
  空态由远程页自己处理。
- **三个入口都要传 tab 状态**：`TaskPageHeader`、`TaskIslandHeader`、
  `TaskIslandSubHeader` 的 `selectedTab/remoteCount/onSelectTab` 必须一致；
  只改其中一个会出现“顶栏切了、岛页签没切”或 pager 与 tab 不同步。
- **`Modifier.animateItem()` 只能在 `LazyItemScope` 内使用**。如果把 `items` 的
  内容转发给普通 `@Composable (item) -> Unit`，接收方没有 LazyItemScope，
  会报 unresolved。远程任务列表当前不使用行动画。
- **远程页空态不要用 `fillMaxSize()` 直接放在 Column 里**：应使用
  `Modifier.weight(1f).fillMaxWidth()`，否则空态可能与顶部设备筛选重叠或把 Column
  撑出视口。
- **发送与任务管理拆开**：文件操作进入 `RemoteSendDialog`，只选择设备/画质并发送；
  任务查看、暂停、继续、重试、取消在「远程」tab。不要再把任务列表塞回发送弹窗。
- **目标设备离线时远程 tab 仍要能看历史**：设备下拉保留离线设备，只是发出动作不可用；
  “全部设备”筛选不能因为 offline 而隐藏记录。
- **接收任务的控制仍在「下载」页**：远程 tab 展示的是 request 级视图，目标设备实际
  落盘的 `DownloadTask` 仍在下载页管理；不要把两套状态强行合成一个列表。

## 应用锁与生物识别

- **`AppLock` 是跨平台接口**：新增能力必须提供默认实现，桌面端保持空实现；
  否则会破坏没有实现这些成员的平台。
- **AndroidX BiometricPrompt 需要 `FragmentActivity`**：
  `MainActivity` 与 `PlayerActivity` 已改为 `FragmentActivity`。以后新增需要
  生物识别的 Activity 也要使用 `FragmentActivity`，不要混用普通 `ComponentActivity`
  后又调用 `BiometricPrompt`。
- **生物识别需要当前 Activity**：`PikoApplication` 通过
  `ActivityLifecycleCallbacks` 维护 `currentActivity`，`AndroidAppLock` 通过
  `activityProvider` 取用。锁屏首次组合时 Activity 可能尚未 resumed，自动弹窗会
  返回 false；PIN 按钮和页面上的“使用生物识别”按钮必须始终可用。
- **自动锁定用 `ProcessLifecycleOwner`，不是单个 Activity**：
  进入后台时开始计时，回到前台取消。多窗口播放器仍可见时进程仍处于 STARTED，
  不应误锁；但播放器窗口本身也要在锁定时暂停并显示锁屏覆盖。
- **不要为了锁屏直接移除播放器组合**：`PlayerActivity` 保留播放器组合并传入
  `paused`，锁定时暂停、解锁后按锁前是否正在播放决定是否恢复；直接 `finish`
  或移除组合会丢失当前播放位置。
- **多窗口播放器是独立 task**：`MainActivity` 的最近任务遮罩不会自动覆盖
  `PlayerActivity`，两边都要调用 `setRecentsScreenshotEnabled` / `FLAG_SECURE`。
- **锁定时关闭系统媒体控制**：多窗口播放器的媒体通知只在聚焦窗口持有；
  锁定后 `ownsSystemMediaControls` 还应为 false，避免通知栏暴露视频标题。
- **默认自动锁定策略保持 `NEVER`**：为了不改变老用户“仅冷启动锁定”的行为，
  新策略由用户主动选择；不要擅自把默认值改成 5 分钟。
- **`AppLockSettings` 的文案要跟随 `autoLockTimeout`**：应用锁行、自动锁定行和
  设置选项必须使用同一套 `autoLockLabel`，避免设置页和对话框显示不同策略。

## 可复用的验证清单

- 远程页：空设备、离线设备、无任务、发出任务、接收任务、目标设备断连各看一遍。
- 应用锁：冷启动、切后台超过阈值、立即锁定、生物识别取消、PIN 错误、播放器窗口锁定。
- 双端：`ui` 桌面编译与 Android app 编译都要跑；只跑其中一端会漏掉
  Material 3 或平台 API 差异。
- 收尾：`git diff --check`，并确认临时 `local.properties`、镜像脚本和 SDK checkout
  没有进入变更。
