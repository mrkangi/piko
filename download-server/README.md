# PikPak Download Server

独立运行的 Go 远程下载与目录同步服务，提供同源 Web 管理界面、REST API 与 SSE 实时状态更新。不依赖 Piko 客户端，也不调用外部 CLI 子进程。

架构与设计规范见 [ARCHITECTURE.md](ARCHITECTURE.md) 和 [DESIGN.md](DESIGN.md)，上游代码许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 启动配置

服务只读取三个环境变量：

```bash
DOWNLOAD_SERVER_HOST=0.0.0.0          # 默认 0.0.0.0
DOWNLOAD_SERVER_PORT=7788             # 默认 7788
DOWNLOAD_SERVER_DATA_DIR=~/.pikpak/downloader  # 默认 $HOME/.pikpak/downloader
```

管理员账号、PikPak 账号、下载目录、并发数、扫描间隔和监控目录全部在服务启动后的 Web 页面配置。

默认数据目录：

```text
$HOME/.pikpak/downloader
```

默认下载目录：

```text
$HOME/PikoDownloads
```

下载目录可在管理页修改；数据目录必须在启动前确定，迁移时停服务后整体移动。

## 本地运行

```bash
cd download-server
npm --prefix web ci
npm --prefix web run build
go test -race ./...
go build -tags embed_ui -o ./bin/download-server ./cmd/server
./bin/download-server
```

首次启动会在控制台打印 Setup Token：

```text
首次启动：请打开 http://<本机地址>:7788/setup 完成配置
Setup Token: ...
```

打开 `/setup`，输入 Token，并配置：

- 管理员用户名和密码
- PikPak 用户名和密码
- 本地下载目录

完成后会自动进入管理面板。后续启动直接使用登录页。

## 前后端分离开发

```bash
# 终端 1：Go API，默认监听 0.0.0.0:7788
cd download-server
go run ./cmd/server

# 终端 2：Vite 开发服务器，默认代理到 127.0.0.1:7788
cd download-server/web
npm install
npm run dev
```

如需把前端静态文件交给 Nginx / Caddy 托管，可部署 `internal/web/dist` 并将 `/api` 反向代理到 Go 后端。

## 多平台构建

```bash
./scripts/build-all.sh
```

默认会先构建前端，再生成：

- Linux：`amd64`、`arm64`、`armv7`、`386`
- macOS：`amd64`、`arm64`
- Windows：`amd64`、`arm64`
- FreeBSD：`amd64`
- Android/Termux：`arm64` 二进制、`termux-aarch64.deb` 安装包与 `install-termux.sh` 一键常驻脚本
- 群晖 DSM 7：`x86_64`、`armv8`、`armv7` 三个 `.spk` 套件包
- 飞牛 fnOS：`x86`、`arm` 两个原生 `.fpk` 应用包

产物在 `download-server/bin/`，并生成 `SHA256SUMS.txt`、`BUILD-INFO.txt` 以及 Termux 用的 apt 源元数据 `Packages`、`Packages.gz`、`Release`（本机有 `dpkg-deb` 时）。已构建前端时可用 `SKIP_WEB=1 ./scripts/build-all.sh` 跳过 Node 构建。

GitHub Actions 已接入主项目 tag 流程：

- 推 `test-*` tag：`build.yml` 的 `download-server` job 构建全部平台，产物并入测试构建 Pre-release。
- 推 `v*` tag：`release.yml` 的 `download-server` job 构建全部平台，产物并入正式草稿 Release。
- 产物命名为 `download-server-<版本>-<平台>`，并带 `BUILD-INFO.txt` 与最终汇总的 `SHA256SUMS.txt`。

## Termux 常驻部署（Android）

支持 **`pkg` 源直接安装**、**`pkg` 安装本地 `.deb`** 或 **一键安装脚本** 三种方式，均基于官方 `termux-services`（`runit`）实现后台常驻与崩溃自启。

### 方式一：Termux apt 源（推荐，可直接 `pkg upgrade`）

构建流程会为 Termux 生成 flat apt 源元数据（`Packages`、`Packages.gz`、`Release`），并随 `.deb` 一起发布。配置一次源之后即可像普通 Termux 包一样安装和升级：

