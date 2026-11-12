package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.serialization.Serializable

/** 一台已添加 Plex 服务器的设备本地配置；访问令牌不在这里，见 [dev.piko.shared.plex.PlexSecrets]。 */
@Serializable
data class PlexServer(
    val mountId: String,
    val machineIdentifier: String,
    val name: String,
    val owned: Boolean = false,
    val profileUuid: String = "",
    val connections: List<PlexConnection> = emptyList(),
    val preferredUrl: String = "",
)

/** Plex 账号、Home 用户与当前启用的服务器集合。 */
@Serializable
data class PlexAccount(
    val accountUuid: String,
    val username: String = "",
    val email: String = "",
    val thumb: String = "",
    val activeProfileUuid: String = "",
    val profiles: List<PlexHomeProfile> = emptyList(),
    val servers: List<PlexServer> = emptyList(),
)

@Serializable
data class PlexHomeProfile(
    val uuid: String,
    val title: String,
    val thumb: String = "",
    val protected: Boolean = false,
    val admin: Boolean = false,
    val restricted: Boolean = false,
    val guest: Boolean = false,
)

@Serializable
data class PlexConnection(
    val protocol: String,
    val address: String,
    val port: Int,
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
    val ipv6: Boolean = false,
)

/** Plex 的虚拟位置。空 [node] 表示服务器根，`s<id>` 是媒体库，`m<ratingKey>` 是条目。 */
data class PlexLocation(
    val mountId: String,
    val node: String = "",
    val directory: Boolean = true,
) {
    init {
        require(mountId.matches(IDENTIFIER))
        require(node.none { it == '/' || it == '\\' || it == '\u0000' })
    }

    val sectionId: String? get() = node.takeIf { it.startsWith("s") }?.drop(1)?.takeIf(String::isNotBlank)
    val itemId: String? get() = node.takeIf { it.startsWith("m") }?.drop(1)?.takeIf(String::isNotBlank)

    val id: String get() = (if (directory) DIR_PREFIX else FILE_PREFIX) + mountId + "/" + node

    companion object {
        const val DIR_PREFIX = "piko-plex-dir:"
        const val FILE_PREFIX = "piko-plex-file:"
        private val IDENTIFIER = Regex("[a-zA-Z0-9_-]{1,80}")

        fun isPlex(id: String): Boolean = id.startsWith(DIR_PREFIX) || id.startsWith(FILE_PREFIX)

        fun of(id: String): PlexLocation? {
            if (!isPlex(id)) return null
            return runCatching {
                val directory = id.startsWith(DIR_PREFIX)
                val raw = id.removePrefix(if (directory) DIR_PREFIX else FILE_PREFIX)
                require('/' in raw)
                PlexLocation(
                    mountId = raw.substringBefore('/'),
                    node = raw.substringAfter('/'),
                    directory = directory,
                ).also { require(it.id == id) }
            }.getOrNull()
        }

        fun logIdentity(id: String): String? = if (isPlex(id)) {
            of(id)?.let { "Plex ${it.mountId}" } ?: "Plex ?"
        } else null
    }
}

val FileStat.isPlex: Boolean get() = PlexLocation.isPlex(id)

/** Plex 条目的续播进度（0..1），没有进度或时长不明时为 null。 */
val FileStat.plexPlaybackFraction: Float?
    get() {
        if (!isPlex) return null
        val position = params["playback_position"]?.toDoubleOrNull() ?: return null
        val duration = params["duration"]?.toDoubleOrNull() ?: return null
        if (position <= 0.0 || duration <= 0.0) return null
        return (position / duration).toFloat().coerceIn(0f, 1f).takeIf { it > 0f }
    }
