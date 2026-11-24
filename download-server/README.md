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
- Android/Termux：`arm64`

产物在 `download-server/bin/`，并生成 `SHA256SUMS.txt` 与 `BUILD-INFO.txt`。已构建前端时可用 `SKIP_WEB=1 ./scripts/build-all.sh` 跳过 Node 构建。

GitHub Actions 已接入主项目 tag 流程：

- 推 `test-*` tag：`build.yml` 的 `download-server` job 构建全部平台，产物并入测试构建 Pre-release。
- 推 `v*` tag：`release.yml` 的 `download-server` job 构建全部平台，产物并入正式草稿 Release。
- 产物命名为 `download-server-<版本>-<平台>`，并带 `BUILD-INFO.txt` 与最终汇总的 `SHA256SUMS.txt`。

## 管理页

- **下载目录**：设置服务端下载根目录、最大并发和最低保留空间。
- **PikPak 目录**：批量勾选或单个添加监控目录，设置首次扫描策略和扫描间隔。
- **最小文件大小**：每个来源可设置 MiB 阈值，小于阈值的文件只建立索引，不生成下载任务；填 0 表示不限。
- **手动扫描**：单来源「立即扫描」；顶部「扫描全部已启用」安排所有启用来源立即扫描。
- **PikPak 账号**：验证并更新账号密码；有活动下载任务时需先暂停。
- **管理员密码**：修改 Web 管理面板登录密码。
- **文件索引**：筛选远端缺失、路径变更和本地交付记录。

默认扫描周期为 5 分钟。扫描仍由服务端单线程调度，避免同时打满 PikPak API。

## 容器部署

- 参考 `Dockerfile` 与 `docker-compose.example.yml`。
- 容器以非 root 用户（`10001:10001`）运行，不挂载 Docker socket。
- 数据目录挂载到 `/data/.pikpak/downloader`，下载目录默认位于 `/data/PikoDownloads`，可在 Web 管理页修改。
- `/data` 必须支持同卷硬链接、目录 fsync 和不覆盖交付；启动时会执行 `Probe()` 校验，不支持的挂载会拒绝启动。
- 不要让多个实例共享同一数据目录；服务通过 `server.lock` 强制单实例。
- 默认监听 `0.0.0.0:7788`，首次配置必须使用控制台打印的 Setup Token。公网部署请放在 TLS 反向代理后。

## 备份与恢复

- **备份**：先停止服务，再备份整个数据目录（默认 `$HOME/.pikpak/downloader`）和下载目录。数据目录包含 SQLite、实例锁、`secret.key`、PikPak 会话和设置。
- **恢复**：停机状态下恢复数据目录与下载目录，重新启动服务；`StartupRecover` 会校验未完成任务与 `.staging` 偏移，已交付文件不会被覆盖或删除。