```bash
# 稳定版：使用最新正式 Release 中的 apt 元数据
mkdir -p "$PREFIX/etc/apt/sources.list.d"
echo 'deb [trusted=yes] https://github.com/NihilDigit/piko/releases/latest/download ./' \
  > "$PREFIX/etc/apt/sources.list.d/piko-download-server.list"
pkg update
pkg install piko-download-server

# 以后升级
pkg upgrade piko-download-server
```

测试版（`test-*-server`，包含 fork 构建）使用固定滚动 Release：

```bash
mkdir -p "$PREFIX/etc/apt/sources.list.d"
echo 'deb [trusted=yes] https://github.com/mrkangi/piko/releases/download/termux-test ./' \
  > "$PREFIX/etc/apt/sources.list.d/piko-download-server.list"
pkg update
pkg install piko-download-server
```

也可以直接运行发布包里的 `download-server-*-termux-add-apt-source.sh`：

```bash
sh download-server-*-termux-add-apt-source.sh stable
sh download-server-*-termux-add-apt-source.sh test --repo mrkangi/piko
```

说明：

- `[trusted=yes]` 表示不做 GPG 签名校验，只依赖 HTTPS；请只添加自己信任的仓库。
- 稳定版源要等下一次正式 Release 带上 apt 元数据后才可用；测试版源由 `test-*-server` 构建自动更新。
- 稳定版和测试版是两个独立通道。测试包的二进制版本仍是 `9.9.9-<tag 后缀>`，但 `.deb` 包版本使用 `9.9.9.<构建时间>`，保证 `pkg upgrade` 能识别后续构建；从测试通道切回稳定通道时需要先卸载或显式安装低版本。
- 升级由 dpkg 执行 `prerm`/`postinst`，会自动停启 `download-server` 服务。

### 方式二：使用 `pkg` 安装本地 `.deb` 包

下载构建产物中的 `download-server-<版本>-termux-aarch64.deb` 后，在 Termux 中执行：

```bash
pkg update
pkg install ./download-server-*-termux-aarch64.deb
```

安装过程会自动：
- 拉取并安装依赖 `termux-services`
- 将二进制安装到 `$PREFIX/bin/download-server`
- 写入环境配置文件 `$PREFIX/etc/default/download-server`（升级包时保留用户修改）
- 注册 `$PREFIX/var/service/download-server` 服务并立即启动
- 申请 `termux-wake-lock` 防止锁屏休眠断连

卸载时直接执行 `pkg uninstall piko-download-server` 即可自动停止并移除服务。

### 方式三：一键安装脚本 `install-termux.sh`

如果本地已有 `download-server-android-arm64` 二进制或 `.deb` 包，或者希望自动从 GitHub Releases 拉取最新版：

```bash
# 自动检测同目录下的 .deb / android-arm64 二进制，若无则从 GitHub Releases 拉取最新版
sh install-termux.sh

# 或指定本地二进制、自定义端口并写入 Termux:Boot 开机自启脚本
sh install-termux.sh --bin ./download-server-android-arm64 --port 7788 --boot
```

### 常用服务管理命令

```bash
sv status download-server    # 查看运行状态
sv restart download-server   # 重启服务（修改 $PREFIX/etc/default/download-server 后执行）
sv down download-server      # 停止服务
sv up download-server        # 启动服务
cat $PREFIX/var/log/sv/download-server/current  # 查看日志与首次启动 Setup Token
```

### 在线升级

管理页右上角的版本按钮会读取构建仓库的 GitHub Releases 检查新版本；有更新时按钮显示「可升级」，点开即可直接升级。

- 正式版本只检查正式 Release；测试构建（版本号带 `-` 后缀）会同时检查 Pre-release，因此 `test-*-server` 构建出的服务可以继续追新测试包。
- 升级包按当前 `GOOS/GOARCH` 选择对应二进制，下载后使用 Release 中的 `SHA256SUMS.txt` 校验，再原子替换当前程序。
- 当前程序会备份为同目录的 `download-server.prev`；Termux、systemd 等常驻方式会在替换成功后原地重启，下载任务由启动恢复逻辑继续。
- Termux 的 `.deb`、`install-termux.sh` 和直接运行二进制都支持自更新；需要 `$PREFIX/bin` 对当前用户可写，并且能访问 GitHub。
- Docker 容器内不支持自更新，请在宿主机执行 `docker compose pull && docker compose up -d`。
- 群晖套件内不执行二进制自更新，请在套件中心手动安装新版本 `.spk`。
- 飞牛 fnOS 应用内不执行二进制自更新，请在应用中心手动安装新版本 `.fpk`。
- GitHub API 未登录请求有频率限制，服务端会缓存检查结果 10 分钟；手动点「重新检查」会强制刷新。
- 可用 `download-server --version` 查看当前二进制版本与构建时间。

