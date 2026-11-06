package dev.piko.shared.smb

import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalMountKind
import dev.piko.shared.data.SmbMountInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmbMountInfoTest {
    @Test
    fun `smb address accepts uri and unc forms`() {
        assertEquals(
            "smb://nas/media/Movies",
            SmbMountInfo.parse("smb://nas/media/Movies")?.displayPath,
        )
        assertEquals(
            "smb://nas/media/Movies",
            SmbMountInfo.parse("\\\\nas\\media\\Movies")?.displayPath,
        )
        assertNull(SmbMountInfo.parse("smb://nas"))
    }

    @Test
    fun `old local mount json stays local`() {
        val old = """
            [{"mountId":"old","account":"a","localRootUriOrPath":"/tmp/x","fallbackName":"x","createdAtMs":1}]
        """.trimIndent()
        val mount = Json { ignoreUnknownKeys = true }.decodeFromString<List<LocalMount>>(old).single()
        assertEquals(LocalMountKind.LOCAL, mount.kind)
        assertTrue(!mount.isSmb)
    }

    @Test
    fun `subpath normalizes separators and rejects traversal`() {
        assertEquals("a/b", SmbMountInfo(host = "nas", share = "media", path = "\\a\\b").normalizedPath)
        assertFailsWith<IllegalArgumentException> {
            SmbMountInfo(host = "nas", share = "media", path = "..\\secret")
        }
    }
}
