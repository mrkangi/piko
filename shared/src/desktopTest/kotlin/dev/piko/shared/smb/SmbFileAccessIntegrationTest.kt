package dev.piko.shared.smb

import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalMountKind
import dev.piko.shared.data.SmbMountInfo
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 本机有 Samba 时用 `PIKO_SMB_TEST=1 PIKO_SMB_HOST=...` 跑真实联调；没有环境变量时跳过。 */
class SmbFileAccessIntegrationTest {
    @Test
    fun `real samba lists probes and streams a range`() {
        if (System.getenv("PIKO_SMB_TEST") != "1") return
        val host = System.getenv("PIKO_SMB_HOST") ?: "127.0.0.1"
        val port = System.getenv("PIKO_SMB_PORT")?.toIntOrNull() ?: 445
        val share = System.getenv("PIKO_SMB_SHARE") ?: "share"
        val user = System.getenv("PIKO_SMB_USER") ?: "pikotest"
        val password = System.getenv("PIKO_SMB_PASSWORD") ?: "pikotest"
        val info = SmbMountInfo(host = host, port = port, share = share, username = user)
        val mount = LocalMount(
            mountId = "smbtest",
            account = "test",
            localRootUriOrPath = info.displayPath,
            fallbackName = share,
            createdAtMs = 0,
            kind = LocalMountKind.SMB,
            smb = info,
        )
        val access = SmbFileAccess { password }
        try {
            runBlocking {
                access.testSmb(info, password)
                val root = LocalLocation(mount.mountId)
                val listing = access.list(mount, root)
                assertTrue(listing.any { it.name == "movie.bin" })
                assertTrue(listing.any { it.name == "sub" && it.isFolder })

                val movie = listing.single { it.name == "movie.bin" }
                assertEquals(1024L, movie.sizeBytes)
                val sub = LocalLocation(mount.mountId, listOf("sub"))
                val photo = access.list(mount, sub).single { it.name == "photo.png" }
                assertTrue(photo.thumbnailLink.startsWith("http://127.0.0.1:"))

                val url = URL(access.streamUrl(mount, LocalLocation(mount.mountId, listOf("movie.bin"), directory = false))!!)
                val request = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Range", "bytes=10-19")
                }
                assertEquals(206, request.responseCode)
                assertContentEquals(ByteArray(10) { (it + 10).toByte() }, request.inputStream.use { it.readBytes() })
                request.disconnect()
            }
        } finally {
            access.close()
        }
    }
}
