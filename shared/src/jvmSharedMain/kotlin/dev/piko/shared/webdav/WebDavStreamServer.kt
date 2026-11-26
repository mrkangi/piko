package dev.piko.shared.webdav

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import kotlin.random.Random

/** 一次 WebDAV 读取的目标：直接可请求的地址与可选的 Basic 认证头。 */
internal class WebDavTarget(val url: String, val authorization: String?)

/**
 * 只给本机播放器/图片加载器用的只读 HTTP 转发服务。
 *
 * 播放器与图片加载器只认不带凭据的本机地址；每次请求都在这里补上认证并转发 `Range`，
 * 上游返回的 206/200 与长度、区间等响应头原样交回，不在内存里缓存整文件。
 */
internal class WebDavStreamServer(
    private val http: () -> HttpClient,
    private val resolver: suspend (mountId: String, segments: List<String>) -> WebDavTarget?,
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
                    runBlocking { serve(request, output) }
                    output.flush()
                }
            }
        } catch (_: Throwable) {
            // 播放器拖动时经常先断开旧请求；这里没有需要上报的错误。
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun serve(request: HttpRequest, output: OutputStream) {
        val parts = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() != token) {
            writeHead(output, 403, "Forbidden", emptyMap())
            return
        }
        val mountId = parts.getOrNull(1)
        if (mountId == null) {
            writeHead(output, 404, "Not Found", emptyMap())
            return
        }
        val segments = runCatching {
            parts.drop(2).map {
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(it).decodeToString(throwOnInvalidSequence = true)
            }
        }.getOrNull()
        if (segments == null) {
            writeHead(output, 400, "Bad Request", emptyMap())
            return
        }
        val target = runCatching { resolver(mountId, segments) }.getOrNull()
        if (target == null) {
            writeHead(output, 404, "Not Found", emptyMap())
            return
        }
        val method = if (request.headOnly) HttpMethod.Head else HttpMethod.Get
        try {
            http().prepareRequest(target.url) {
                this.method = method
                target.authorization?.let { header(HttpHeaders.Authorization, it) }
                request.headers["range"]?.let { header(HttpHeaders.Range, it) }
                request.headers["if-range"]?.let { header(HttpHeaders.IfRange, it) }
                request.headers["if-none-match"]?.let { header(HttpHeaders.IfNoneMatch, it) }
                request.headers["if-modified-since"]?.let { header(HttpHeaders.IfModifiedSince, it) }
            }.execute { response ->
                val headers = buildMap {
                    response.headers[HttpHeaders.ContentType]?.let { put(HttpHeaders.ContentType, it) }
                    response.headers[HttpHeaders.ContentLength]?.let { put(HttpHeaders.ContentLength, it) }
                    response.headers[HttpHeaders.ContentRange]?.let { put(HttpHeaders.ContentRange, it) }
                    response.headers[HttpHeaders.AcceptRanges]?.let { put(HttpHeaders.AcceptRanges, it) }
                    response.headers[HttpHeaders.ETag]?.let { put(HttpHeaders.ETag, it) }
                    response.headers[HttpHeaders.LastModified]?.let { put(HttpHeaders.LastModified, it) }
                }
                writeHead(output, response.status.value, response.status.description, headers)
                if (request.headOnly) return@execute
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(STREAM_BUFFER)
                while (true) {
                    val read = channel.readAvailable(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
            }
        } catch (_: Throwable) {
            runCatching { writeHead(output, 502, "Bad Gateway", emptyMap()) }
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") return null
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

    private fun writeHead(output: OutputStream, status: Int, reason: String, headers: Map<String, String>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        builder.append("Connection: close\r\n")
        headers.forEach { (name, value) ->
            builder.append(name).append(": ").append(value.replace("\r", "").replace("\n", "")).append("\r\n")
        }
        if (HttpHeaders.ContentLength !in headers && status != 304) builder.append("Content-Length: 0\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.US_ASCII))
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
        const val THREADS = 8
        const val BACKLOG = 32
        const val STREAM_BUFFER = 64 * 1024
        const val MAX_HEADERS = 64
        const val MAX_HEADER_LINE = 8 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
        const val HEX = "0123456789abcdef"
    }
}
