package dev.piko.shared.media.proxy

import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代理协议层的冒烟：起真实的回环代理，用原始 socket 发请求。
 *
 * 数据源的 reader 会检测自己被并发调用：SDK 的 reader 一个读位置一个调用方，被并发调用时不会报错，
 * 只会悄悄交出错位的字节，所以每个请求用各自的 reader 这一点只能在这里抓。
 */
class PikoMediaProxyTest {
    private val proxy = PikoMediaProxy()

    @AfterTest
    fun tearDown() = proxy.close()

    @Test
    fun servesRangesWithCorrectHeadersAndBytes() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val url = proxy.register(source, "movie.mkv").url

        val open = request(url, range = "bytes=1000-")
        assertEquals(206, open.status)
        assertEquals("bytes 1000-999999/1000000", open.headers["content-range"])
        assertEquals("999000", open.headers["content-length"])
        assertContentEquals(source.expected(1000, 1_000_000), open.body)

        val bounded = request(url, range = "bytes=10-19")
        assertEquals(206, bounded.status)
        assertContentEquals(source.expected(10, 20), bounded.body)

        val suffix = request(url, range = "bytes=-100")
        assertEquals("bytes 999900-999999/1000000", suffix.headers["content-range"])
        assertContentEquals(source.expected(999_900, 1_000_000), suffix.body)

        val whole = request(url)
        assertEquals(200, whole.status)
        assertEquals("bytes", whole.headers["accept-ranges"])
        assertEquals(1_000_000, whole.body.size)

        // FFmpeg 探到文件尾之后会发这种请求，回错状态码它会当成读错误
        val beyond = request(url, range = "bytes=1000000-")
        assertEquals(416, beyond.status)
        assertEquals("bytes */1000000", beyond.headers["content-range"])

        val head = request(url, "HEAD", range = "bytes=0-")
        assertEquals(206, head.status)
        assertEquals("1000000", head.headers["content-length"])

