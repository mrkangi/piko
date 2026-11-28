package dev.piko.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeSeekTest {
    @Test
    fun `drag maps displacement from the press position with the shared span`() {
        val duration = 1_200_000L
        assertEquals(
            60_000L,
            relativeSeekTargetMillis(60_000L, 0f, 1_000f, 300_000L, duration),
        )
        assertEquals(
            210_000L,
            relativeSeekTargetMillis(60_000L, 500f, 1_000f, 300_000L, duration),
        )
        assertEquals(
            0L,
            relativeSeekTargetMillis(60_000L, -500f, 1_000f, 300_000L, duration),
        )
    }

    @Test
    fun `target stays inside the media bounds`() {
        assertEquals(
            90_000L,
            relativeSeekTargetMillis(90_000L, 4_000f, 1_000f, 300_000L, 90_000L),
        )
        assertEquals(
            0L,
            relativeSeekTargetMillis(10_000L, -4_000f, 1_000f, 300_000L, 90_000L),
        )
        assertEquals(
            0L,
            relativeSeekTargetMillis(0L, 100f, 1_000f, 300_000L, 0L),
        )
    }
}
