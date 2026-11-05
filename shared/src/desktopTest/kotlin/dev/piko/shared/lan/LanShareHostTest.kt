package dev.piko.shared.lan

import dev.piko.shared.data.LanLocation
import dev.piko.shared.data.LocalFileAccess
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.smoke.FakePikPakServer
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.awt.image.BufferedImage
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanShareHostTest {
    @Test
    fun hostPairsServesRangeAndBuildsIndex() = smoke { scope ->
        val root = Files.createTempDirectory("piko-lan-share").toFile()
        try {
            File(root, "movie.mp4").writeBytes(ByteArray(1024) { it.toByte() })
            File(root, "sub").mkdirs()
            val photo = File(root, "sub/photo.png")
            javax.imageio.ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "png", photo)
            val preferences = preferences()
            val repository = PikoDriveRepository(FakePikPakServer().provider(), preferences)
            repository.localMounts.access = FileAccess(root)
            val mount = repository.localMounts.mount(root.absolutePath, "Media")
            repository.localMounts.setLanShared(mount.location.id, true)

            val settings = LanShareSettings(preferences, scope)
            val port = ServerSocket(0).use { it.localPort }
            settings.update { it.copy(enabled = true, port = port) }
            val host = DesktopLanShareHost(repository, preferences, settings, "Test host", "test", scope)
            host.start()
            awaitUntil("局域网 host 启动") { host.status.value.running }
            val status = host.status.value
            assertTrue(status.port in port until (port + 10))

            val http = HttpClient(OkHttp)
            try {
                val client = LanShareRepository(
                    http = http,
                    settings = settings,
                    scope = scope,
                    clientDeviceId = { "test-client" },
                    clientName = { "Test client" },
                    clientPlatform = "test",
                )
                val peer = client.pair("127.0.0.1", status.port, status.pairingCode)
                val shares = client.shareNodes(peer.hostDeviceId)
                assertEquals(listOf(LanLocation(peer.hostDeviceId, mount.mountId).id), shares.map { it.id })

                val shareRoot = LanLocation(peer.hostDeviceId, mount.mountId)
                val rootListing = client.list(shareRoot)
                assertEquals(setOf("movie.mp4", "sub"), rootListing.map { it.name }.toSet())
                val subFolder = assertNotNull(rootListing.firstOrNull { it.name == "sub" })
                val photos = client.list(LanLocation.of(subFolder.id)!!)
                assertEquals(listOf("photo.png"), photos.map { it.name })
                assertTrue(photos.single().thumbnailLink.contains("/v1/thumb"))

                client.ensureIndex(shareRoot)
                assertEquals(setOf("movie.mp4", "sub"), client.list(shareRoot).map { it.name }.toSet())
                assertEquals(listOf("photo.png"), client.list(LanLocation.of(subFolder.id)!!).map { it.name })

                val movie = assertNotNull(rootListing.firstOrNull { it.name == "movie.mp4" })
                val movieLocation = assertNotNull(LanLocation.of(movie.id))
                val url = assertNotNull(client.streamUrl(movieLocation))
                val ranged = http.get(url) { header("Range", "bytes=10-19") }
                assertEquals(HttpStatusCode.PartialContent, ranged.status)
                assertEquals(10, ranged.bodyAsBytes().size)
                val thumbnail = http.get(photos.single().thumbnailLink)
                assertEquals(HttpStatusCode.OK, thumbnail.status)
                assertEquals("image/jpeg", thumbnail.headers["Content-Type"])
            } finally {
                http.close()
                host.stop()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private class FileAccess(private val root: File) : LocalFileAccess {
        override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> {
            val directory = resolve(location)
            return directory.listFiles().orEmpty().map { file ->
                stat(mount, location.child(file.name, file.isDirectory), file)
            }
        }

        override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String = resolve(location).absolutePath

        override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? {
            val file = resolve(location)
            if (!file.exists()) return null
            return stat(mount, location, file)
        }

        private fun resolve(location: LocalLocation): File =
            location.segments.fold(root) { current, segment -> File(current, segment) }

        private fun stat(mount: LocalMount, location: LocalLocation, file: File): FileStat = FileStat(
            id = location.copy(directory = file.isDirectory).id,
            parentId = location.parent?.id ?: dev.piko.shared.data.DeviceLocation.SELF_ID,
            name = file.name,
            kind = if (file.isDirectory) FileKind.FOLDER else FileKind.FILE,
            size = if (file.isDirectory) "0" else file.length().toString(),
            mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> ""
            },
            modifiedTime = kotlin.time.Instant.fromEpochMilliseconds(file.lastModified()).toString(),
            phase = TaskPhase.COMPLETE,
            fileExtension = file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
        )
    }

    private fun preferences(): PikoUserPreferences = object : PikoUserPreferences by MemoryPreferences() {
        private val mounts = kotlinx.coroutines.flow.MutableStateFlow("")
        private val lan = kotlinx.coroutines.flow.MutableStateFlow("")
        override val localMountsFlow = mounts
        override suspend fun saveLocalMounts(serialized: String) { mounts.value = serialized }
        override val lanShareSettingsFlow = lan
        override suspend fun saveLanShareSettings(serialized: String) { lan.value = serialized }
    }
}