        assertEquals(0, source.concurrencyViolations.get())
    }

    // 交错得不好的 MP4 一直在两处来回读，一个请求掐断另一个，两边都读不完
    @Test
    fun overlappingRequestsAreServedSideBySide() = runBlocking {
        // 读得慢，保证第一个请求在第二个到来时还在读
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        val first = openRequest(url, range = "bytes=0-999999")
        first.body.readNBytes(100_000)

        val second = withTimeout(10_000) { request(url, range = "bytes=8000000-8099999") }
        assertEquals(206, second.status)
        assertContentEquals(source.expected(8_000_000, 8_100_000), second.body)

        val rest = withTimeout(10_000) { first.body.readAllBytes() }
        assertContentEquals(source.expected(100_000, 1_000_000), rest, "the earlier request was cut off")
        first.socket.close()

        assertEquals(0, source.concurrencyViolations.get(), "one reader was used by two requests")
    }

    @Test
    fun clientDisconnectReleasesTheReaderForTheNextRequest() = runBlocking {
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        val dropped = openRequest(url, range = "bytes=0-")
        dropped.body.readNBytes(50_000)
        dropped.socket.close()
        // 断开之后读端的看门协程应当取消响应，reader 上不再有人读
        withTimeout(5_000) {
            while (source.activeReads.get() > 0) delay(10)
        }

        val next = request(url, range = "bytes=100-199")
        assertContentEquals(source.expected(100, 200), next.body)
        assertEquals(0, source.concurrencyViolations.get())
    }

    @Test
    fun transientPrematureEofIsRetriedWithoutTruncatingTheResponse() = runBlocking {
        val source = FakeSource(size = 2 * 1024 * 1024, failAt = 700_000L, failures = 1)
        val url = proxy.register(source, "movie.mp4").url

        val response = withTimeout(10_000) { request(url, range = "bytes=0-") }
        assertEquals(206, response.status)
        assertContentEquals(source.expected(0, 2 * 1024 * 1024), response.body)
        assertEquals(1, source.shortReads.get())
    }

    @Test
    fun transientReadExceptionIsRetriedWithoutTruncatingTheResponse() = runBlocking {
        val source = FakeSource(size = 2 * 1024 * 1024, failAt = 900_000L, failures = 1, throwOnFailure = true)
        val url = proxy.register(source, "movie.mp4").url

        val response = withTimeout(10_000) { request(url, range = "bytes=0-") }
        assertEquals(206, response.status)
        assertContentEquals(source.expected(0, 2 * 1024 * 1024), response.body)
        assertEquals(1, source.thrownFailures.get())
    }

    // 错一个偏移，播放器拿到的是另一处的字节，TS 照样能解，只是画面不在该在的时刻
    @Test
    fun aSliceServesItsWindowAsAWholeFile() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val url = proxy.register(SlicedByteSource(source, offset = 300_000, size = 200_000), "clip.ts").url

        val whole = request(url)
        assertEquals("200000", whole.headers["content-length"])
        assertContentEquals(source.expected(300_000, 500_000), whole.body)

        val tail = request(url, range = "bytes=199000-")
        assertEquals("bytes 199000-199999/200000", tail.headers["content-range"])
        assertContentEquals(source.expected(499_000, 500_000), tail.body)
    }

    @Test
    fun sequentialAndInterleavedShortRangesReusePooledReadersWithoutExtraSeeks() = runBlocking {
        val source = FakeSource(size = 32 * 1024 * 1024)
        val url = proxy.register(source, "interleaved.mp4").url

        // 模拟交错不良的 MP4 在视频轨（1 MiB 起）与音轨（16 MiB 起）之间交替发短 Range 请求
        var videoPos = 1_000_000L
        var audioPos = 16_000_000L
        repeat(10) {
            val vChunk = request(url, range = "bytes=$videoPos-${videoPos + 16_383}")
            assertEquals(206, vChunk.status)
            assertContentEquals(source.expected(videoPos, videoPos + 16_384), vChunk.body)
            videoPos += 16_384

            val aChunk = request(url, range = "bytes=$audioPos-${audioPos + 4_095}")
            assertEquals(206, aChunk.status)
            assertContentEquals(source.expected(audioPos, audioPos + 4_096), aChunk.body)
            audioPos += 4_096
        }

        // 20 个交替短请求只应建起视频、音频两个游标，且各自仅首次定位一次，后续全部原地零 seek 接续
        assertEquals(2, source.openedReaders.get(), "openedReaders=${source.openedReaders.get()}")
        assertEquals(2, source.seekCalls.get(), "seekCalls=${source.seekCalls.get()}")
        assertEquals(0, source.concurrencyViolations.get())
    }

    @Test
    fun httpStreamSourceReusesTwoLongLivedConnectionsAcrossAbortedOpenEndedPingPongRanges() = runBlocking {
        val fileSize = 64 * 1024 * 1024
        val upstreamRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/video.mp4") { exchange ->
            upstreamRequests.incrementAndGet()
            val rangeHeader = exchange.requestHeaders.getFirst("Range").orEmpty()
            val start = rangeHeader.substringAfter("bytes=", "0").substringBefore('-').toLongOrNull() ?: 0L
            val remaining = fileSize - start
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Range", "bytes $start-${fileSize - 1}/$fileSize")
            exchange.sendResponseHeaders(206, remaining)
            runCatching {
                exchange.responseBody.use { out ->
                    val chunk = ByteArray(32 * 1024)
                    var pos = start
                    while (pos < fileSize) {
                        val n = minOf(chunk.size.toLong(), fileSize - pos).toInt()
                        for (i in 0 until n) chunk[i] = ((pos + i) % 251).toByte()
                        out.write(chunk, 0, n)
                        pos += n
                    }
                }
            }
        }
        server.start()
        try {
            val upstreamUrl = "http://127.0.0.1:${server.address.port}/video.mp4"
            val source = HttpStreamByteSource(upstreamUrl, fileSize.toLong())
            val stream = proxy.register(source, "video.mp4")
            try {
                var audioPos = 2L * 1024 * 1024
                var videoPos = 32L * 1024 * 1024
                var prevAudioSocket: java.net.Socket? = null
                var prevVideoSocket: java.net.Socket? = null
                repeat(12) {
                    val audioResp = openRequest(stream.url, range = "bytes=$audioPos-")
                    prevAudioSocket?.close()
                    prevAudioSocket = audioResp.socket
                    assertEquals(206, audioResp.status)
                    val aBytes = audioResp.body.readNBytes(12_000)
                    assertEquals(12_000, aBytes.size)
                    for (i in aBytes.indices) {
                        assertEquals(((audioPos + i) % 251).toByte(), aBytes[i])
                    }
                    audioPos += 12_000

                    val videoResp = openRequest(stream.url, range = "bytes=$videoPos-")
                    prevVideoSocket?.close()
                    prevVideoSocket = videoResp.socket
                    assertEquals(206, videoResp.status)
                    val vBytes = videoResp.body.readNBytes(48_000)
                    assertEquals(48_000, vBytes.size)
                    for (i in vBytes.indices) {
                        assertEquals(((videoPos + i) % 251).toByte(), vBytes[i])
                    }
                    videoPos += 48_000
                }
                prevAudioSocket?.close()
                prevVideoSocket?.close()
                // 24 次开放 Range 短请求（每次读几十 KB 即主动断开），远端应只建立音轨、视频轨 2 条长连接
                assertEquals(2, upstreamRequests.get(), "upstreamRequests=${upstreamRequests.get()}")
            } finally {
                stream.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun httpStreamSourceResumesAfterUpstreamShortWrite() = runBlocking {
        val fileSize = 4 * 1024 * 1024
        val firstCut = 256 * 1024
        val upstreamRequests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/flaky.mp4") { exchange ->
            val attempt = upstreamRequests.incrementAndGet()
            val rangeHeader = exchange.requestHeaders.getFirst("Range").orEmpty()
            val start = rangeHeader.substringAfter("bytes=", "0").substringBefore('-').toLongOrNull() ?: 0L
            val remaining = fileSize - start
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.responseHeaders.add("Content-Range", "bytes $start-${fileSize - 1}/$fileSize")
            exchange.sendResponseHeaders(206, remaining)
            val limit = if (attempt == 1) minOf(remaining, firstCut.toLong()) else remaining
            runCatching {
                exchange.responseBody.use { out ->
                    var pos = start
                    val end = start + limit
                    val chunk = ByteArray(32 * 1024)
                    while (pos < end) {
                        val n = minOf(chunk.size.toLong(), end - pos).toInt()
                        for (i in 0 until n) chunk[i] = ((pos + i) % 251).toByte()
                        out.write(chunk, 0, n)
                        pos += n
                    }
                    // 故意提前关闭：模拟反向代理/服务端在声明长度内断流
                    if (attempt == 1) out.flush()
                }
            }
            if (attempt == 1) exchange.close()
        }
        server.start()
        try {
            val upstreamUrl = "http://127.0.0.1:${server.address.port}/flaky.mp4"
            val source = HttpStreamByteSource(upstreamUrl, fileSize.toLong())
            val stream = proxy.register(source, "flaky.mp4")
            try {
                val response = withTimeout(20_000) { request(stream.url, range = "bytes=0-") }
                assertEquals(206, response.status)
                assertEquals(fileSize, response.body.size)
                for (i in response.body.indices) {
                    assertEquals(((i) % 251).toByte(), response.body[i], "byte $i")
                }
                assertTrue(upstreamRequests.get() >= 2, "upstreamRequests=${upstreamRequests.get()}")
            } finally {
                stream.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closedSessionIsGone() = runBlocking {
        val source = FakeSource(size = 1000)
        val stream = proxy.register(source, null)
        stream.close()
        assertEquals(404, request(stream.url).status)
        assertTrue(source.closed)
    }

    /**
     * 内容可预测的来源。每个 reader 在自己的调用重叠时记一次违规，模拟 SDK reader 的单调用方约束。
     */
    private class FakeSource(
        override val size: Long,
        private val readDelayMillis: Long = 0,
        private val failAt: Long? = null,
        private val failures: Int = 0,
        private val throwOnFailure: Boolean = false,
    ) : ProxyByteSource {
        val concurrencyViolations = AtomicInteger(0)
        val openedReaders = AtomicInteger(0)
        val seekCalls = AtomicInteger(0)
        val shortReads = AtomicInteger(0)
        val thrownFailures = AtomicInteger(0)
        private val remainingFailures = AtomicInteger(failures)

        /** 所有 reader 上正在进行的读。 */
        val activeReads = AtomicInteger(0)

        @Volatile
        var closed = false

        fun expected(start: Long, endExclusive: Long) =
            ByteArray((endExclusive - start).toInt()) { byteAt(start + it) }

        override suspend fun openReader(): ProxyReader {
            openedReaders.incrementAndGet()
            return FakeReader()
        }

        override fun close() {
            closed = true
        }

        private inner class FakeReader : ProxyReader {
            private val ownReads = AtomicInteger(0)

            override var position = 0L
                private set

            override suspend fun seekTo(position: Long) {
                seekCalls.incrementAndGet()
                this.position = position
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (ownReads.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                activeReads.incrementAndGet()
                try {
                    if (readDelayMillis > 0) delay(readDelayMillis)
                    if (position >= size) return -1
                    val shouldFail = failAt != null && position >= failAt &&
                        remainingFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
                    if (shouldFail) {
                        if (throwOnFailure) {
                            thrownFailures.incrementAndGet()
                            throw java.io.IOException("模拟上游断流")
                        }
                        shortReads.incrementAndGet()
                        return -1
                    }
                    val count = minOf(length.toLong(), size - position, 64L * 1024).toInt()
                    for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
                    position += count
                    return count
                } finally {
                    activeReads.decrementAndGet()
                    ownReads.decrementAndGet()
                }
            }

            override fun close() = Unit
        }

        private fun byteAt(position: Long): Byte = (position % 251).toByte()
    }
}
