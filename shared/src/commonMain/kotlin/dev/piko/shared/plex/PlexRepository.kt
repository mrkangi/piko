package dev.piko.shared.plex

import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PlexAccount
import dev.piko.shared.data.PlexHomeProfile
import dev.piko.shared.data.PlexServer
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Plex 直读播放信息；[url] 已带访问令牌，只交给播放器，不写日志。 */
data class PlexPlayback(
    val url: String,
    val name: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val ratingKey: String,
    val sessionIdentifier: String,
    val mediaSourceId: String = "",
)

@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val authUrl: String,
)

sealed interface PlexPinPoll {
    data object Waiting : PlexPinPoll
    data class Claimed(val token: String) : PlexPinPoll
    data object Expired : PlexPinPoll
}

data class PlexAccountIdentity(
    val uuid: String,
    val username: String,
    val email: String,
    val thumb: String,
)

data class PlexChosenProfile(
    val profile: PlexHomeProfile?,
    val token: String,
)

data class PlexDiscoveredServer(
    val server: PlexServer,
    val accessToken: String,
)

class PlexException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PlexHomePinException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Plex 只读媒体服务器。目录与播放接口与 Jellyfin 平行，登录与 Home 用户由这里统一管理。 */
interface PlexRepository {
    val accounts: StateFlow<List<PlexAccount>>

    fun serverNodes(): List<FileStat>
    fun serverName(mountId: String): String

    suspend fun createPin(): PlexPin
    suspend fun pollPin(pinId: Long): PlexPinPoll
    suspend fun identity(token: String): PlexAccountIdentity
    suspend fun homeProfiles(token: String): List<PlexHomeProfile>
    suspend fun switchProfile(accountToken: String, profileUuid: String, pin: String? = null): String
    suspend fun discoverServers(token: String): List<PlexDiscoveredServer>
    suspend fun addAccount(
        accountToken: String,
        identity: PlexAccountIdentity,
        profiles: List<PlexHomeProfile>,
        profile: PlexHomeProfile?,
        profileToken: String,
        servers: List<PlexDiscoveredServer>,
    ): PlexAccount

    suspend fun refreshAccount(accountUuid: String): Result<PlexAccount>
    suspend fun setActiveProfile(accountUuid: String, profileUuid: String, pin: String? = null): Result<Unit>
    suspend fun removeAccount(accountUuid: String)
    suspend fun removeServer(mountId: String)

    suspend fun list(mountId: String, node: String): List<FileStat>
    fun cachedPathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>?
    fun cachedParentOf(fileId: String): String?
    suspend fun pathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>?
    suspend fun playback(fileId: String): PlexPlayback
    suspend fun originalImageUrl(fileId: String): String?
    fun logIdentity(fileId: String): String?

    suspend fun playbackHistory(): List<FileStat>
    suspend fun resumePosition(fileId: String): Long?
    suspend fun reportPlayback(fileId: String, positionMillis: Long, durationMillis: Long, stopped: Boolean)
}

/** 每个 Plex 账号的设备级凭据包；普通配置不保存任何令牌。 */
interface PlexSecrets {
    suspend fun loadPlexCredentials(accountUuid: String): String? = null
    suspend fun savePlexCredentials(accountUuid: String, serialized: String) {}
    suspend fun clearPlexCredentials(accountUuid: String) {}
}

@Serializable
data class PlexCredentialBundle(
    val accountToken: String = "",
    val profileTokens: Map<String, String> = emptyMap(),
    val serverTokens: Map<String, String> = emptyMap(),
)
