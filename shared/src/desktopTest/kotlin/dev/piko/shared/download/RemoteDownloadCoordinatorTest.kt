package dev.piko.shared.download

import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.smoke.MemoryPreferences
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteDownloadCoordinatorTest {
    private val file = FileStat(
        id = "file-1",
        parentId = "folder-1",
        name = "episode.mkv",
        hash = "GCID1",
        size = "1024",
    )

    @Test
    fun targetAcceptsRequestFromAnotherDevice() = runBlocking {
        val request = RemoteDownloadRequest(
            requestId = "request-1",
            sourceDeviceId = "android",
            targetDeviceId = "windows",
            fileId = file.id,
            fileName = file.name,
            gcid = file.hash,
            parentId = file.parentId,
            totalBytes = file.sizeBytes,
            createdAtMs = 1_000L,
            expiresAtMs = Long.MAX_VALUE,
        )
        val store = MemoryStore(
            mutableMapOf(
                "android" to RemoteDeviceDocument(
                    deviceId = "android",
                    name = "Pixel",
                    platform = "android",
                    receiverEnabled = true,
                    requests = listOf(request),
                ),
            ),
        )
        val executor = FakeExecutor()
        val coordinator = coordinator("windows", store, executor)

        coordinator.refresh()

        assertEquals(listOf("request-1"), executor.enqueued.map { it.requestId })
        val state = assertNotNull(store.documents["windows"]).states.single()
        assertEquals(RemoteDownloadStatus.ACCEPTED, state.status)
        assertEquals("android", state.sourceDeviceId)
    }

    @Test
    fun sourceWritesRequestIntoItsOwnDocument() = runBlocking {
        val store = MemoryStore(
            mutableMapOf(
                "windows" to RemoteDeviceDocument(
                    deviceId = "windows",
                    name = "Desktop",
                    platform = "windows",
                    receiverEnabled = true,
                ),
            ),
        )
        val coordinator = coordinator("android", store, FakeExecutor())
        coordinator.refresh()

        val result = coordinator.sendDownload(listOf(file), "windows")

        assertTrue(result.isSuccess)
        val sent = assertNotNull(store.documents["android"]).requests.single()
        assertEquals("windows", sent.targetDeviceId)
        assertEquals(file.id, sent.fileId)
        assertEquals(RemoteDownloadStatus.QUEUED, coordinator.remoteDownloads.value.single().status)
    }

    @Test
    fun requestIsRejectedWhenFileNoLongerExists() = runBlocking {
        val request = RemoteDownloadRequest(
            requestId = "missing",
            sourceDeviceId = "android",
            targetDeviceId = "windows",
            fileId = "gone",
            fileName = "gone.mkv",
            totalBytes = 1,
            createdAtMs = 1_000L,
            expiresAtMs = Long.MAX_VALUE,
        )
        val store = MemoryStore(
            mutableMapOf(
                "android" to RemoteDeviceDocument(
                    deviceId = "android",
                    name = "Pixel",
                    platform = "android",
                    requests = listOf(request),
                ),
            ),
        )
        val coordinator = coordinator("windows", store, FakeExecutor(), resolveFile = { null })

        coordinator.refresh()

        val state = assertNotNull(store.documents["windows"]).states.single()
        assertEquals(RemoteDownloadStatus.REJECTED, state.status)
    }

    private suspend fun coordinator(
        deviceId: String,
        store: MemoryStore,
        executor: FakeExecutor,
        resolveFile: suspend (String) -> FileStat? = { file },
    ): RemoteDownloadCoordinator {
        val preferences = MemoryPreferences()
        preferences.setRemoteDownloadEnabled(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return RemoteDownloadCoordinator(
            clients = object : PikoClientProvider {
                override val currentClient: StateFlow<PikPakClient?> = MutableStateFlow(null)
            },
            driveRepo = null,
            executor = executor,
            preferences = preferences,
            deviceName = "Test device",
            platformName = "test",
            scope = scope,
            storeFactory = { store },
            resolveFile = resolveFile,
            accountProvider = { "account" },
            deviceIdOverride = deviceId,
        )
    }

    private class FakeExecutor : RemoteDownloadExecutor {
        override val tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
        val enqueued = mutableListOf<RemoteDownloadRequest>()

        override fun enqueueRemote(file: FileStat, request: RemoteDownloadRequest, onQueued: (List<String>) -> Unit) {
            enqueued += request
            onQueued(listOf("task-${request.requestId}"))
        }

        override fun cancelRemote(taskId: String) = Unit

        override suspend fun findCompletedLocalPath(file: FileStat): String? = null
    }

    private class MemoryStore(
        val documents: MutableMap<String, RemoteDeviceDocument> = mutableMapOf(),
    ) : RemoteDownloadStore {
        override suspend fun readAll(account: String): List<RemoteDeviceDocument> = documents.values.toList()

        override suspend fun write(account: String, document: RemoteDeviceDocument) {
            documents[document.deviceId] = document
        }
    }
}
