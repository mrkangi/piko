package dev.piko.shared.webdav

import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.LocalMountKind
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.WebDavMountInfo
import io.github.nihildigit.pikpak.FileKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavFileAccessTest {
    private val listing = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/media/</d:href>
            <d:propstat><d:prop>
              <d:displayname>media</d:displayname>
              <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/media/Movies/</d:href>
            <d:propstat><d:prop>
              <d:displayname>Movies</d:displayname>
              <d:resourcetype><d:collection/></d:resourcetype>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/media/a%20b.mp4</d:href>
            <d:propstat><d:prop>
              <d:displayname>a b.mp4</d:displayname>
              <d:getcontentlength>1234</d:getcontentlength>
              <d:getlastmodified>Wed, 01 Jan 2025 00:00:00 GMT</d:getlastmodified>
              <d:resourcetype/>
            </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun listsEntriesAndSendsBasicAuth() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = listing,
                status = HttpStatusCode.MultiStatus,
                headers = headersOf(HttpHeaders.ContentType, "application/xml; charset=utf-8"),
            )
        }
        val access = WebDavFileAccess({ HttpClient(engine) }, { "secret" })
        val info = WebDavMountInfo("https://nas.local/dav/media", username = "piko")
        val mount = LocalMount(
            mountId = "mount-1",
            account = "account-a",
            localRootUriOrPath = info.url,
            fallbackName = "Media",
            createdAtMs = 0L,
            kind = LocalMountKind.WEBDAV,
            webDav = info,
        )

        val files = access.list(mount, mount.location)

        assertEquals(listOf("Movies", "a b.mp4"), files.map { it.name })
        assertEquals(FileKind.FOLDER, files[0].kind)
        assertEquals(FileKind.FILE, files[1].kind)
        assertEquals("1234", files[1].size)
        assertEquals("webdav", files[1].params["local_kind"])
        assertTrue(files[1].modifiedTime.startsWith("2025-01-01"))
        assertEquals("PROPFIND", requests.single().method.value)
        assertEquals("1", requests.single().headers["Depth"])
        assertEquals("Basic cGlrbzpzZWNyZXQ=", requests.single().headers[HttpHeaders.Authorization])
        access.close()
    }

    @Test
    fun loopbackStreamForwardsRangeAndCredentials() = runBlocking {
        val engine = MockEngine { request ->
            when (request.method.value) {
                "GET" -> {
                    if (request.headers[HttpHeaders.Range] == "bytes=2-4") {
                        respond(
                            content = "234",
                            status = HttpStatusCode.PartialContent,
                            headers = headersOf(
                                HttpHeaders.ContentRange to listOf("bytes 2-4/10"),
                                HttpHeaders.ContentLength to listOf("3"),
                                HttpHeaders.AcceptRanges to listOf("bytes"),
                            ),
                        )
                    } else {
                        respond(
                            content = "0123456789",
                            headers = headersOf(
                                HttpHeaders.ContentLength to listOf("10"),
                                HttpHeaders.AcceptRanges to listOf("bytes"),
                            ),
                        )
                    }
                }
                else -> respond("", HttpStatusCode.OK)
            }
        }
        val access = WebDavFileAccess({ HttpClient(engine) }, { "secret" })
        val info = WebDavMountInfo("https://nas.local/dav/media", username = "piko")
        val mount = LocalMount(
            mountId = "mount-1",
            account = "account-a",
            localRootUriOrPath = info.url,
            fallbackName = "Media",
            createdAtMs = 0L,
            kind = LocalMountKind.WEBDAV,
            webDav = info,
        )

        val url = assertNotNull(access.streamUrl(mount, LocalLocation("mount-1", listOf("a b.mp4"), directory = false)))
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=2-4")
        try {
            assertEquals(HttpURLConnection.HTTP_PARTIAL, connection.responseCode)
            assertEquals("234", connection.inputStream.use { it.readBytes().decodeToString() })
            assertEquals("bytes 2-4/10", connection.getHeaderField("Content-Range"))
        } finally {
            connection.disconnect()
            access.close()
        }
    }
}