### 外置存储与 SD 卡

- 下载目录支持 Android 共享存储（FUSE）与 exFAT/FAT32 SD 卡：交付只依赖同卷改名，不再要求硬链接。
- 目标路径已有文件时直接采用本地文件，任务标记 `COMPLETED` 并带「本地已存在」标记，不下载、不覆盖；远端后续更新也不会改写它。
- 目录 `fsync` 不被文件系统支持时自动降级；崩溃后启动恢复会用已持久化的提交摘要重新核对目标文件。
- Android 共享存储可直接选 `/sdcard`、`/storage/emulated/0` 或 `~/storage/shared` 下的目录，例如 `~/storage/shared/Download/Piko`。
- **数据目录**（SQLite、实例锁、`secret.key`、PikPak 会话）仍建议留在 Termux 内部存储。共享存储的 POSIX 文件锁不可靠，把数据目录放上去可能导致单实例锁或数据库异常。
- 外置存储写入通常更慢，建议在管理页调低并发；SD 卡损坏或拔出会导致任务失败，恢复后重新扫描即可续传。

### Termux DNS

Termux 的 `ping` 使用 Android netd 解析域名，而静态 Go 二进制无法调用 bionic resolver；若纯 Go resolver 读不到 `/etc/resolv.conf`，会退回到没有服务的 `127.0.0.1/::1:53`。服务在 Termux/Android 启动时会自动读取 `$PREFIX/etc/resolv.conf`，忽略回环 DNS，并使用其中可用的非回环 DNS；文件不存在或只有回环项时回退到公共 DNS。

### 旧版本报 `hardlink ... permission denied`

只有旧版本依赖硬链接。升级前若无法启动，可先把已保存的下载目录改回内部存储：

```bash
sv down download-server
pkg install -y sqlite
sqlite3 "$HOME/.pikpak/downloader/download-server.db" \
  "UPDATE app_settings SET value = '$HOME/PikoDownloads' WHERE key = 'download_dir';"
sv up download-server
```

升级后的版本在共享存储上可直接启动，也可以留在外部目录。

## 群晖 DSM 7 套件（SPK）

构建会生成三个架构的套件包：

```text
download-server-<版本>-synology-x86_64.spk   # Intel / AMD 机型
download-server-<版本>-synology-armv8.spk   # ARM64 机型
download-server-<版本>-synology-armv7.spk   # 较老的 ARMv7 机型
```

只支持 DSM 7.0 及以上。套件以 DSM 内部用户 `sc-piko-download-server` 运行，不使用 root。

### 安装

1. 在套件中心打开「设置 → 常规 → 信任级别」，选择允许安装任何发行者的套件。当前构建没有 Synology 官方签名。
2. 套件中心 → 手动安装 → 选择对应架构的 `.spk`。
3. 安装向导里填写下载共享文件夹名，默认 `piko-downloads`；不存在时会创建，并授权给套件用户。
4. 安装完成后打开 `http://<NAS 地址>:7788/` 或套件中心里的 Piko Download Server。

首次启动的 Setup Token 会写入套件日志：

```bash
tail -f /var/packages/piko-download-server/var/logs/service.log
```

也可以从套件中心的服务日志入口查看。

### 路径与配置

- 数据目录：`/var/packages/piko-download-server/var/data`，包含 SQLite、`secret.key`、实例锁和 PikPak 会话。
- 环境配置：`/var/packages/piko-download-server/var/etc/download-server.env`。
- 服务日志：`/var/packages/piko-download-server/var/logs/service.log`。
- 默认下载目录：安装向导选择的共享文件夹下的 `PikoDownloads`；没有选择时回退到套件 `var/PikoDownloads`。
- 监听端口：`0.0.0.0:7788`，套件会同时登记 DSM 防火墙端口。

