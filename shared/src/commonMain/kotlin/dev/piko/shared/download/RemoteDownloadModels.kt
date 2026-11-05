package dev.piko.shared.download

import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.PikoLog
import dev.piko.shared.sync.DriveSettingsStore
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Clock

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
    val error: String? = null,
    val updatedAtMs: Long = 0L,
)

/**
 * 一台设备在 `.piko` 里的完整账本。每个设备只写自己的文件：
 * requests 是它发出的请求，states 是它作为目标设备执行过的任务状态。
 */
@Serializable
data class RemoteDeviceDocument(
    val version: Int = 1,
    val deviceId: String,
    val name: String,
    val platform: String,
    val receiverEnabled: Boolean = false,
    val updatedAtMs: Long = 0L,
    val requests: List<RemoteDownloadRequest> = emptyList(),
    val states: List<RemoteDownloadState> = emptyList(),
)

/** 界面用的一台已发现设备。 */
data class RemoteDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
    val receiverEnabled: Boolean,
    val updatedAtMs: Long,
)

/** 界面用的一条远程下载记录；源设备与目标设备看到的是同一条，方向不同。 */
data class RemoteDownloadItem(
    val request: RemoteDownloadRequest,
    val state: RemoteDownloadState?,
    val sourceDeviceName: String,
    val targetDeviceName: String,
    val outgoing: Boolean,
    val status: RemoteDownloadStatus,
) {
    val requestId: String get() = request.requestId
    val fileName: String get() = request.fileName
    val totalBytes: Long get() = state?.totalBytes?.takeIf { it > 0L } ?: request.totalBytes
    val downloadedBytes: Long get() = state?.downloadedBytes ?: 0L
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

    suspend fun findCompletedLocalPath(file: FileStat): String?
}

/** 远程设备账本的存储边界。生产实现读写网盘 `.piko`，测试用内存实现。 */
interface RemoteDownloadStore {
    suspend fun readAll(account: String): List<RemoteDeviceDocument>

    suspend fun write(account: String, document: RemoteDeviceDocument)
}

/**
 * `.piko/remote-<deviceId>-<timestamp>.json` 的存储实现。
 * 每个设备只写自己的前缀，因此多台设备并发写不会互相覆盖；读取时按 `remote-` 前缀汇总。
 */
class DriveRemoteDownloadStore(
    clients: PikoClientProvider,
    driveRepo: PikoDriveRepository,
    deviceId: String,
) : RemoteDownloadStore {
    private val reader = DriveSettingsStore(clients, driveRepo, PREFIX)
    private val writer = DriveSettingsStore(clients, driveRepo, "$PREFIX$deviceId-")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun readAll(account: String): List<RemoteDeviceDocument> =
        reader.readAll(account, PREFIX).mapNotNull { (name, text) ->
            runCatching { json.decodeFromString(RemoteDeviceDocument.serializer(), text) }
                .onFailure { PikoLog.w(TAG, "读取远程设备文件 $name 失败", it) }
                .getOrNull()
        }

    override suspend fun write(account: String, document: RemoteDeviceDocument) {
        writer.write(
            account,
            json.encodeToString(RemoteDeviceDocument.serializer(), document),
            Clock.System.now().toEpochMilliseconds(),
        )
    }

    private companion object {
        const val PREFIX = "remote-"
        const val TAG = "RemoteDownload"
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
