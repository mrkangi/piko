package dev.piko.shared.download

import dev.piko.download.DownloadTask
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.smoke.MemoryPreferences
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val request = request("request-1")
        val store = MemoryStore()
        val executor = FakeExecutor()
        val realtime = FakeRealtime()
        val coordinator = coordinator("windows", store, executor, realtime).also { it.start() }
        coordinator.refresh()
        await("realtime started") { realtime.started }

        realtime.emit(RemoteRealtimeEvent.Presence(presence("android", "Pixel", online = true)))
        realtime.emit(RemoteRealtimeEvent.Request(request))
        await("target enqueues request") { executor.enqueued.size == 1 }

        assertEquals("request-1", executor.enqueued.single().requestId)
        assertEquals(RemoteDownloadStatus.ACCEPTED, realtime.states.last().status)
        assertEquals("android", realtime.states.last().sourceDeviceId)
    }

    @Test
    fun sourceSendsRequestAndLearnsTargetOnline() = runBlocking {
        val store = MemoryStore()
        val realtime = FakeRealtime()
        val coordinator = coordinator("android", store, FakeExecutor(), realtime).also { it.start() }
        coordinator.refresh()
        await("realtime started") { realtime.started }
        realtime.emit(RemoteRealtimeEvent.Presence(presence("windows", "Desktop", online = true)))
        await("target online") { coordinator.devices.value.any { it.deviceId == "windows" && it.online } }

        val result = coordinator.sendDownload(listOf(file), "windows")

        assertTrue(result.isSuccess)
        val request = realtime.requests.single()
        assertEquals("windows", request.targetDeviceId)
        assertEquals(file.id, request.fileId)
        assertEquals(RemoteDownloadStatus.QUEUED, coordinator.remoteDownloads.value.single().status)
    }

    @Test
    fun targetPausesAndRetriesRemoteTask() = runBlocking {
        val request = request("request-command")
        val store = MemoryStore()
        val executor = FakeExecutor()
        executor.tasks.value = mapOf(
            "remote-task" to DownloadTask(
                taskId = "remote-task",
                fileId = file.id,
                fileName = file.name,
                gcid = file.hash,
                totalBytes = file.sizeBytes,
                downloadedBytes = 256,
                speedBytesPerSec = 128,
                status = DownloadStatus.DOWNLOADING,
                remoteRequestId = request.requestId,
                originDeviceId = "android",
            ),
        )
        val realtime = FakeRealtime()
        val coordinator = coordinator("windows", store, executor, realtime).also { it.start() }
        coordinator.refresh()
        await("realtime started") { realtime.started }

        realtime.emit(RemoteRealtimeEvent.Request(request.copy(command = RemoteDownloadCommand.PAUSE, commandAtMs = 2)))
        await("target paused") { executor.paused == listOf("remote-task") }
        assertEquals(RemoteDownloadStatus.PAUSED, realtime.states.last().status)

        realtime.emit(RemoteRealtimeEvent.Request(request.copy(command = RemoteDownloadCommand.RETRY, commandAtMs = 3)))
        await("target retried") { executor.retried == listOf("remote-task") }
        assertEquals(RemoteDownloadStatus.ACCEPTED, realtime.states.last().status)
    }

    @Test
    fun realtimeStateUpdatesOutgoingRequest() = runBlocking {
        val store = MemoryStore()
        val realtime = FakeRealtime()
        val coordinator = coordinator("android", store, FakeExecutor(), realtime).also { it.start() }
        coordinator.refresh()
        await("realtime started") { realtime.started }
        realtime.emit(RemoteRealtimeEvent.Presence(presence("windows", "Desktop", online = true)))
        await("target online") { coordinator.devices.value.any { it.deviceId == "windows" && it.online } }
        val requestId = coordinator.sendDownload(listOf(file), "windows").getOrThrow().single()

        realtime.emit(
            RemoteRealtimeEvent.State(
                RemoteDownloadState(
                    requestId = requestId,
                    sourceDeviceId = "android",
                    targetDeviceId = "windows",
                    status = RemoteDownloadStatus.DOWNLOADING,
                    downloadedBytes = 512,
                    totalBytes = 1024,
                    updatedAtMs = 2_000L,
                ),
            ),
        )

        await("state applied") { coordinator.remoteDownloads.value.singleOrNull()?.status == RemoteDownloadStatus.DOWNLOADING }
        assertEquals(512L, coordinator.remoteDownloads.value.single().downloadedBytes)
    }

    @Test
    fun offlineDeviceCanBeForgotten() = runBlocking {
        val store = MemoryStore()
        val realtime = FakeRealtime()
        val coordinator = coordinator("android", store, FakeExecutor(), realtime).also { it.start() }
        coordinator.refresh()
        await("realtime started") { realtime.started }
        realtime.emit(RemoteRealtimeEvent.Presence(presence("windows", "Desktop", online = false)))
        await("offline device visible") { coordinator.devices.value.any { it.deviceId == "windows" && !it.online } }

        assertTrue(coordinator.forgetDevice("windows").isSuccess)

        assertTrue(coordinator.devices.value.none { it.deviceId == "windows" })
    }

    @Test
    fun requestsSurviveRestartFromLocalCache() = runBlocking {
        val store = MemoryStore()
        val realtime = FakeRealtime()
        val first = coordinator("android", store, FakeExecutor(), realtime).also { it.start() }
        first.refresh()
        await("realtime started") { realtime.started }
        realtime.emit(RemoteRealtimeEvent.Presence(presence("windows", "Desktop", online = true)))
        await("target online") { first.devices.value.any { it.deviceId == "windows" && it.online } }
        first.sendDownload(listOf(file), "windows").getOrThrow()

        val second = coordinator("android", store, FakeExecutor(), FakeRealtime())
        second.refresh()

        assertEquals(1, second.remoteDownloads.value.size)
        assertEquals(file.name, second.remoteDownloads.value.single().fileName)
    }

    private fun request(id: String) = RemoteDownloadRequest(
        requestId = id,
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

    private fun presence(deviceId: String, name: String, online: Boolean) = RemotePresence(
        deviceId = deviceId,
        name = name,
        platform = "test",
        receiverEnabled = true,
        online = online,
        updatedAtMs = System.currentTimeMillis(),
    )

    private suspend fun coordinator(
        deviceId: String,
        store: MemoryStore,
        executor: FakeExecutor,
        realtime: RemoteRealtimeTransport,
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
            deviceName = "Test device · Piko test",
            platformName = "test",
            scope = scope,
            storeFactory = { store },
            resolveFile = { file },
            accountProvider = { "account" },
            deviceIdOverride = deviceId,
            realtime = realtime,
        )
    }

    private suspend fun await(message: String, condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private class FakeRealtime : RemoteRealtimeTransport {
        private val _events = MutableSharedFlow<RemoteRealtimeEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<RemoteRealtimeEvent> = _events
        override val connected: StateFlow<Boolean> = MutableStateFlow(true)
        val requests = mutableListOf<RemoteDownloadRequest>()
        val states = mutableListOf<RemoteDownloadState>()
        var started = false
            private set

        suspend fun emit(event: RemoteRealtimeEvent) {
            _events.emit(event)
        }

        override fun start(account: String, deviceId: String, deviceName: String, platform: String) {
            started = true
        }

        override fun stop() = Unit
        override fun updateConfig(config: RemoteRealtimeConfig?) = Unit
        override suspend fun testConnection(config: RemoteRealtimeConfig): Result<Unit> = Result.success(Unit)
        override fun publishPresence(receiverEnabled: Boolean) = Unit

        override fun publishRequest(targetDeviceId: String, request: RemoteDownloadRequest): Boolean {
            requests += request
            return true
        }

        override fun publishState(sourceDeviceId: String, state: RemoteDownloadState): Boolean {
            states += state
            return true
        }
    }

    private class FakeExecutor : RemoteDownloadExecutor {
        override val tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
        val enqueued = mutableListOf<RemoteDownloadRequest>()
        val paused = mutableListOf<String>()
        val retried = mutableListOf<String>()

        override fun enqueueRemote(file: FileStat, request: RemoteDownloadRequest, onQueued: (List<String>) -> Unit) {
            enqueued += request
            onQueued(listOf("task-${request.requestId}"))
        }

        override fun cancelRemote(taskId: String) = Unit

        override fun pauseRemote(taskId: String) {
            paused += taskId
            tasks.update { current -> current + (taskId to current.getValue(taskId).copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)) }
        }

        override fun resumeRemote(taskId: String) {
            tasks.update { current -> current + (taskId to current.getValue(taskId).copy(status = DownloadStatus.PENDING)) }
        }

        override fun retryRemote(taskId: String) {
            retried += taskId
            tasks.update { current -> current + (taskId to current.getValue(taskId).copy(status = DownloadStatus.PENDING, errorMessage = null)) }
        }

        override suspend fun findCompletedLocalPath(file: FileStat): String? = null
    }

    private class MemoryStore : RemoteDownloadStore {
        private val values = mutableMapOf<String, RemoteDownloadLocalState>()

        override suspend fun read(account: String): RemoteDownloadLocalState? = values[account]

        override suspend fun write(account: String, state: RemoteDownloadLocalState) {
            values[account] = state
        }
    }
}
