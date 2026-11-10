package dev.piko.shared.lan

import kotlinx.serialization.Serializable

@Serializable
data class LanDeviceInfo(
    val protocol: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    val platform: String = "",
) {
    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

@Serializable
data class LanShareDescriptor(
    val shareId: String,
    val name: String,
    val fileCount: Int = 0,
)

/** 共享内的一个条目。[path] 相对共享根，文件夹不带结尾斜杠。 */
@Serializable
data class LanShareEntry(
    val path: String,
    val name: String,
    val folder: Boolean,
    val size: Long = 0,
    val modifiedMs: Long = 0,
    val mimeType: String = "",
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class LanShareListing(
    val entries: List<LanShareEntry> = emptyList(),
)

@Serializable
data class LanShareIndex(
    val shareId: String,
    val deviceName: String = "",
    val entries: List<LanShareEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class LanPairRequest(
    val code: String,
    val clientDeviceId: String,
    val clientName: String,
    val clientPlatform: String = "",
)

@Serializable
data class LanPairResponse(
    val token: String,
    val hostDeviceId: String,
    val hostName: String,
    val platform: String = "",
)

@Serializable
data class LanHostToken(
    val token: String,
    val clientDeviceId: String,
    val clientName: String,
    val createdAtMs: Long = 0,
    val account: String = "",
)

@Serializable
data class LanPeer(
    val hostDeviceId: String,
    val name: String,
    val address: String,
    val port: Int,
    val token: String,
    val lastSeenMs: Long = 0,
    val platform: String = "",
    val account: String = "",
)

@Serializable
data class LanLocalSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val peers: List<LanPeer> = emptyList(),
    val hostTokens: List<LanHostToken> = emptyList(),
) {
    companion object {
        const val DEFAULT_PORT = 47821
    }
}

class LanShareException(message: String, cause: Throwable? = null) : Exception(message, cause)