修改 `download-server.env` 后执行：

```bash
synopkg stop piko-download-server
synopkg start piko-download-server
```

### 升级与卸载

- 升级：直接手动安装新版本 `.spk`，`preupgrade`/`postupgrade` 会停启服务并保留 `var` 数据。
- 套件内不执行二进制自更新；管理页会提示通过套件中心升级，避免绕过套件脚本和权限。
- 卸载默认保留 `/var/packages/piko-download-server/var` 与共享目录中的下载文件；彻底清理时手动删除该目录。
- 不建议把 7788 直接暴露到公网，推荐使用 DSM 反向代理加 HTTPS。

## 飞牛 fnOS 应用（FPK）

构建会生成原生 `.fpk`：

```text
download-server-<版本>-fnos-x86.fpk   # x86_64 设备
download-server-<版本>-fnos-arm.fpk   # ARM64 设备
```

`.fpk` 是飞牛应用中心使用的 tar.gz 包，包含 `manifest`、`app.tgz`、`cmd`、`config`、`wizard`、`ui`、图标和防火墙声明。应用以专用用户运行，不依赖 Docker。

### 安装

1. 在飞牛应用中心选择手动安装，上传对应架构的 `.fpk`。
2. 安装完成后从应用中心打开 Piko Download Server，或访问 `http://<NAS 地址>:7788/`。
3. 首次启动的 Setup Token 写入应用日志。

### 路径与配置

- 应用目录：`/var/apps/piko-download-server`。
- 数据目录：`/var/apps/piko-download-server/var/data`。
- 配置目录：`/var/apps/piko-download-server/etc`，环境文件为 `download-server.env`。
- 日志：`/var/apps/piko-download-server/var/download-server.log`。
- 下载共享目录：`piko-download-server/downloads`，可在文件管理器中访问；默认下载子目录为 `PikoDownloads`。
- 监听端口：`0.0.0.0:7788`，并通过 `piko-download-server.sc` 登记防火墙规则。

修改环境文件后，在应用中心停止再启动应用即可生效。

### 升级与卸载

- 升级：在应用中心手动安装新版本 `.fpk`，`upgrade_init`/`upgrade_callback` 会停启服务并保留数据。
- 应用内不执行二进制自更新；管理页会提示通过应用中心升级。
- 卸载时可以选择保留或删除应用数据；默认保留下载共享目录中的文件。
- 不建议把 7788 直接暴露到公网，推荐放在 HTTPS 反向代理后。

## 管理页

- **下载目录**：设置服务端下载根目录、最大并发和最低保留空间。
- **分片下载**：默认单流下载。手动把单文件分片连接数调到 2 以上且文件达到两倍最小分片大小时，才使用多个 Range 连接并行下载；改回 1 会立即降级为真正的单流。下载节点返回 429/503 时停止派发新分片，已连接分片完成当前传输后退出，自适应减半并发并带抖动退避；单连接仍遇 503 会刷新直链有限重试。每个下载节点最多同时保留 5 条连接，建议分片连接数设为 4–5，过高不会更快，只会更容易触发节点限流；不支持 Range 时自动回退单流。
- **网络代理**：可配置 HTTP、HTTPS 或 SOCKS5 代理，统一用于 PikPak API 和原始文件下载节点；支持在 URL 中携带认证信息。代理地址加密保存，页面只显示脱敏结果；关闭后恢复直连。
- **PikPak 目录**：批量勾选或单个添加监控目录，设置首次扫描策略和扫描间隔。
- **最小文件大小**：每个来源可设置 MiB 阈值，小于阈值的文件只建立索引，不生成下载任务；填 0 表示不限。
- **手动扫描**：单来源「立即扫描」；顶部「扫描全部已启用」安排所有启用来源立即扫描。
- **PikPak 账号**：验证并更新账号密码；有活动下载任务时需先暂停。
- **管理员密码**：修改 Web 管理面板登录密码。
- **通知渠道**：添加飞书群机器人或通用 Webhook，可分别启用/停用、测试发送和选择通知事件。
- **文件索引**：筛选远端缺失、路径变更和本地交付记录；采用本地文件的任务与文件会显示「本地已存在」标记。
- **分页与搜索**：任务列表与文件索引均为服务端分页（每页 50 条），返回筛选后的准确总数；搜索、状态筛选和来源筛选都在 SQL 中执行，不会只搜索当前页或固定前 500 条。
- **运行日志**：页面内直接查看服务端标准日志，支持关键词搜索、自动刷新、加载更早和回到最新。日志只保存在内存环形缓冲中（默认最多 4000 条 / 2 MiB，单行最多 8 KiB），页面最多渲染 500 条；服务重启后清空，不会因日志过多拖垮浏览器或占满磁盘。
- **文件名隐藏**：默认脱敏。右上角眼睛按钮可在明文与脱敏模式之间切换；隐藏后任务、文件索引和详情抽屉中的路径只保留目录层级与扩展名，设置保存在当前浏览器本地，不影响服务端数据或其他设备。

