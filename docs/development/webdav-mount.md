# WebDAV 虚拟目录

只读 WebDAV 目录作为本机虚拟目录挂载到 `设备 / 此设备` 下，与本地文件夹、SMB 共用文件浏览、播放器、瀑布流与信息流入口。v1 不支持写入、移动、删除或上传。

## 分层

- `shared/.../data/LocalMounts.kt`：`LocalMountKind.WEBDAV` 与 `WebDavMountInfo`；挂载表按账号隔离，密码不进入挂载表。
- `shared/.../webdav/WebDavFileAccess.kt`：用 `PROPFIND`（`Depth: 0/1`）列目录、查元数据，解析 `DAV:` 命名空间的响应。
- `shared/.../webdav/WebDavStreamServer.kt`：只绑定 `127.0.0.1` 的只读转发服务，带进程随机令牌，把播放器的 `GET` / `HEAD` 与 `Range` 原样转发到上游，并补上 Basic 认证。
- `webDavFileAccess` 与本地路径、SMB 来源由 `CompositeLocalFileAccess` 按挂载类型分派。WebDAV 密码与 SMB 共用平台机密存储：Android 走 `AndroidKeyStore`，桌面走 `DPAPI` / 登录钥匙串 / Secret Service。

## 播放与图片

`WebDavFileAccess` 为文件和图片生成回环 URL，凭据只存在于进程内，不写进交给播放器的地址。拖动时每个 HTTP 请求带上 `Range` 转发到上游，不在内存或磁盘缓存整部视频；图片查看器、瀑布流与字幕读取同一个 URL。URL 只在当前进程有效。

## 安全边界

- 只读，仅支持 `http://` 与 `https://` 目录地址；地址中不允许内嵌用户名或密码。
- 服务只监听回环，不暴露局域网；局域网共享只发布本地文件夹，不发布 SMB / WebDAV 挂载。
- 密码按账号与挂载 ID 存入设备机密存储，不进入 `.piko` 设置同步；卸载挂载时清理。
- 认证目前只支持 Basic；使用自签名证书时需改用 `http` 或受信任证书。
- 重命名、移动、删除、上传与写回不在 v1 范围内。
