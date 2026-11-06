package dev.piko.shared.lan

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.PikoHome
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Clock

/**
 * 桌面端的局域网共享 host。只读 HTTP，令牌配对；文件内容支持 Range/HEAD，播放器可以直接拖进度。
 *
 * 这是 P1 的实现：明文 HTTP + 配对令牌，适合可信局域网；TLS 与 mDNS 在后续阶段接入。
 */
class DesktopLanShareHost(
    private val repository: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val settings: LanShareSettings,
    private val deviceName: String,
    private val platformName: String,
    private val scope: CoroutineScope,
) : LanShareHost {
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(LanHostStatus())
    override val status: StateFlow<LanHostStatus> = _status.asStateFlow()

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var announcer: UdpLanAnnouncer? = null
    private val indexCache = ConcurrentHashMap<String, CachedIndex>()
    private val startLock = Mutex()

    @Volatile private var pairingCode: String = ""
    @Volatile private var deviceId: String = ""
    @Volatile private var running: Boolean = false

    init {
        scope.launch {
            settings.flow.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) start() else stop()
            }
        }
    }

    override fun start() {
        if (running) return
        scope.launch(Dispatchers.IO) {
            runCatching { startServer() }
                .onFailure { error ->
                    PikoLog.w(TAG, "局域网共享 host 启动失败", error)
                    _status.value = LanHostStatus(error = error.message ?: tr("启动失败", "Failed to start", "起動に失敗しました", "시작 실패", "啟動失敗"))
                }
        }
    }

    override fun stop() {
        if (!running && server == null) return
        running = false
        runCatching { server?.stop(1) }
        runCatching { executor?.shutdownNow() }
        announcer?.stop()
        server = null
        executor = null
        announcer = null
        _status.value = LanHostStatus()
    }

    override fun regeneratePairingCode() {
        pairingCode = newPairingCode()
        _status.value = _status.value.copy(pairingCode = pairingCode)
    }

    private suspend fun startServer() = startLock.withLock {
        if (running) return
        deviceId = preferences.loadOrCreateRemoteDeviceId()
        val desiredPort = settings.current().port.takeIf { it in 1..65535 } ?: LanLocalSettings.DEFAULT_PORT
        var bound: HttpServer? = null
        var boundPort = desiredPort
        for (offset in 0 until PORT_ATTEMPTS) {
            val candidate = desiredPort + offset
            if (candidate > 65535) break
            val created = runCatching {
                HttpServer.create(InetSocketAddress(candidate), 0)
            }.getOrNull() ?: continue
            bound = created
            boundPort = candidate
            break
        }
        val http = bound ?: error(tr("端口被占用", "Port is in use", "ポートが使用中です", "포트가 사용 중입니다", "連接埠已被占用"))
        val pool = Executors.newFixedThreadPool(HANDLER_THREADS)
        http.executor = pool
        http.createContext("/") { exchange -> handleSafely(exchange) }
        http.start()
        server = http
        executor = pool
        running = true
        pairingCode = newPairingCode()
        announcer = UdpLanAnnouncer(
            LanDiscoveryPacket(deviceId = deviceId, deviceName = deviceName, port = boundPort, platform = platformName),
        ).also { it.start() }
        _status.value = LanHostStatus(
            running = true,
            address = localAddress(),
            port = boundPort,
            pairingCode = pairingCode,
        )
        PikoLog.i(TAG, "局域网共享 host 已启动，端口 $boundPort")
    }

    private fun handleSafely(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (cancel: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            PikoLog.w(TAG, "局域网共享请求处理失败：${exchange.requestURI.path}", error)
            runCatching { sendError(exchange, 500, "internal") }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = parseQuery(exchange.requestURI.rawQuery)
        when {
            path == "/v1/info" && exchange.requestMethod == "GET" -> {
                sendJson(
                    exchange,
                    200,
                    LanDeviceInfo(deviceId = deviceId, deviceName = deviceName, platform = platformName),
                )
            }
            path == "/v1/pair" && exchange.requestMethod == "POST" -> handlePair(exchange)
            path == "/v1/shares" && exchange.requestMethod == "GET" -> withAuth(exchange, query) {
                sendJson(exchange, 200, publishedShares())
            }
            path == "/v1/list" && exchange.requestMethod == "GET" -> withAuth(exchange, query) {
                val shareId = query["share"].orEmpty()
                val relative = query["path"].orEmpty()
                val files = runBlocking { listShare(shareId, relative) }
                sendJson(
                    exchange,
                    200,
                    LanShareListing(files.map { it.toEntry(if (relative.isEmpty()) it.name else "$relative/${it.name}") }),
                )
            }
            path == "/v1/stat" && exchange.requestMethod == "GET" -> withAuth(exchange, query) {
                val shareId = query["share"].orEmpty()
                val relative = query["path"].orEmpty()
                val stat = runBlocking { statShare(shareId, relative) }
                sendJson(exchange, 200, LanShareListing(listOfNotNull(stat?.toEntry(relative))))
            }
            path == "/v1/index" && exchange.requestMethod == "GET" -> withAuth(exchange, query) {
                sendJson(exchange, 200, indexShare(query["share"].orEmpty()))
            }
            path == "/v1/thumb" && exchange.requestMethod == "GET" ->
                withAuth(exchange, query) { serveThumbnail(exchange, query) }
            path == "/v1/content" && (exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD") ->
                withAuth(exchange, query) { streamContent(exchange, query["share"].orEmpty(), query["path"].orEmpty()) }
            else -> sendError(exchange, 404, "not_found")
        }
    }

    private fun handlePair(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes().decodeToString() }
        val request = runCatching { json.decodeFromString<LanPairRequest>(body) }.getOrNull()
            ?: return sendError(exchange, 400, "invalid_request")
        val expected = pairingCode
        if (expected.isBlank() || request.code.trim() != expected) {
            return sendError(exchange, 403, "invalid_code")
        }
        val token = newToken()
        val client = LanHostToken(
            token = token,
            clientDeviceId = request.clientDeviceId,
            clientName = request.clientName,
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        runBlocking {
            settings.update { current ->
                current.copy(hostTokens = (current.hostTokens.filterNot { it.clientDeviceId == client.clientDeviceId } + client).takeLast(MAX_HOST_TOKENS))
            }
        }
        sendJson(
            exchange,
            200,
            LanPairResponse(
                token = token,
                hostDeviceId = deviceId,
                hostName = deviceName,
                platform = platformName,
            ),
        )
    }

    private fun withAuth(exchange: HttpExchange, query: Map<String, String>, action: () -> Unit) {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val token = header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") }
            ?: query["token"]
            ?: ""
        val known = settings.current().hostTokens.any { it.token == token }
        if (!known) return sendError(exchange, 401, "unauthorized")
        action()
    }

    private fun publishedShares(): List<LanShareDescriptor> = runBlocking {
        repository.localMounts.all()
            .filter { it.lanShared && !it.isSmb }
            .map { LanShareDescriptor(shareId = it.mountId, name = it.fallbackName) }
    }

    private suspend fun mountFor(shareId: String): LocalMount? =
        repository.localMounts.all().firstOrNull { it.mountId == shareId && it.lanShared }

    private suspend fun listShare(shareId: String, relativePath: String): List<FileStat> {
        val mount = mountFor(shareId) ?: throw LanShareException("share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = true)
        return repository.localMounts.list(location.id)
    }

    private suspend fun statShare(shareId: String, relativePath: String): FileStat? {
        val mount = mountFor(shareId) ?: return null
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = true)
        repository.localMounts.probe(location.id)?.let { return it }
        if (relativePath.isEmpty()) return mount.toFileStat()
        val parentPath = relativePath.substringBeforeLast('/', "")
        val parent = LocalLocation(mount.mountId, pathSegments(parentPath), directory = true)
        return repository.localMounts.list(parent.id).firstOrNull { it.name == relativePath.substringAfterLast('/') }
    }

    private fun indexShare(shareId: String): LanShareIndex {
        indexCache[shareId]?.takeIf { System.currentTimeMillis() - it.atMs <= INDEX_TTL_MS }?.let { return it.index }
        val mount = runBlocking { mountFor(shareId) } ?: throw LanShareException("share_not_found")
        val entries = mutableListOf<LanShareEntry>()
        val queue = ArrayDeque<String>()
        queue += ""
        var truncated = false
        while (queue.isNotEmpty()) {
            val relative = queue.removeFirst()
            val files = runBlocking {
                val location = LocalLocation(mount.mountId, pathSegments(relative), directory = true)
                repository.localMounts.list(location.id)
            }
            for (file in files) {
                if (entries.size >= MAX_INDEX_ENTRIES) {
                    truncated = true
                    break
                }
                val childRelative = if (relative.isEmpty()) file.name else "$relative/${file.name}"
                entries += file.toEntry(childRelative)
                if (file.isFolder) queue += childRelative
            }
            if (truncated) break
        }
        val index = LanShareIndex(
            shareId = shareId,
            deviceName = deviceName,
            entries = entries,
            truncated = truncated,
        )
        indexCache[shareId] = CachedIndex(System.currentTimeMillis(), index)
        return index
    }

    private fun streamContent(exchange: HttpExchange, shareId: String, relativePath: String) {
        val mount = runBlocking { mountFor(shareId) } ?: return sendError(exchange, 404, "share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = false)
        val localPath = runBlocking { repository.localMounts.resolve(location.id) }
            ?: return sendError(exchange, 404, "not_found")
        if (localPath.startsWith("content://")) {
            return sendError(exchange, 501, "unsupported_host")
        }
        val file = File(localPath)
        if (!file.isFile) return sendError(exchange, 404, "not_found")
        val length = file.length()
        val lastModified = file.lastModified()
        val etag = "\"$length-$lastModified\""
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("ETag", etag)
        exchange.responseHeaders.set("Content-Type", contentTypeOf(file.name))
        val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
        if (ifNoneMatch == etag) {
            exchange.sendResponseHeaders(304, -1)
            return
        }
        val range = parseRange(exchange.requestHeaders.getFirst("Range"), length)
        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1).coerceAtLeast(0L)
        val count = if (length == 0L) 0L else (end - start + 1).coerceAtLeast(0L)
        if (range != null) {
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$length")
        }
        val status = if (range != null) 206 else 200
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.set("Content-Length", count.toString())
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, count)
        if (count <= 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            exchange.responseBody.use { out ->
                val buffer = ByteArray(STREAM_BUFFER)
                var remaining = count
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun serveThumbnail(exchange: HttpExchange, query: Map<String, String>) {
        val shareId = query["share"].orEmpty()
        val relativePath = query["path"].orEmpty()
        val width = query["w"]?.toIntOrNull()?.coerceIn(96, 1024) ?: 320
        val mount = runBlocking { mountFor(shareId) } ?: return sendError(exchange, 404, "share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = false)
        val localPath = runBlocking { repository.localMounts.resolve(location.id) }
            ?: return sendError(exchange, 404, "not_found")
        if (localPath.startsWith("content://")) return sendError(exchange, 501, "unsupported_host")
        val source = File(localPath)
        if (!source.isFile) return sendError(exchange, 404, "not_found")
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$shareId|$relativePath|${source.lastModified()}|$width".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = PikoHome.root.resolve("cache/lan-thumbs").toFile().apply { mkdirs() }
        val output = File(directory, "$key.jpg")
        if (!output.isFile || output.length() == 0L) {
            val image = runCatching { ImageIO.read(source) }.getOrNull()
                ?: return sendError(exchange, 415, "not_an_image")
            val targetWidth = minOf(width, image.width).coerceAtLeast(1)
            val targetHeight = (image.height.toLong() * targetWidth / image.width).toInt().coerceAtLeast(1)
            val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = scaled.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
            } finally {
                graphics.dispose()
            }
            val tmp = File.createTempFile("lan-thumb-", ".jpg", directory)
            try {
                ImageIO.write(scaled, "jpg", tmp)
                if (!tmp.renameTo(output)) tmp.copyTo(output, overwrite = true)
            } finally {
                tmp.delete()
            }
            directory.listFiles().orEmpty()
                .sortedByDescending { it.lastModified() }
                .drop(512)
                .forEach { it.delete() }
        }
        val data = output.readBytes()
        exchange.responseHeaders.set("Content-Type", "image/jpeg")
        exchange.responseHeaders.set("Cache-Control", "private, max-age=3600")
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
    }

    private fun FileStat.toEntry(relativePath: String): LanShareEntry = LanShareEntry(
        path = relativePath,
        name = name,
        folder = isFolder,
        size = sizeBytes,
        modifiedMs = modifiedTime.toLongOrNull() ?: runCatching { Instant.parse(modifiedTime).toEpochMilli() }.getOrDefault(0L),
        mimeType = mimeType,
        durationMs = params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() },
        width = params["width"]?.toIntOrNull() ?: 0,
        height = params["height"]?.toIntOrNull() ?: 0,
    )

    private fun sendJson(exchange: HttpExchange, status: Int, value: Any) {
        val body = when (value) {
            is LanDeviceInfo -> json.encodeToString(LanDeviceInfo.serializer(), value)
            is LanPairResponse -> json.encodeToString(LanPairResponse.serializer(), value)
            is LanShareListing -> json.encodeToString(LanShareListing.serializer(), value)
            is LanShareIndex -> json.encodeToString(LanShareIndex.serializer(), value)
            is List<*> -> json.encodeToString(ListSerializer(LanShareDescriptor.serializer()), value.filterIsInstance<LanShareDescriptor>())
            else -> "{}"
        }.encodeToByteArray()
        sendBody(exchange, status, body)
    }

    private fun sendError(exchange: HttpExchange, status: Int, code: String) {
        sendBody(exchange, status, """{"error":"$code"}""".encodeToByteArray())
    }

    private fun sendBody(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else {
                val key = URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8)
                key to value
            }
        }.toMap()
    }

    private fun pathSegments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    private fun parseRange(header: String?, length: Long): LongRange? {
        if (header == null || !header.startsWith("bytes=") || ',' in header) return null
        val spec = header.removePrefix("bytes=")
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', "")
        if (startText.isBlank()) {
            val suffix = endText.toLongOrNull()?.coerceAtLeast(1L) ?: return null
            val start = (length - suffix).coerceAtLeast(0L)
            return start..(length - 1).coerceAtLeast(0L)
        }
        val start = startText.toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start > end || start >= length) return null
        return start..end
    }

    private fun contentTypeOf(name: String): String =
        java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"

    private fun localAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    private fun newPairingCode(): String = (0 until 6).map { Random.nextInt(10) }.joinToString("")

    private fun newToken(): String = buildString {
        append('t')
        repeat(32) { append(HEX[Random.nextInt(HEX.length)]) }
    }

    private data class CachedIndex(val atMs: Long, val index: LanShareIndex)

    private companion object {
        const val TAG = "LanShareHost"
        const val HANDLER_THREADS = 6
        const val PORT_ATTEMPTS = 10
        const val STREAM_BUFFER = 64 * 1024
        const val INDEX_TTL_MS = 60_000L
        const val MAX_INDEX_ENTRIES = 50_000
        const val MAX_HOST_TOKENS = 32
        const val HEX = "0123456789abcdef"
    }
}
