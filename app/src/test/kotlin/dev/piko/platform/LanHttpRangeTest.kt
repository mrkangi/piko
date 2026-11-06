package dev.piko.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanHttpRangeTest {
    @Test
    fun `closed and open ended ranges are clamped to the file`() {
        assertEquals(0L..99L, parseLanHttpRange("bytes=0-99", 100))
        assertEquals(50L..99L, parseLanHttpRange("bytes=50-", 100))
        assertEquals(50L..99L, parseLanHttpRange("bytes=50-999", 100))
    }

    @Test
    fun `suffix ranges start from the end`() {
        assertEquals(80L..99L, parseLanHttpRange("bytes=-20", 100))
        assertEquals(0L..99L, parseLanHttpRange("bytes=-500", 100))
    }

    @Test
    fun `invalid and multipart ranges fall back to the full file`() {
        assertNull(parseLanHttpRange("bytes=100-", 100))
        assertNull(parseLanHttpRange("bytes=20-10", 100))
        assertNull(parseLanHttpRange("bytes=0-1,5-6", 100))
        assertNull(parseLanHttpRange("items=0-1", 100))
        assertNull(parseLanHttpRange("bytes=0-1", 0))
    }
}
