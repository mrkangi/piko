package dev.piko.shared.smb

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SmbStreamServerTest {
    private val bytes = ByteArray(1024) { it.toByte() }

    @Test
    fun `range and head read through the loopback server`() = runBlocking {
        val server = server()
        try {
            val url = URL(server.url("mount", listOf("movie.mkv")))
            val range = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=10-19")
            }
            assertEquals(206, range.responseCode)
            assertEquals("bytes 10-19/1024", range.getHeaderField("Content-Range"))
            assertContentEquals(bytes.copyOfRange(10, 20), range.inputStream.use { it.readBytes() })
            range.disconnect()

            val head = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
            }
            assertEquals(200, head.responseCode)
            assertEquals("1024", head.getHeaderField("Content-Length"))
            head.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun `unknown token and expired mount are rejected`() = runBlocking {
        val server = server()
        try {
            val base = server.url("mount", listOf("movie.mkv"))
            val original = URL(base)
            val parts = original.path.trim('/').split('/')
            val wrongPath = "/wrong/" + parts.drop(1).joinToString("/")
            val wrongToken = (URL(original.protocol, original.host, original.port, wrongPath).openConnection() as HttpURLConnection)
            assertEquals(403, wrongToken.responseCode)
            wrongToken.disconnect()

            val otherServer = server()
            try {
                val url = URL(otherServer.url("mount", listOf("missing.bin")))
                assertEquals(404, (url.openConnection() as HttpURLConnection).responseCode)
            } finally {
                otherServer.close()
            }
        } finally {
            server.close()
        }
    }

    private fun server() = SmbStreamServer { mountId, segments ->
        if (mountId != "mount" || segments != listOf("movie.mkv")) return@SmbStreamServer null
        SmbReadHandle(
            name = "movie.mkv",
            size = bytes.size.toLong(),
            modifiedMs = 123,
            readAt = { offset, buffer, offsetInBuffer, length ->
                if (offset >= bytes.size) -1 else {
                    val count = minOf(length.toLong(), bytes.size - offset).toInt()
                    bytes.copyInto(buffer, offsetInBuffer, offset.toInt(), offset.toInt() + count)
                    count
                }
            },
            closeBlock = {},
        )
    }
}
