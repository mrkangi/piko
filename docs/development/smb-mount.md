# SMB 虚拟目录

只读 SMB2/3 共享作为本机虚拟目录挂载到 `设备 / 此设备` 下，和本地文件夹共用文件浏览、播放器、瀑布流与信息流入口。v1 不支持写入、移动、删除或上传。

## 分层

- `shared/.../data/LocalMounts.kt`：`LocalMountKind.SMB` 与 `SmbMountInfo`；挂载表仍按账号隔离，密码不进入挂载表。
- `shared/.../smb/SmbFileAccess.kt`：用 `smbj` 建连接、列目录、查元数据和打开随机读句柄。
- `shared/.../smb/SmbStreamServer.kt`：只绑定 `127.0.0.1` 的只读 HTTP 服务，带进程随机令牌，支持 `GET`、`HEAD` 与单段 `Range`。
- `smbFileAccess` 与本地路径来源由 `CompositeLocalFileAccess` 按挂载类型分派。SMB 密码由平台机密存储保管：Android 走 `AndroidKeyStore`，桌面走 `DPAPI` / 登录钥匙串 / Secret Service。

## 播放与图片

`SmbFileAccess` 为文件和图片生成稳定的回环 URL。播放器收到的是普通 `PlaybackTarget.Url`，拖动时每个 HTTP 请求按 Range 重新打开 SMB 文件，不把整部视频落到磁盘。图片查看器和瀑布流直接使用同一个 URL，字幕也通过它读取；URL 只在当前进程有效。

## 安全边界

- 只读，且只支持 SMB2/3；SMB1 不接入。
- 服务只监听回环，不暴露局域网；局域网共享当前不发布 SMB 挂载。
- 密码按账号与挂载 ID 存入设备机密存储，不进入 `.piko` 设置同步。
- 卸载挂载时关闭连接并清理对应密码。
- 重命名、移动、删除、上传与写回不在 v1 范围内。

本机有 Samba 时可跑真实联调：

```bash
PIKO_SMB_TEST=1 PIKO_SMB_HOST=127.0.0.1 PIKO_SMB_PORT=445 \
PIKO_SMB_SHARE=share PIKO_SMB_USER=user PIKO_SMB_PASSWORD=password \
./gradlew :shared:desktopTest --tests 'dev.piko.shared.smb.SmbFileAccessIntegrationTest'
```
