# 局域网共享

局域网共享把另一台设备上发布的本机挂载目录，只读地接入当前设备的 `设备` 命名空间：

```text
设备/
  此设备/
  Living room/
    Media/
      movie.mp4
```

## 分工

- `shared/.../data/LanLocation.kt`：客户端共享路径的 ID，按 `peerId + shareId + 相对路径` 编码；共享内路径不进入网盘索引。
- `shared/.../lan/LanShareRepository.kt`：配对、共享列表、目录、host 索引与取流地址；目录结果转成普通 `FileStat`，复用网盘页、播放器、瀑布流与信息流。
- `shared/.../lan/DesktopLanShareHost.kt`：桌面 host，JDK `HttpServer`，只读 HTTP + 令牌配对。
- `PikoDriveRepository`：把设备节点与 `LanLocation` 接入统一虚拟列表；`isDriveFolderId` 与写操作保护都排除这些位置。
- `PikoMediaRepository`：局域网文件走 `PlayableMediaInfo.currentUrl`，视频与字幕都由 host 的 `/v1/content` 提供。

## 协议（v1）

所有响应为 JSON；文件内容为二进制。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/v1/info` | 无 | 设备名、设备 ID、平台 |
| POST | `/v1/pair` | 配对码 | 客户端用一次性配对码换长期令牌 |
| GET | `/v1/shares` | Bearer | 已发布目录 |
| GET | `/v1/list?share=&path=` | Bearer | 一层目录 |
| GET | `/v1/stat?share=&path=` | Bearer | 单个条目元数据 |
| GET | `/v1/index?share=` | Bearer | host 预建的整棵共享索引（上限 50,000 项，可能标记 truncated） |
| GET/HEAD | `/v1/content?share=&path=&token=` | Bearer 或 token 查询 | 文件内容，支持 `Range`、`ETag`、`Accept-Ranges` |

`/v1/content` 同时接受查询参数里的令牌，因为 mpv / ExoPlayer 加载外挂字幕时未必转发自定义请求头。令牌只在本机可信局域网内使用。

## 索引与瀑布流

`VideoDiscovery` 在开始遍历前调用 `PikoDriveRepository.prefetchLanIndex(rootId)`。若根是局域网共享，客户端先取 `/v1/index`，之后逐层列目录直接命中内存树；host 端索引缓存 60 秒，超限后标记 truncated，客户端对未知目录回退 `/v1/list`。

## 安全边界

- 共享目录必须显式打开 `lanShared`；默认不发布。
- 服务只读；上传、删除、重命名、移动都不支持。
- host 端把相对路径交给 `LocalLocation`，拒绝 `.`、`..`、绝对路径、反斜杠与冒号；符号链接策略由 `LocalFileAccess` 负责。
- 令牌与配对设置存在设备本地偏好，不进入网盘设置同步。
- v1 是明文 HTTP + 配对令牌，只应在可信局域网使用；TLS 与 mDNS 在后续阶段接入。
- Android 作为 host 需要 SAF 随机读与前台服务，v1 只支持桌面 host；Android/桌面都可作为客户端。

## 已知限制

- 不支持写操作与双向同步。
- LAN 文件没有 GCID，不能参与网盘去重。
- 普通浏览按需请求；媒体扫描使用 host 索引。索引上限目前为 50,000 项。
- Android host 尚未接入；桌面 host 关闭应用后共享停止。
