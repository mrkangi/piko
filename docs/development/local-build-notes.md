# 本机 / 容器构建备忘

这里记的是特定开发机（`/mnt/kangjin-default` 这套容器）上验证过的环境限制与绕过方式。
CI 与普通开发机按根 `AGENTS.md` 的命令直接跑即可，**不要把下面的绝对路径与镜像写进
构建脚本或提交到仓库**；`local.properties` 已被 `.gitignore` 忽略，可以放本机配置。

## 1. `dl.google.com` 不可达

症状：配置阶段卡几分钟后报
`Could not HEAD 'https://dl.google.com/dl/android/maven2/...' ... Connect timed out`，
常见于 `com.google.code.gson`、AGP、Kotlin/Compose 插件。

原因：`settings.gradle.kts` 的 `pluginManagement` 与根 `build.gradle.kts` 的
`buildscript.repositories` 都写了 `google()`，且 `google()` 对 `com.google.*`
有内容过滤，镜像必须排在它前面。

绕过：用临时 init script（不要放进仓库）把三处仓库都换成阿里云镜像并**清空重排**：

```groovy
// /tmp/piko-mirror.gradle
def aliyun = { repoHandler ->
    repoHandler.maven { url = 'https://maven.aliyun.com/repository/google' }
    repoHandler.maven { url = 'https://maven.aliyun.com/repository/public' }
    repoHandler.maven { url = 'https://maven.aliyun.com/repository/gradle-plugin' }
}
beforeSettings { settings ->
    settings.pluginManagement.repositories.clear()
    aliyun(settings.pluginManagement.repositories)
    settings.pluginManagement.repositories.maven {
        url = 'https://maven.pkg.jetbrains.space/public/p/compose/dev'
    }
}
gradle.beforeProject { project ->
    aliyun(project.buildscript.repositories)
}
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories.clear()
    aliyun(settings.dependencyResolutionManagement.repositories)
    settings.dependencyResolutionManagement.repositories.maven {
        url = 'https://maven.pkg.jetbrains.space/public/p/compose/dev'
    }
}
```

```bash
./gradlew --init-script /tmp/piko-mirror.gradle :app:compileDebugKotlin
```

只清 `pluginManagement` 不够：根项目 `buildscript` 的 `classpath` 会重新加回
`google()`，所以必须用 `gradle.beforeProject` 往 `buildscript.repositories` 前面插。

## 2. JDK：Gradle 用 21，桌面工具链要 25

- 默认 `java` 是 8，Gradle 直接拒绝启动：`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`。
- `desktopApp` 与 `shots` 声明 `jvmToolchain(25)`，本机已装
  `/mnt/kangjin-default/tools/jdk25`。
- **不要**把工具链临时降到 21：`WindowsToast.kt` 用了 JDK 25 的
  `MemorySegment.setAtIndex` 等 FFM API，降到 21 会报一片类型错误，与业务代码无关。
- foojay 0.9.0 在 Gradle 9.6.1 上找不到工具链时会抛
  `NoSuchFieldError: JvmVendorSpec.IBM_SEMERU`。直接告诉 Gradle 已安装的 JDK：

```bash
-Porg.gradle.java.installations.paths=/mnt/kangjin-default/tools/jdk25
```

## 3. Android SDK

本机 SDK 在 `/mnt/kangjin-default/tools/android-sdk`（含 `android-37.1`）。二选一：

```bash
export ANDROID_HOME=/mnt/kangjin-default/tools/android-sdk
# 或写进被忽略的 local.properties
sdk.dir=/mnt/kangjin-default/tools/android-sdk
```

## 4. `shared` 需要 SDK 源码（复合构建）

当前分支用到了 `pikpak-kotlin` 尚未发布的 API（`passwordSupplier`、
`concurrentAcceleration`）。只依赖 `libs.versions.toml` 里锁定的 2.5.0 会报
`Unresolved reference` / `No parameter with name`。

```bash
git clone --depth 1 https://github.com/NihilDigit/pikpak-kotlin.git ../pikpak-kotlin
printf 'pikpak.sdk.dir=../pikpak-kotlin\n' >> local.properties
printf 'sdk.dir=/mnt/kangjin-default/tools/android-sdk\n' > ../pikpak-kotlin/local.properties
```

SDK 目录自己的 `local.properties` 也必须有 `sdk.dir`，否则复合构建里的 Android
插件找不到 SDK。

## 5. 内存

复合构建会一起编译 SDK，Kotlin daemon 默认 2 GB 会 `OutOfMemoryError`：

```bash
-Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
-Pkotlin.daemon.jvmargs="-Xmx5g"
```

## 6. 只编译一端时加 `--configure-on-demand`

`:app:compileDebugKotlin` 默认会配置 `:desktopApp`，在没有 JDK 25 的机器上会先撞工具链。
加 `--configure-on-demand` 可以只配置被依赖的项目。

## 7. 已知的既有测试失败（不要当成本次回归）

在当前 SDK `main` 上跑 `:shared:desktopTest` 会有 26 个失败，集中在
`smoke/*` 与 `state/*`，错误形如：

```
PikPak error_code=-1, error="batchMove: response missing task_id and ids"
PikPak error_code=-1, error="batchTrash: response missing task_id and ids"
```

这是 Piko 的 fake 云端与较新 SDK 批量接口响应形状不一致，与播放器无关。
`PlayerScreenStateSmokeTest`、`VrModeStateTest` 与 `:ui:desktopTest` 均为绿色。
判断回归时优先看这几项，别去追这批批量接口用例。

## 8. 一次完整的验证命令

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/mnt/kangjin-default/tools/android-sdk

./gradlew --init-script /tmp/piko-mirror.gradle \
  :app:compileDebugKotlin :desktopApp:compileKotlinDesktop :ui:desktopTest \
  --configure-on-demand \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" \
  -Pkotlin.daemon.jvmargs="-Xmx5g" \
  -Porg.gradle.java.installations.paths=/mnt/kangjin-default/tools/jdk25
```
