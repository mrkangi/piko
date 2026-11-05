package dev.piko.shared.lan

import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.LanLocation
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * 局域网共享的客户端：配对、列共享、列目录、读取 host 预建索引，以及给播放器取流地址。
 * 这里只处理协议与缓存，不持有界面状态。
 */
class LanShareRepository(
    private val http: HttpClient,
    private val settings: LanShareSettings,
    scope: CoroutineScope,
    private val clientDeviceId: suspend () -> String = { "piko-client" },
    private val clientName: () -> String = { "Piko" },
    private val clientPlatform: String = "unknown",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheLock = Mutex()
    private var shareCache = emptyMap<String, CachedShares>()
    private var indexCache = emptyMap<Pair<String, String>, CachedIndex>()
    private var shareNames = emptyMap<Pair<String, String>, String>()

    val peers: StateFlow<List<LanPeer>> = settings.flow
        .map { it.peers }
        .stateIn(scope, SharingStarted.Eagerly, settings.current().peers)

    fun hasPeers(): Boolean = settings.current().peers.isNotEmpty()

    suspend fun ensureLoaded() = settings.ensureLoaded()

    fun peer(deviceId: String): LanPeer? = settings.current().peers.firstOrNull { it.hostDeviceId == deviceId }

    fun peerName(deviceId: String): String = peer(deviceId)?.name ?: deviceId

    fun deviceNodes(): List<FileStat> = settings.current().peers.map { peer ->
        DeviceLocation.peerStat(peer.hostDeviceId, peer.name)
    }

    suspend fun shareNodes(peerId: String): List<FileStat> {
        val peer = peer(peerId) ?: throw LanShareException(tr("主机已取消配对", "Host is no longer paired", "ホストのペアリングが解除されています", "호스트 페어링이 해제되었습니다", "主機已取消配對"))
        val shares = fetchShares(peer)
        return shares.map { share ->
            FileStat(
                id = LanLocation(peerId, share.shareId).id,
                parentId = DeviceLocation.peerId(peerId),
                name = share.name,
                kind = FileKind.FOLDER,
                size = "0",
                phase = TaskPhase.COMPLETE,
                params = mapOf(
                    "lan_peer" to peerId,
                    "lan_share" to share.shareId,
                ),
            )
        }
    }

    suspend fun list(location: LanLocation): List<FileStat> {
        val peer = peer(location.peerId) ?: throw LanShareException(tr("主机已取消配对", "Host is no longer paired", "ホストのペアリングが解除されています", "호스트 페어링이 해제되었습니다", "主機已取消配對"))
        val cached = cachedIndex(peer, location.shareId)
        if (cached != null && (!cached.truncated || cached.byParent.containsKey(location.path))) {
            val entries = cached.byParent[location.path].orEmpty()
            return entries.map { entryToStat(peer, location.shareId, it) }
        }
        val response = getJson<LanShareListing>(
            peer,
            "/v1/list?share=${location.shareId.encodeURLParameter()}&path=${location.path.encodeURLParameter()}",
        )
        return response.entries.map { entryToStat(peer, location.shareId, it) }
    }

    suspend fun probe(location: LanLocation): FileStat? {
        val peer = peer(location.peerId) ?: return null
        val response = getJson<LanShareListing>(
            peer,
            "/v1/stat?share=${location.shareId.encodeURLParameter()}&path=${location.path.encodeURLParameter()}",
        )
        return response.entries.firstOrNull()?.let { entryToStat(peer, location.shareId, it) }
    }

    /** host 预建的整棵共享索引；瀑布流/信息流在第一次遍历前调用，之后逐层列目录都从缓存出。 */
    suspend fun ensureIndex(location: LanLocation) {
        val peer = peer(location.peerId) ?: throw LanShareException(tr("主机已取消配对", "Host is no longer paired", "ホストのペアリングが解除されています", "호스트 페어링이 해제되었습니다", "主機已取消配對"))
        if (cachedIndex(peer, location.shareId) != null) return
        val response = getJson<LanShareIndex>(
            peer,
            "/v1/index?share=${location.shareId.encodeURLParameter()}",
        )
        val byParent = response.entries.groupBy { it.path.substringBeforeLast('/', "") }
        val cache = CachedIndex(now(), byParent, response.truncated)
        cacheLock.withLock {
            indexCache = (indexCache + (peer.hostDeviceId to location.shareId to cache))
                .entries.sortedByDescending { it.value.atMs }.take(MAX_INDEX_CACHE).associate { it.key to it.value }
        }
    }

    suspend fun prefetchIndexFor(fileId: String) {
        val location = LanLocation.of(fileId) ?: return
        runCatching { ensureIndex(location) }
    }

    suspend fun pair(address: String, port: Int, code: String): LanPeer {
        val host = normalizeAddress(address)
        require(host.isNotBlank()) { tr("请输入主机地址", "Enter the host address", "ホストのアドレスを入力してください", "호스트 주소를 입력하세요", "請輸入主機位址") }
        require(port in 1..65535) { tr("端口无效", "Invalid port", "ポートが無効です", "포트가 올바르지 않습니다", "連接埠無效") }
        val request = LanPairRequest(
            code = code.trim(),
            clientDeviceId = clientDeviceId(),
            clientName = clientName(),
            clientPlatform = clientPlatform,
        )
        val url = "http://${hostForUrl(host)}:$port/v1/pair"
        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
        }
        if (!response.status.isSuccess()) {
            throw LanShareException(tr("配对失败，请检查配对码", "Pairing failed. Check the code", "ペアリングに失敗しました。コードを確認してください", "페어링 실패. 코드를 확인하세요", "配對失敗，請檢查配對碼"))
        }
        val paired = json.decodeFromString<LanPairResponse>(response.bodyAsText())
        val peer = LanPeer(
            hostDeviceId = paired.hostDeviceId,
            name = paired.hostName.ifBlank { host },
            address = host,
            port = port,
            token = paired.token,
            lastSeenMs = now(),
            platform = paired.platform,
        )
        settings.update { current ->
            current.copy(peers = current.peers.filterNot { it.hostDeviceId == peer.hostDeviceId } + peer)
        }
        cacheLock.withLock {
            shareCache = shareCache - peer.hostDeviceId
            indexCache = indexCache.filterKeys { it.first != peer.hostDeviceId }
        }
        return peer
    }

    suspend fun removePeer(deviceId: String) {
        settings.update { current -> current.copy(peers = current.peers.filterNot { it.hostDeviceId == deviceId }) }
        cacheLock.withLock {
            shareCache = shareCache - deviceId
            indexCache = indexCache.filterKeys { it.first != deviceId }
        }
    }

    suspend fun refresh(peerId: String): Result<Unit> = runCatching {
        val peer = peer(peerId) ?: return@runCatching
        val info = getJson<LanDeviceInfo>(peer, "/v1/info", authenticated = false)
        val updated = peer.copy(
            name = info.deviceName.ifBlank { peer.name },
            platform = info.platform,
            lastSeenMs = now(),
        )
        settings.update { current ->
            current.copy(peers = current.peers.map { if (it.hostDeviceId == peerId) updated else it })
        }
        fetchShares(updated)
    }

    suspend fun refreshAll() {
        settings.current().peers.forEach { refresh(it.hostDeviceId) }
    }

    fun streamUrl(fileId: String): String? = LanLocation.of(fileId)?.let(::streamUrl)

    fun streamUrl(location: LanLocation): String? {
        if (location.directory) return null
        val peer = peer(location.peerId) ?: return null
        return contentUrl(peer, location.shareId, location.path)
    }

    fun cachedPathTo(location: LanLocation): List<PikoPathBreadcrumb>? {
        val peer = peer(location.peerId) ?: return null
        val shareName = shareNames[location.peerId to location.shareId] ?: return null
        return listOf(
            PikoDriveRepository.ROOT_BREADCRUMB,
            DeviceLocation.rootCrumb(),
            DeviceLocation.peerCrumb(peer.hostDeviceId, peer.name),
            PikoPathBreadcrumb(LanLocation(peer.hostDeviceId, location.shareId).id, shareName),
        ) + location.segments.mapIndexed { index, name ->
            val directory = index != location.segments.lastIndex || location.directory
            PikoPathBreadcrumb(
                LanLocation(peer.hostDeviceId, location.shareId, location.segments.take(index + 1), directory).id,
                name,
            )
        }
    }

    fun cachedPathTo(fileId: String): List<PikoPathBreadcrumb>? = LanLocation.of(fileId)?.let(::cachedPathTo)

    private suspend fun fetchShares(peer: LanPeer): List<LanShareDescriptor> {
        cacheLock.withLock {
            shareCache[peer.hostDeviceId]?.takeIf { now() - it.atMs <= SHARE_TTL_MS }?.let { return it.shares }
        }
        val shares = getJson<List<LanShareDescriptor>>(peer, "/v1/shares")
        cacheLock.withLock {
            shareCache = shareCache + (peer.hostDeviceId to CachedShares(now(), shares))
            val names = shares.associate { (peer.hostDeviceId to it.shareId) to it.name }
            shareNames = shareNames + names
        }
        return shares
    }

    private suspend fun cachedIndex(peer: LanPeer, shareId: String): CachedIndex? = cacheLock.withLock {
        indexCache[peer.hostDeviceId to shareId]?.takeIf { now() - it.atMs <= INDEX_TTL_MS }
    }

    private fun entryToStat(peer: LanPeer, shareId: String, entry: LanShareEntry): FileStat {
        val segments = entry.path.split('/').filter { it.isNotEmpty() }
        val location = LanLocation(peer.hostDeviceId, shareId, segments, entry.folder)
        val parent = if (segments.isEmpty()) {
            DeviceLocation.peerId(peer.hostDeviceId)
        } else {
            LanLocation(peer.hostDeviceId, shareId, segments.dropLast(1), true).id
        }
        val extension = entry.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val isImage = entry.mimeType.startsWith("image/")
        return FileStat(
            id = location.id,
            parentId = parent,
            name = entry.name,
            kind = if (entry.folder) FileKind.FOLDER else FileKind.FILE,
            size = entry.size.toString(),
            mimeType = entry.mimeType,
            modifiedTime = entry.modifiedMs.takeIf { it > 0 }?.let { kotlin.time.Instant.fromEpochMilliseconds(it).toString() }.orEmpty(),
            phase = TaskPhase.COMPLETE,
            fileExtension = extension,
            thumbnailLink = if (isImage) thumbUrl(peer, shareId, entry.path) else "",
            params = buildMap {
                put("lan_peer", peer.hostDeviceId)
                put("lan_share", shareId)
                entry.durationMs?.takeIf { it > 0 }?.let { put("duration", (it / 1000.0).toString()) }
                if (entry.width > 0) put("width", entry.width.toString())
                if (entry.height > 0) put("height", entry.height.toString())
            },
        )
    }

    private fun contentUrl(peer: LanPeer, shareId: String, path: String): String =
        "http://${hostForUrl(peer.address)}:${peer.port}/v1/content" +
            "?share=${shareId.encodeURLParameter()}&path=${path.encodeURLParameter()}&token=${peer.token.encodeURLParameter()}"

    private fun thumbUrl(peer: LanPeer, shareId: String, path: String): String =
        "http://${hostForUrl(peer.address)}:${peer.port}/v1/thumb" +
            "?share=${shareId.encodeURLParameter()}&path=${path.encodeURLParameter()}&token=${peer.token.encodeURLParameter()}&w=320"

    private suspend inline fun <reified T> getJson(peer: LanPeer, path: String, authenticated: Boolean = true): T {
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                http.get("http://${hostForUrl(peer.address)}:${peer.port}$path") {
                    if (authenticated) header("Authorization", "Bearer ${peer.token}")
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            throw LanShareException(tr("无法连接局域网主机", "Unable to reach the LAN host", "LAN ホストに接続できません", "LAN 호스트에 연결할 수 없습니다", "無法連線至區域網路主機"), e)
        }
        if (!response.status.isSuccess()) {
            throw LanShareException(
                when (response.status.value) {
                    401, 403 -> tr("主机拒绝访问，请重新配对", "Host denied access. Pair again", "ホストがアクセスを拒否しました。再ペアリングしてください", "호스트가 접근을 거부했습니다. 다시 페어링하세요", "主機拒絕存取，請重新配對")
                    else -> tr("主机返回错误", "The host returned an error", "ホストがエラーを返しました", "호스트가 오류를 반환했습니다", "主機傳回錯誤")
                },
            )
        }
        val body = try {
            withTimeout(REQUEST_TIMEOUT_MS) { response.bodyAsText() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            throw LanShareException(tr("读取局域网主机响应失败", "Failed to read the LAN host response", "LAN ホストの応答を読み取れません", "LAN 호스트 응답을 읽지 못했습니다", "讀取區域網路主機回應失敗"), e)
        }
        return json.decodeFromString(body)
    }

    private fun normalizeAddress(input: String): String = input.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')

    private fun hostForUrl(host: String): String =
        if (':' in host && !host.startsWith('[')) "[$host]" else host

    private data class CachedShares(val atMs: Long, val shares: List<LanShareDescriptor>)
    private data class CachedIndex(val atMs: Long, val byParent: Map<String, List<LanShareEntry>>, val truncated: Boolean)

    private companion object {
        const val SHARE_TTL_MS = 30_000L
        const val INDEX_TTL_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val MAX_INDEX_CACHE = 4
    }
}
