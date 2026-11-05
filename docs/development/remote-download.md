# 远程下载

同一 PikPak 账号下的多台设备可以互相发送「下载到这台设备」的任务。设备之间只同步任务与状态，文件本身仍由目标设备直接向 PikPak 下载，避免跨设备搬运大文件。

## 任务信箱

目标设备不需要公网 IP 或端口映射。每台设备在网盘根目录的 `.piko` 文件夹里只写自己的账本：

```text
.piko/remote-<deviceId>-<timestamp>.json
```

账本包含 `requests`（本机发出的请求）和 `states`（本机作为目标设备执行过的任务状态）。每台设备只写自己的文件，因此多台设备并发同步不会互相覆盖。目标是让每台设备都能读到其他设备的账本，并只处理 `targetDeviceId` 指向自己的请求。

请求包含 `requestId`、源/目标设备、`fileId`、`gcid`、`parentId`、文件名、大小、画质上限、创建时间和过期时间。状态包含 `ACCEPTED`、`DOWNLOADING`、`PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED`、`REJECTED` 和 `EXPIRED`。

## 执行链路

1. 源设备在文件操作里选择「下载到设备…」，`RemoteDownloadCoordinator.sendDownload` 把请求写进自己的账本。
2. 目标设备轮询 `.piko`，解析请求并通过 `PikoDriveRepository.getFileDetail` 核对文件仍在当前账号。
3. 目标设备调用 `PikoDownloadCoordinator.enqueueRemote`，文件直接下载到目标设备配置的下载目录。
4. `DownloadTask.remoteRequestId` 与 `originDeviceId` 标记任务来源；下载状态节流写回目标设备账本。
5. 源设备读取目标设备的账本，显示进度、完成或失败。

轮询在账号已登录且用户开启「远程下载」时进行。桌面端进程常驻托盘即可；Android 由 `RemoteDownloadService` 以前台服务保持进程，通知栏提供关闭入口。

## 边界

- 只支持同一 PikPak 账号；跨账号需要另走分享链接。
- 当前只支持文件，不支持文件夹、归档条目和本地挂载文件。
- 目标设备必须在线并运行 Piko；离线时请求留在账本中，过期后标记为 `EXPIRED`。
- 目标设备的下载目录和画质默认值由目标设备自己决定，源设备不能指定任意本地路径。
- `.piko` 账本对 PikPak 服务端可见，不写入账号密码、访问令牌或媒体直链。
