package dev.piko.shared.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackBufferingTest {
    @Test
    fun lowBitratePreviewKeepsTheOriginalFloor() {
        // 720P 转码约 120 KB/s：2 秒与 10 秒都远小于 8 MiB，行为保持原来的固定预算
        assertEquals(8L * 1024 * 1024, PlaybackBuffering.previewForwardBytes(120_000, 2))
        assertEquals(8L * 1024 * 1024, PlaybackBuffering.previewForwardBytes(120_000, 10))
    }

    @Test
    fun highBitratePreviewGrowsWithThePlaybackWindowAndCaps() {
        val rate = 20L * 1024 * 1024
        assertEquals(40L * 1024 * 1024, PlaybackBuffering.previewForwardBytes(rate, 2))
        assertEquals(48L * 1024 * 1024, PlaybackBuffering.previewForwardBytes(rate, 10))
    }

    @Test
    fun unknownRateLeavesTheBackendDefaultAlone() {
        assertNull(PlaybackBuffering.previewForwardBytes(null, 10))
        assertNull(PlaybackBuffering.previewForwardBytes(0, 10))
    }

    @Test
    fun backBufferStaysBounded() {
        assertEquals(4L * 1024 * 1024, PlaybackBuffering.previewBackBytes(8L * 1024 * 1024))
        assertEquals(12L * 1024 * 1024, PlaybackBuffering.previewBackBytes(48L * 1024 * 1024))
    }
}
