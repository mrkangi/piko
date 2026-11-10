package dev.piko.shared.download

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class RemoteRealtimeConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** 一台设备在实时通道上的存在状态。presence 是 retained 消息，心跳只更新 ts。 */
@Serializable
data class RemotePresence(
    val deviceId: String,
    val name: String,
    val platform: String = "",
    val receiverEnabled: Boolean = false,
    val online: Boolean = true,
    val updatedAtMs: Long = 0L,
)

/** 远程下载的实时消息。请求、状态和 presence 都走 MQTT，不再读写网盘账本。 */
sealed interface RemoteRealtimeEvent {
    /** 设备上线、下线或心跳。 */
    data class Presence(val presence: RemotePresence) : RemoteRealtimeEvent

    /** 源设备发来的下载请求，包含取消与暂停/继续/重试命令。 */
    data class Request(val request: RemoteDownloadRequest) : RemoteRealtimeEvent

    /** 目标设备写回的执行状态。 */
    data class State(val state: RemoteDownloadState) : RemoteRealtimeEvent
}

/**
 * 远程下载的唯一传输层。没有 MQTT 连接时远程下载不可用，不再回退到网盘轮询。
 */
interface RemoteRealtimeTransport {
    val events: SharedFlow<RemoteRealtimeEvent>
    val connected: StateFlow<Boolean>

    fun start(account: String, deviceId: String, deviceName: String, platform: String)
    fun stop()
    fun updateConfig(config: RemoteRealtimeConfig?)
    suspend fun testConnection(config: RemoteRealtimeConfig): Result<Unit>
    fun publishPresence(receiverEnabled: Boolean)
    fun publishRequest(targetDeviceId: String, request: RemoteDownloadRequest): Boolean
    fun publishState(sourceDeviceId: String, state: RemoteDownloadState): Boolean
}
