package dev.piko.shared.jellyfin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.piko.shared.data.JellyfinLocation
import dev.piko.shared.smoke.smoke
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JellyfinRepositoryTest {
    private class Secrets : JellyfinSecrets {
        private val values = mutableMapOf<String, String>()
        override suspend fun loadJellyfinToken(serverId: String) = values[serverId]
        override suspend fun saveJellyfinToken(serverId: String, token: String) { values[serverId] = token }
        override suspend fun clearJellyfinToken(serverId: String) { values.remove(serverId) }
    }

    private val userId = "11111111-1111-1111-1111-111111111111"
    private val libraryId = "22222222-2222-2222-2222-222222222222"
    private val movieId = "33333333-3333-3333-3333-333333333333"

    private val movieJson = """
        {"Id":"$movieId","Name":"Example","Type":"Movie","MediaType":"Video","RunTimeTicks":600000000,
         "ImageTags":{"Primary":"tag-1"},"ParentId":"$libraryId",
         "UserData":{"PlaybackPositionTicks":300000000,"PlayCount":1,"IsFavorite":true,
                     "Played":false,"LastPlayedDate":"2026-11-09T10:00:00",
                     "Key":"$movieId","ItemId":"$movieId"},
         "MediaSources":[{"Protocol":"Http","Type":"Default","Id":"source-1","Container":"mkv",
           "Size":1234,"RunTimeTicks":600000000,"IsRemote":false,"ReadAtNativeFramerate":false,
           "IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,"SupportsTranscoding":true,
           "SupportsDirectStream":true,"SupportsDirectPlay":true,"IsInfiniteStream":false,
           "RequiresOpening":false,"RequiresClosing":false,"RequiresLooping":false,
           "SupportsProbing":false,"TranscodingSubProtocol":"http","HasSegments":false,
           "MediaStreams":[{"Type":"Video","Index":0,"Width":1920,"Height":1080,"IsInterlaced":false,
             "IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"IsOriginal":false,
             "IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false}]}]}
    """

    @Test fun authenticatesBrowsesAndBuildsADirectPlayUrl() = smoke { scope ->
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/Users/AuthenticateByName" -> exchange.reply(
                    """
                    {"AccessToken":"token-1","ServerId":"server-1",
                     "User":{"Id":"$userId","Name":"demo","ServerId":"server-1","ServerName":"Home"}}
                    """,
                )
                path == "/UserViews" -> exchange.reply(
                    """{"Items":[{"Id":"$libraryId","Name":"Movies","Type":"CollectionFolder","IsFolder":true}],"TotalRecordCount":1,"StartIndex":0}""",
                )
                path == "/Items" -> exchange.reply(
                    """
                    {"Items":[{"Id":"$movieId","Name":"Example","Type":"Movie","MediaType":"Video",
                      "RunTimeTicks":600000000,"ImageTags":{"Primary":"tag-1"},
                      "MediaSources":[{"Protocol":"Http","Type":"Default","Id":"source-1","Container":"mkv",
                        "Size":1234,"RunTimeTicks":600000000,"IsRemote":false,"ReadAtNativeFramerate":false,
                        "IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,"SupportsTranscoding":true,
                        "SupportsDirectStream":true,"SupportsDirectPlay":true,"IsInfiniteStream":false,
                        "RequiresOpening":false,"RequiresClosing":false,"RequiresLooping":false,
                        "SupportsProbing":false,"TranscodingSubProtocol":"http","HasSegments":false,
                        "MediaStreams":[{"Type":"Video","Index":0,"Width":1920,"Height":1080,"IsInterlaced":false,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"IsOriginal":false,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false}]}]}],
                     "TotalRecordCount":1,"StartIndex":0}
                    """,
                )
                path == "/Items/$movieId/PlaybackInfo" -> exchange.reply(
                    """
                    {"PlaySessionId":"session-1","MediaSources":[{"Protocol":"Http","Type":"Default","Id":"source-1","Name":"Example",
                      "Container":"mkv","Size":1234,"RunTimeTicks":600000000,"IsRemote":false,
                      "ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,
                      "SupportsTranscoding":true,"SupportsDirectStream":true,"SupportsDirectPlay":true,
                      "IsInfiniteStream":false,"RequiresOpening":false,"RequiresClosing":false,
                      "RequiresLooping":false,"SupportsProbing":false,"TranscodingSubProtocol":"http",
                      "HasSegments":false,
                      "MediaStreams":[{"Type":"Video","Index":0,"Width":1920,"Height":1080,"IsInterlaced":false,"IsDefault":false,"IsForced":false,"IsHearingImpaired":false,"IsOriginal":false,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false}]}]}
                    """,
                )
                path == "/Items/$movieId/Ancestors" -> exchange.reply(
                    """[{"Id":"$libraryId","Name":"Movies","Type":"CollectionFolder"}]""",
                )
                path == "/Items/$movieId" -> exchange.reply(
                    """{"Id":"$movieId","Name":"Example","Type":"Movie","ParentId":"$libraryId"}""",
                )
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
        }
        server.start()
        try {
            val repo = createDesktopJellyfinRepository(
                settings = JellyfinSettings(null, scope),
                secrets = Secrets(),
                scope = scope,
                clientVersion = "1.0",
                deviceName = "PKB110 · Android",
                deviceId = "test-device",
            )
            val added = repo.authenticate("http://127.0.0.1:${server.address.port}", "demo", "pw", "Home")
            assertEquals("Home", added.name)
            assertEquals("server-1", added.serverId)
            assertEquals(userId, added.userId)

            val libraries = repo.list(added.serverId, "")
            assertEquals(listOf(libraryId), libraries.map { JellyfinLocation.of(it.id)?.itemId })

            val movies = repo.list(added.serverId, libraryId)
            val movie = movies.single()
            assertEquals("Example", movie.name)
            assertEquals("1920", movie.params["width"])
            assertTrue(movie.thumbnailLink.contains("api_key=token-1"))

            val playback = repo.playback(movie.id)
            assertTrue(playback.url.contains("/Videos/$movieId/stream"))
            assertTrue(playback.url.contains("api_key=token-1"))
            assertEquals(60, playback.durationSeconds)
            assertEquals(1920, playback.width)
            assertEquals("session-1", playback.playSessionId)
            assertEquals("source-1", playback.mediaSourceId)

            val path = repo.pathTo(movie.id, containing = true)
            assertNotNull(path)
            assertEquals("Movies", path[3].name)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(body: String) {
        val bytes = body.trimIndent().toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test fun readsFavoritesHistoryProgressAndReportsPlayback() = smoke { scope ->
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query.orEmpty()
            requests += "${exchange.requestMethod} $path?$query"
            when {
                path == "/Users/AuthenticateByName" -> exchange.reply(
                    """{"AccessToken":"token-1","ServerId":"server-1",
                       "User":{"Id":"$userId","Name":"demo","ServerId":"server-1","ServerName":"Home"}}""",
                )
                path == "/UserViews" -> exchange.reply(
                    """{"Items":[],"TotalRecordCount":0,"StartIndex":0}""",
                )
                path == "/UserItems/Resume" -> exchange.reply(
                    """{"Items":[$movieJson],"TotalRecordCount":1,"StartIndex":0}""",
                )
                path == "/Items" && query.contains("IsFavorite", ignoreCase = true) -> exchange.reply(
                    """{"Items":[$movieJson],"TotalRecordCount":1,"StartIndex":0}""",
                )
                path == "/Items" && query.contains("IsPlayed", ignoreCase = true) -> exchange.reply(
                    """{"Items":[$movieJson],"TotalRecordCount":1,"StartIndex":0}""",
                )
                path == "/Items" -> exchange.reply("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")
                path == "/Items/$movieId" -> exchange.reply(movieJson)
                path == "/Sessions/Playing" -> exchange.replyEmpty()
                path == "/Sessions/Playing/Progress" -> exchange.replyEmpty()
                path == "/Sessions/Playing/Stopped" -> exchange.replyEmpty()
                path == "/UserFavoriteItems/$movieId" -> exchange.reply(
                    """{"PlaybackPositionTicks":0,"PlayCount":1,"IsFavorite":true,"Played":false,
                        "Key":"$movieId","ItemId":"$movieId"}""",
                )
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
        }
        server.start()
        try {
            val repo = createDesktopJellyfinRepository(
                settings = JellyfinSettings(null, scope),
                secrets = Secrets(),
                scope = scope,
                clientVersion = "1.0",
                deviceName = "PKB110 · Android",
                deviceId = "test-device",
            )
            val added = repo.authenticate("http://127.0.0.1:${server.address.port}", "demo", "pw", "Home")

            val favorites = repo.favorites()
            assertTrue(favorites.isNotEmpty(), requests.joinToString("\n"))
            val favorite = favorites.single()
            assertTrue(favorite.isStarred)
            assertEquals(JellyfinLocation(added.serverId, libraryId).id, favorite.parentId)

            val history = repo.playbackHistory()
            assertEquals(1, history.size)
            assertEquals("30.0", history.single().params["playback_position"])
            assertNotNull(history.single().params["last_played"])

            assertEquals(30_000L, repo.resumePosition(favorite.id))

            repo.reportPlayback(favorite.id, 31_000L, 60_000L, stopped = false)
            repo.reportPlayback(favorite.id, 32_000L, 60_000L, stopped = true)
            assertTrue(requests.any { it.startsWith("POST /Sessions/Playing?") })
            assertTrue(requests.any { it.startsWith("POST /Sessions/Playing/Progress?") })
            assertTrue(requests.any { it.startsWith("POST /Sessions/Playing/Stopped?") })

            repo.setFavorite(listOf(favorite.id), true)
            assertTrue(requests.any { it.startsWith("POST /UserFavoriteItems/$movieId") })
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.replyEmpty() {
        sendResponseHeaders(204, -1)
        close()
    }
}