默认扫描周期为 5 分钟。扫描仍由服务端单线程调度，避免同时打满 PikPak API。

## 通知

通知事件包括：

- `tasks.queued`：一次扫描新增下载任务（按批次汇总，不会逐个文件刷屏）
- `task.started`：开始下载
- `task.retry`：失败后进入重试
- `task.failed`：最终失败
- `task.completed`：下载完成
- `task.conflict`：本地路径冲突
- `scan.failed`：来源扫描失败
- `system.low_disk` / `system.disk_ok`：下载卷空间不足与恢复

通知正文只包含事件类型、数量和来源，不包含文件名、文件路径或错误详情；开始/完成/失败/重试/冲突等任务事件会在短窗口内按渠道和来源汇总为“N 个任务”。

飞书群机器人支持可选的加签密钥；通用 Webhook 会发送 JSON，`secret` 非空时作为 `Authorization: Bearer <secret>`。渠道 URL 与密钥使用数据目录中的 `secret.key` 加密保存。

通知发送是尽力送达：单次发送失败会在内存中重试 3 次；服务重启后不会补发失败通知。

## 容器部署

- 参考 `Dockerfile` 与 `docker-compose.example.yml`。
- 容器以非 root 用户（`10001:10001`）运行，不挂载 Docker socket。
- 数据目录挂载到 `/data/.pikpak/downloader`，下载目录默认位于 `/data/PikoDownloads`，可在 Web 管理页修改。
- `/data` 必须支持同卷改名与文件 `fsync`；启动时会执行 `Probe()` 校验，不满足的挂载会拒绝启动。目录 fsync 不支持时会自动降级。
- 不要让多个实例共享同一数据目录；服务通过 `server.lock` 强制单实例。
- 默认监听 `0.0.0.0:7788`，首次配置必须使用控制台打印的 Setup Token。公网部署请放在 TLS 反向代理后。

### 极简镜像

`Dockerfile.minimal` 使用 `scratch` 作为运行镜像，只包含：

- 静态 Go 二进制（前端已嵌入）
- CA 证书
- 可写的 `/data` 和 `/tmp`

构建与启动：

```bash
cd download-server
docker build -f Dockerfile.minimal -t piko-download-server:minimal .

docker volume create piko-download-data
docker run -d \
  --name piko-download-server \
  -p 7788:7788 \
  -v piko-download-data:/data \
  piko-download-server:minimal

docker logs -f piko-download-server
```

首次启动日志里会打印 Setup Token；打开 `http://<host>:7788/setup` 完成初始化。

如果使用 `docker-compose.minimal.yml`：

```bash
docker compose -f docker-compose.minimal.yml up -d --build
```

绑定宿主机目录 `./data` 时需要保证容器用户 `10001:10001` 可写，例如：

```bash
mkdir -p data
sudo chown -R 10001:10001 data
```

极简镜像为了体积不包含 `wget/curl`，因此没有 Docker `HEALTHCHECK`；需要健康检查时使用普通 `Dockerfile`。

## 备份与恢复

- **备份**：先停止服务，再备份整个数据目录（默认 `$HOME/.pikpak/downloader`）和下载目录。数据目录包含 SQLite、实例锁、`secret.key`、PikPak 会话和设置。
- **恢复**：停机状态下恢复数据目录与下载目录，重新启动服务；`StartupRecover` 会校验未完成任务与 `.staging` 偏移，已交付文件不会被覆盖或删除。
