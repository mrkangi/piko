package dev.piko.shared.lan

import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.LanLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.smoke
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanBrowseTest {
    @Test
    fun locationsRoundTripAndRejectTraversal() {
        val directory = LanLocation("host1", "share1", listOf("影片", "season 01"), directory = true)
        val file = LanLocation("host1", "share1", listOf("影片", "movie.mkv"), directory = false)
        assertEquals(directory, LanLocation.of(directory.id))
        assertEquals(file, LanLocation.of(file.id))
        assertEquals("host1", DeviceLocation.peerDeviceId(DeviceLocation.peerId("host1")))
        for (segment in listOf("..", ".", "", "/etc", "a/b")) {
            assertTrue(runCatching { LanLocation("host1", "share1", listOf(segment)) }.isFailure)
        }
        for (segment in listOf("a\\b", "C:")) {
            val location = LanLocation("host1", "share1", listOf(segment))
            assertEquals(location, LanLocation.of(location.id))
        }
        assertTrue(DeviceLocation.isDevice(DeviceLocation.peerId("host1")))
        assertTrue(DeviceLocation.isPeer(DeviceLocation.peerId("host1")))
    }

    @Test
    fun pairedHostAppearsUnderDevicesAndSharesAreBrowsable() = smoke { scope ->
        val preferences = MemoryPreferences()
        val settings = LanShareSettings(preferences, scope)
        settings.update {
            it.copy(
                peers = listOf(
                    LanPeer(
                        hostDeviceId = "host1",
                        name = "Living room",
                        address = "127.0.0.1",
                        port = 47821,
                        token = "token-1",
                    ),
                ),
            )
        }
        val http = HttpClient(
            MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/v1/shares" -> """[{"shareId":"share1","name":"Media","fileCount":2}]"""
                    "/v1/list" -> when (request.url.parameters["path"].orEmpty()) {
                        "" -> """{"entries":[
                            {"path":"movie.mp4","name":"movie.mp4","folder":false,"size":1024,"mimeType":"video/mp4"},
                            {"path":"sub","name":"sub","folder":true}
                        ]}"""
                        "sub" -> """{"entries":[{"path":"sub/photo.jpg","name":"photo.jpg","folder":false,"size":64,"mimeType":"image/jpeg"}]}"""
                        else -> """{"entries":[]}"""
                    }
                    "/v1/index" -> """{"shareId":"share1","deviceName":"Living room","entries":[
                        {"path":"movie.mp4","name":"movie.mp4","folder":false,"size":1024,"mimeType":"video/mp4"},
                        {"path":"sub","name":"sub","folder":true},
                        {"path":"sub/photo.jpg","name":"photo.jpg","folder":false,"size":64,"mimeType":"image/jpeg"}
                    ],"truncated":false}"""
                    "/v1/stat" -> """{"entries":[{"path":"movie.mp4","name":"movie.mp4","folder":false,"size":1024,"mimeType":"video/mp4"}]}"""
                    else -> """{"entries":[]}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        try {
            val shares = LanShareRepository(http, settings, scope)
            val repository = PikoDriveRepository(FakePikPakServer().provider(), preferences, lanShares = shares)

            val root = repository.listBrowsable("", PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertTrue(root.any { it.id == DeviceLocation.ROOT_ID })
            val devices = repository.listBrowsable(DeviceLocation.ROOT_ID, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(setOf(DeviceLocation.SELF_ID, DeviceLocation.peerId("host1")), devices.map { it.id }.toSet())
            val peerCursor = DeviceLocation.peerId("host1")
            val peerShares = repository.listBrowsable(peerCursor, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(listOf("Media"), peerShares.map { it.name })
            val shareRoot = LanLocation("host1", "share1")
            assertEquals(shareRoot.id, peerShares.single().id)
            val media = repository.listBrowsable(shareRoot.id, PikoFileSortOrder.NAME_ASC).getOrThrow()
            assertEquals(setOf("movie.mp4", "sub"), media.map { it.name }.toSet())
            val movie = media.single { it.name == "movie.mp4" }
            assertEquals(
                listOf(
                    PikoDriveRepository.ROOT_BREADCRUMB,
                    DeviceLocation.rootCrumb(),
                    DeviceLocation.peerCrumb("host1", "Living room"),
                    PikoPathBreadcrumb(shareRoot.id, "Media"),
                    PikoPathBreadcrumb(movie.id, "movie.mp4"),
                ),
                repository.knownLocalPath(movie.id),
            )
            repository.prefetchLanIndex(shareRoot.id)
            assertEquals(setOf("movie.mp4", "sub"), repository.listBrowsable(shareRoot.id, PikoFileSortOrder.NAME_ASC).getOrThrow().map { it.name }.toSet())
        } finally {
            http.close()
        }
    }

    @Test
    fun peersAndHostTokensAreScopedToTheCurrentAccount() = runBlocking {
        val account = MutableStateFlow<String?>("account-a")
        val settings = LanShareSettings(
            preferences = MemoryPreferences(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
            accountProvider = { account.value },
            accountFlow = account,
        )
        settings.update {
            it.copy(
                peers = listOf(
                    LanPeer("host-a", "A", "127.0.0.1", 1, "token-a", account = "account-a"),
                    LanPeer("host-b", "B", "127.0.0.1", 2, "token-b", account = "account-b"),
                ),
                hostTokens = listOf(
                    LanHostToken("host-token-a", "client-a", "A", account = "account-a"),
                    LanHostToken("host-token-b", "client-b", "B", account = "account-b"),
                ),
            )
        }

        assertEquals(listOf("host-a"), settings.currentPeers().map { it.hostDeviceId })
        assertEquals(listOf("host-token-a"), settings.currentTokens().map { it.token })

        account.value = "account-b"

        assertEquals(listOf("host-b"), settings.currentPeers().map { it.hostDeviceId })
        assertEquals(listOf("host-token-b"), settings.currentTokens().map { it.token })
    }
}
