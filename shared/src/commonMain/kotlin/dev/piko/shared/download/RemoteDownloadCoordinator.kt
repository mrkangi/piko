package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 跨设备远程下载的协调器。
 *
 * 设备之间只同步「请求」与「状态」，不传文件本身。目标设备拿到 `fileId` 后直接用自己的
 * PikPak 会话下载到本机，源设备只负责读回状态。传输层是网盘根目录 `.piko` 下每台设备一份的
 * `remote-<deviceId>-<timestamp>.json`，不依赖公网 IP、端口映射或中间服务器。
 */
class RemoteDownloadCoordinator(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository?,
    private val executor: RemoteDownloadExecutor,
    private val preferences: PikoUserPreferences,
    private val deviceName: String,
    private val platformName: String,
    private val scope: CoroutineScope,
    private val storeFactory: (String) -> RemoteDownloadStore = { id ->
        DriveRemoteDownloadStore(clients, requireNotNull(driveRepo), id)
    },
    private val resolveFile: suspend (String) -> FileStat? = { id ->
        driveRepo?.getFileDetail(id)?.getOrNull()?.let { detail ->
            FileStat(
                id = detail.id,
                parentId = detail.parentId,
                name = detail.name,
                hash = detail.hash,
                size = detail.size,
                kind = detail.kind,
                phase = TaskPhase.COMPLETE,
            )
        }
    },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val accountProvider: () -> String? = { clients.currentClient.value?.account },
    private val pollIntervalMs: Long = 15_000L,
    private val requestTtlMs: Long = 24L * 60L * 60L * 1000L,
    private val retentionMs: Long = 7L * 24L * 60L * 60L * 1000L,
    private val deviceIdOverride: String? = null,
) {
    private val initLock = Mutex()
    private val lock = Mutex()

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _remoteDownloads = MutableStateFlow<List<RemoteDownloadItem>>(emptyList())
    val remoteDownloads: StateFlow<List<RemoteDownloadItem>> = _remoteDownloads.asStateFlow()

    @Volatile
    var deviceId: String = ""
        private set

    @Volatile
    var resolvedDeviceName: String = deviceName
        private set

    private var store: RemoteDownloadStore? = null
    private var own: RemoteDeviceDocument? = null
    /** 最近一轮读到的全部设备账本，供发送后立即刷新界面用。 */
    private var documents: Map<String, RemoteDeviceDocument> = emptyMap()
    @Volatile
    private var enabled = false
    private var initialized = false
    private var started = false
    private var dirty = false
    private var lastWriteMs = 0L
    private val processed = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { initialize() }
                .onFailure {
                    PikoLog.w(TAG, "远程下载初始化失败", it)
                    return@launch
                }
            launch {
                preferences.remoteDownloadEnabledFlow.distinctUntilChanged().collect { on ->
                    enabled = on
                    if (on) {
                        refresh()
                    } else {
                        lock.withLock {
                            val current = own ?: return@withLock
                            if (current.receiverEnabled) {
                                own = current.copy(receiverEnabled = false, updatedAtMs = now())
                                dirty = true
                                writeOwnLocked()
                            }
                        }
                    }
                }
            }
            launch {
                clients.currentClient.map { it?.account }.distinctUntilChanged().collect { account ->
                    lock.withLock {
                        processed.clear()
                        own = null
                        documents = emptyMap()
                        dirty = false
                    }
                    _devices.value = emptyList()
                    _remoteDownloads.value = emptyList()
                    if (account != null && enabled) {
                        refresh()
                    }
                }
            }
            launch {
                executor.tasks.collect { tasks ->
                    lock.withLock {
                        if (syncTaskStatesLocked(tasks.values)) dirty = true
                    }
                }
            }
            while (currentCoroutineContext().isActive) {
                if (enabled && clients.currentClient.value != null) refresh()
                delay(pollIntervalMs)
            }
        }
    }

    /** 应用回到前台或用户点「立即刷新」时调用。 */
    fun refreshNow() {
        scope.launch { refresh() }
    }

    /** 测试与前台刷新共用；初始化后读一轮设备、处理请求并写回状态。 */
    suspend fun refresh() {
        initialize()
        enabled = preferences.remoteDownloadEnabledFlow.first()
        lock.withLock { refreshLocked() }
    }

    suspend fun sendDownload(
        files: List<FileStat>,
        targetDeviceId: String,
        qualityCap: Int? = null,
    ): Result<List<String>> {
        initialize()
        enabled = preferences.remoteDownloadEnabledFlow.first()
        return lock.withLock {
            if (targetDeviceId == deviceId) {
                return@withLock Result.failure(IllegalArgumentException(tr("目标设备不能是本机", "The target must be another device", "保存先は別の端末にしてください", "대상은 다른 기기여야 합니다", "目標裝置不能是本機")))
            }
            val current = own ?: refreshLocked().let { own }
                ?: return@withLock Result.failure(IllegalStateException(tr("尚未连接账号", "No account is connected", "アカウントに接続していません", "계정에 연결되어 있지 않습니다", "尚未連線帳號")))
            val target = _devices.value.firstOrNull { it.deviceId == targetDeviceId }
                ?: return@withLock Result.failure(IllegalArgumentException(tr("设备不存在", "Device not found", "端末が見つかりません", "기기를 찾을 수 없습니다", "找不到裝置")))
            if (!target.receiverEnabled) {
                return@withLock Result.failure(IllegalStateException(tr("目标设备未开启远程下载", "Remote downloads are off on the target device", "保存先のリモートダウンロードがオフです", "대상 기기의 원격 다운로드가 꺼져 있습니다", "目標裝置未開啟遠端下載")))
            }
            val accepted = files.filter { !it.isFolder && !it.isUploading }.distinctBy { it.id }
            if (accepted.isEmpty()) {
                return@withLock Result.failure(IllegalArgumentException(tr("没有可下载的文件", "No downloadable files", "ダウンロードできるファイルがありません", "다운로드할 파일이 없습니다", "沒有可下載的檔案")))
            }
            val stamp = now()
            val requests = accepted.map { file ->
                RemoteDownloadRequest(
                    requestId = newRemoteRequestId(),
                    sourceDeviceId = deviceId,
                    targetDeviceId = targetDeviceId,
                    fileId = file.id,
                    fileName = file.name,
                    gcid = file.hash,
                    parentId = file.parentId,
                    totalBytes = file.sizeBytes,
                    qualityCap = qualityCap,
                    createdAtMs = stamp,
                    expiresAtMs = stamp + requestTtlMs,
                )
            }
            own = current.copy(
                requests = (current.requests + requests).takeLast(MAX_REQUESTS),
                updatedAtMs = stamp,
            )
            dirty = true
            writeOwnLocked()
            publishLocked(requests = allRequestsLocked(), states = allStatesLocked())
            PikoLog.i(TAG, "已发送 ${requests.size} 个远程下载请求到 ${target.name}")
            Result.success(requests.map { it.requestId })
        }
    }

    suspend fun cancel(requestId: String): Result<Unit> {
        initialize()
        return lock.withLock {
            val current = own ?: return@withLock Result.failure(IllegalStateException(tr("尚未连接账号", "No account is connected", "アカウントに接続していません", "계정에 연결되어 있지 않습니다", "尚未連線帳號")))
            val index = current.requests.indexOfFirst { it.requestId == requestId }
            if (index < 0) return@withLock Result.failure(IllegalArgumentException(tr("请求不存在", "Request not found", "リクエストが見つかりません", "요청을 찾을 수 없습니다", "找不到請求")))
            val requests = current.requests.toMutableList()
            requests[index] = requests[index].copy(cancelRequested = true)
            own = current.copy(requests = requests, updatedAtMs = now())
            dirty = true
            writeOwnLocked()
            publishLocked(requests = allRequestsLocked(), states = allStatesLocked())
            Result.success(Unit)
        }
    }

    private suspend fun initialize() {
        initLock.withLock {
            if (initialized) return@withLock
            val id = deviceIdOverride?.takeIf { it.isNotBlank() }
                ?: preferences.loadOrCreateRemoteDeviceId().takeIf { it.isNotBlank() }
                ?: newRemoteDeviceId()
            deviceId = id
            val storedName = preferences.loadRemoteDeviceName().trim()
            resolvedDeviceName = storedName.ifBlank { deviceName }
            if (storedName.isBlank()) preferences.saveRemoteDeviceName(resolvedDeviceName)
            store = storeFactory(id)
            initialized = true
        }
    }

    private suspend fun refreshLocked() {
        if (!enabled) return
        val account = accountProvider() ?: return
        val currentStore = store ?: return
        val docs = runCatching { currentStore.readAll(account) }
            .logFailure(TAG, "读取远程设备账本失败")
            .getOrDefault(emptyList())
        val byDevice = LinkedHashMap<String, RemoteDeviceDocument>()
        docs.forEach { doc ->
            if (doc.deviceId.isNotBlank() && (byDevice[doc.deviceId]?.updatedAtMs ?: Long.MIN_VALUE) < doc.updatedAtMs) {
                byDevice[doc.deviceId] = doc
            }
        }
        val mine = own ?: byDevice[deviceId] ?: RemoteDeviceDocument(
            deviceId = deviceId,
            name = resolvedDeviceName,
            platform = platformName,
        )
        own = mine.copy(
            name = resolvedDeviceName,
            platform = platformName,
            receiverEnabled = enabled,
            updatedAtMs = now(),
        )
        byDevice[deviceId] = own!!
        this.documents = byDevice

        _devices.value = byDevice.values
            .map { doc ->
                RemoteDevice(
                    deviceId = doc.deviceId,
                    name = doc.name.ifBlank { doc.deviceId },
                    platform = doc.platform,
                    receiverEnabled = doc.receiverEnabled,
                    updatedAtMs = doc.updatedAtMs,
                )
            }
            .sortedByDescending { it.updatedAtMs }

        if (enabled) {
            processIncomingLocked(byDevice.values.flatMap { it.requests })
        }
        syncTaskStatesLocked(executor.tasks.value.values)
        cleanupLocked()
        processed.retainAll(allRequestsLocked().mapTo(mutableSetOf()) { it.requestId })
        byDevice[deviceId] = own!!
        this.documents = byDevice
        publishLocked(requests = allRequestsLocked(), states = allStatesLocked())
        if (dirty || now() - lastWriteMs >= WRITE_INTERVAL_MS) writeOwnLocked()
    }

    private suspend fun processIncomingLocked(requests: List<RemoteDownloadRequest>) {
        val mine = own ?: return
        val states = mine.states.associateBy { it.requestId }
        val stamp = now()
        for (request in requests) {
            if (request.targetDeviceId != deviceId) continue
            if (request.sourceDeviceId == deviceId) continue
            if (request.cancelRequested) {
                val existing = own?.states?.firstOrNull { it.requestId == request.requestId }
                if (existing?.status !in TERMINAL_STATUSES) {
                    executor.tasks.value.values
                        .firstOrNull { it.remoteRequestId == request.requestId }
                        ?.let { executor.cancelRemote(it.taskId) }
                    upsertStateLocked(request, RemoteDownloadStatus.CANCELLED, error = null)
                }
                processed += request.requestId
                continue
            }
            if (states[request.requestId] != null || request.requestId in processed) continue
            if (request.expiresAtMs > 0L && request.expiresAtMs < stamp) {
                upsertStateLocked(request, RemoteDownloadStatus.EXPIRED, error = tr("请求已过期", "Request expired", "リクエストの期限が切れました", "요청이 만료되었습니다", "請求已過期"))
                processed += request.requestId
                continue
            }

            val file = resolveFile(request.fileId)
            if (file == null || file.isFolder) {
                upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = tr("文件不存在或不可下载", "The file is missing or cannot be downloaded", "ファイルが存在しないか、ダウンロードできません", "파일이 없거나 다운로드할 수 없습니다", "檔案不存在或無法下載"))
                processed += request.requestId
                continue
            }
            val completed = executor.findCompletedLocalPath(file)
            if (completed != null) {
                upsertStateLocked(
                    request,
                    RemoteDownloadStatus.COMPLETED,
                    downloadedBytes = file.sizeBytes,
                    totalBytes = file.sizeBytes,
                )
                processed += request.requestId
                continue
            }

            upsertStateLocked(request, RemoteDownloadStatus.ACCEPTED, totalBytes = file.sizeBytes)
            processed += request.requestId
            runCatching {
                executor.enqueueRemote(file, request) { taskIds ->
                    scope.launch {
                        lock.withLock {
                            if (taskIds.isEmpty()) {
                                upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = tr("目标设备无法加入下载队列", "The target device could not queue this download", "保存先がダウンロードキューに追加できませんでした", "대상 기기가 다운로드 대기열에 추가하지 못했습니다", "目標裝置無法加入下載佇列"))
                                writeOwnLocked()
                            }
                        }
                    }
                }
            }.onFailure { error ->
                PikoLog.w(TAG, "目标设备加入下载队列失败", error)
                upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = error.message)
            }
        }
    }

    /** @return 是否有状态变化。 */
    private fun syncTaskStatesLocked(tasks: Collection<DownloadTask>): Boolean {
        if (own == null) return false
        var changed = false
        val remoteTasks = tasks.filter { it.remoteRequestId.isNotBlank() }
        for (task in remoteTasks) {
            val requestId = task.remoteRequestId
            val existing = own?.states?.firstOrNull { it.requestId == requestId }
            if (existing?.status == RemoteDownloadStatus.CANCELLED ||
                existing?.status == RemoteDownloadStatus.REJECTED ||
                existing?.status == RemoteDownloadStatus.EXPIRED
            ) {
                continue
            }
            val status = when (task.status) {
                DownloadStatus.PENDING -> RemoteDownloadStatus.ACCEPTED
                DownloadStatus.DOWNLOADING -> RemoteDownloadStatus.DOWNLOADING
                DownloadStatus.PAUSED -> RemoteDownloadStatus.PAUSED
                DownloadStatus.COMPLETED -> RemoteDownloadStatus.COMPLETED
                DownloadStatus.FAILED -> RemoteDownloadStatus.FAILED
            }
            val next = RemoteDownloadState(
                requestId = requestId,
                sourceDeviceId = task.originDeviceId,
                targetDeviceId = deviceId,
                status = status,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                error = task.errorMessage,
                updatedAtMs = now(),
            )
            val prior = own?.states?.firstOrNull { it.requestId == requestId }
            if (prior != null &&
                prior.status == next.status &&
                prior.downloadedBytes == next.downloadedBytes &&
                prior.totalBytes == next.totalBytes &&
                prior.error == next.error
            ) {
                continue
            }
            upsertState(next)
            changed = true
        }

        val activeRequestIds = remoteTasks.mapTo(mutableSetOf()) { it.remoteRequestId }
        val stamp = now()
        for (state in own?.states.orEmpty()) {
            if (state.status == RemoteDownloadStatus.ACCEPTED &&
                state.requestId !in activeRequestIds &&
                stamp - state.updatedAtMs > ACCEPT_TIMEOUT_MS
            ) {
                upsertState(
                    state.copy(
                        status = RemoteDownloadStatus.FAILED,
                        error = tr("目标设备未能开始下载", "The target device could not start the download", "保存先がダウンロードを開始できませんでした", "대상 기기가 다운로드를 시작하지 못했습니다", "目標裝置未能開始下載"),
                        updatedAtMs = stamp,
                    ),
                )
                changed = true
            }
        }
        return changed
    }

    private fun upsertStateLocked(
        request: RemoteDownloadRequest,
        status: RemoteDownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = request.totalBytes,
        error: String? = null,
    ) {
        upsertState(
            RemoteDownloadState(
                requestId = request.requestId,
                sourceDeviceId = request.sourceDeviceId,
                targetDeviceId = request.targetDeviceId,
                status = status,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                error = error,
                updatedAtMs = now(),
            ),
        )
    }

    private fun upsertState(state: RemoteDownloadState) {
        val current = own ?: return
        val states = current.states.toMutableList()
        val index = states.indexOfFirst { it.requestId == state.requestId }
        if (index >= 0) states[index] = state else states += state
        own = current.copy(states = states.takeLast(MAX_STATES), updatedAtMs = now())
        dirty = true
    }

    private fun publishLocked(
        requests: List<RemoteDownloadRequest>,
        states: Map<String, RemoteDownloadState>,
    ) {
        val devices = _devices.value.associateBy { it.deviceId }
        val stamp = now()
        _remoteDownloads.value = requests
            .filter { it.sourceDeviceId == deviceId || it.targetDeviceId == deviceId }
            .distinctBy { it.requestId }
            .map { request ->
                val state = states[request.requestId]
                val expired = request.expiresAtMs > 0L && request.expiresAtMs < stamp
                RemoteDownloadItem(
                    request = request,
                    state = state,
                    sourceDeviceName = devices[request.sourceDeviceId]?.name ?: request.sourceDeviceId,
                    targetDeviceName = devices[request.targetDeviceId]?.name ?: request.targetDeviceId,
                    outgoing = request.sourceDeviceId == deviceId,
                    status = state?.status ?: if (expired) RemoteDownloadStatus.EXPIRED else RemoteDownloadStatus.QUEUED,
                )
            }
            .sortedByDescending { it.state?.updatedAtMs ?: it.request.createdAtMs }
    }

    private fun allRequestsLocked(): List<RemoteDownloadRequest> {
        val latest = documents.toMutableMap()
        own?.let { latest[deviceId] = it }
        return latest.values.flatMap { it.requests }.distinctBy { it.requestId }
    }

    private fun allStatesLocked(): Map<String, RemoteDownloadState> {
        val latest = documents.toMutableMap()
        own?.let { latest[deviceId] = it }
        return latest.values
            .flatMap { it.states }
            .groupBy { it.requestId }
            .mapValues { (_, states) -> states.maxBy { it.updatedAtMs } }
    }

    private fun cleanupLocked() {
        val current = own ?: return
        val stamp = now()
        val requests = current.requests.filter { it.createdAtMs == 0L || stamp - it.createdAtMs <= retentionMs }
        val states = current.states.filter { it.updatedAtMs == 0L || stamp - it.updatedAtMs <= retentionMs }
        if (requests.size != current.requests.size || states.size != current.states.size) {
            own = current.copy(requests = requests, states = states)
            dirty = true
        }
    }

    private suspend fun writeOwnLocked() {
        val account = accountProvider() ?: return
        val currentStore = store ?: return
        val current = own ?: return
        val document = current.copy(updatedAtMs = now())
        own = document
        runCatching { currentStore.write(account, document) }
            .logFailure(TAG, "写入远程设备账本失败")
            .onSuccess {
                dirty = false
                lastWriteMs = now()
            }
    }

    private companion object {
        const val TAG = "RemoteDownload"
        const val WRITE_INTERVAL_MS = 60_000L
        const val ACCEPT_TIMEOUT_MS = 120_000L
        const val MAX_REQUESTS = 200
        const val MAX_STATES = 200
        val TERMINAL_STATUSES = setOf(
            RemoteDownloadStatus.COMPLETED,
            RemoteDownloadStatus.FAILED,
            RemoteDownloadStatus.CANCELLED,
            RemoteDownloadStatus.REJECTED,
            RemoteDownloadStatus.EXPIRED,
        )
    }
}
