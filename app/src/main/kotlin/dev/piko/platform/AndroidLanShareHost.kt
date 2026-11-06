package dev.piko.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.lan.LanDeviceInfo
import dev.piko.shared.lan.LanHostStatus
import dev.piko.shared.lan.LanHostToken
import dev.piko.shared.lan.LanLocalSettings
import dev.piko.shared.lan.LanPairRequest
import dev.piko.shared.lan.LanPairResponse
import dev.piko.shared.lan.LanShareDescriptor
import dev.piko.shared.lan.LanShareEntry
import dev.piko.shared.lan.LanShareException
import dev.piko.shared.lan.LanShareHost
import dev.piko.shared.lan.LanShareIndex
import dev.piko.shared.lan.LanShareListing
import dev.piko.shared.lan.LanShareSettings
import dev.piko.shared.lan.UdpLanAnnouncer
import dev.piko.shared.lan.LanDiscoveryPacket
import dev.piko.shared.log.PikoLog
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Android 端的局域网共享 host。
 *
 * Android 没有 JDK 的 `HttpServer`，这里用 `ServerSocket` 实现协议所需的 HTTP/1.1 子集。
 * 本机目录经 SAF 暴露为 `content://`，内容读取用 `openFileDescriptor` 随机定位以支持 Range；
 * 缩略图用 `BitmapFactory` 解码后缩放并缓存。服务只读，不提供任何写操作。
 */
