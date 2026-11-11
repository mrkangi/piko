package dev.piko.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPinHasherTest {

    @Test
    fun `only four digit pins are accepted`() {
        assertTrue(AppPinHasher.isWellFormed("0427"))
        assertFalse(AppPinHasher.isWellFormed("427"))
        assertFalse(AppPinHasher.isWellFormed("04270"))
        assertFalse(AppPinHasher.isWellFormed("04a7"))
        assertFalse(AppPinHasher.isWellFormed(""))
    }

    @Test
    fun `hash verifies the pin and rejects another one`() {
        val stored = AppPinHasher.hash("0427")
        assertFalse(stored.contains("0427"))
        assertTrue(AppPinHasher.verify("0427", stored))
        assertFalse(AppPinHasher.verify("0428", stored))
        assertFalse(AppPinHasher.verify("427", stored))
    }

    @Test
    fun `the same pin gets a fresh salt each time`() {
        val first = AppPinHasher.hash("0427")
        val second = AppPinHasher.hash("0427")
        assertNotEquals(first, second)
        assertTrue(AppPinHasher.verify("0427", first))
        assertTrue(AppPinHasher.verify("0427", second))
    }

    @Test
    fun `malformed stored values never verify`() {
        assertFalse(AppPinHasher.verify("0427", ""))
        assertFalse(AppPinHasher.verify("0427", "v1:not-a-number:c2FsdA==:aGFzaA=="))
        assertFalse(AppPinHasher.verify("0427", "v2:120000:c2FsdA==:aGFzaA=="))
        assertFalse(AppPinHasher.verify("0427", "v1:120000:not-base64:not-base64"))
    }
}
