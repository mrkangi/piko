package dev.piko.shared.plex

import dev.piko.shared.data.DeviceLocation
import dev.piko.shared.data.PlexConnection
import dev.piko.shared.data.PlexHomeProfile
import dev.piko.shared.data.PlexLocation
import dev.piko.shared.data.PlexServer
import dev.piko.shared.smoke.smoke
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PlexRepositoryTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Secrets : PlexSecrets {
        val values = ConcurrentHashMap<String, String>()
        override suspend fun loadPlexCredentials(accountUuid: String): String? = values[accountUuid]
        override suspend fun savePlexCredentials(accountUuid: String, serialized: String) {
            values[accountUuid] = serialized
        }
        override suspend fun clearPlexCredentials(accountUuid: String) {
            values.remove(accountUuid)
        }
    }

    @Test
    fun locationsRoundTripAndIdentifyPlexItems() {
        val root = PlexLocation("plex_abc")
        val section = PlexLocation("plex_abc", "s1")
        val movie = PlexLocation("plex_abc", "m42", directory = false)
        assertEquals(root, PlexLocation.of(root.id))
        assertEquals(section, PlexLocation.of(section.id))
        assertEquals(movie, PlexLocation.of(movie.id))
        assertTrue(PlexLocation.isPlex(root.id))
        assertTrue(movie.id.startsWith(PlexLocation.FILE_PREFIX))
        assertFalse(PlexLocation.isPlex("piko-device-root"))
        assertEquals("1", section.sectionId)
        assertEquals("42", movie.itemId)
    }

    @Test
    fun loginBrowsePlaybackAndTimelineStayOnDirectPlay() = smoke { scope ->
        val requests = mutableListOf<String>()
        val secrets = Secrets()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val query = request.url.encodedQuery
            requests += "${request.method.value} ${request.url.host}$path?$query"
            when {
                request.url.host == "plex.tv" && path == "/api/v2/pins" -> respond(
                    """{"id":7,"code":"ABCD"}""",
                    headers = jsonHeaders,
                )
                request.url.host == "plex.tv" && path == "/api/v2/pins/7" -> respond(
                    """{"authToken":"account-token"}""",
                    headers = jsonHeaders,
                )
                request.url.host == "plex.tv" && path == "/api/v2/user" -> respond(
                    """{"uuid":"account-uuid","username":"demo","email":"demo@example.test"}""",
                    headers = jsonHeaders,
                )
                request.url.host == "plex.tv" && path == "/api/v2/home/users" -> respond(
                    """{"users":[{"uuid":"home-1","title":"Kids","protected":true}]}""",
                    headers = jsonHeaders,
                )
                request.url.host == "plex.tv" && path == "/api/v2/home/users/home-1/switch" -> respond(
                    """{"authToken":"home-token"}""",
                    headers = jsonHeaders,
                )
                request.url.host == "clients.plex.tv" && path == "/api/v2/resources" -> respond(
                    """[{"name":"Home","clientIdentifier":"machine-1","provides":"server","owned":true,"accessToken":"server-token",
                      "connections":[{"protocol":"http","address":"pms.test","port":32400,"uri":"http://pms.test","local":true}]}]""",
                    headers = jsonHeaders,
                )
                request.url.host == "pms.test" && path == "/identity" -> respond(
                    """{"MediaContainer":{"machineIdentifier":"machine-1"}}""",
                    headers = jsonHeaders,
                )
                request.url.host == "pms.test" && path == "/library/sections" -> respond(
                    """{"MediaContainer":{"Directory":[{"key":"1","title":"Movies","type":"movie"}]}}""",
                    headers = jsonHeaders,
                )
                request.url.host == "pms.test" && path == "/library/sections/1/all" -> respond(
                    """{"MediaContainer":{"Metadata":[${movieJson()}]}}""",
                    headers = jsonHeaders,
                )
                request.url.host == "pms.test" && path == "/library/metadata/42" -> respond(
                    """{"MediaContainer":{"Metadata":[${movieJson()}]}}""",
                    headers = jsonHeaders,
                )
                request.url.host == "pms.test" && path == "/:/timeline" -> respond(
                    """{"MediaContainer":{}}""",
                    headers = jsonHeaders,
                )
                else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val api = PlexApi(
            http = HttpClient(engine),
            clientIdentifier = "piko-device",
            clientVersion = "1.0",
            deviceName = "Test",
            platform = "Desktop",
        )
        val repository = HttpPlexRepository(
            api = api,
            settings = PlexSettings(null, scope),
            secrets = secrets,
            scope = scope,
        )

        val pin = repository.createPin()
        assertEquals("ABCD", pin.code)
        assertTrue(pin.authUrl.contains("code=ABCD"))
        assertTrue(repository.pollPin(pin.id) is PlexPinPoll.Claimed)

        val identity = repository.identity("account-token")
        assertEquals("account-uuid", identity.uuid)
        assertEquals("home-1", repository.homeProfiles("account-token").single().uuid)
        assertEquals("home-token", repository.switchProfile("account-token", "home-1", "1234"))

        val discovered = repository.discoverServers("home-token")
        assertEquals("machine-1", discovered.single().server.machineIdentifier)
        assertEquals("server-token", discovered.single().accessToken)

        val account = repository.addAccount(
            accountToken = "account-token",
            identity = identity,
            profiles = listOf(PlexHomeProfile("home-1", "Kids", protected = true)),
            profile = PlexHomeProfile("home-1", "Kids", protected = true),
            profileToken = "home-token",
            servers = discovered,
        )
        val mount = account.servers.single()
        assertEquals(DeviceLocation.ROOT_ID, repository.serverNodes().single().parentId)

        val sections = repository.list(mount.mountId, "")
        assertEquals("Movies", sections.single().name)
        val movie = repository.list(mount.mountId, "s1").single()
        assertEquals(".mkv", movie.fileExtension)
        assertEquals("42", PlexLocation.of(movie.id)?.itemId)

        val playback = repository.playback(movie.id)
        assertEquals("http://pms.test/library/parts/42/file.mkv?X-Plex-Token=server-token", playback.url)
        assertEquals(600L, playback.durationSeconds)
        assertEquals(1920, playback.width)
        assertEquals("", playback.mediaSourceId)
        assertFalse(requests.any { it.contains("/video/:/transcode") })

        repository.reportPlayback(movie.id, 30_000, 60_000, stopped = false)
        assertTrue(requests.any { it.contains("/:/timeline") && it.contains("ratingKey=42") && it.contains("time=30000") })
        assertTrue(secrets.values["account-uuid"].orEmpty().contains("server-token"))
    }

    @Test
    fun resourcesFallBackOnlyOnTransportFailure() = smoke { scope ->
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.host
            if (request.url.host == "clients.plex.tv") throw IOException("DNS failed")
            respond(
                """[{"name":"Home","clientIdentifier":"machine-1","provides":"server","accessToken":"server-token",
                  "connections":[{"protocol":"https","address":"192.168.1.2","port":32400,
                  "uri":"https://192-168-1-2.machine.plex.direct:32400","local":true}]}]""",
                headers = jsonHeaders,
            )
        }
        val api = PlexApi(HttpClient(engine), "piko-device", "1.0", "Test", "Desktop")
        val servers = api.resources("token")
        assertEquals(1, servers.size)
        assertEquals(listOf("clients.plex.tv", "plex.tv"), requests)
    }

    @Test
    fun unclaimedPinWithJsonNullTokenStaysWaiting() = smoke { scope ->
        val engine = MockEngine { request ->
            respond(
                """{"id":7,"code":"ABCD","authToken":null,"expiresIn":1800}""",
                headers = jsonHeaders,
            )
        }
        val repository = HttpPlexRepository(
            api = PlexApi(HttpClient(engine), "piko-device", "1.0", "Test", "Desktop"),
            settings = PlexSettings(null, scope),
            secrets = Secrets(),
            scope = scope,
        )
        assertEquals(PlexPinPoll.Waiting, repository.pollPin(7))
    }

    @Test
    fun httpFailureIdentifiesTheLoginStageWithoutLeakingResponseSecrets() = smoke { scope ->
        val engine = MockEngine { request ->
            assertEquals("account-token", request.headers["X-Plex-Token"])
            respond(
                """{"error":"unauthorized","debug":"do-not-expose-this-value"}""",
                HttpStatusCode.Unauthorized,
                jsonHeaders,
            )
        }
        val api = PlexApi(HttpClient(engine), "piko-device", "1.0", "Test", "Android")
        val error = assertFailsWith<PlexApiException> { api.identity("account-token") }
        assertEquals("Plex account verification", error.operation)
        assertEquals("Plex account verification returned HTTP 401", error.message)
        assertFalse(error.message.orEmpty().contains("do-not-expose-this-value"))
    }

    @Test
    fun resourcesDoNotFallBackOnAuthenticationFailure() = smoke { scope ->
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.host
            respond("""{"errors":[]}""", HttpStatusCode.Unauthorized, jsonHeaders)
        }
        val api = PlexApi(HttpClient(engine), "piko-device", "1.0", "Test", "Desktop")
        runCatching { api.resources("bad-token") }
        assertEquals(listOf("clients.plex.tv"), requests)
    }

    @Test
    fun homeSwitchReportsAnIncorrectPinSeparately() = smoke { scope ->
        val engine = MockEngine { request ->
            respond(
                """{"errors":[{"code":1041,"message":"Invalid PIN"}]}""",
                HttpStatusCode.Forbidden,
                jsonHeaders,
            )
        }
        val repository = HttpPlexRepository(
            api = PlexApi(HttpClient(engine), "piko-device", "1.0", "Test", "Desktop"),
            settings = PlexSettings(null, scope),
            secrets = Secrets(),
            scope = scope,
        )
        assertFailsWith<PlexHomePinException> {
            repository.switchProfile("account-token", "home-1", "0000")
        }
    }

    @Test
    fun qrEncoderProducesASquareMatrix() {
        val matrix = ZxingPlexQrEncoder.encode("https://app.plex.tv/auth#?code=ABCD")
        assertNotNull(matrix)
        assertTrue(matrix.size > 0)
        assertEquals(matrix.size * matrix.size, matrix.cells.size)
    }

    private fun movieJson(): String =
        """{"ratingKey":"42","type":"movie","title":"Example","duration":600000,
          "Media":[{"id":"","container":"mkv","width":1920,"height":1080,
            "Part":[{"id":"p1","key":"/library/parts/42/file.mkv","size":1234,"duration":600000,
              "exists":true,"accessible":true,
              "Stream":[{"streamType":1,"width":1920,"height":1080}]}]}]}"""
}
