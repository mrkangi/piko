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

产物在 `download-server/bin/`，并生成 `SHA256SUMS.txt` 与 `BUILD-INFO.txt`。已构建前端时可用 `SKIP_WEB=1 ./scripts/build-all.sh` 跳过 Node 构建。

GitHub Actions 已接入主项目 tag 流程：

- 推 `test-*` tag：`build.yml` 的 `download-server` job 构建全部平台，产物并入测试构建 Pre-release。
- 推 `v*` tag：`release.yml` 的 `download-server` job 构建全部平台，产物并入正式草稿 Release。
- 产物命名为 `download-server-<版本>-<平台>`，并带 `BUILD-INFO.txt` 与最终汇总的 `SHA256SUMS.txt`。

## Termux 常驻部署（Android）

支持 **`pkg` 直接安装 `.deb` 包** 或 **一键安装脚本** 两种方式，均基于官方 `termux-services`（`runit`）实现后台常驻与崩溃自启。

### 方式一：使用 `pkg` 安装 `.deb` 包（推荐）

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

### 方式二：一键安装脚本 `install-termux.sh`

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

### 外置存储与 SD 卡

- 下载目录支持 Android 共享存储（FUSE）与 exFAT/FAT32 SD 卡：交付只依赖同卷改名，不再要求硬链接。
- 目标路径已有文件时直接采用本地文件，任务标记 `COMPLETED` 并带「本地已存在」标记，不下载、不覆盖；远端后续更新也不会改写它。
- 目录 `fsync` 不被文件系统支持时自动降级；崩溃后启动恢复会用已持久化的提交摘要重新核对目标文件。
- Android 共享存储可直接选 `/sdcard`、`/storage/emulated/0` 或 `~/storage/shared` 下的目录，例如 `~/storage/shared/Download/Piko`。
- **数据目录**（SQLite、实例锁、`secret.key`、PikPak 会话）仍建议留在 Termux 内部存储。共享存储的 POSIX 文件锁不可靠，把数据目录放上去可能导致单实例锁或数据库异常。
- 外置存储写入通常更慢，建议在管理页调低并发；SD 卡损坏或拔出会导致任务失败，恢复后重新扫描即可续传。

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

## 管理页

- **下载目录**：设置服务端下载根目录、最大并发和最低保留空间。
- **分片下载**：设置单文件分片连接数和最小分片大小。文件达到两倍最小分片大小时使用多个 Range 连接并行下载；设为 1 个连接可关闭。下载节点不支持 Range 时自动回退单流。
- **网络代理**：可配置 HTTP、HTTPS 或 SOCKS5 代理，统一用于 PikPak API 和原始文件下载节点；支持在 URL 中携带认证信息。代理地址加密保存，页面只显示脱敏结果；关闭后恢复直连。
- **PikPak 目录**：批量勾选或单个添加监控目录，设置首次扫描策略和扫描间隔。
- **最小文件大小**：每个来源可设置 MiB 阈值，小于阈值的文件只建立索引，不生成下载任务；填 0 表示不限。
- **手动扫描**：单来源「立即扫描」；顶部「扫描全部已启用」安排所有启用来源立即扫描。
- **PikPak 账号**：验证并更新账号密码；有活动下载任务时需先暂停。
- **管理员密码**：修改 Web 管理面板登录密码。
- **通知渠道**：添加飞书群机器人或通用 Webhook，可分别启用/停用、测试发送和选择通知事件。
- **文件索引**：筛选远端缺失、路径变更和本地交付记录；采用本地文件的任务与文件会显示「本地已存在」标记。

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
