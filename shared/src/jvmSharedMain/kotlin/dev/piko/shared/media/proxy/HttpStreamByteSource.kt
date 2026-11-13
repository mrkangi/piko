package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("HttpStreamReader"))
    private val lock = Any()

    private var positionValue = 0L

    private var generation = 0L
    private var closed = false
    private var pumpActive = false
    private var pumpJob: Job? = null
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var openedAt = 0L
    private var openedMark: TimeMark? = null
    private var openedBytes = 0L
    private var eof = false
    private var streamError: IOException? = null

    private var dataAvailableSignal: CompletableDeferred<Unit>? = null
    private var spaceAvailableSignal: CompletableDeferred<Unit>? = null

    /**
     * 环形内存窗口 `[windowStart, streamPosition)`，总容量 [RING_CAPACITY]（16 MiB）：
     * - 后台预读协程持续从底层 HTTP 流超前拉取最多 [MAX_READ_AHEAD_BYTES]（8 MiB），不随本机 `127.0.0.1` 短连接断开而停顿；
     * - 消费位置 [positionValue] 之前保留约 8 MiB 已读历史，吸收本机回环 socket 发送缓冲区超前吞入的偏移差，
     *   使非交错 MP4 音视频频繁切轨时全程在内存窗口内零等待命中，底层长连接不断开。
     */
    private val window = ByteArray(RING_CAPACITY)
    private var windowStart = 0L

    /** 后台预读已写入环形窗口的文件字节右边界（不含）。 */
    private var streamPosition = 0L

    override val position: Long
        get() = synchronized(lock) { positionValue }

    override val retainedStartPosition: Long
        get() = synchronized(lock) {
            if (hasUsableWindowLocked()) retainedStartLocked() else positionValue
        }

    override val retainedEndPosition: Long
        get() = synchronized(lock) {
            if (hasUsableWindowLocked() && streamPosition > positionValue) streamPosition else positionValue
        }

    private fun hasUsableWindowLocked(): Boolean =
        !closed && streamError == null && (pumpActive || streamPosition > windowStart)

    /** 留出两个网络读取块的安全余量，保证后台预读在途的一块写回时绝不覆盖对外承诺的可回退起点。 */
    private fun retainedStartLocked(): Long =
        maxOf(windowStart, streamPosition - MAX_SAFE_RETAIN_BYTES)

    override suspend fun seekTo(position: Long) {
        val target = position.coerceIn(0L, size)
        var toClose: RetiredConnection? = null
        synchronized(lock) {
            if (closed) return
            if (target == positionValue && (pumpActive || eof)) return
            if (hasUsableWindowLocked()) {
                val minStart = retainedStartLocked()
                // 1. 目标偏移落在已保留的内存窗口内（含回退重读与已超前预读部分）：只挪游标并唤醒预读泵
                if (target in minStart..streamPosition) {
                    positionValue = target
                    spaceAvailableSignal?.complete(Unit)
                    spaceAvailableSignal = null
                    return
                }
                // 2. 目标偏移在预读流前方不远处且后台流仍在运行：直接把消费目标设过去，让后台预读泵顺着推进
                val forwardDelta = target - streamPosition
                if (pumpActive && forwardDelta in 1..FORWARD_SKIP_BYTES) {
                    positionValue = target
                    spaceAvailableSignal?.complete(Unit)
                    spaceAvailableSignal = null
                    return
                }
            }
            // 3. 远距离跳转或当前无活动流：切换代次并启动新位置的后台预读
            toClose = restartPumpLocked(target)
        }
        toClose?.let(::retireAsync)
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        while (true) {
            var waitData: CompletableDeferred<Unit>? = null
            var toClose: RetiredConnection? = null
            var copied = 0
            var reachedEof = false
            var errorToThrow: IOException? = null

            synchronized(lock) {
                if (closed || positionValue >= size) return -1
                // 尚未启动预读，或消费位置已掉出窗口左边界，或前跳等待时底层流提前结束
                if ((!pumpActive && !eof && streamError == null) ||
                    positionValue < windowStart ||
                    (eof && streamPosition <= positionValue && positionValue < size)
                ) {
                    toClose = restartPumpLocked(positionValue)
                }
                if (positionValue in windowStart until streamPosition) {
                    val available = (streamPosition - positionValue).toInt()
                    val toCopy = minOf(length, available)
                    copyFromWindow(positionValue, buffer, offset, toCopy)
                    positionValue += toCopy
                    if (streamPosition - positionValue < MAX_READ_AHEAD_BYTES) {
                        spaceAvailableSignal?.complete(Unit)
                        spaceAvailableSignal = null
                    }
                    copied = toCopy
                } else if (streamError != null) {
                    errorToThrow = streamError
                    streamError = null
                    toClose = toClose ?: detachConnectionLocked()
                } else if (eof) {
                    reachedEof = true
                    toClose = toClose ?: detachConnectionLocked()
                } else {
                    val signal = dataAvailableSignal ?: CompletableDeferred<Unit>().also { dataAvailableSignal = it }
                    waitData = signal
                }
            }

            toClose?.let(::retireAsync)
            if (copied > 0) return copied
            errorToThrow?.let { throw it }
            if (reachedEof) return -1
            waitData?.await()
        }
    }

    private fun restartPumpLocked(startOffset: Long): RetiredConnection? {
        val retired = detachConnectionLocked()
        val gen = ++generation
        positionValue = startOffset
        openedAt = startOffset
        openedMark = TimeSource.Monotonic.markNow()
        openedBytes = 0L
        windowStart = startOffset
        streamPosition = startOffset
        eof = false
        streamError = null
        pumpActive = true
        dataAvailableSignal?.complete(Unit)
        dataAvailableSignal = null
        spaceAvailableSignal?.complete(Unit)
        spaceAvailableSignal = null
        pumpJob = readerScope.launch {
            pumpLoop(gen, startOffset)
        }
        return retired
    }

    private suspend fun pumpLoop(gen: Long, startOffset: Long) {
        var openedConn: HttpURLConnection? = null
        var openedStream: InputStream? = null
        try {
            val opened = URL(url).openConnection() as HttpURLConnection
            opened.connectTimeout = CONNECT_TIMEOUT_MILLIS
            opened.readTimeout = READ_TIMEOUT_MILLIS
            opened.setRequestProperty("Range", "bytes=$startOffset-")
            // 让服务端别压缩，字节位置才对得上
            opened.setRequestProperty("Accept-Encoding", "identity")
            val code = opened.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                opened.disconnect()
                throw IOException("HTTP $code")
            }
            openedConn = opened
            val stream = opened.inputStream
            // 服务端不认 Range 时回的是整份文件，跳过前 [startOffset] 字节才对齐
            if (code == HttpURLConnection.HTTP_OK && startOffset > 0L) skipFully(stream, startOffset)
            openedStream = stream

            val accepted = synchronized(lock) {
                if (gen == generation && !closed) {
                    connection = opened
                    input = stream
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                runCatching { stream.close() }
                runCatching { opened.disconnect() }
                return
            }

            val scratch = ByteArray(NETWORK_READ_CHUNK_BYTES)
            while (true) {
                var waitSpace: CompletableDeferred<Unit>? = null
                var toRead = 0
                synchronized(lock) {
                    if (gen != generation || closed) return
                    if (streamPosition >= size) {
                        eof = true
                        pumpActive = false
                        dataAvailableSignal?.complete(Unit)
                        dataAvailableSignal = null
                        return
                    }
                    val maxAllowedEnd = minOf(size, positionValue + MAX_READ_AHEAD_BYTES)
                    val room = maxAllowedEnd - streamPosition
                    if (room <= 0L) {
                        val signal = spaceAvailableSignal ?: CompletableDeferred<Unit>().also { spaceAvailableSignal = it }
                        waitSpace = signal
                    } else {
                        toRead = minOf(scratch.size.toLong(), room).toInt()
                    }
                }
                if (waitSpace != null) {
                    waitSpace.await()
                    continue
                }
                val count = stream.read(scratch, 0, toRead)
                if (count < 0) {
                    synchronized(lock) {
                        if (gen == generation) {
                            // 只有读满声明长度才算正常结束；提前 EOF 是上游断流，交给上层带退避重连。
                            if (streamPosition >= size) {
                                eof = true
                            } else {
                                streamError = IOException("HTTP 流提前结束：$streamPosition/$size")
                            }
                            pumpActive = false
                            dataAvailableSignal?.complete(Unit)
                            dataAvailableSignal = null
                        }
                    }
                    return
                }
                if (count > 0) {
                    while (true) {
                        var waitBeforeCommit: CompletableDeferred<Unit>? = null
                        synchronized(lock) {
                            if (gen != generation || closed) return
                            // 若读取期间游标被 seekTo 大幅回退到了窗口左边缘，等消费者推进后再写入，绝不覆盖 positionValue
                            if (streamPosition + count - positionValue > MAX_SAFE_RETAIN_BYTES) {
                                val signal = spaceAvailableSignal ?: CompletableDeferred<Unit>().also { spaceAvailableSignal = it }
                                waitBeforeCommit = signal
                            } else {
                                writeRing(streamPosition, scratch, 0, count)
                                streamPosition += count
                                openedBytes += count
                                val minStart = streamPosition - RING_CAPACITY
                                if (minStart > windowStart) {
                                    windowStart = minStart
                                }
                                dataAvailableSignal?.complete(Unit)
                                dataAvailableSignal = null
                            }
                        }
                        if (waitBeforeCommit != null) {
                            waitBeforeCommit.await()
                        } else {
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            val stillCurrent = synchronized(lock) { gen == generation && !closed }
            if (!stillCurrent) {
                runCatching { openedStream?.close() }
                runCatching { openedConn?.disconnect() }
                if (e is CancellationException) throw e
                return
            }
            if (e is CancellationException) throw e
            val ioError = e as? IOException ?: IOException(e)
            synchronized(lock) {
                if (gen == generation) {
                    streamError = ioError
                    pumpActive = false
                    dataAvailableSignal?.complete(Unit)
                    dataAvailableSignal = null
                }
            }
        }
    }

    private fun writeRing(filePosition: Long, source: ByteArray, offset: Int, length: Int) {
        val capacity = window.size
        val head = (filePosition % capacity.toLong()).toInt()
        val first = minOf(length, capacity - head)
        source.copyInto(window, destinationOffset = head, startIndex = offset, endIndex = offset + first)
        val second = length - first
        if (second > 0) {
            source.copyInto(window, destinationOffset = 0, startIndex = offset + first, endIndex = offset + length)
        }
    }

    private fun copyFromWindow(filePosition: Long, destination: ByteArray, offset: Int, length: Int) {
        val capacity = window.size
        val head = (filePosition % capacity.toLong()).toInt()
        val first = minOf(length, capacity - head)
        window.copyInto(destination, destinationOffset = offset, startIndex = head, endIndex = head + first)
        val second = length - first
        if (second > 0) {
            window.copyInto(destination, destinationOffset = offset + first, startIndex = 0, endIndex = second)
        }
    }

    private class RetiredConnection(
        val job: Job?,
        val input: InputStream?,
        val connection: HttpURLConnection?,
        val openedAt: Long,
        val openedMark: TimeMark?,
        val openedBytes: Long,
    )

    private fun detachConnectionLocked(): RetiredConnection? {
        val prevJob = pumpJob
        val prevInput = input
        val prevConn = connection
        val prevMark = openedMark
        val prevBytes = openedBytes
        val prevOpenedAt = openedAt
        pumpJob = null
        input = null
        connection = null
        openedMark = null
        openedBytes = 0L
        pumpActive = false
        if (prevJob == null && prevInput == null && prevConn == null && prevMark == null) return null
        return RetiredConnection(prevJob, prevInput, prevConn, prevOpenedAt, prevMark, prevBytes)
    }

    private fun retireAsync(retired: RetiredConnection) {
        retired.job?.cancel()
        readerScope.launch {
            retireBlocking(retired)
        }
    }

    private fun retireBlocking(retired: RetiredConnection) {
        retired.job?.cancel()
        runCatching { retired.input?.close() }
        runCatching { retired.connection?.disconnect() }
        val mark = retired.openedMark ?: return
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        if (retired.openedBytes <= 0L || elapsed <= 0L) return
        // 只记偏移与吞吐，不带 URL（里面有访问令牌）
        PikoLog.d(
            "JellyfinHttp",
            "从 ${retired.openedAt} 起读了 ${retired.openedBytes / 1024} KiB，用时 $elapsed ms，${retired.openedBytes * 1000 / elapsed / 1024} KB/s",
        )
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            dataAvailableSignal?.complete(Unit)
            dataAvailableSignal = null
            spaceAvailableSignal?.complete(Unit)
            spaceAvailableSignal = null
            detachConnectionLocked()
        }
        readerScope.cancel()
        if (retired != null) {
            Thread { retireBlocking(retired) }.start()
        }
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000

private const val RING_CAPACITY = 16 * 1024 * 1024
private const val MAX_READ_AHEAD_BYTES = 8L * 1024 * 1024
private const val NETWORK_READ_CHUNK_BYTES = 64 * 1024
private const val MAX_SAFE_RETAIN_BYTES = (RING_CAPACITY - NETWORK_READ_CHUNK_BYTES * 2).toLong()

/** 向前跳这么多以内就沿用现有后台预读流推进，超过就重开一次 Range 请求。 */
private const val FORWARD_SKIP_BYTES = 4L * 1024 * 1024

private fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    val scratch = ByteArray(64 * 1024)
    while (remaining > 0L) {
        val skipped = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (skipped < 0) throw IOException("跳过前缀时流已结束")
        remaining -= skipped
    }
}
