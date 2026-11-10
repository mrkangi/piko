package dev.piko.shared.jellyfin

import dev.piko.shared.data.JellyfinServer
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.StateFlow

enum class JellyfinPlaybackMethod { DIRECT_PLAY, TRANSCODE }

/** Jellyfin 播放需要的信息；[url] 已带访问令牌，只交给播放器，不写日志。 */
data class JellyfinPlayback(
    val url: String,
    val name: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val isHls: Boolean = false,
    val playSessionId: String = "",
    val mediaSourceId: String = "",
    val playMethod: JellyfinPlaybackMethod = JellyfinPlaybackMethod.DIRECT_PLAY,
)

class JellyfinException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Jellyfin 只读来源的统一接口；平台实现使用官方 jellyfin-core SDK。 */
interface JellyfinRepository {
    val servers: StateFlow<List<JellyfinServer>>

    fun serverNodes(): List<FileStat>
    fun serverName(serverId: String): String

    suspend fun authenticate(url: String, username: String, password: String, name: String = ""): JellyfinServer
    suspend fun remove(serverId: String)

    suspend fun list(serverId: String, itemId: String): List<FileStat>
    fun cachedPathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>?
    fun cachedParentOf(fileId: String): String?
    suspend fun playback(fileId: String): JellyfinPlayback
    suspend fun originalImageUrl(fileId: String): String?
    suspend fun pathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>?
    fun logIdentity(fileId: String): String?

    /** 所有服务器上加了收藏的条目；单台服务器失败不影响其余。 */
    suspend fun favorites(): List<FileStat>

    /** 所有服务器最近播放过的条目，按最后播放时间倒序，带续播位置。 */
    suspend fun playbackHistory(): List<FileStat>

    /** 该条目的续播位置，毫秒；没看过或已看完为 null。 */
    suspend fun resumePosition(fileId: String): Long?

    /** 上报开始/进度/停止，与 Findroid 使用同一组 Session API。 */
    suspend fun reportPlayback(fileId: String, positionMillis: Long, durationMillis: Long, stopped: Boolean)

    suspend fun setFavorite(fileIds: Collection<String>, favorite: Boolean)
}