class AndroidLanShareHost(
    context: Context,
    private val repository: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val settings: LanShareSettings,
    private val deviceName: String,
    private val platformName: String,
    private val scope: CoroutineScope,
) : LanShareHost {
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(LanHostStatus())
    override val status: StateFlow<LanHostStatus> = _status.asStateFlow()

    private var server: ServerSocket? = null
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
        if (running || !settings.current().enabled) return
        scope.launch(Dispatchers.IO) {
            runCatching { startServer() }
                .onFailure { error ->
                    PikoLog.w(TAG, "局域网共享 host 启动失败", error)
                    _status.value = LanHostStatus(
                        error = error.message ?: tr("启动失败", "Failed to start", "起動に失敗しました", "시작 실패", "啟動失敗"),
                    )
                }
        }
    }

    override fun stop() {
        runBlocking {
            startLock.withLock {
                running = false
                runCatching { server?.close() }
                runCatching { executor?.shutdownNow() }
                announcer?.stop()
                server = null
                executor = null
                announcer = null
            }
        }
        _status.value = LanHostStatus()
    }

    override fun regeneratePairingCode() {
        pairingCode = newPairingCode()
        _status.value = _status.value.copy(pairingCode = pairingCode)
    }

    private suspend fun startServer() {
        startLock.withLock {
            if (running || !settings.current().enabled) return
            deviceId = preferences.loadOrCreateRemoteDeviceId()
            val desiredPort = settings.current().port.takeIf { it in 1..65535 } ?: LanLocalSettings.DEFAULT_PORT
            var bound: ServerSocket? = null
            var boundPort = desiredPort
            for (offset in 0 until PORT_ATTEMPTS) {
                val candidate = desiredPort + offset
                if (candidate > 65535) break
                val created = runCatching {
                    val socket = ServerSocket()
                    try {
                        socket.apply {
                            reuseAddress = true
                            bind(InetSocketAddress(candidate), BACKLOG)
                        }
                    } catch (error: Throwable) {
                        runCatching { socket.close() }
                        throw error
                    }
                }.getOrNull() ?: continue
                bound = created
                boundPort = candidate
                break
            }
            val socket = bound ?: error(tr("端口被占用", "Port is in use", "ポートが使用中です", "포트가 사용 중입니다", "連接埠已被占用"))
            if (!settings.current().enabled) {
                runCatching { socket.close() }
                return
            }
            val pool = Executors.newFixedThreadPool(HANDLER_THREADS)
            server = socket
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
            scope.launch(Dispatchers.IO) {
                while (running) {
                    val accepted = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        pool.execute { handleSafely(accepted) }
                    }.onFailure { runCatching { accepted.close() } }
                }
            }
            PikoLog.i(TAG, "局域网共享 host 已启动，端口 $boundPort")
        }
    }

    private fun handleSafely(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            BufferedInputStream(socket.getInputStream()).use { input ->
                BufferedOutputStream(socket.getOutputStream()).use { buffered ->
                    val output = TrackingOutputStream(buffered)
                    val request = readRequest(input) ?: return
                    handle(request, output)
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            PikoLog.w(TAG, "局域网共享请求处理失败", error)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(request: HttpRequest, output: OutputStream) {
        try {
            val query = parseQuery(request.query)
            when {
                request.path == "/v1/info" && request.method == "GET" -> {
                    sendJson(output, 200, LanDeviceInfo(deviceId = deviceId, deviceName = deviceName, platform = platformName), request.headOnly)
                }
                request.path == "/v1/pair" && request.method == "POST" -> handlePair(request, output)
                request.path == "/v1/shares" && request.method == "GET" -> withAuth(request, query, output) {
                    sendJson(output, 200, publishedShares(), request.headOnly)
                }
                request.path == "/v1/list" && request.method == "GET" -> withAuth(request, query, output) {
                    val shareId = query["share"].orEmpty()
                    val relative = query["path"].orEmpty()
                    val files = runBlocking { listShare(shareId, relative) }
                    sendJson(
                        output,
                        200,
                        LanShareListing(files.map { it.toEntry(if (relative.isEmpty()) it.name else "$relative/${it.name}") }),
                        request.headOnly,
                    )
                }
                request.path == "/v1/stat" && request.method == "GET" -> withAuth(request, query, output) {
                    val shareId = query["share"].orEmpty()
                    val relative = query["path"].orEmpty()
                    val stat = runBlocking { statShare(shareId, relative) }
                    sendJson(output, 200, LanShareListing(listOfNotNull(stat?.toEntry(relative))), request.headOnly)
                }
                request.path == "/v1/index" && request.method == "GET" -> withAuth(request, query, output) {
                    sendJson(output, 200, indexShare(query["share"].orEmpty()), request.headOnly)
                }
                request.path == "/v1/thumb" && request.method == "GET" -> withAuth(request, query, output) {
                    serveThumbnail(output, query, request.headOnly)
                }
                request.path == "/v1/content" && (request.method == "GET" || request.method == "HEAD") ->
                    withAuth(request, query, output) {
                        streamContent(request, output, query["share"].orEmpty(), query["path"].orEmpty())
                    }
                else -> sendError(output, 404, "not_found")
            }
        } catch (error: Throwable) {
            PikoLog.w(TAG, "局域网共享请求失败：${request.path}", error)
            if (output !is TrackingOutputStream || !output.started) {
                val status = when (error) {
                    is LanShareException -> 404
                    is IllegalArgumentException -> 400
                    else -> 500
                }
                val code = when (status) {
                    404 -> error.message ?: "not_found"
                    400 -> "invalid_request"
                    else -> "internal"
                }
                runCatching { sendError(output, status, code) }
            }
        }
    }

    private fun handlePair(request: HttpRequest, output: OutputStream) {
        val parsed = runCatching { json.decodeFromString<LanPairRequest>(request.body.decodeToString()) }.getOrNull()
            ?: return sendError(output, 400, "invalid_request")
        val expected = pairingCode
        if (expected.isBlank() || parsed.code.trim() != expected) {
            return sendError(output, 403, "invalid_code")
        }
        val client = LanHostToken(
            token = newToken(),
            clientDeviceId = parsed.clientDeviceId,
            clientName = parsed.clientName,
            createdAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        runBlocking {
            settings.update { current ->
                current.copy(hostTokens = (current.hostTokens.filterNot { it.clientDeviceId == client.clientDeviceId } + client).takeLast(MAX_HOST_TOKENS))
            }
        }
        sendJson(
            output,
            200,
            LanPairResponse(
                token = client.token,
                hostDeviceId = deviceId,
                hostName = deviceName,
                platform = platformName,
            ),
            request.headOnly,
        )
    }

    private fun withAuth(
        request: HttpRequest,
        query: Map<String, String>,
        output: OutputStream,
        action: () -> Unit,
    ) {
        val header = request.headers["authorization"].orEmpty()
        val token = if (header.startsWith("Bearer ")) header.removePrefix("Bearer ") else query["token"].orEmpty()
        if (settings.current().hostTokens.none { it.token == token }) {
            return sendError(output, 401, "unauthorized")
        }
        action()
    }

    private fun publishedShares(): List<LanShareDescriptor> = runBlocking {
        repository.localMounts.all()
            .filter { it.lanShared && !it.isSmb }
            .map { LanShareDescriptor(shareId = it.mountId, name = it.fallbackName) }
    }

    private suspend fun mountFor(shareId: String): LocalMount? =
        repository.localMounts.all().firstOrNull { it.mountId == shareId && it.lanShared && !it.isSmb }

    private suspend fun listShare(shareId: String, relativePath: String): List<FileStat> {
        val mount = mountFor(shareId) ?: throw LanShareException("share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = true)
        return repository.localMounts.list(location.id)
    }

    private suspend fun statShare(shareId: String, relativePath: String): FileStat? {
        val mount = mountFor(shareId) ?: return null
        if (relativePath.isEmpty()) return mount.toFileStat()
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = true)
        repository.localMounts.probe(location.id)?.let { return it }
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

    private fun streamContent(request: HttpRequest, output: OutputStream, shareId: String, relativePath: String) {
        val mount = runBlocking { mountFor(shareId) } ?: return sendError(output, 404, "share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = false)
        val localPath = runBlocking { repository.localMounts.resolve(location.id) }
            ?: return sendError(output, 404, "not_found")
        if (localPath.startsWith("content://")) {
            streamContentUri(request, output, localPath, relativePath)
        } else {
            streamFile(request, output, File(localPath))
        }
    }

    private fun streamContentUri(request: HttpRequest, output: OutputStream, rawUri: String, relativePath: String) {
        val uri = Uri.parse(rawUri)
        val length = contentLength(uri)
        val etag = "\"$length-${rawUri.hashCode()}\""
        val contentType = context.contentResolver.getType(uri)
            ?: contentTypeOf(relativePath)
        if (request.headers["if-none-match"] == etag) {
            writeHead(output, 304, "Not Modified", mapOf("ETag" to etag), 0)
            return
        }
        val range = parseLanHttpRange(request.headers["range"], length)
        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1).coerceAtLeast(0L)
        val count = if (length == 0L) 0L else (end - start + 1).coerceAtLeast(0L)
        writeContentHead(output, range, start, end, length, count, etag, contentType)
        if (request.headOnly || count <= 0L) return
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        if (descriptor == null) {
            PikoLog.w(TAG, "无法打开共享文件")
            return
        }
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            stream.channel.position(start)
            copyRange(stream, output, count)
        }
    }

    private fun streamFile(request: HttpRequest, output: OutputStream, file: File) {
        if (!file.isFile) return sendError(output, 404, "not_found")
        val length = file.length()
        val etag = "\"$length-${file.lastModified()}\""
        if (request.headers["if-none-match"] == etag) {
            writeHead(output, 304, "Not Modified", mapOf("ETag" to etag), 0)
            return
        }
        val range = parseLanHttpRange(request.headers["range"], length)
        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1).coerceAtLeast(0L)
        val count = if (length == 0L) 0L else (end - start + 1).coerceAtLeast(0L)
        writeContentHead(output, range, start, end, length, count, etag, contentTypeOf(file.name))
        if (request.headOnly || count <= 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            copyRange(raf, output, count)
        }
    }

    private fun writeContentHead(
        output: OutputStream,
        range: LongRange?,
        start: Long,
        end: Long,
        length: Long,
        count: Long,
        etag: String,
        contentType: String,
    ) {
        val headers = buildMap {
            put("Content-Type", contentType)
            put("Accept-Ranges", "bytes")
            put("ETag", etag)
            put("Content-Length", count.toString())
            if (range != null) put("Content-Range", "bytes $start-$end/$length")
        }
        writeHead(output, if (range != null) 206 else 200, statusText(if (range != null) 206 else 200), headers, count)
    }

    private fun copyRange(source: InputStream, output: OutputStream, count: Long) {
        val buffer = ByteArray(STREAM_BUFFER)
        var remaining = count
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun copyRange(source: RandomAccessFile, output: OutputStream, count: Long) {
        val buffer = ByteArray(STREAM_BUFFER)
        var remaining = count
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun contentLength(uri: Uri): Long {
        val resolver = context.contentResolver
        val fromColumns = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        if (fromColumns != null && fromColumns >= 0) return fromColumns
        return runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 } }.getOrNull() ?: 0L
    }

    private fun localSignature(localPath: String): String {
        if (!localPath.startsWith("content://")) {
            val file = File(localPath)
            return "${file.length()}-${file.lastModified()}"
        }
        val uri = Uri.parse(localPath)
        val modified = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L
        return "${contentLength(uri)}-$modified"
    }

    private fun serveThumbnail(output: OutputStream, query: Map<String, String>, headOnly: Boolean) {
        val shareId = query["share"].orEmpty()
        val relativePath = query["path"].orEmpty()
        val width = query["w"]?.toIntOrNull()?.coerceIn(96, 1024) ?: 320
        val mount = runBlocking { mountFor(shareId) } ?: return sendError(output, 404, "share_not_found")
        val location = LocalLocation(mount.mountId, pathSegments(relativePath), directory = false)
        val localPath = runBlocking { repository.localMounts.resolve(location.id) }
            ?: return sendError(output, 404, "not_found")
        val cacheDirectory = File(context.cacheDir, "lan-thumbs").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$shareId|$relativePath|$localPath|${localSignature(localPath)}|$width".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(cacheDirectory, "$key.jpg")
        if (!cached.isFile || cached.length() == 0L) {
            val bitmap = decodeThumbnail(localPath, width) ?: return sendError(output, 415, "not_an_image")
            try {
                val tmp = File.createTempFile("lan-thumb-", ".jpg", cacheDirectory)
                try {
                    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    if (!tmp.renameTo(cached)) tmp.copyTo(cached, overwrite = true)
                } finally {
                    tmp.delete()
                }
            } finally {
                bitmap.recycle()
            }
            cacheDirectory.listFiles().orEmpty()
                .sortedByDescending { it.lastModified() }
                .drop(THUMB_CACHE_LIMIT)
                .forEach { it.delete() }
        }
        val data = cached.readBytes()
        writeResponse(output, 200, "OK", "image/jpeg", data.size.toLong(), mapOf("Cache-Control" to "private, max-age=3600"), headOnly) {
            if (!headOnly) output.write(data)
        }
    }

    private fun decodeThumbnail(localPath: String, width: Int): Bitmap? {
        val uri = localPath.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        fun open(): InputStream? = if (uri != null) {
            context.contentResolver.openInputStream(uri)
        } else {
            File(localPath).takeIf { it.isFile }?.inputStream()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = max(1, Integer.highestOneBit(max(1, bounds.outWidth / width)))
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        if (decoded.width <= width) return decoded
        val targetHeight = max(1, decoded.height * width / decoded.width)
        val scaled = Bitmap.createScaledBitmap(decoded, width, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun FileStat.toEntry(relativePath: String): LanShareEntry = LanShareEntry(
        path = relativePath,
        name = name,
        folder = isFolder,
        size = sizeBytes,
        modifiedMs = modifiedTime.toLongOrNull()
            ?: runCatching { Instant.parse(modifiedTime).toEpochMilli() }.getOrDefault(0L),
        mimeType = mimeType,
        durationMs = params["duration"]?.toDoubleOrNull()?.let { (it * 1000).toLong() },
        width = params["width"]?.toIntOrNull() ?: 0,
        height = params["height"]?.toIntOrNull() ?: 0,
    )

    private fun sendJson(output: OutputStream, status: Int, value: Any, headOnly: Boolean) {
        val body = when (value) {
            is LanDeviceInfo -> json.encodeToString(LanDeviceInfo.serializer(), value)
            is LanPairResponse -> json.encodeToString(LanPairResponse.serializer(), value)
            is LanShareListing -> json.encodeToString(LanShareListing.serializer(), value)
            is LanShareIndex -> json.encodeToString(LanShareIndex.serializer(), value)
            is List<*> -> json.encodeToString(ListSerializer(LanShareDescriptor.serializer()), value.filterIsInstance<LanShareDescriptor>())
            else -> "{}"
        }.encodeToByteArray()
        writeResponse(output, status, statusText(status), "application/json; charset=utf-8", body.size.toLong(), emptyMap(), headOnly) {
            if (!headOnly) output.write(body)
        }
    }

    private fun sendError(output: OutputStream, status: Int, code: String) {
        val body = """{"error":"$code"}""".encodeToByteArray()
        writeResponse(output, status, statusText(status), "application/json; charset=utf-8", body.size.toLong(), emptyMap(), false) {
            output.write(body)
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        length: Long,
        extraHeaders: Map<String, String>,
        headOnly: Boolean,
        body: () -> Unit,
    ) {
        writeHead(output, status, reason, mapOf("Content-Type" to contentType) + extraHeaders, length)
        if (!headOnly) body()
    }

    private fun writeHead(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        length: Long,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        builder.append("Connection: close\r\n")
        headers.forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }
        if ("Content-Length" !in headers && status != 304) {
            builder.append("Content-Length: ").append(length).append("\r\n")
        }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = mutableMapOf<String, String>()
        var remainingHeaders = MAX_HEADERS
        while (remainingHeaders-- > 0) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toLongOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) return null
                offset += read
            }
            buffer
        } else {
            ByteArray(0)
        }
        val path = target.substringBefore('?')
        return HttpRequest(
            method = method,
            path = path,
            query = target.substringAfter('?', ""),
            headers = headers,
            body = body,
            headOnly = method == "HEAD",
        )
    }

    /** 读取 CRLF/LF 行；超过 HTTP 头部上限视为坏请求。 */
    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= MAX_HEADER_LINE) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().decodeToString()
            }
            bytes += value.toByte()
        }
        return null
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

    private fun pathSegments(path: String): List<String> {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "invalid_path" }
        return segments
    }

    private fun contentTypeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun localAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
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

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        415 -> "Unsupported Media Type"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val headOnly: Boolean,
    )

    private data class CachedIndex(val atMs: Long, val index: LanShareIndex)

    private class TrackingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var started: Boolean = false
            private set

        override fun write(value: Int) {
            started = true
            delegate.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > 0) started = true
            delegate.write(buffer, offset, length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val TAG = "AndroidLanShareHost"
        const val HANDLER_THREADS = 6
        const val PORT_ATTEMPTS = 10
        const val BACKLOG = 32
        const val STREAM_BUFFER = 64 * 1024
        const val INDEX_TTL_MS = 60_000L
        const val MAX_INDEX_ENTRIES = 50_000
        const val MAX_HOST_TOKENS = 32
        const val MAX_HEADERS = 64
        const val MAX_HEADER_LINE = 8 * 1024
        const val MAX_BODY = 64 * 1024L
        const val SOCKET_TIMEOUT_MS = 30_000
        const val THUMB_CACHE_LIMIT = 512
        const val HEX = "0123456789abcdef"
    }
}

/** 解析单段 `Range`；多段、越界或非法值退回完整内容。 */
internal fun parseLanHttpRange(header: String?, length: Long): LongRange? {
    if (length <= 0L) return null
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
