package dev.piko.shared.download

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoCacheStore
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
 * 跨设备远程下载协调器。
 *
 * 请求、执行状态与设备在线状态全部经 MQTT 传输；网盘 `.piko` 不再保存远程下载账本。
 * 本机只在本设备缓存里保存请求与状态，用于进程重启后恢复和重发。
 */
class RemoteDownloadCoordinator(
    private val clients: PikoClientProvider,
    private val driveRepo: PikoDriveRepository?,
    private val executor: RemoteDownloadExecutor,
    private val preferences: PikoUserPreferences,
    private val deviceName: String,
    private val platformName: String,
    private val scope: CoroutineScope,
    private val cacheStore: PikoCacheStore? = null,
    private val storeFactory: (String) -> RemoteDownloadStore = { CacheRemoteDownloadStore(cacheStore) },
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
    private val realtime: RemoteRealtimeTransport? = null,
    private val requestTtlMs: Long = 24L * 60L * 60L * 1000L,
    private val retentionMs: Long = 7L * 24L * 60L * 60L * 1000L,
    private val offlineAfterMs: Long = 60_000L,
    private val presenceRecheckMs: Long = 10_000L,
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
    private var account: String? = null
    private val requests = LinkedHashMap<String, RemoteDownloadRequest>()
    private val states = LinkedHashMap<String, RemoteDownloadState>()
    private val presence = LinkedHashMap<String, RemotePresence>()
    private val forgotten = mutableSetOf<String>()
    private val processed = mutableSetOf<String>()
    private val progressSentAt = mutableMapOf<String, Long>()
    private val handledCommands = mutableMapOf<String, Long>()
    @Volatile
    private var enabled = false
    @Volatile
    private var channelConnected = false
    private var initialized = false
    private var started = false
    private var dirty = false
    private var lastPersistMs = 0L

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { initialize() }
                .onFailure {
                    PikoLog.w(TAG, "远程下载初始化失败", it)
                    return@launch
                }
            channelConnected = realtime?.connected?.value == true
            switchAccount(accountProvider())
            realtime?.let { transport ->
                launch {
                    transport.events.collect { event -> handleEvent(event) }
                }
                launch {
                    transport.connected.collect { connected ->
                        channelConnected = connected
                        if (connected && enabled) {
                            transport.publishPresence(true)
                            lock.withLock { republishPendingLocked() }
                        }
                        lock.withLock { publishLocked() }
                    }
                }
            }
            launch {
                preferences.remoteDownloadEnabledFlow.distinctUntilChanged().collect { on ->
                    enabled = on
                    if (on) {
                        startRealtime()
                        realtime?.publishPresence(true)
                    } else {
                        realtime?.publishPresence(false)
                        realtime?.stop()
                    }
                    lock.withLock {
                        if (on) republishPendingLocked()
                        publishLocked()
                        persistLocked(force = true)
                    }
                }
            }
            launch {
                clients.currentClient.map { accountProvider() }.distinctUntilChanged().collect { next ->
                    switchAccount(next)
                }
            }
            launch {
                executor.tasks.collect { tasks ->
                    lock.withLock {
                        if (syncTaskStatesLocked(tasks.values)) publishLocked()
                        persistLocked()
                    }
                }
            }
            launch {
                while (currentCoroutineContext().isActive) {
                    delay(presenceRecheckMs)
                    lock.withLock {
                        syncTaskStatesLocked(executor.tasks.value.values)
                        publishLocked()
                        cleanupLocked()
                        persistLocked()
                    }
                }
            }
        }
    }

    /** 应用回到前台或用户点「立即刷新」时调用。 */
    fun refreshNow() {
        scope.launch { refresh() }
    }

    /** 实时通道配置变化后调用：远程下载开着就按当前配置重连，关着就断开。 */
    fun refreshRealtime() {
        if (!started) return
        if (enabled) {
            startRealtime()
            realtime?.publishPresence(true)
            scope.launch { lock.withLock { republishPendingLocked(); publishLocked() } }
        } else {
            realtime?.publishPresence(false)
            realtime?.stop()
        }
    }

    /** 测试与前台刷新共用：对齐账号、重新发布在线状态和待发请求。 */
    suspend fun refresh() {
        initialize()
        enabled = preferences.remoteDownloadEnabledFlow.first()
        switchAccount(accountProvider())
        if (enabled) {
            startRealtime()
            realtime?.publishPresence(true)
        }
        lock.withLock {
            if (enabled) republishPendingLocked()
            syncTaskStatesLocked(executor.tasks.value.values)
            publishLocked()
            persistLocked()
        }
    }

    suspend fun sendDownload(
        files: List<FileStat>,
        targetDeviceId: String,
        qualityCap: Int? = null,
    ): Result<List<String>> {
        initialize()
        enabled = preferences.remoteDownloadEnabledFlow.first()
        if (!enabled) {
            return Result.failure(IllegalStateException(tr("远程下载未开启", "Remote downloads are off", "リモートダウンロードがオフです", "원격 다운로드가 꺼져 있습니다", "遠端下載未開啟")))
        }
        val transport = realtime
            ?: return Result.failure(IllegalStateException(tr("实时通道不可用", "The realtime channel is unavailable", "リアルタイムチャンネルを利用できません", "실시간 채널을 사용할 수 없습니다", "即時通道無法使用")))
        if (!transport.connected.value) {
            return Result.failure(IllegalStateException(tr("实时通道未连接，请检查 MQTT 配置", "The realtime channel is not connected. Check the MQTT settings", "リアルタイムチャンネルが未接続です。MQTT 設定を確認してください", "실시간 채널이 연결되지 않았습니다. MQTT 설정을 확인하세요", "即時通道未連線，請檢查 MQTT 設定")))
        }
        return lock.withLock {
            if (targetDeviceId == deviceId) {
                return@withLock Result.failure(IllegalArgumentException(tr("目标设备不能是本机", "The target must be another device", "保存先は別の端末にしてください", "대상은 다른 기기여야 합니다", "目標裝置不能是本機")))
            }
            val target = devices.value.firstOrNull { it.deviceId == targetDeviceId }
                ?: return@withLock Result.failure(IllegalArgumentException(tr("设备不存在", "Device not found", "端末が見つかりません", "기기를 찾을 수 없습니다", "找不到裝置")))
            if (!target.online) {
                return@withLock Result.failure(IllegalStateException(tr("目标设备不在线", "The target device is offline", "保存先の端末がオンラインではありません", "대상 기기가 오프라인입니다", "目標裝置不在線")))
            }
            if (!target.receiverEnabled) {
                return@withLock Result.failure(IllegalStateException(tr("目标设备未开启远程下载", "Remote downloads are off on the target device", "保存先のリモートダウンロードがオフです", "대상 기기의 원격 다운로드가 꺼져 있습니다", "目標裝置未開啟遠端下載")))
            }
            val accepted = files.filter { !it.isFolder && !it.isUploading }.distinctBy { it.id }
            if (accepted.isEmpty()) {
                return@withLock Result.failure(IllegalArgumentException(tr("没有可下载的文件", "No downloadable files", "ダウンロードできるファイルがありません", "다운로드할 파일이 없습니다", "沒有可下載的檔案")))
            }
            val stamp = now()
            val created = accepted.map { file ->
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
            created.forEach { requests[it.requestId] = it }
            trimRequestsLocked()
            dirty = true
            created.forEach { transport.publishRequest(it.targetDeviceId, it) }
            publishLocked()
            persistLocked(force = true)
            PikoLog.i(TAG, "已发送 ${created.size} 个远程下载请求到 ${target.name}")
            Result.success(created.map { it.requestId })
        }
    }

    suspend fun cancel(requestId: String): Result<Unit> {
        initialize()
        return lock.withLock {
            val request = requests[requestId]
                ?: return@withLock Result.failure(IllegalArgumentException(tr("请求不存在", "Request not found", "リクエストが見つかりません", "요청을 찾을 수 없습니다", "找不到請求")))
            if (request.sourceDeviceId != deviceId) {
                return@withLock Result.failure(IllegalArgumentException(tr("只能操作本机发出的任务", "Only requests sent by this device can be controlled", "この端末が送信したタスクのみ操作できます", "이 기기에서 보낸 작업만 제어할 수 있습니다", "只能操作本機送出的工作")))
            }
            val updated = request.copy(cancelRequested = true)
            requests[requestId] = updated
            dirty = true
            realtime?.publishRequest(updated.targetDeviceId, updated)
            publishLocked()
            persistLocked(force = true)
            Result.success(Unit)
        }
    }

    suspend fun pause(requestId: String): Result<Unit> = sendCommand(requestId, RemoteDownloadCommand.PAUSE)

    suspend fun resume(requestId: String): Result<Unit> = sendCommand(requestId, RemoteDownloadCommand.RESUME)

    suspend fun retry(requestId: String): Result<Unit> = sendCommand(requestId, RemoteDownloadCommand.RETRY)

    suspend fun forgetDevice(deviceId: String): Result<Unit> {
        initialize()
        if (deviceId == this.deviceId) {
            return Result.failure(IllegalArgumentException(tr("不能移除本机", "This device cannot be removed", "この端末は削除できません", "이 기기는 제거할 수 없습니다", "不能移除此裝置")))
        }
        return lock.withLock {
            forgotten += deviceId
            presence.remove(deviceId)
            requests.entries.removeAll { it.value.sourceDeviceId == deviceId || it.value.targetDeviceId == deviceId }
            states.keys.retainAll(requests.keys)
            processed.retainAll(requests.keys)
            handledCommands.keys.retainAll(requests.keys)
            dirty = true
            publishLocked()
            persistLocked(force = true)
            Result.success(Unit)
        }
    }

    private suspend fun sendCommand(requestId: String, command: RemoteDownloadCommand): Result<Unit> {
        initialize()
        return lock.withLock {
            val request = requests[requestId]
                ?: return@withLock Result.failure(IllegalArgumentException(tr("请求不存在", "Request not found", "リクエストが見つかりません", "요청을 찾을 수 없습니다", "找不到請求")))
            if (request.sourceDeviceId != deviceId) {
                return@withLock Result.failure(IllegalArgumentException(tr("只能操作本机发出的任务", "Only requests sent by this device can be controlled", "この端末が送信したタスクのみ操作できます", "이 기기에서 보낸 작업만 제어할 수 있습니다", "只能操作本機送出的工作")))
            }
            val updated = request.copy(command = command, commandAtMs = now())
            requests[requestId] = updated
            dirty = true
            realtime?.publishRequest(updated.targetDeviceId, updated)
            publishLocked()
            persistLocked(force = true)
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
            resolvedDeviceName = deviceName
            preferences.saveRemoteDeviceName(deviceName)
            store = storeFactory(id)
            initialized = true
        }
    }

    private suspend fun switchAccount(next: String?) {
        if (account == next) return
        lock.withLock { persistLocked(force = true) }
        lock.withLock {
            account = next
            requests.clear()
            states.clear()
            presence.clear()
            forgotten.clear()
            processed.clear()
            handledCommands.clear()
            progressSentAt.clear()
            dirty = false
            if (next != null) {
                val restored = runCatching { store?.read(next) }
                    .logFailure(TAG, "读取远程下载本地缓存失败")
                    .getOrNull()
                restored?.requests?.forEach { requests[it.requestId] = it }
                restored?.states?.forEach { states[it.requestId] = it }
                restored?.forgottenDevices?.forEach { forgotten += it }
                trimRequestsLocked()
                // 目标设备上次收到请求后、写回 ACCEPTED 之前退出：本地没有状态，重新入队。
                requests.values.toList()
                    .filter { it.targetDeviceId == deviceId && it.sourceDeviceId != deviceId && states[it.requestId] == null }
                    .forEach { handleRequestLocked(it) }
            }
        }
        realtime?.stop()
        if (next != null && enabled) startRealtime()
        lock.withLock {
            publishLocked()
            if (enabled) republishPendingLocked()
        }
    }

    private fun startRealtime() {
        val owner = account ?: return
        if (!enabled) return
        realtime?.start(owner, deviceId, resolvedDeviceName, platformName)
    }

    private suspend fun handleEvent(event: RemoteRealtimeEvent) {
        lock.withLock {
            when (event) {
                is RemoteRealtimeEvent.Presence -> handlePresenceLocked(event.presence)
                is RemoteRealtimeEvent.Request -> handleRequestLocked(event.request)
                is RemoteRealtimeEvent.State -> handleStateLocked(event.state)
            }
            publishLocked()
            persistLocked()
        }
    }

    private fun handlePresenceLocked(value: RemotePresence) {
        if (value.deviceId.isBlank() || value.deviceId == deviceId) return
        if (!value.online && value.deviceId in forgotten) return
        if (value.online && forgotten.remove(value.deviceId)) dirty = true
        presence[value.deviceId] = value
        if (value.online && value.receiverEnabled) republishPendingToLocked(value.deviceId)
    }

    private fun handleRequestLocked(request: RemoteDownloadRequest) {
        if (request.targetDeviceId != deviceId || request.sourceDeviceId == deviceId) return
        requests[request.requestId] = request
        trimRequestsLocked()
        if (request.cancelRequested) {
            val existing = states[request.requestId]
            if (existing == null || existing.status !in TERMINAL_STATUSES) {
                executor.tasks.value.values
                    .firstOrNull { it.remoteRequestId == request.requestId }
                    ?.let { executor.cancelRemote(it.taskId) }
                upsertStateLocked(request, RemoteDownloadStatus.CANCELLED)
            } else {
                publishStateLocked(existing)
            }
            processed += request.requestId
            return
        }
        if (request.command != RemoteDownloadCommand.NONE &&
            request.commandAtMs > (handledCommands[request.requestId] ?: 0L)
        ) {
            val task = executor.tasks.value.values.firstOrNull { it.remoteRequestId == request.requestId }
            if (task == null) {
                upsertStateLocked(
                    request,
                    RemoteDownloadStatus.FAILED,
                    error = tr("任务不存在，请重新发送", "The task no longer exists; send it again", "タスクが見つかりません。再送信してください", "작업이 없습니다. 다시 보내 주세요", "工作不存在，請重新傳送"),
                )
            } else {
                when (request.command) {
                    RemoteDownloadCommand.PAUSE -> {
                        executor.pauseRemote(task.taskId)
                        upsertStateLocked(request, RemoteDownloadStatus.PAUSED, task.downloadedBytes, task.totalBytes)
                    }
                    RemoteDownloadCommand.RESUME -> {
                        executor.resumeRemote(task.taskId)
                        upsertStateLocked(request, RemoteDownloadStatus.ACCEPTED, task.downloadedBytes, task.totalBytes)
                    }
                    RemoteDownloadCommand.RETRY -> {
                        executor.retryRemote(task.taskId)
                        upsertStateLocked(request, RemoteDownloadStatus.ACCEPTED, task.downloadedBytes, task.totalBytes)
                    }
                    RemoteDownloadCommand.NONE -> Unit
                }
            }
            handledCommands[request.requestId] = request.commandAtMs
            dirty = true
            return
        }
        val existing = states[request.requestId]
        if (existing != null) {
            publishStateLocked(existing)
            return
        }
        if (request.requestId in processed) return
        if (request.expiresAtMs > 0L && request.expiresAtMs < now()) {
            upsertStateLocked(request, RemoteDownloadStatus.EXPIRED, error = tr("请求已过期", "Request expired", "リクエストの期限が切れました", "요청이 만료되었습니다", "請求已過期"))
            processed += request.requestId
            return
        }
        processed += request.requestId
        scope.launch {
            val file = runCatching { resolveFile(request.fileId) }
                .onFailure { PikoLog.w(TAG, "查询远程下载文件详情失败", it) }
                .getOrNull()
            if (file == null || file.isFolder) {
                lock.withLock {
                    upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = tr("文件不存在或不可下载", "The file is missing or cannot be downloaded", "ファイルが存在しないか、ダウンロードできません", "파일이 없거나 다운로드할 수 없습니다", "檔案不存在或無法下載"))
                    publishLocked()
                    persistLocked(force = true)
                }
                return@launch
            }
            val completed = runCatching { executor.findCompletedLocalPath(file) }.getOrNull()
            if (completed != null) {
                lock.withLock {
                    upsertStateLocked(
                        request,
                        RemoteDownloadStatus.COMPLETED,
                        downloadedBytes = file.sizeBytes,
                        totalBytes = file.sizeBytes,
                    )
                    publishLocked()
                    persistLocked(force = true)
                }
                return@launch
            }
            val accepted = lock.withLock {
                if (states[request.requestId] != null) return@withLock false
                upsertStateLocked(request, RemoteDownloadStatus.ACCEPTED, totalBytes = file.sizeBytes)
                publishLocked()
                persistLocked(force = true)
                true
            }
            if (!accepted) return@launch
            runCatching {
                executor.enqueueRemote(file, request) { taskIds ->
                    scope.launch {
                        if (taskIds.isEmpty()) {
                            lock.withLock {
                                upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = tr("目标设备无法加入下载队列", "The target device could not queue this download", "保存先がダウンロードキューに追加できませんでした", "대상 기기가 다운로드 대기열에 추가하지 못했습니다", "目標裝置無法加入下載佇列"))
                                publishLocked()
                                persistLocked(force = true)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                PikoLog.w(TAG, "目标设备加入下载队列失败", error)
                lock.withLock {
                    upsertStateLocked(request, RemoteDownloadStatus.REJECTED, error = error.message)
                    publishLocked()
                    persistLocked(force = true)
                }
            }
        }
    }

    private fun handleStateLocked(state: RemoteDownloadState) {
        if (state.sourceDeviceId != deviceId) return
        val request = requests[state.requestId] ?: return
        if (request.sourceDeviceId != deviceId || request.targetDeviceId != state.targetDeviceId) return
        val existing = states[state.requestId]
        if (existing == null || state.updatedAtMs >= existing.updatedAtMs) {
            states[state.requestId] = state
            dirty = true
        }
    }

    /** @return 是否有状态变化。 */
    private fun syncTaskStatesLocked(tasks: Collection<DownloadTask>): Boolean {
        if (account == null) return false
        var changed = false
        val remoteTasks = tasks.filter { it.remoteRequestId.isNotBlank() }
        for (task in remoteTasks) {
            val requestId = task.remoteRequestId
            val request = requests[requestId] ?: continue
            if (request.targetDeviceId != deviceId) continue
            val existing = states[requestId]
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
                sourceDeviceId = request.sourceDeviceId,
                targetDeviceId = deviceId,
                status = status,
                downloadedBytes = task.downloadedBytes,
                totalBytes = task.totalBytes,
                speedBytesPerSec = if (status == RemoteDownloadStatus.DOWNLOADING) task.speedBytesPerSec else 0L,
                error = task.errorMessage,
                updatedAtMs = now(),
            )
            if (existing != null &&
                existing.status == next.status &&
                existing.downloadedBytes == next.downloadedBytes &&
                existing.totalBytes == next.totalBytes &&
                existing.speedBytesPerSec == next.speedBytesPerSec &&
                existing.error == next.error
            ) {
                continue
            }
            states[requestId] = next
            changed = true
            dirty = true
            if (status in TERMINAL_STATUSES || now() - (progressSentAt[requestId] ?: 0L) >= REALTIME_PROGRESS_INTERVAL_MS) {
                publishStateLocked(next)
                progressSentAt[requestId] = now()
            }
        }

        val activeRequestIds = remoteTasks.mapTo(mutableSetOf()) { it.remoteRequestId }
        val stamp = now()
        for (state in states.values.toList()) {
            val request = requests[state.requestId] ?: continue
            if (request.targetDeviceId != deviceId) continue
            if ((state.status == RemoteDownloadStatus.ACCEPTED || state.status == RemoteDownloadStatus.DOWNLOADING) &&
                state.requestId !in activeRequestIds &&
                stamp - state.updatedAtMs > ACCEPT_TIMEOUT_MS
            ) {
                val failed = state.copy(
                    status = RemoteDownloadStatus.FAILED,
                    error = tr("目标设备未能开始下载", "The target device could not start the download", "保存先がダウンロードを開始できませんでした", "대상 기기가 다운로드를 시작하지 못했습니다", "目標裝置未能開始下載"),
                    updatedAtMs = stamp,
                )
                states[state.requestId] = failed
                publishStateLocked(failed)
                changed = true
                dirty = true
            }
        }
        return changed
    }

    private fun publishStateLocked(state: RemoteDownloadState) {
        realtime?.publishState(state.sourceDeviceId, state)
    }

    private fun upsertStateLocked(
        request: RemoteDownloadRequest,
        status: RemoteDownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = request.totalBytes,
        error: String? = null,
    ) {
        val next = RemoteDownloadState(
            requestId = request.requestId,
            sourceDeviceId = request.sourceDeviceId,
            targetDeviceId = request.targetDeviceId,
            status = status,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            error = error,
            updatedAtMs = now(),
        )
        states[request.requestId] = next
        dirty = true
        publishStateLocked(next)
    }

    private fun republishPendingLocked() {
        for (request in requests.values) {
            if (request.sourceDeviceId != deviceId) continue
            if (states[request.requestId]?.let { it.status in TERMINAL_STATUSES } == true) continue
            realtime?.publishRequest(request.targetDeviceId, request)
        }
    }

    private fun republishPendingToLocked(targetDeviceId: String) {
        for (request in requests.values) {
            if (request.sourceDeviceId != deviceId || request.targetDeviceId != targetDeviceId) continue
            if (states[request.requestId]?.let { it.status in TERMINAL_STATUSES } == true) continue
            realtime?.publishRequest(request.targetDeviceId, request)
        }
    }

    private fun publishLocked() {
        val stamp = now()
        val selfOnline = channelConnected && enabled
        val own = RemoteDevice(
            deviceId = deviceId,
            name = resolvedDeviceName,
            platform = platformName,
            receiverEnabled = enabled,
            updatedAtMs = stamp,
            online = selfOnline,
        )
        val remote = presence.values
            .asSequence()
            .filter { it.deviceId.isNotBlank() && it.deviceId != deviceId }
            .filterNot { !it.online && it.deviceId in forgotten }
            .map { value ->
                RemoteDevice(
                    deviceId = value.deviceId,
                    name = value.name.ifBlank { value.deviceId },
                    platform = value.platform,
                    receiverEnabled = value.receiverEnabled,
                    updatedAtMs = value.updatedAtMs,
                    online = channelConnected && value.online && stamp - value.updatedAtMs <= offlineAfterMs,
                    stale = stamp - value.updatedAtMs > STALE_AFTER_MS,
                )
            }
            .sortedWith(compareByDescending<RemoteDevice> { it.online }.thenByDescending { it.updatedAtMs })
            .toList()
        val devices = listOf(own) + remote
        _devices.value = devices
        val byId = devices.associateBy { it.deviceId }
        _remoteDownloads.value = requests.values
            .filter { it.sourceDeviceId == deviceId || it.targetDeviceId == deviceId }
            .distinctBy { it.requestId }
            .map { request ->
                val state = states[request.requestId]
                val expired = request.expiresAtMs > 0L && request.expiresAtMs < stamp
                RemoteDownloadItem(
                    request = request,
                    state = state,
                    sourceDeviceName = byId[request.sourceDeviceId]?.name ?: request.sourceDeviceId,
                    targetDeviceName = byId[request.targetDeviceId]?.name ?: request.targetDeviceId,
                    outgoing = request.sourceDeviceId == deviceId,
                    status = state?.status ?: if (expired) RemoteDownloadStatus.EXPIRED else RemoteDownloadStatus.QUEUED,
                    targetOnline = byId[request.targetDeviceId]?.online == true,
                )
            }
            // 按添加时间倒序固定展示顺序：进度状态每秒刷新，若按 updatedAtMs 排序会导致列表持续跳动。
            .sortedByDescending { it.request.createdAtMs.takeIf { at -> at > 0L } ?: it.state?.updatedAtMs ?: 0L }
    }

    private fun cleanupLocked() {
        val stamp = now()
        val removedRequests = requests.entries.removeAll { (id, request) ->
            request.createdAtMs != 0L && stamp - request.createdAtMs > retentionMs &&
                states[id]?.let { it.status in TERMINAL_STATUSES } == true
        }
        val removedStates = states.entries.removeAll { (_, state) ->
            state.updatedAtMs != 0L && stamp - state.updatedAtMs > retentionMs
        }
        if (removedRequests || removedStates) {
            processed.retainAll(requests.keys)
            handledCommands.keys.retainAll(requests.keys)
            dirty = true
        }
        val cutoff = stamp - retentionMs
        progressSentAt.entries.removeAll { it.value < cutoff }
    }

    private fun trimRequestsLocked() {
        while (requests.size > MAX_REQUESTS) {
            val removable = requests.values.firstOrNull { states[it.requestId]?.let { state -> state.status in TERMINAL_STATUSES } == true }
                ?: requests.values.firstOrNull()
                ?: break
            requests.remove(removable.requestId)
            states.remove(removable.requestId)
            processed -= removable.requestId
            handledCommands.remove(removable.requestId)
        }
    }

    private suspend fun persistLocked(force: Boolean = false) {
        val owner = account ?: return
        val currentStore = store ?: return
        val stamp = now()
        if (!force && !dirty) return
        if (!force && stamp - lastPersistMs < PERSIST_INTERVAL_MS) return
        val snapshot = RemoteDownloadLocalState(
            requests = requests.values.toList(),
            states = states.values.toList(),
            forgottenDevices = forgotten.toList(),
            updatedAtMs = stamp,
        )
        runCatching { currentStore.write(owner, snapshot) }
            .logFailure(TAG, "保存远程下载本地缓存失败")
            .onSuccess {
                dirty = false
                lastPersistMs = stamp
            }
    }

    private companion object {
        const val TAG = "RemoteDownload"
        const val ACCEPT_TIMEOUT_MS = 120_000L
        const val REALTIME_PROGRESS_INTERVAL_MS = 1_000L
        const val PERSIST_INTERVAL_MS = 1_000L
        const val MAX_REQUESTS = 200
        const val STALE_AFTER_MS = 30L * 24L * 60L * 60L * 1000L
        val TERMINAL_STATUSES = setOf(
            RemoteDownloadStatus.COMPLETED,
            RemoteDownloadStatus.FAILED,
            RemoteDownloadStatus.CANCELLED,
            RemoteDownloadStatus.REJECTED,
            RemoteDownloadStatus.EXPIRED,
        )
    }
}
