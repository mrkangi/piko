package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.Serializable

/** Jellyfin 服务器配置。令牌不进这里，见 [JellyfinSecrets]。 */
@Serializable
data class JellyfinServer(
    val serverId: String,
    val name: String,
    val url: String,
    val userId: String = "",
    val username: String = "",
)

/** Jellyfin 的虚拟位置。itemId 为空表示服务器根节点，子项按 Jellyfin 的 item id 定位。 */
data class JellyfinLocation(
    val serverId: String,
    val itemId: String = "",
    val directory: Boolean = true,
) {
    init {
        require(serverId.matches(IDENTIFIER))
        require(itemId.none { it == '/' || it == '\\' || it == '\u0000' })
    }

    val id: String get() = (if (directory) DIR_PREFIX else FILE_PREFIX) + serverId + "/" + itemId

    companion object {
        const val DIR_PREFIX = "piko-jellyfin-dir:"
        const val FILE_PREFIX = "piko-jellyfin-file:"
        private val IDENTIFIER = Regex("[a-zA-Z0-9_-]{1,80}")

        fun isJellyfin(id: String): Boolean = id.startsWith(DIR_PREFIX) || id.startsWith(FILE_PREFIX)

        fun of(id: String): JellyfinLocation? {
            if (!isJellyfin(id)) return null
            return runCatching {
                val directory = id.startsWith(DIR_PREFIX)
                val raw = id.removePrefix(if (directory) DIR_PREFIX else FILE_PREFIX)
                require('/' in raw)
                val serverId = raw.substringBefore('/')
                val itemId = raw.substringAfter('/')
                JellyfinLocation(serverId, itemId, directory).also { require(it.id == id) }
            }.getOrNull()
        }

        /** 日志里不能出现服务器内路径，只留服务器标识。 */
        fun logIdentity(id: String): String? = if (isJellyfin(id)) {
            of(id)?.let { "Jellyfin ${it.serverId}" } ?: "Jellyfin ?"
        } else null
    }
}

val FileStat.isJellyfin: Boolean get() = JellyfinLocation.isJellyfin(id)

/** Jellyfin 条目的续播进度（0..1），没有进度或时长不明时为 null。 */
val FileStat.jellyfinPlaybackFraction: Float?
    get() {
        if (!isJellyfin) return null
        val position = params["playback_position"]?.toDoubleOrNull() ?: return null
        val duration = params["duration"]?.toDoubleOrNull() ?: return null
        if (position <= 0.0 || duration <= 0.0) return null
        return (position / duration).toFloat().coerceIn(0f, 1f).takeIf { it > 0f }
    }
