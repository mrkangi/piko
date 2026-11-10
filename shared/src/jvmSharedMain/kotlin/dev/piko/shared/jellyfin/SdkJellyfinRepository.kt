package dev.piko.shared.jellyfin

import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.JellyfinLocation
import dev.piko.shared.data.JellyfinServer
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.TaskPhase
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile

/**
 * 官方 jellyfin-core SDK 的只读实现，接口用法对齐 Findroid：
 * 登录走 `authenticateUserByName`，媒体库走 `userViewApi.getUserViews`，
 * 目录与详情走 `libraryApi`，播放信息与直链走 `mediaInfoApi` / `videoApi`。
 */
class SdkJellyfinRepository internal constructor(
    private val jellyfin: Jellyfin,
    private val settings: JellyfinSettings,
    private val secrets: JellyfinSecrets?,
    scope: CoroutineScope,
) : JellyfinRepository {
    private val itemNames = ConcurrentHashMap<String, String>()
    private val itemParents = ConcurrentHashMap<String, String>()
    private val playbackInfos = ConcurrentHashMap<String, JellyfinPlayback>()
    private val apis = ConcurrentHashMap<String, CachedApi>()
    private val startedItems = ConcurrentHashMap.newKeySet<String>()

    override val servers: StateFlow<List<JellyfinServer>> = settings.flow
        .stateIn(scope, SharingStarted.Eagerly, settings.current())

    override fun serverNodes(): List<FileStat> = settings.current().map { server ->
        FileStat(
            id = JellyfinLocation(server.serverId).id,
            parentId = DeviceLocation.ROOT_ID,
            name = server.name,
            kind = FileKind.FOLDER,
            size = "0",
            phase = TaskPhase.COMPLETE,
            params = mapOf("jellyfin_server" to server.serverId),
        )
    }

    override fun serverName(serverId: String): String = settings.find(serverId)?.name ?: serverId

    override suspend fun authenticate(url: String, username: String, password: String, name: String): JellyfinServer {
        val base = normalizeUrl(url)
        val api = jellyfin.createApi(baseUrl = base)
        val result = try {
            api.authenticationApi
                .authenticateUserByName(AuthenticateUserByName(username = username, pw = password))
                .content
        } catch (error: InvalidStatusException) {
            throw if (error.status == 401 || error.status == 403) {
                JellyfinException(
                    tr("Jellyfin 用户名或密码不正确", "Incorrect Jellyfin username or password", "Jellyfin のユーザー名またはパスワードが正しくありません", "Jellyfin 사용자 이름 또는 비밀번호가 올바르지 않습니다", "Jellyfin 使用者名稱或密碼不正確"),
                    error,
                )
            } else {
                loginFailure(error)
            }
        } catch (error: ApiClientException) {
            throw loginFailure(error)
        }
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw JellyfinException(tr("Jellyfin 未返回访问令牌", "Jellyfin did not return an access token", "Jellyfin がアクセストークンを返しませんでした", "Jellyfin이 액세스 토큰을 반환하지 않았습니다", "Jellyfin 未傳回存取權杖"))
        val user = result.user
            ?: throw JellyfinException(tr("Jellyfin 返回了无法识别的登录结果", "Jellyfin returned an invalid sign-in result", "Jellyfin のサインイン結果を認識できません", "Jellyfin 로그인 결과를 인식할 수 없습니다", "Jellyfin 傳回無法辨識的登入結果"))
        val serverId = safeServerId(result.serverId.orEmpty().ifBlank { user.serverId.orEmpty() })
        val server = JellyfinServer(
            serverId = serverId,
            name = name.ifBlank { user.serverName.orEmpty().ifBlank { username.ifBlank { base } } },
            url = base,
            userId = user.id.toString(),
            username = username,
        )
        secrets?.saveJellyfinToken(serverId, token)
        settings.upsert(server)
        apis.remove(serverId)
        return server
    }

    override suspend fun remove(serverId: String) {
        settings.remove(serverId)
        secrets?.clearJellyfinToken(serverId)
        apis.remove(serverId)
        itemNames.keys.removeAll { JellyfinLocation.of(it)?.serverId == serverId }
        itemParents.keys.removeAll { JellyfinLocation.of(it)?.serverId == serverId }
        playbackInfos.keys.removeAll { JellyfinLocation.of(it)?.serverId == serverId }
        startedItems.removeAll { JellyfinLocation.of(it)?.serverId == serverId }
    }

    override suspend fun list(serverId: String, itemId: String): List<FileStat> = withContext(Dispatchers.IO) {
        val server = requireServer(serverId)
        val api = apiFor(server)
        val userId = requireUserId(server)
        val parent = itemId.takeIf { it.isNotBlank() }?.toUuid()
        val token = tokenFor(server) ?: throw JellyfinException(
            tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入"),
        )
        val items = try {
            if (parent == null) {
                runSuspendCatching {
                    api.userViewApi.getUserViews(userId = userId).content.items
                }.getOrElse { error ->
                    PikoLog.d(TAG, "Jellyfin 媒体库列表读取失败，回退到 Items：${error::class.simpleName}")
                    api.libraryApi.getItems(userId = userId).content.items
                }
            } else {
                api.libraryApi.getItems(
                    userId = userId,
                    parentId = parent,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    fields = LIST_FIELDS,
                    enableUserData = true,
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY),
                ).content.items
            }
        } catch (error: Throwable) {
            throw requestFailure(error)
        }
        val parentLocationId = JellyfinLocation(server.serverId, itemId).id
        items.mapNotNull { item -> item.toFileStat(server, parentLocationId, token) }
    }

    /** 从已列过的目录拼路径，供瀑布流/信息流定位用；没列到的祖先返回 null。 */
    override fun cachedPathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>? {
        val location = JellyfinLocation.of(fileId) ?: return null
        val server = settings.find(location.serverId) ?: return null
        val serverRoot = JellyfinLocation(server.serverId).id
        val target = if (containing && !location.directory) itemParents[fileId] ?: return null else fileId
        val chain = ArrayDeque<PikoPathBreadcrumb>()
        val visited = HashSet<String>()
        var cursor = target
        while (cursor != serverRoot) {
            if (!visited.add(cursor)) return null
            val name = itemNames[cursor] ?: return null
            chain.addFirst(PikoPathBreadcrumb(cursor, name))
            cursor = itemParents[cursor] ?: return null
        }
        return listOf(
            PikoDriveRepository.ROOT_BREADCRUMB,
            DeviceLocation.rootCrumb(),
            PikoPathBreadcrumb(serverRoot, server.name),
        ) + chain
    }

    override suspend fun playback(fileId: String): JellyfinPlayback = withContext(Dispatchers.IO) {
        val location = JellyfinLocation.of(fileId)
            ?: throw JellyfinException(tr("不是 Jellyfin 文件", "Not a Jellyfin file", "Jellyfin ファイルではありません", "Jellyfin 파일이 아닙니다", "不是 Jellyfin 檔案"))
        val server = requireServer(location.serverId)
        val api = apiFor(server)
        val userId = requireUserId(server)
        val token = tokenFor(server) ?: throw JellyfinException(
            tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入"),
        )
        val itemId = location.itemId.toUuid()
        val info = try {
            api.mediaInfoApi.getPostedPlaybackInfo(
                itemId,
                PlaybackInfoDto(
                    userId = userId,
                    deviceProfile = DEVICE_PROFILE,
                    maxStreamingBitrate = MAX_BITRATE,
                    enableDirectPlay = true,
                    enableDirectStream = true,
                    enableTranscoding = true,
                ),
            ).content
        } catch (error: Throwable) {
            throw requestFailure(error)
        }
        val source = info.mediaSources.firstOrNull()
            ?: throw JellyfinException(tr("Jellyfin 没有可播放的媒体源", "Jellyfin has no playable media source", "Jellyfin に再生可能なメディアがありません", "Jellyfin에 재생 가능한 미디어 소스가 없습니다", "Jellyfin 沒有可播放的媒體來源"))
        // 我们自带 libmpv，容器与编码基本都能解，永远先取原始文件（static）。
        // 以前只在 Jellyfin 认为支持直连时才取，遇到它不认的 HEVC/10bit 就退回服务端转码：
        // 转码 HEVC 在多数服务端跟不上实时，表现为「读速只有几百 KB/s、反复 loading」，换解码器也没用
        val directUrl = runSuspendCatching {
            api.videoApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                mediaSourceId = source.id,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val transcoding = source.transcodingUrl?.takeIf { it.isNotBlank() }
            ?.let { withAccessToken(absoluteUrl(server.url, it), token) }
        val url = directUrl?.let { withAccessToken(it, token) } ?: transcoding ?: runSuspendCatching {
            api.videoApi.getVideoStreamUrl(itemId = itemId, static = true, mediaSourceId = source.id)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { withAccessToken(it, token) }
            ?: throw JellyfinException(tr("Jellyfin 不支持直接播放，也没有可用的转码流", "Jellyfin cannot direct-play this file or provide a transcode stream", "Jellyfin は直接再生もトランスコードもできません", "Jellyfin에서 직접 재생하거나 트랜스코딩할 수 없습니다", "Jellyfin 不支援直接播放，也沒有可用的轉碼串流"))
        val video = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
        val container = source.container?.substringBefore(',')?.trim().orEmpty()
        val playback = JellyfinPlayback(
            url = url,
            name = itemNames[fileId] ?: source.name.orEmpty(),
            durationSeconds = ((source.runTimeTicks ?: 0L) / 10_000_000L).coerceAtLeast(0),
            sizeBytes = source.size ?: 0L,
            width = video?.width ?: 0,
            height = video?.height ?: 0,
            mimeType = if (container.isBlank()) "video/mp4" else "video/$container",
            isHls = directUrl == null || source.transcodingSubProtocol == MediaStreamProtocol.HLS,
            playSessionId = info.playSessionId.orEmpty(),
            mediaSourceId = source.id.orEmpty(),
            playMethod = if (directUrl != null) JellyfinPlaybackMethod.DIRECT_PLAY else JellyfinPlaybackMethod.TRANSCODE,
        )
        playbackInfos[fileId] = playback
        playback
    }

    override suspend fun originalImageUrl(fileId: String): String? = withContext(Dispatchers.IO) {
        val location = JellyfinLocation.of(fileId) ?: return@withContext null
        val server = settings.find(location.serverId) ?: return@withContext null
        val itemId = location.itemId.toUuidOrNull() ?: return@withContext null
        val token = tokenFor(server) ?: return@withContext null
        val api = apiFor(server)
        api.imageApi.getItemImageUrl(
            itemId = itemId,
            imageType = ImageType.PRIMARY,
            maxWidth = 1920,
            quality = 90,
        ) + "&api_key=" + token
    }

    override suspend fun pathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>? = withContext(Dispatchers.IO) {
        val location = JellyfinLocation.of(fileId) ?: return@withContext null
        val server = settings.find(location.serverId) ?: return@withContext null
        val root = listOf(
            PikoDriveRepository.ROOT_BREADCRUMB,
            DeviceLocation.rootCrumb(),
            PikoPathBreadcrumb(JellyfinLocation(server.serverId).id, server.name),
        )
        if (location.itemId.isBlank()) return@withContext root
        val api = apiFor(server)
        val userId = requireUserId(server)
        val itemId = location.itemId.toUuid()
        val target = if (containing && !location.directory) {
            runSuspendCatching { api.libraryApi.getItem(itemId, userId).content.parentId }.getOrNull()
                ?: return@withContext null
        } else {
            itemId
        }
        val ancestors = runSuspendCatching {
            api.libraryApi.getAncestors(target, userId).content
        }.getOrDefault(emptyList())
        val chain = ancestors.map { item ->
            PikoPathBreadcrumb(JellyfinLocation(server.serverId, item.id.toString()).id, item.name.orEmpty())
        }.toMutableList()
        val targetLocation = JellyfinLocation(server.serverId, target.toString())
        if (chain.none { it.id == targetLocation.id }) {
            val targetName = itemNames[targetLocation.id]
                ?: runSuspendCatching { api.libraryApi.getItem(target, userId).content.name.orEmpty() }.getOrNull()
                ?: return@withContext null
            chain += PikoPathBreadcrumb(targetLocation.id, targetName)
        }
        root + chain
    }

    override fun logIdentity(fileId: String): String? = JellyfinLocation.logIdentity(fileId)

    override fun cachedParentOf(fileId: String): String? = itemParents[fileId]

    override suspend fun favorites(): List<FileStat> = withContext(Dispatchers.IO) {
        settings.current().flatMap { server ->
            runSuspendCatching { favoriteItems(server) }.getOrElse { error ->
                PikoLog.w(TAG, "读取收藏失败：${error::class.simpleName}")
                emptyList()
            }
        }.distinctBy { it.id }
    }

    override suspend fun playbackHistory(): List<FileStat> = withContext(Dispatchers.IO) {
        settings.current().flatMap { server ->
            runSuspendCatching { historyItems(server) }.getOrElse { error ->
                PikoLog.w(TAG, "读取播放记录失败：${error::class.simpleName}")
                emptyList()
            }
        }.distinctBy { it.id }
            .sortedWith(compareByDescending { it.params["last_played"].orEmpty() })
            .take(200)
    }

    override suspend fun resumePosition(fileId: String): Long? = withContext(Dispatchers.IO) {
        val location = JellyfinLocation.of(fileId)
            ?.takeIf { !it.directory }
            ?: return@withContext null
        val server = settings.find(location.serverId) ?: return@withContext null
        val api = apiFor(server)
        val userData = runSuspendCatching {
            api.libraryApi.getItem(location.itemId.toUuid(), requireUserId(server)).content.userData
        }.getOrNull() ?: return@withContext null
        val ticks = userData.playbackPositionTicks ?: return@withContext null
        (ticks / TICKS_PER_MILLI).takeIf { it > 0 && userData.played != true }
    }

    override suspend fun reportPlayback(
        fileId: String,
        positionMillis: Long,
        durationMillis: Long,
        stopped: Boolean,
    ) = withContext(Dispatchers.IO) {
        val location = JellyfinLocation.of(fileId)
            ?.takeIf { !it.directory }
            ?: return@withContext
        if (positionMillis <= 0L || durationMillis <= 0L) return@withContext
        val server = settings.find(location.serverId) ?: return@withContext
        val api = apiFor(server)
        val itemId = location.itemId.toUuid()
        val positionTicks = positionMillis * TICKS_PER_MILLI
        val playback = playbackInfos[fileId]
        val playMethod = if (playback?.playMethod == JellyfinPlaybackMethod.TRANSCODE) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY
        val mediaSourceId = playback?.mediaSourceId?.takeIf { it.isNotBlank() }
        val playSessionId = playback?.playSessionId?.takeIf { it.isNotBlank() }
        if (startedItems.add(fileId)) {
            api.sessionApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        }
        if (stopped) {
            startedItems.remove(fileId)
            playbackInfos.remove(fileId)
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    positionTicks = positionTicks,
                    playSessionId = playSessionId,
                    failed = false,
                ),
            )
        } else {
            api.sessionApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    positionTicks = positionTicks,
                ),
            )
        }
    }

    override suspend fun setFavorite(fileIds: Collection<String>, favorite: Boolean) = withContext(Dispatchers.IO) {
        fileIds.forEach { fileId ->
            val location = JellyfinLocation.of(fileId) ?: return@forEach
            val server = settings.find(location.serverId) ?: return@forEach
            val api = apiFor(server)
            val itemId = location.itemId.toUuid()
            if (favorite) api.userDataApi.markFavoriteItem(itemId) else api.userDataApi.unmarkFavoriteItem(itemId)
        }
    }

    private suspend fun apiFor(server: JellyfinServer): ApiClient {
        val token = tokenFor(server)
            ?: throw JellyfinException(tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入"))
        val base = normalizeUrl(server.url)
        val cached = apis[server.serverId]
        if (cached != null && cached.token == token && cached.baseUrl == base) return cached.api
        val created = jellyfin.createApi(baseUrl = base, accessToken = token)
        apis[server.serverId] = CachedApi(token, base, created)
        return created
    }

    private suspend fun tokenFor(server: JellyfinServer): String? =
        secrets?.loadJellyfinToken(server.serverId)?.takeIf { it.isNotBlank() }

    private suspend fun favoriteItems(server: JellyfinServer): List<FileStat> {
        val api = apiFor(server)
        val userId = requireUserId(server)
        val token = tokenFor(server) ?: throw JellyfinException(
            tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入"),
        )
        val items = api.libraryApi.getItems(
            userId = userId,
            filters = listOf(ItemFilter.IS_FAVORITE),
            includeItemTypes = COLLECTION_TYPES,
            recursive = true,
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = LIST_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            limit = 1000,
        ).content.items
        val root = JellyfinLocation(server.serverId).id
        return items.mapNotNull { it.toFileStat(server, root, token) }
    }

    private suspend fun historyItems(server: JellyfinServer): List<FileStat> {
        val api = apiFor(server)
        val userId = requireUserId(server)
        val token = tokenFor(server) ?: throw JellyfinException(
            tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入"),
        )
        val resume = runSuspendCatching {
            api.libraryApi.getResumeItems(
                userId = userId,
                limit = 100,
                includeItemTypes = PLAYABLE_TYPES,
                mediaTypes = listOf(MediaType.VIDEO),
                fields = LIST_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
            ).content.items
        }.getOrDefault(emptyList())
        val played = runSuspendCatching {
            api.libraryApi.getItems(
                userId = userId,
                filters = listOf(ItemFilter.IS_PLAYED),
                includeItemTypes = PLAYABLE_TYPES,
                recursive = true,
                sortBy = listOf(ItemSortBy.DATE_PLAYED),
                sortOrder = listOf(SortOrder.DESCENDING),
                fields = LIST_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY),
                limit = 100,
            ).content.items
        }.getOrDefault(emptyList())
        val root = JellyfinLocation(server.serverId).id
        return (resume + played).distinctBy { it.id }.mapNotNull { it.toFileStat(server, root, token) }
    }

    private fun BaseItemDto.toFileStat(server: JellyfinServer, parentLocationId: String, token: String): FileStat? {
        val itemName = name?.takeIf { it.isNotBlank() } ?: return null
        val folder = isFolder == true || type in FOLDER_TYPES
        val source = mediaSources?.firstOrNull()
        val video = source?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
        val duration = (source?.runTimeTicks ?: runTimeTicks)?.div(10_000_000.0)
        val label = when {
            type == BaseItemKind.EPISODE && indexNumber != null -> "E${indexNumber.toString().padStart(2, '0')} $itemName"
            else -> itemName
        }
        val containerName = (source?.container ?: container)?.substringBefore(',')?.trim().orEmpty()
        val mime = when {
            folder -> ""
            mediaType == MediaType.VIDEO || type in VIDEO_TYPES ->
                if (containerName.isBlank()) "video/mp4" else "video/$containerName"
            mediaType == MediaType.AUDIO || type == BaseItemKind.AUDIO ->
                if (containerName.isBlank()) "audio/mpeg" else "audio/$containerName"
            mediaType == MediaType.PHOTO || type == BaseItemKind.PHOTO -> "image/jpeg"
            else -> ""
        }
        val location = JellyfinLocation(server.serverId, id.toString(), folder)
        val tag = imageTags?.get(ImageType.PRIMARY)
        val user = userData
        val stars = if (user?.isFavorite == true) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList()
        val parent = parentId?.let { JellyfinLocation(server.serverId, it.toString()).id }
            ?: parentLocationId
        val positionSeconds = user?.playbackPositionTicks?.div(10_000_000.0)?.takeIf { it > 0 }
        val lastPlayed = user?.lastPlayedDate?.toUtcIso()
        val stat = FileStat(
            id = location.id,
            parentId = parent,
            name = label,
            kind = if (folder) FileKind.FOLDER else FileKind.FILE,
            size = (source?.size ?: 0L).toString(),
            mimeType = mime,
            modifiedTime = "",
            createdTime = "",
            phase = TaskPhase.COMPLETE,
            fileExtension = if (containerName.isBlank()) "" else ".$containerName",
            thumbnailLink = if (!folder && tag != null) thumbnailUrl(server, id, tag, token) else "",
            tags = stars,
            params = buildMap {
                put("jellyfin_server", server.serverId)
                put("jellyfin_item", id.toString())
                if (folder) put("jellyfin_folder", "1")
                if (duration != null && duration > 0) put("duration", duration.toString())
                if (positionSeconds != null) put("playback_position", positionSeconds.toString())
                if (user?.played == true) put("played", "1")
                if (lastPlayed != null) put("last_played", lastPlayed)
                video?.width?.let { put("width", it.toString()) }
                video?.height?.let { put("height", it.toString()) }
                if (primaryImageAspectRatio != null) put("aspect_ratio", primaryImageAspectRatio.toString())
            },
        )
        itemNames[stat.id] = stat.name
        itemParents[stat.id] = parent
        return stat
    }

    private fun thumbnailUrl(server: JellyfinServer, itemId: UUID, tag: String, token: String): String = buildString {
        append(normalizeUrl(server.url))
        append("/Items/").append(itemId).append("/Images/Primary?maxWidth=480&quality=90")
        if (tag.isNotBlank()) append("&tag=").append(tag)
        append("&api_key=").append(token)
    }

    private fun requireServer(serverId: String): JellyfinServer =
        settings.find(serverId) ?: throw JellyfinException(
            tr("Jellyfin 服务器已移除", "Jellyfin server was removed", "Jellyfin サーバーが削除されました", "Jellyfin 서버가 제거되었습니다", "Jellyfin 伺服器已移除"),
        )

    private fun requireUserId(server: JellyfinServer): UUID {
        val value = server.userId.takeIf { it.isNotBlank() }
            ?: throw JellyfinException(tr("Jellyfin 登录信息不完整，请重新登录", "Jellyfin sign-in data is incomplete; sign in again", "Jellyfin のサインイン情報が不完全です。再ログインしてください", "Jellyfin 로그인 정보가 불완전합니다. 다시 로그인하세요", "Jellyfin 登入資訊不完整，請重新登入"))
        return value.toUuid()
    }

    private fun String.toUuid(): UUID = toUuidOrNull()
        ?: throw JellyfinException(tr("Jellyfin 返回了无法识别的标识", "Jellyfin returned an invalid identifier", "Jellyfin の識別子を認識できません", "Jellyfin 식별자를 인식할 수 없습니다", "Jellyfin 傳回無法辨識的識別碼"))

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun LocalDateTime.toUtcIso(): String = atZone(ZoneOffset.UTC).toOffsetDateTime().toString()

    private fun loginFailure(error: ApiClientException): JellyfinException {
        // 地址写错（404）、反向代理拒绝（400/405）与服务器内部错误都落到这里；只记类名没法定位，
        // 把 HTTP 状态一并带出来，用户反馈日志时才能区分是路径、权限还是服务端问题。
        val status = (error as? InvalidStatusException)?.status
        PikoLog.w(TAG, "登录失败：${error::class.simpleName}${status?.let { "（HTTP $it）" }.orEmpty()}")
        return JellyfinException(
            if (status != null) {
                tr(
                    "Jellyfin 登录失败（HTTP $status）",
                    "Jellyfin sign-in failed (HTTP $status)",
                    "Jellyfin へのサインインに失敗しました（HTTP $status）",
                    "Jellyfin 로그인에 실패했습니다(HTTP $status)",
                    "Jellyfin 登入失敗（HTTP $status）",
                )
            } else {
                tr("Jellyfin 登录失败", "Jellyfin sign-in failed", "Jellyfin へのサインインに失敗しました", "Jellyfin 로그인에 실패했습니다", "Jellyfin 登入失敗")
            },
            error,
        )
    }

    private fun requestFailure(error: Throwable): JellyfinException {
        val status = (error as? InvalidStatusException)?.status
        PikoLog.w(TAG, "请求失败：${status ?: error::class.simpleName}")
        val message = when {
            status == 401 || status == 403 -> tr("Jellyfin 登录已失效，请重新登录", "Jellyfin session expired; sign in again", "Jellyfin のセッションが切れました。再ログインしてください", "Jellyfin 세션이 만료되었습니다. 다시 로그인하세요", "Jellyfin 登入已失效，請重新登入")
            status != null -> tr("Jellyfin 请求失败（HTTP $status）", "Jellyfin request failed (HTTP $status)", "Jellyfin リクエストに失敗しました（HTTP $status）", "Jellyfin 요청 실패(HTTP $status)", "Jellyfin 請求失敗（HTTP $status）")
            else -> tr("无法连接 Jellyfin 服务器", "Unable to reach the Jellyfin server", "Jellyfin サーバーに接続できません", "Jellyfin 서버에 연결할 수 없습니다", "無法連線 Jellyfin 伺服器")
        }
        return JellyfinException(message, error)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))) {
            throw JellyfinException(tr("Jellyfin 地址必须以 http:// 或 https:// 开头", "Jellyfin URL must start with http:// or https://", "Jellyfin の URL は http:// または https:// で始めてください", "Jellyfin 주소는 http:// 또는 https://로 시작해야 합니다", "Jellyfin 位址必須以 http:// 或 https:// 開頭"))
        }
        return trimmed
    }

    private fun absoluteUrl(base: String, value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else normalizeUrl(base) + "/" + value.trimStart('/')

    private fun withAccessToken(url: String, token: String): String =
        if (url.contains("api_key=")) url else url + (if ('?' in url) "&" else "?") + "api_key=" + token

    /** Jellyfin 的授权头是 HTTP 头，只能放 ASCII；设备名里的「·」等字符会让请求直接失败。 */
    private fun safeServerId(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        return cleaned.ifBlank { UUID.randomUUID().toString() }
    }

    private data class CachedApi(val token: String, val baseUrl: String, val api: ApiClient)

    private companion object {
        const val TAG = "Jellyfin"
        const val MAX_BITRATE = 1_000_000_000
        const val TICKS_PER_MILLI = 10_000L

        val LIST_FIELDS = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.DATE_CREATED,
        )

        val PLAYABLE_TYPES = listOf(
            BaseItemKind.MOVIE,
            BaseItemKind.EPISODE,
            BaseItemKind.VIDEO,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.TRAILER,
            BaseItemKind.RECORDING,
        )

        val COLLECTION_TYPES = PLAYABLE_TYPES + listOf(
            BaseItemKind.SERIES,
            BaseItemKind.SEASON,
            BaseItemKind.BOX_SET,
            BaseItemKind.FOLDER,
        )

        val FOLDER_TYPES = setOf(
            BaseItemKind.AGGREGATE_FOLDER,
            BaseItemKind.BOX_SET,
            BaseItemKind.COLLECTION_FOLDER,
            BaseItemKind.FOLDER,
            BaseItemKind.GENRE,
            BaseItemKind.MANUAL_PLAYLISTS_FOLDER,
            BaseItemKind.MUSIC_ALBUM,
            BaseItemKind.MUSIC_ARTIST,
            BaseItemKind.MUSIC_GENRE,
            BaseItemKind.PERSON,
            BaseItemKind.PHOTO_ALBUM,
            BaseItemKind.PLAYLIST,
            BaseItemKind.PLAYLISTS_FOLDER,
            BaseItemKind.SEASON,
            BaseItemKind.SERIES,
            BaseItemKind.STUDIO,
            BaseItemKind.USER_VIEW,
            BaseItemKind.YEAR,
        )

        val VIDEO_TYPES = setOf(
            BaseItemKind.EPISODE,
            BaseItemKind.MOVIE,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.RECORDING,
            BaseItemKind.TRAILER,
            BaseItemKind.VIDEO,
        )

        val DEVICE_PROFILE = DeviceProfile(
            name = "Piko",
            maxStaticBitrate = MAX_BITRATE,
            maxStreamingBitrate = MAX_BITRATE,
            codecProfiles = emptyList(),
            containerProfiles = emptyList(),
            // 播放端是 libmpv + FFmpeg，容器与编码面比浏览器宽得多。这里必须把能直接播的都列出来：
            // 留空时 Jellyfin 会把 HEVC、10bit 这类判成不能直连，退回服务端实时转码，读速立刻掉到几百 KB/s
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = "mp4,m4v,mkv,webm,ts,m2ts,mov,avi,flv,wmv,mpg,mpeg,ogv,3gp",
                    audioCodec = "aac,ac3,eac3,flac,opus,mp3,vorbis,dts,truehd,pcm,alac,mp2,wma",
                    videoCodec = "h264,hevc,vp8,vp9,av1,mpeg4,mpeg2video,vc1,wmv3,msmpeg4v3",
                    type = DlnaProfileType.VIDEO,
                ),
                DirectPlayProfile(
                    container = "mp3,flac,aac,m4a,ogg,opus,wav,wma",
                    audioCodec = "aac,flac,opus,mp3,vorbis,pcm,alac,wma",
                    videoCodec = null,
                    type = DlnaProfileType.AUDIO,
                ),
            ),
            // 真解不了时仍留一路服务端转码，统一转成 H.264/AAC 的 HLS
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts",
                    type = DlnaProfileType.VIDEO,
                    videoCodec = "h264",
                    audioCodec = "aac,ac3,eac3,mp3",
                    protocol = MediaStreamProtocol.HLS,
                    conditions = emptyList(),
                ),
            ),
            subtitleProfiles = listOf(
                SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
            ),
        )
    }
}
