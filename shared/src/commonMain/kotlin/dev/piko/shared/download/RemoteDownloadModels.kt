package dev.piko.shared.download

import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoCacheStore
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
enum class RemoteDownloadStatus {
    QUEUED,
    ACCEPTED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED,
    EXPIRED,
}

@Serializable
enum class RemoteDownloadCommand {
    NONE,
    PAUSE,
    RESUME,
    RETRY,
}

/** 一台设备发出的下载请求。只有源设备会写它，目标设备只读。 */
@Serializable
data class RemoteDownloadRequest(
    val requestId: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val fileId: String,
    val fileName: String,
    val gcid: String = "",
    val parentId: String = "",
    val totalBytes: Long = 0L,
    val qualityCap: Int? = null,
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    /** 源设备请求取消；目标设备看到后停止任务并写回 CANCELLED。 */
    val cancelRequested: Boolean = false,
    /** 源设备请求暂停/继续/重试；[commandAtMs] 用来让目标设备只执行一次。 */
    val command: RemoteDownloadCommand = RemoteDownloadCommand.NONE,
    val commandAtMs: Long = 0L,
)

/** 目标设备对一条请求的执行状态。只有目标设备会写它，源设备只读。 */
@Serializable
data class RemoteDownloadState(
    val requestId: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val status: RemoteDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null,
    val updatedAtMs: Long = 0L,
)

/** 界面用的一台已发现设备。 */
data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val receiverEnabled: Boolean,
    val updatedAtMs: Long,
    val online: Boolean = false,
    val stale: Boolean = false,
)

/** 界面用的一条远程下载记录；源设备与目标设备看到的是同一条，方向不同。 */
data class RemoteDownloadItem(
    val request: RemoteDownloadRequest,
    val state: RemoteDownloadState?,
    val sourceDeviceName: String,
    val targetDeviceName: String,
    val outgoing: Boolean,
    val status: RemoteDownloadStatus,
    val targetOnline: Boolean = false,
) {
    val requestId: String get() = request.requestId
    val fileName: String get() = request.fileName
    val totalBytes: Long get() = state?.totalBytes?.takeIf { it > 0L } ?: request.totalBytes
    val downloadedBytes: Long get() = state?.downloadedBytes ?: 0L
    val speedBytesPerSec: Long get() = state?.speedBytesPerSec ?: 0L
    val error: String? get() = state?.error
}

/**
 * 目标设备的下载执行器。生产实现是 [PikoDownloadCoordinator]，
 * 测试里可以换成不碰网络与磁盘的假实现。
 */
interface RemoteDownloadExecutor {
    val tasks: StateFlow<Map<String, DownloadTask>>

    fun enqueueRemote(file: FileStat, request: RemoteDownloadRequest, onQueued: (List<String>) -> Unit)

    fun cancelRemote(taskId: String)

    fun pauseRemote(taskId: String)
    fun resumeRemote(taskId: String)
    fun retryRemote(taskId: String)

    suspend fun findCompletedLocalPath(file: FileStat): String?
}

/**
 * 远程下载在本机的持久化。请求与状态不再写入网盘；这里只保存当前账号下本设备需要恢复的数据。
 */
@Serializable
data class RemoteDownloadLocalState(
    val version: Int = 1,
    val requests: List<RemoteDownloadRequest> = emptyList(),
    val states: List<RemoteDownloadState> = emptyList(),
    val forgottenDevices: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
)

interface RemoteDownloadStore {
    suspend fun read(account: String): RemoteDownloadLocalState?

    suspend fun write(account: String, state: RemoteDownloadLocalState)
}

/** 设备本地缓存实现。丢了只会丢远程任务视图，不会影响网盘中的任何文件。 */
class CacheRemoteDownloadStore(
    private val cache: PikoCacheStore?,
) : RemoteDownloadStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(account: String): RemoteDownloadLocalState? =
        cache?.read(key(account))?.let { text ->
            runCatching { json.decodeFromString(RemoteDownloadLocalState.serializer(), text) }.getOrNull()
        }

    override suspend fun write(account: String, state: RemoteDownloadLocalState) {
        cache?.write(key(account), json.encodeToString(RemoteDownloadLocalState.serializer(), state))
    }

    private fun key(account: String) =
        "remote-download-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

    private companion object {
        val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
    }
}

private const val HEX = "0123456789abcdef"

fun newRemoteDeviceId(): String = buildString {
    append('d')
    repeat(24) { append(HEX[Random.nextInt(HEX.length)]) }
}

fun newRemoteRequestId(): String = buildString {
    append('r')
    repeat(24) { append(HEX[Random.nextInt(HEX.length)]) }
}
