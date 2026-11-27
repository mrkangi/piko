package dev.piko.ui.screens.player

import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SeekPreviewTest {
    @Test
    fun `seek preview positions quantize to five second buckets`() {
        assertEquals(0L, seekPreviewBucketOf(0L))
        assertEquals(0L, seekPreviewBucketOf(4_999L))
        assertEquals(5_000L, seekPreviewBucketOf(5_000L))
        assertEquals(10_000L, seekPreviewBucketOf(14_999L))
    }

    @Test
    fun `lru keeps recently used frames`() {
        val cache = SeekPreviewLruCache<Int, String>(2)
        cache[1] = "one"
        cache[2] = "two"
        assertEquals("one", cache[1])
        cache[3] = "three"
        assertNull(cache[2])
        assertEquals("one", cache[1])
        assertEquals("three", cache[3])
    }

    @Test
    fun `rapid scrubbing only extracts the settled bucket`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val support = RecordingSupport()
        val controller = SeekPreviewController(scope, support)
        try {
            controller.bind(source)
            controller.request(1_000L)
            controller.request(6_000L)
            controller.request(11_000L)
            delay(80L)
            assertEquals(emptyList(), support.requested)

            support.await(1)
            delay(200L)
            assertEquals(listOf(10_000L), support.requested)

            // 同一档位再次经过时直接命中缓存，不再打开解码器。
            controller.request(12_000L)
            delay(180L)
            assertEquals(listOf(10_000L), support.requested)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `request during extraction replaces the next item without queue buildup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val support = RecordingSupport(extractDelayMillis = 120L)
        val controller = SeekPreviewController(scope, support)
        try {
            controller.bind(source)
            controller.request(1_000L)
            support.await(1)
            controller.request(6_000L)
            controller.request(11_000L)
            support.await(2)
            delay(250L)
            assertEquals(listOf(0L, 10_000L), support.requested)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    @Test
    fun `source becoming ready retries the pending scrub position`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val support = RecordingSupport()
        val controller = SeekPreviewController(scope, support)
        try {
            controller.bind(null)
            controller.request(6_000L)
            controller.bind(source)
            support.await(1)
            assertEquals(listOf(5_000L), support.requested)
        } finally {
            controller.release()
            scope.cancel()
        }
    }

    private class RecordingSupport(
        private val extractDelayMillis: Long = 10L,
    ) : SeekPreviewSupport {
        val requested = mutableListOf<Long>()

        override suspend fun open(source: SeekPreviewSource): SeekPreviewExtractor = object : SeekPreviewExtractor {
            override suspend fun frameAt(positionMillis: Long, targetWidthPx: Int): SeekPreviewFrame {
                synchronized(requested) { requested += positionMillis }
                delay(extractDelayMillis)
                return frame()
            }

            override fun close() = Unit
        }

        suspend fun await(count: Int) {
            withTimeout(3_000L) {
                while (synchronized(requested) { requested.size } < count) delay(10L)
            }
        }
    }

    private companion object {
        val source = SeekPreviewSource(
            fileId = "file",
            localPath = "/tmp/file.mp4",
            isLocal = true,
        )

        fun frame(): SeekPreviewFrame {
            val image = org.jetbrains.skia.Surface.makeRasterN32Premul(2, 1).use { surface ->
                surface.makeImageSnapshot().toComposeImageBitmap()
            }
            return SeekPreviewFrame(image, 2f)
        }
    }
}
