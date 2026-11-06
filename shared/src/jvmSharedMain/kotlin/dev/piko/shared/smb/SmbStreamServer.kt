package dev.piko.shared.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import kotlin.random.Random

/** 一次 SMB 读取的句柄；每个 HTTP 请求各开一个，响应结束就关。 */
internal class SmbReadHandle(
    val name: String,
    val size: Long,
    val modifiedMs: Long,
    val readAt: (offset: Long, buffer: ByteArray, offsetInBuffer: Int, length: Int) -> Int,
    private val closeBlock: () -> Unit,
) : AutoCloseable {
    override fun close() = closeBlock()
}

/**
 * 只给本机播放器/图片加载器用的只读 HTTP 服务。
 *
 * URL 带进程随机令牌，只绑回环地址；每个请求按 `Range` 重新打开 SMB 文件，不在内存里缓存整文件。
 */
internal class SmbStreamServer(
    private val opener: suspend (mountId: String, segments: List<String>) -> SmbReadHandle?,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val token = buildString {
        repeat(32) { append(HEX[Random.nextInt(HEX.length)]) }
    }
    private var server: ServerSocket? = null
    private var executor: ExecutorService? = null

    suspend fun url(mountId: String, segments: List<String>): String {
        val base = ensureStarted()
        val suffix = if (segments.isEmpty()) "" else "/" + segments.joinToString("/") {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(it.encodeToByteArray())
        }
        return "$base/$token/$mountId$suffix"
    }

    private suspend fun ensureStarted(): String = startLock.withLock {
        server?.let { return@withLock "http://127.0.0.1:${it.localPort}" }
        val bound = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        server = bound
        executor = Executors.newFixedThreadPool(THREADS).also { pool ->
            scope.launch {
                while (true) {
                    val socket = runCatching { bound.accept() }.getOrNull() ?: break
                    runCatching { pool.execute { serveSafely(socket) } }
                        .onFailure { runCatching { socket.close() } }
                }
            }
        }
        "http://127.0.0.1:${bound.localPort}"
    }

    private fun serveSafely(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            BufferedInputStream(socket.getInputStream()).use { input ->
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    val request = readRequest(input) ?: return
                    serve(request, output)
                    output.flush()
                }
            }
        } catch (_: Throwable) {
            // 播放器拖动时经常先断开旧请求；这里没有需要上报的错误。
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serve(request: HttpRequest, output: OutputStream) {
        val parts = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() != token) {
            writeResponse(output, 403, "Forbidden", emptyMap(), 0) {}
            return
        }
        val mountId = parts.getOrNull(1)
        if (mountId == null) {
            writeResponse(output, 404, "Not Found", emptyMap(), 0) {}
            return
        }
        val segments = runCatching {
            parts.drop(2).map {
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).decodeToString(throwOnInvalidSequence = true)
            }
        }.getOrNull()
        if (segments == null) {
            writeResponse(output, 400, "Bad Request", emptyMap(), 0) {}
            return
        }
        val handle = runCatching { runBlocking { opener(mountId, segments) } }.getOrNull()
        if (handle == null) {
            writeResponse(output, 404, "Not Found", emptyMap(), 0) {}
            return
        }
        try {
            val length = handle.size
            val etag = "\"$length-${handle.modifiedMs}\""
            if (request.headers["if-none-match"] == etag) {
                writeHead(output, 304, "Not Modified", mapOf("ETag" to etag))
                return
            }
            val range = parseRange(request.headers["range"], length)
            val start = range?.first ?: 0L
            val end = range?.last ?: (length - 1).coerceAtLeast(0L)
            val count = if (length == 0L) 0L else (end - start + 1).coerceAtLeast(0L)
            val headers = buildMap {
                put("Content-Type", contentTypeOf(handle.name))
                put("Accept-Ranges", "bytes")
                put("ETag", etag)
                put("Content-Length", count.toString())
                if (range != null) put("Content-Range", "bytes $start-$end/$length")
            }
            writeHead(output, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", headers)
            if (request.headOnly || count <= 0L) return
            val buffer = ByteArray(STREAM_BUFFER)
            var offset = start
            var remaining = count
            while (remaining > 0) {
                val read = handle.readAt(offset, buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                offset += read
                remaining -= read
            }
        } finally {
            runCatching { handle.close() }
        }
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
            if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return HttpRequest(method, target.substringBefore('?'), headers, method == "HEAD")
    }

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

    private fun parseRange(header: String?, length: Long): LongRange? {
        if (length <= 0L || header == null || !header.startsWith("bytes=") || ',' in header) return null
        val spec = header.removePrefix("bytes=")
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', "")
        if (startText.isBlank()) {
            val suffix = endText.toLongOrNull()?.coerceAtLeast(1L) ?: return null
            return (length - suffix).coerceAtLeast(0L)..(length - 1).coerceAtLeast(0L)
        }
        val start = startText.toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        if (start > end || start >= length) return null
        return start..end
    }

    private fun writeHead(output: OutputStream, status: Int, reason: String, headers: Map<String, String>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        builder.append("Connection: close\r\n")
        headers.forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }
        if ("Content-Length" !in headers && status != 304) builder.append("Content-Length: 0\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        length: Long,
        body: () -> Unit,
    ) {
        writeHead(output, status, reason, headers + ("Content-Length" to length.toString()))
        body()
    }

    override fun close() {
        scope.cancel()
        runCatching { server?.close() }
        runCatching { executor?.shutdownNow() }
        server = null
        executor = null
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val headOnly: Boolean,
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val THREADS = 4
        const val BACKLOG = 16
        const val STREAM_BUFFER = 64 * 1024
        const val MAX_HEADERS = 64
        const val MAX_HEADER_LINE = 8 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
        const val HEX = "0123456789abcdef"
    }
}

private fun contentTypeOf(name: String): String =
    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
