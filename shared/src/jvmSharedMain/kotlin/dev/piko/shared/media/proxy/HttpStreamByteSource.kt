package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlin.time.TimeMark

/**
 * 直连 HTTP 地址（Jellyfin 等）的字节来源：一条连接顺序读，读到哪交到哪。
 *
 * 存在的理由不是并发，而是换 HTTP 栈。播放器内置的 FFmpeg HTTP 只用很小的 TCP 接收缓冲，
 * 跨公网 RTT 一高，单流吞吐就被带宽时延积压在 3~4 Mbps（实测 480 KB/s）；同样的地址
 * 浏览器与 Findroid 走系统 OkHttp，缓冲自适应该链路，能跑满。这里走 [HttpURLConnection]
 * （Android 上就是 OkHttp），把读到的字节经本机代理喂给播放器，行为与直读一致，只换传输层。
 */
internal class HttpStreamByteSource(
    private val url: String,
    override val size: Long,
) : ProxyByteSource {

    override suspend fun openReader(): ProxyReader = HttpStreamReader(url, size)

    override fun close() = Unit
}

/** 给直连 HTTP 播放装上系统 HTTP 栈的读取通道；长度未知时先探一次，探不到就退回播放器直读。 */
fun PikoMediaRepository.installHttpStreamSource() {
    httpStreamSourceFactory = { url, sizeBytes ->
        val size = sizeBytes.takeIf { it > 0L } ?: probeHttpContentLength(url)
        size?.takeIf { it > 0L }?.let { HttpStreamByteSource(url, it) }
    }
}

/** 读一次 Range 0-0，从 Content-Range 里取总长度。取不到返回 null。 */
internal suspend fun probeHttpContentLength(url: String): Long? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) return@runCatching null
            // Content-Range: bytes 0-0/1234567
            connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { it > 0 }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private class HttpStreamReader(
    private val url: String,
    private val size: Long,
) : ProxyReader {

    @Volatile
    private var positionValue = 0L

    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var openedAt = 0L
    private var openedMark: TimeMark? = null
    private var openedBytes = 0L

    override val position: Long get() = positionValue

    override suspend fun seekTo(position: Long) {
        val target = position.coerceIn(0L, size)
        val stream = input
        val delta = target - positionValue
        // 向前的小跳跃直接在现有连接上丢掉这一段，不为几十 KB 重开一次请求：
        // 有些文件会让播放器频繁小步前进，每次重连都有一次 Jellyfin 往返，吞吐就掉到几十 KB/s
        if (stream != null && delta in 1..FORWARD_SKIP_BYTES) {
            val skipped = runCatching {
                withContext(Dispatchers.IO) {
                    var remaining = delta
                    val scratch = ByteArray(64 * 1024)
                    while (remaining > 0L) {
                        val count = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        remaining -= count
                        openedBytes += count
                    }
                    delta - remaining
                }
            }.getOrElse {
                closeConnection()
                positionValue = target
                return
            }
            if (skipped == delta) {
                positionValue = target
                return
            }
            closeConnection()
            positionValue = target
            return
        }
        closeConnection()
        positionValue = target
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (positionValue >= size) return -1
        val stream = ensureOpen() ?: return -1
        val count = try {
            stream.read(buffer, offset, length)
        } catch (error: IOException) {
            closeConnection()
            throw error
        }
        if (count < 0) {
            closeConnection()
            return -1
        }
        positionValue += count
        openedBytes += count
        return count
    }

    private suspend fun ensureOpen(): InputStream? {
        input?.let { return it }
        return withContext(Dispatchers.IO) {
            val start = positionValue
            val opened = URL(url).openConnection() as HttpURLConnection
            opened.connectTimeout = CONNECT_TIMEOUT_MILLIS
            opened.readTimeout = READ_TIMEOUT_MILLIS
            opened.setRequestProperty("Range", "bytes=$start-")
            // 让服务端别压缩，字节位置才对得上
            opened.setRequestProperty("Accept-Encoding", "identity")
            val code = opened.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                opened.disconnect()
                throw IOException("HTTP $code")
            }
            connection = opened
            openedAt = start
            openedMark = TimeSource.Monotonic.markNow()
            openedBytes = 0L
            val stream = opened.inputStream
            // 服务端不认 Range 时回的是整份文件，跳过前 [start] 字节才对齐
            if (code == HttpURLConnection.HTTP_OK && start > 0L) skipFully(stream, start)
            input = stream
            stream
        }
    }

    private fun closeConnection() {
        runCatching { input?.close() }
        input = null
        runCatching { connection?.disconnect() }
        connection = null
        val mark = openedMark ?: return
        openedMark = null
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        if (openedBytes <= 0L || elapsed <= 0L) return
        // 只记偏移与吞吐，不带 URL（里面有访问令牌）
        PikoLog.d(
            "JellyfinHttp",
            "从 $openedAt 起读了 ${openedBytes / 1024} KiB，用时 $elapsed ms，${openedBytes * 1000 / elapsed / 1024} KB/s",
        )
    }

    override fun close() {
        closeConnection()
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000

/** 向前跳这么多以内就沿用现有连接丢字节，超过就重开一次 Range 请求。 */
private const val FORWARD_SKIP_BYTES = 2L * 1024 * 1024

private fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    val scratch = ByteArray(64 * 1024)
    while (remaining > 0L) {
        val skipped = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (skipped < 0) throw IOException("跳过前缀时流已结束")
        remaining -= skipped
    }
}
