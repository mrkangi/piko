package dev.piko.shared.plex

import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PlexAccount
import dev.piko.shared.data.PlexConnection
import dev.piko.shared.data.PlexHomeProfile
import dev.piko.shared.data.PlexLocation
import dev.piko.shared.data.PlexServer
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plex 的只读目录与播放实现。
 *
 * 这里不调用 Plex 的 Transcode/Direct Stream 接口：播放 URL 永远直接指向
 * `Media.Part.key`，由 Piko 的本机 HTTP 代理负责读取和 Range。
 */
internal class HttpPlexRepository(
    private val api: PlexApi,
    private val settings: PlexSettings,
    private val secrets: PlexSecrets?,
    private val scope: CoroutineScope,
) : PlexRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val bundleCache = ConcurrentHashMap<String, PlexCredentialBundle>()
    private val bundleLock = Mutex()
    private val resolvedBases = ConcurrentHashMap<String, String>()
    private val itemNames = ConcurrentHashMap<String, String>()
    private val itemParents = ConcurrentHashMap<String, String>()
    private val itemThumbs = ConcurrentHashMap<String, String>()
    private val playbackCache = ConcurrentHashMap<String, PlexPlayback>()
    private val sessionIds = ConcurrentHashMap<String, String>()

    override val accounts: StateFlow<List<PlexAccount>> = settings.flow

    override fun serverNodes(): List<FileStat> = settings.current().flatMap { account ->
        account.servers.map { server ->
            FileStat(
                id = PlexLocation(server.mountId).id,
                parentId = DeviceLocation.ROOT_ID,
                name = server.name,
                kind = FileKind.FOLDER,
                size = "0",
                phase = TaskPhase.COMPLETE,
                params = mapOf("plex_server" to server.mountId),
            )
        }
    }

    override fun serverName(mountId: String): String = settings.server(mountId)?.name ?: mountId

    override suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
        val data = api.createPin()
        val id = data.long("id") ?: throw PlexException("Plex PIN id missing")
        val code = data.string("code") ?: throw PlexException("Plex PIN code missing")
        PlexPin(
            id = id,
            code = code,
            authUrl = "https://app.plex.tv/auth#?clientID=${api.clientIdentifier()}&code=$code&context%5Bdevice%5D%5Bproduct%5D=Piko",
        ).also { PikoLog.i(TAG, "登录 PIN 创建成功，等待用户授权") }
    }

    override suspend fun pollPin(pinId: Long): PlexPinPoll = withContext(Dispatchers.IO) {
        try {
            val data = api.pollPin(pinId)
            val token = data.string("authToken").orEmpty()
            if (token.isBlank()) {
                PikoLog.d(TAG, "PIN 尚未授权，继续等待")
                PlexPinPoll.Waiting
            } else {
                PikoLog.i(TAG, "PIN 已返回凭据（长度 ${token.length}），开始校验账号")
                PlexPinPoll.Claimed(token)
            }
        } catch (error: PlexApiException) {
            when (error.status.value) {
                404, 410 -> PlexPinPoll.Expired
                401, 403 -> throw PlexException(
                    tr("Plex 登录请求被拒绝", "Plex sign-in was rejected", "Plex のサインインが拒否されました", "Plex 로그인이 거부되었습니다", "Plex 登入遭到拒絕"),
                    error,
                )
                else -> throw PlexException("Plex PIN check failed", error)
            }
        }
    }

    override suspend fun identity(token: String): PlexAccountIdentity = withContext(Dispatchers.IO) {
        val data = api.identity(token)
        PlexAccountIdentity(
            uuid = data.string("uuid").orEmpty().ifBlank { data.string("id").orEmpty() },
            username = data.string("username").orEmpty(),
            email = data.string("email").orEmpty(),
            thumb = data.string("thumb").orEmpty(),
        ).also {
            if (it.uuid.isBlank()) throw PlexException("Plex account uuid missing")
            PikoLog.i(TAG, "Plex 账号校验成功")
        }
    }

    override suspend fun homeProfiles(token: String): List<PlexHomeProfile> = withContext(Dispatchers.IO) {
        api.homeProfiles(token).map { user ->
            PlexHomeProfile(
                uuid = user.string("uuid").orEmpty(),
                title = user.string("title").orEmpty().ifBlank { user.string("username").orEmpty() },
                thumb = user.string("thumb").orEmpty(),
                protected = user.bool("protected") || user.bool("hasPassword"),
                admin = user.bool("admin"),
                restricted = user.bool("restricted"),
                guest = user.bool("guest"),
            )
        }.filter { it.uuid.isNotBlank() }
    }

    override suspend fun switchProfile(accountToken: String, profileUuid: String, pin: String?): String =
        withContext(Dispatchers.IO) {
            try {
                val data = api.switchProfile(accountToken, profileUuid, pin)
                data.string("authToken").orEmpty().ifBlank { data.string("authenticationToken").orEmpty() }
                    .takeIf { it.isNotBlank() }
                    ?: throw PlexException(
                        tr("Plex 家庭用户切换没有返回令牌", "Plex Home switch did not return a token", "Plex Home の切り替えでトークンが返りませんでした", "Plex Home 전환에서 토큰이 반환되지 않았습니다", "Plex 家庭使用者切換未傳回權杖"),
                    )
            } catch (error: PlexApiException) {
                if (error.status.value == 403 && error.responseBody.contains("1041")) {
                    throw PlexHomePinException(
                        tr("Plex 家庭用户 PIN 不正确", "Incorrect Plex Home PIN", "Plex Home PIN が正しくありません", "Plex Home PIN이 올바르지 않습니다", "Plex 家庭使用者 PIN 不正確"),
                        error,
                    )
                }
                throw PlexException("Plex Home switch failed", error)
            }
        }

    override suspend fun discoverServers(token: String): List<PlexDiscoveredServer> = withContext(Dispatchers.IO) {
        api.resources(token).mapNotNull { resource ->
            val provides = resource.string("provides").orEmpty()
            if (provides.split(',').none { it.trim() == "server" }) return@mapNotNull null
            val machine = resource.string("clientIdentifier").orEmpty()
            if (machine.isBlank()) return@mapNotNull null
            val connections = (resource["connections"] as? JsonArray).orEmpty().mapNotNull { element ->
                val connection = element as? JsonObject ?: return@mapNotNull null
                val protocol = connection.string("protocol").orEmpty().ifBlank { "https" }
                val address = connection.string("address").orEmpty()
                val port = connection.int("port") ?: return@mapNotNull null
                val uri = connection.string("uri").orEmpty().ifBlank {
                    "$protocol://${addressForUrl(address)}:$port"
                }
                if (uri.isBlank() || address.isBlank()) return@mapNotNull null
                PlexConnection(
                    protocol = protocol,
                    address = address,
                    port = port,
                    uri = uri,
                    local = connection.bool("local"),
                    relay = connection.bool("relay"),
                    ipv6 = connection.bool("IPv6") || address.contains(':'),
                )
            }
            PlexDiscoveredServer(
                server = PlexServer(
                    mountId = "",
                    machineIdentifier = machine,
                    name = resource.string("name").orEmpty().ifBlank { machine },
                    owned = resource.bool("owned"),
                    connections = connections,
                ),
                accessToken = resource.string("accessToken").orEmpty().ifBlank { token },
            )
        }
    }

    override suspend fun addAccount(
        accountToken: String,
        identity: PlexAccountIdentity,
        profiles: List<PlexHomeProfile>,
        profile: PlexHomeProfile?,
        profileToken: String,
        servers: List<PlexDiscoveredServer>,
    ): PlexAccount = withContext(Dispatchers.IO) {
        val previous = settings.find(identity.uuid)
        val serverConfigs = servers.map { discovered ->
            val existing = previous?.servers?.firstOrNull {
                it.machineIdentifier == discovered.server.machineIdentifier &&
                    it.profileUuid == (profile?.uuid ?: "")
            }
            discovered.server.copy(
                mountId = existing?.mountId ?: newMountId(),
                profileUuid = profile?.uuid ?: "",
                preferredUrl = existing?.preferredUrl.orEmpty(),
                connections = discovered.server.connections,
            )
        }
        val account = PlexAccount(
            accountUuid = identity.uuid,
            username = identity.username,
            email = identity.email,
            thumb = identity.thumb,
            activeProfileUuid = profile?.uuid ?: "",
            profiles = profiles,
            servers = serverConfigs,
        )
        val bundle = PlexCredentialBundle(
            accountToken = accountToken,
            profileTokens = mapOf((profile?.uuid ?: "") to profileToken),
            serverTokens = servers.associate { discovered ->
                val configured = serverConfigs.first { it.machineIdentifier == discovered.server.machineIdentifier }
                configured.mountId to discovered.accessToken
            },
        )
        saveBundle(account.accountUuid, bundle)
        settings.upsert(account)
        account
    }

    override suspend fun refreshAccount(accountUuid: String): Result<PlexAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val account = settings.find(accountUuid) ?: error("Plex account not found")
            val bundle = bundleFor(accountUuid)
            val token = profileToken(account, bundle)
            val discovered = discoverServers(token)
            val next = account.copy(
                servers = mergeServers(account, account.activeProfileUuid, discovered),
            )
            val nextBundle = bundle.copy(
                serverTokens = bundle.serverTokens + discovered.associate { discoveredServer ->
                    val configured = next.servers.first { it.machineIdentifier == discoveredServer.server.machineIdentifier }
                    configured.mountId to discoveredServer.accessToken
                },
            )
            saveBundle(accountUuid, nextBundle)
            settings.upsert(next)
            next
        }
    }

    override suspend fun setActiveProfile(accountUuid: String, profileUuid: String, pin: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val account = settings.find(accountUuid) ?: error("Plex account not found")
                val bundle = bundleFor(accountUuid)
                val token = if (profileUuid.isBlank()) {
                    bundle.accountToken
                } else {
                    switchProfile(bundle.accountToken, profileUuid, pin)
                }
                val profiles = runCatching { homeProfiles(bundle.accountToken) }.getOrDefault(account.profiles)
                val discovered = discoverServers(token)
                val next = account.copy(
                    activeProfileUuid = profileUuid,
                    profiles = profiles,
                    servers = mergeServers(account, profileUuid, discovered),
                )
                val nextBundle = bundle.copy(
                    profileTokens = bundle.profileTokens + (profileUuid to token),
                    serverTokens = discovered.associate { discoveredServer ->
                        val configured = next.servers.first { it.machineIdentifier == discoveredServer.server.machineIdentifier }
                        configured.mountId to discoveredServer.accessToken
                    },
                )
                saveBundle(accountUuid, nextBundle)
                settings.upsert(next)
            }
        }

    override suspend fun removeAccount(accountUuid: String) = withContext(Dispatchers.IO) {
        settings.find(accountUuid)?.servers?.forEach { resolvedBases.remove(it.mountId) }
        bundleCache.remove(accountUuid)
        secrets?.clearPlexCredentials(accountUuid)
        settings.remove(accountUuid)
    }

    override suspend fun removeServer(mountId: String) = withContext(Dispatchers.IO) {
        val account = settings.accountOfServer(mountId) ?: return@withContext
        val bundle = bundleFor(account.accountUuid)
        saveBundle(account.accountUuid, bundle.copy(serverTokens = bundle.serverTokens - mountId))
        resolvedBases.remove(mountId)
        settings.removeServer(mountId)
        if (settings.find(account.accountUuid) == null) {
            bundleCache.remove(account.accountUuid)
            secrets?.clearPlexCredentials(account.accountUuid)
        }
    }

    override suspend fun list(mountId: String, node: String): List<FileStat> = withContext(Dispatchers.IO) {
        val account = settings.accountOfServer(mountId) ?: return@withContext emptyList()
        val server = account.servers.firstOrNull { it.mountId == mountId } ?: return@withContext emptyList()
        val token = serverToken(account, server)
        val base = baseUrl(server, token)
        when {
            node.isBlank() -> {
                val container = api.sections(base, token).container()
                container.objects("Directory").mapNotNull { directory ->
                    val sectionId = directory.string("key").orEmpty()
                    if (sectionId.isBlank()) return@mapNotNull null
                    val type = directory.string("type").orEmpty()
                    if (type.isNotBlank() && type !in LIBRARY_TYPES) return@mapNotNull null
                    val parent = PlexLocation(mountId).id
                    val location = PlexLocation(mountId, "s$sectionId", directory = true)
                    itemNames[location.id] = directory.string("title").orEmpty().ifBlank { sectionId }
                    itemParents[location.id] = parent
                    FileStat(
                        id = location.id,
                        parentId = parent,
                        name = itemNames.getValue(location.id),
                        kind = FileKind.FOLDER,
                        size = "0",
                        phase = TaskPhase.COMPLETE,
                        params = mapOf("plex_server" to mountId, "plex_section" to sectionId),
                    )
                }
            }
            node.startsWith("s") -> {
                val sectionId = node.drop(1)
                val items = mutableListOf<JsonObject>()
                var start = 0
                while (start < MAX_SECTION_ITEMS) {
                    val page = api.sectionItems(base, token, sectionId, start, SECTION_PAGE_SIZE)
                        .container()
                        .objects("Metadata")
                    items += page
                    if (page.size < SECTION_PAGE_SIZE) break
                    start += SECTION_PAGE_SIZE
                }
                items.mapNotNull {
                    mapItem(account, server, token, base, sectionId, it, PlexLocation(mountId, "s$sectionId").id)
                }
            }
            node.startsWith("m") -> {
                val ratingKey = node.drop(1)
                val sectionId = sectionIdOf(account, server, token, base, ratingKey)
                val location = PlexLocation(mountId, node)
                val parent = itemParents[location.id]
                    ?: PlexLocation(mountId, "s$sectionId").id
                api.children(base, token, ratingKey).container().objects("Metadata", "Directory").mapNotNull {
                    mapItem(account, server, token, base, sectionId, it, parent)
                }
            }
            else -> emptyList()
        }
    }

    override fun cachedPathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>? =
        pathFromCache(fileId, containing)

    override fun cachedParentOf(fileId: String): String? = itemParents[fileId]

    override suspend fun pathTo(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>? =
        withContext(Dispatchers.IO) {
            val location = PlexLocation.of(fileId) ?: return@withContext null
            val account = settings.accountOfServer(location.mountId) ?: return@withContext null
            val server = account.servers.firstOrNull { it.mountId == location.mountId } ?: return@withContext null
            val token = runCatching { serverToken(account, server) }.getOrNull() ?: return@withContext null
            val base = runCatching { baseUrl(server, token) }.getOrNull() ?: return@withContext null
            if (location.node.isBlank()) return@withContext crumbRoot(server)
            val itemId = location.itemId
            if (itemId != null) {
                runCatching {
                    val metadata = api.metadata(base, token, itemId).container().objects("Metadata").firstOrNull()
                    val sectionId = metadata?.int("librarySectionID")?.toString().orEmpty()
                    metadata?.let {
                        mapItem(
                            account,
                            server,
                            token,
                            base,
                            sectionId,
                            it,
                            PlexLocation(server.mountId, "s$sectionId").id,
                        )
                    }
                }
                ensureAncestors(account, server, token, base, fileId)
            }
            pathFromCache(fileId, containing) ?: run {
                if (location.itemId == null) {
                    return@withContext crumbRoot(server) + PikoPathBreadcrumb(
                        location.id,
                        itemNames[location.id] ?: location.node,
                    )
                }
                val chain = ArrayDeque<PikoPathBreadcrumb>()
                var cursor = if (containing && !location.directory) itemParents[fileId] else fileId
                while (cursor != null && cursor != PlexLocation(server.mountId).id) {
                    val name = itemNames[cursor] ?: return@withContext null
                    chain.addFirst(PikoPathBreadcrumb(cursor, name))
                    cursor = itemParents[cursor]
                }
                if (chain.isEmpty()) null else crumbRoot(server) + chain
            }
        }

    override suspend fun playback(fileId: String): PlexPlayback = withContext(Dispatchers.IO) {
        val location = PlexLocation.of(fileId) ?: throw PlexException("Not a Plex item")
        val ratingKey = location.itemId ?: throw PlexException("Plex item is not playable")
        val account = settings.accountOfServer(location.mountId) ?: throw PlexException("Plex server not found")
        val server = account.servers.firstOrNull { it.mountId == location.mountId } ?: throw PlexException("Plex server not found")
        val token = serverToken(account, server)
        val base = baseUrl(server, token)
        playbackCache[fileId]?.let { return@withContext it }
        val metadata = api.metadata(base, token, ratingKey).container().objects("Metadata").firstOrNull()
            ?: throw PlexException("Plex metadata missing")
        val media = playablePart(metadata)
        val part = media.second
        val mediaObject = media.first
        val partKey = part.string("key").orEmpty()
        if (partKey.isBlank()) throw PlexException(
            tr("Plex 没有可播放的媒体源", "Plex has no playable media source", "Plex に再生可能なメディアがありません", "Plex에 재생 가능한 미디어 소스가 없습니다", "Plex 沒有可播放的媒體來源"),
        )
        val durationMs = part.long("duration") ?: mediaObject.long("duration") ?: metadata.long("duration") ?: 0L
        val container = mediaObject.string("container").orEmpty().ifBlank { part.string("container").orEmpty() }
        val video = (part["Stream"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.int("streamType") == 1 }
        val playback = PlexPlayback(
            url = withToken(normalizeBase(base) + partKey, token),
            name = itemNames[fileId] ?: metadata.string("title").orEmpty().ifBlank { "Plex" },
            durationSeconds = (durationMs / 1000L).coerceAtLeast(0),
            sizeBytes = part.long("size") ?: mediaObject.long("size") ?: 0L,
            width = video?.int("width") ?: mediaObject.int("width") ?: 0,
            height = video?.int("height") ?: mediaObject.int("height") ?: 0,
            mimeType = if (container.isBlank()) "video/mp4" else "video/$container",
            ratingKey = ratingKey,
            sessionIdentifier = sessionIds.getOrPut(fileId) { UUID.randomUUID().toString() },
            mediaSourceId = mediaObject.string("id").orEmpty(),
        )
        playbackCache[fileId] = playback
        playback
    }

    override suspend fun originalImageUrl(fileId: String): String? = withContext(Dispatchers.IO) {
        val location = PlexLocation.of(fileId) ?: return@withContext null
        val account = settings.accountOfServer(location.mountId) ?: return@withContext null
        val server = account.servers.firstOrNull { it.mountId == location.mountId } ?: return@withContext null
        val token = runCatching { serverToken(account, server) }.getOrNull() ?: return@withContext null
        val base = runCatching { baseUrl(server, token) }.getOrNull() ?: return@withContext null
        val thumb = itemThumbs[fileId] ?: run {
            val ratingKey = location.itemId ?: return@withContext null
            runCatching {
                api.metadata(base, token, ratingKey).container().objects("Metadata").firstOrNull()
                    ?.let { metadata ->
                        val value = metadata.string("thumb").orEmpty()
                        itemThumbs[fileId] = value
                        value
                    }
            }.getOrNull()
        }
        thumb?.takeIf { it.isNotBlank() }?.let { withToken(normalizeBase(base) + it, token) }
    }

    override fun logIdentity(fileId: String): String? = PlexLocation.logIdentity(fileId)

    override suspend fun playbackHistory(): List<FileStat> = withContext(Dispatchers.Default) {
        settings.current().flatMap { account ->
            account.servers.flatMap { server ->
                runCatching { historyFor(account, server) }.getOrElse { emptyList() }
            }
        }.distinctBy { it.id }
            .sortedByDescending { it.params["last_played"].orEmpty() }
            .take(200)
    }

    override suspend fun resumePosition(fileId: String): Long? = withContext(Dispatchers.IO) {
        val location = PlexLocation.of(fileId) ?: return@withContext null
        val ratingKey = location.itemId ?: return@withContext null
        val account = settings.accountOfServer(location.mountId) ?: return@withContext null
        val server = account.servers.firstOrNull { it.mountId == location.mountId } ?: return@withContext null
        val token = runCatching { serverToken(account, server) }.getOrNull() ?: return@withContext null
        val base = runCatching { baseUrl(server, token) }.getOrNull() ?: return@withContext null
        val metadata = runCatching {
            api.metadata(base, token, ratingKey).container().objects("Metadata").firstOrNull()
        }.getOrNull() ?: return@withContext null
        val position = metadata.long("viewOffset") ?: 0L
        val duration = metadata.long("duration") ?: 0L
        position.takeIf { it > 0L && (duration <= 0L || it < duration - 30_000L) }
    }

    override suspend fun reportPlayback(fileId: String, positionMillis: Long, durationMillis: Long, stopped: Boolean) =
        withContext(Dispatchers.IO) {
            val playback = playbackCache[fileId] ?: runCatching { playback(fileId) }.getOrNull() ?: return@withContext
            val location = PlexLocation.of(fileId) ?: return@withContext
            val account = settings.accountOfServer(location.mountId) ?: return@withContext
            val server = account.servers.firstOrNull { it.mountId == location.mountId } ?: return@withContext
            val token = runCatching { serverToken(account, server) }.getOrNull() ?: return@withContext
            val base = runCatching { baseUrl(server, token) }.getOrNull() ?: return@withContext
            runCatching {
                api.timeline(
                    baseUrl = base,
                    token = token,
                    ratingKey = playback.ratingKey,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    state = if (stopped) "stopped" else "playing",
                    sessionIdentifier = playback.sessionIdentifier,
                )
            }.onFailure { if (it is CancellationException) throw it }
        }

    private suspend fun historyFor(account: PlexAccount, server: PlexServer): List<FileStat> {
        val token = serverToken(account, server)
        val base = baseUrl(server, token)
        val container = api.onDeck(base, token).container()
        return container.objects("Metadata").mapNotNull { item ->
            val section = item.int("librarySectionID")?.toString().orEmpty()
            mapItem(
                account = account,
                server = server,
                token = token,
                base = base,
                sectionId = section,
                item = item,
                parentId = if (section.isBlank()) PlexLocation(server.mountId).id else PlexLocation(server.mountId, "s$section").id,
            )
        }
    }

    private suspend fun mapItem(
        account: PlexAccount,
        server: PlexServer,
        token: String,
        base: String,
        sectionId: String,
        item: JsonObject,
        parentId: String,
    ): FileStat? {
        val ratingKey = item.string("ratingKey").orEmpty()
        if (ratingKey.isBlank()) return null
        val type = item.string("type").orEmpty()
        val folder = type in FOLDER_TYPES
        val location = PlexLocation(server.mountId, "m$ratingKey", directory = folder)
        val parent = item.string("parentRatingKey")?.let { PlexLocation(server.mountId, "m$it").id }
            ?: parentId
        val title = item.string("title").orEmpty().ifBlank {
            item.string("titleSort").orEmpty().ifBlank { ratingKey }
        }
        val label = if (type == "episode") {
            val index = item.int("index")
            if (index != null) "E${index.toString().padStart(2, '0')} $title" else title
        } else {
            title
        }
        val media = (item["Media"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull()
        val part = (media?.get("Part") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull()
        val video = (part?.get("Stream") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.int("streamType") == 1 }
        val durationMs = item.long("duration") ?: media?.long("duration") ?: 0L
        val positionMs = item.long("viewOffset") ?: 0L
        val container = media?.string("container").orEmpty()
        val thumb = item.string("thumb").orEmpty()
        val lastViewed = item.long("lastViewedAt")
        val stat = FileStat(
            id = location.id,
            parentId = parent,
            name = label,
            kind = if (folder) FileKind.FOLDER else FileKind.FILE,
            size = (part?.long("size") ?: media?.long("size") ?: 0L).toString(),
            mimeType = when {
                folder -> ""
                type == "movie" || type == "episode" || type == "clip" ->
                    if (container.isBlank()) "video/mp4" else "video/$container"
                else -> ""
            },
            modifiedTime = "",
            createdTime = "",
            phase = TaskPhase.COMPLETE,
            fileExtension = container.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty(),
            thumbnailLink = thumb.takeIf { it.isNotBlank() }?.let { withToken(normalizeBase(base) + it, token) }.orEmpty(),
            tags = emptyList(),
            params = buildMap {
                put("plex_server", server.mountId)
                put("plex_section", sectionId)
                put("plex_item", ratingKey)
                if (folder) put("plex_folder", "1")
                if (durationMs > 0) put("duration", (durationMs / 1000L).toString())
                if (positionMs > 0) put("playback_position", (positionMs / 1000L).toString())
                if ((item.long("viewCount") ?: 0L) > 0L) put("played", "1")
                if (lastViewed != null) put("last_played", Instant.fromEpochSeconds(lastViewed).toString())
                val width = video?.int("width") ?: media?.int("width")
                val height = video?.int("height") ?: media?.int("height")
                width?.let { put("width", it.toString()) }
                height?.let { put("height", it.toString()) }
                item.double("aspectRatio")?.let { put("aspect_ratio", it.toString()) }
            },
        )
        itemNames[stat.id] = stat.name
        itemParents[stat.id] = parent
        if (thumb.isNotBlank()) itemThumbs[stat.id] = thumb
        return stat
    }

    private fun playablePart(metadata: JsonObject): Pair<JsonObject, JsonObject> {
        val media = (metadata["Media"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        for (version in media) {
            val parts = (version["Part"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val playable = parts.filter { part ->
                val key = part.string("key").orEmpty()
                key.isNotBlank() && part.bool("exists", true) && part.bool("accessible", true)
            }
            if (playable.size > 1) throw PlexException(
                tr("Plex 的多文件版本暂不支持播放", "Plex multi-file versions are not supported yet", "Plex の複数ファイル版はまだ再生できません", "Plex 다중 파일 버전은 아직 재생할 수 없습니다", "Plex 的多檔案版本暫不支援播放"),
            )
            playable.firstOrNull()?.let { return version to it }
        }
        throw PlexException(
            tr("Plex 没有可播放的媒体源", "Plex has no playable media source", "Plex に再生可能なメディアがありません", "Plex에 재생 가능한 미디어 소스가 없습니다", "Plex 沒有可播放的媒體來源"),
        )
    }

    private suspend fun sectionIdOf(
        account: PlexAccount,
        server: PlexServer,
        token: String,
        base: String,
        ratingKey: String,
    ): String {
        val metadata = runCatching {
            api.metadata(base, token, ratingKey).container().objects("Metadata").firstOrNull()
        }.getOrNull() ?: return ""
        return metadata.int("librarySectionID")?.toString().orEmpty()
    }

    /** 从历史、搜索结果直接打开时，补齐到媒体库根部的名称与父级，避免只能看到最末一级。 */
    private suspend fun ensureAncestors(
        account: PlexAccount,
        server: PlexServer,
        token: String,
        base: String,
        fileId: String,
    ) {
        val root = PlexLocation(server.mountId).id
        var cursor = itemParents[fileId]
        var depth = 0
        while (cursor != null && cursor != root && itemNames[cursor] == null && depth++ < 12) {
            val location = PlexLocation.of(cursor) ?: return
            val ratingKey = location.itemId ?: return
            val metadata = runCatching {
                api.metadata(base, token, ratingKey).container().objects("Metadata").firstOrNull()
            }.getOrNull() ?: return
            val sectionId = metadata.int("librarySectionID")?.toString().orEmpty()
            val sectionParent = if (sectionId.isBlank()) root else PlexLocation(server.mountId, "s$sectionId").id
            if (sectionId.isNotBlank() && itemNames[sectionParent] == null) {
                runCatching {
                    api.sections(base, token).container().objects("Directory").forEach { section ->
                        val key = section.string("key").orEmpty()
                        if (key == sectionId) {
                            itemNames[sectionParent] = section.string("title").orEmpty().ifBlank { key }
                            itemParents[sectionParent] = root
                        }
                    }
                }
            }
            mapItem(account, server, token, base, sectionId, metadata, sectionParent)
            cursor = itemParents[cursor]
        }
    }

    private suspend fun profileToken(account: PlexAccount, bundle: PlexCredentialBundle): String =
        if (account.activeProfileUuid.isBlank()) {
            bundle.accountToken
        } else {
            bundle.profileTokens[account.activeProfileUuid].orEmpty().ifBlank { bundle.accountToken }
        }

    private suspend fun serverToken(account: PlexAccount, server: PlexServer): String =
        bundleFor(account.accountUuid).serverTokens[server.mountId].orEmpty().ifBlank {
            throw PlexException(
                tr("Plex 服务器令牌已失效，请重新登录", "Plex server token expired; sign in again", "Plex サーバートークンが切れました。再ログインしてください", "Plex 서버 토큰이 만료되었습니다. 다시 로그인하세요", "Plex 伺服器權杖已失效，請重新登入"),
            )
        }

    private suspend fun bundleFor(accountUuid: String): PlexCredentialBundle = bundleLock.withLock {
        bundleCache[accountUuid] ?: run {
            val raw = secrets?.loadPlexCredentials(accountUuid).orEmpty()
            val value = if (raw.isBlank()) PlexCredentialBundle() else runCatching {
                json.decodeFromString<PlexCredentialBundle>(raw)
            }.getOrDefault(PlexCredentialBundle())
            bundleCache[accountUuid] = value
            value
        }
    }

    private suspend fun saveBundle(accountUuid: String, bundle: PlexCredentialBundle) = bundleLock.withLock {
        bundleCache[accountUuid] = bundle
        secrets?.savePlexCredentials(accountUuid, json.encodeToString(bundle))
    }

    private suspend fun baseUrl(server: PlexServer, token: String): String {
        resolvedBases[server.mountId]?.let { return it }
        val candidates = endpointCandidates(server)
        if (candidates.isEmpty()) {
            throw PlexException(
                tr("Plex 服务器没有可用地址", "The Plex server has no usable address", "Plex サーバーに使用可能なアドレスがありません", "Plex 서버에 사용 가능한 주소가 없습니다", "Plex 伺服器沒有可用位址"),
            )
        }
        val preferred = server.preferredUrl.takeIf { it.isNotBlank() }
        val winner = preferred?.let { url ->
            withTimeoutOrNull(PREFERRED_HEAD_START_MILLIS) {
                if (api.probeIdentity(url, server.machineIdentifier)) url else null
            }
        } ?: raceEndpoints(candidates, server.machineIdentifier)
        if (winner != null) {
            resolvedBases[server.mountId] = winner
            rememberPreferredUrl(server, winner)
            return winner
        }
        throw PlexException(
            tr("无法连接 Plex 服务器", "Unable to reach the Plex server", "Plex サーバーに接続できません", "Plex 서버에 연결할 수 없습니다", "無法連線 Plex 伺服器"),
        )
    }

    private suspend fun raceEndpoints(candidates: List<String>, machineIdentifier: String): String? =
        kotlinx.coroutines.coroutineScope {
            val results = Channel<Pair<String, Boolean>>(candidates.size)
            val jobs = candidates.map { candidate ->
                launch {
                    results.send(candidate to api.probeIdentity(candidate, machineIdentifier))
                }
            }
            var remaining = candidates.size
            var winner: String? = null
            while (remaining > 0) {
                val (url, reachable) = results.receive()
                remaining--
                if (reachable) {
                    winner = url
                    break
                }
            }
            jobs.forEach { it.cancel() }
            winner
        }

    private fun endpointCandidates(server: PlexServer): List<String> {
        val ordered = server.connections.sortedWith(
            compareByDescending<PlexConnection> { it.local && !it.relay }
                .thenBy { it.relay }
                .thenBy { it.ipv6 }
                .thenByDescending { it.protocol == "https" },
        )
        val httpFallbacks = server.connections.filter {
            it.protocol == "https" && it.local && !it.relay && isPrivateHost(it.address)
        }.map { "http://${addressForUrl(it.address)}:${it.port}" }
        return (listOf(server.preferredUrl) + ordered.map { it.uri } + httpFallbacks)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun rememberPreferredUrl(server: PlexServer, url: String) {
        val connection = server.connections.firstOrNull { it.uri == url } ?: return
        if (connection.relay || server.preferredUrl == url) return
        val account = settings.accountOfServer(server.mountId) ?: return
        val next = account.copy(
            servers = account.servers.map { current ->
                if (current.mountId == server.mountId) current.copy(preferredUrl = url) else current
            },
        )
        settings.upsert(next)
    }

    private fun pathFromCache(fileId: String, containing: Boolean): List<PikoPathBreadcrumb>? {
        val location = PlexLocation.of(fileId) ?: return null
        val server = settings.server(location.mountId) ?: return null
        if (location.node.isBlank()) return crumbRoot(server)
        val target = if (containing && !location.directory) itemParents[fileId] ?: return null else fileId
        val chain = ArrayDeque<PikoPathBreadcrumb>()
        val visited = HashSet<String>()
        var cursor: String? = target
        while (cursor != null && cursor != PlexLocation(server.mountId).id) {
            if (!visited.add(cursor)) return null
            val name = itemNames[cursor] ?: return null
            chain.addFirst(PikoPathBreadcrumb(cursor, name))
            cursor = itemParents[cursor]
        }
        return crumbRoot(server) + chain
    }

    private fun crumbRoot(server: PlexServer): List<PikoPathBreadcrumb> = listOf(
        PikoDriveRepository.ROOT_BREADCRUMB,
        DeviceLocation.rootCrumb(),
        PikoPathBreadcrumb(PlexLocation(server.mountId).id, server.name),
    )

    private fun mergeServers(
        account: PlexAccount,
        profileUuid: String,
        discovered: List<PlexDiscoveredServer>,
    ): List<PlexServer> = discovered.map { item ->
        val existing = account.servers.firstOrNull {
            it.machineIdentifier == item.server.machineIdentifier && it.profileUuid == profileUuid
        }
        item.server.copy(
            mountId = existing?.mountId ?: newMountId(),
            profileUuid = profileUuid,
            preferredUrl = existing?.preferredUrl.orEmpty(),
            connections = item.server.connections,
        )
    }

    private fun newMountId(): String = "plex_" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun sectionIdOfContainer(container: JsonObject): String =
        container.int("librarySectionID")?.toString().orEmpty()

    private fun JsonObject.container(): JsonObject = this["MediaContainer"] as? JsonObject ?: this

    private fun JsonObject.objects(vararg keys: String): List<JsonObject> = buildList {
        for (key in keys) {
            val array = (this@objects[key] as? JsonArray) ?: continue
            array.mapNotNullTo(this) { it as? JsonObject }
        }
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
        val value = (this[key] as? JsonPrimitive)?.content ?: return default
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    private fun normalizeBase(base: String): String = base.trimEnd('/')

    private fun withToken(url: String, token: String): String =
        url + if ('?' in url) "&X-Plex-Token=$token" else "?X-Plex-Token=$token"

    private fun addressForUrl(address: String): String =
        if (address.contains(':') && !address.startsWith('[')) "[$address]" else address

    /** 只给私有 LAN 地址合成 HTTP 回退；公网、Relay 与自定义反代都不降级。 */
    private fun isPrivateHost(address: String): Boolean {
        val host = address.removePrefix("[").removeSuffix("]")
        if (host == "localhost") return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 169 && parts[1] == 254) ||
            (parts[0] == 100 && parts[1] in 64..127)
    }

    private companion object {
        const val TAG = "Plex"
        val LIBRARY_TYPES = setOf("movie", "show")
        val FOLDER_TYPES = setOf("show", "season", "directory", "collection")
        const val SECTION_PAGE_SIZE = 200
        const val MAX_SECTION_ITEMS = 2000
        const val PREFERRED_HEAD_START_MILLIS = 250L
    }
}
