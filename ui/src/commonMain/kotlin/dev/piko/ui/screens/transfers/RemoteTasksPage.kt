package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.download.RemoteDownloadItem
import dev.piko.shared.download.RemoteDownloadStatus
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.RemoteDevicePicker
import dev.piko.ui.components.RemoteTaskRow
import dev.piko.ui.components.CANCELLABLE_STATUSES
import dev.piko.ui.components.needsAttention
import dev.piko.ui.components.otherDeviceId
import dev.piko.ui.screens.settings.RemoteRealtimeDialog
import kotlinx.coroutines.launch

private enum class RemoteDirection {
    ALL,
    OUTGOING,
    INCOMING,
}

/**
 * 传输页的远程任务页。远程请求与本地下载任务分开管理：这里看设备、请求和整体状态，
 * 目标设备实际落盘的文件仍在「下载」页。
 */
@Composable
internal fun RemoteTasksPage(modifier: Modifier = Modifier) {
    val services = LocalPikoServices.current
    val snackbar = LocalRootSnackbar.current
    val scope = rememberCoroutineScope()
    val items by services.remoteDownloads.remoteDownloads.collectAsStateWithLifecycle()
    val devices by services.remoteDownloads.devices.collectAsStateWithLifecycle()
    val enabled by services.preferences.remoteDownloadEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val realtimeConfig by services.remoteRealtimeConfig.collectAsStateWithLifecycle(initialValue = null)
    val connected by services.remoteRealtimeConnected.collectAsStateWithLifecycle(initialValue = false)
    val ownId = services.remoteDownloads.deviceId
    val otherDevices = devices.filter { it.deviceId != ownId }
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var directionKey by rememberSaveable { mutableStateOf(RemoteDirection.ALL.name) }
    var showRealtimeConfig by remember { mutableStateOf(false) }
    val direction = runCatching { RemoteDirection.valueOf(directionKey) }.getOrDefault(RemoteDirection.ALL)

    LaunchedEffect(Unit) {
        services.remoteDownloads.refreshNow()
    }
    LaunchedEffect(otherDevices) {
        if (selectedDeviceId != null && otherDevices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = null
        }
    }

    val visibleItems = items.filter { item ->
        val matchesDevice = selectedDeviceId == null || item.otherDeviceId() == selectedDeviceId
        val matchesDirection = when (direction) {
            RemoteDirection.ALL -> true
            RemoteDirection.OUTGOING -> item.outgoing
            RemoteDirection.INCOMING -> !item.outgoing
        }
        matchesDevice && matchesDirection
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteDevicePicker(
                devices = otherDevices,
                selectedDeviceId = selectedDeviceId,
                onSelect = { selectedDeviceId = it },
                modifier = Modifier.weight(1f, fill = false),
            )
            DirectionChip(
                label = tr("发出", "Sent", "送信", "보냄", "發出"),
                selected = direction == RemoteDirection.OUTGOING,
                onClick = {
                    directionKey = if (direction == RemoteDirection.OUTGOING) {
                        RemoteDirection.ALL.name
                    } else {
                        RemoteDirection.OUTGOING.name
                    }
                },
            )
            DirectionChip(
                label = tr("接收", "Received", "受信", "받음", "接收"),
                selected = direction == RemoteDirection.INCOMING,
                onClick = {
                    directionKey = if (direction == RemoteDirection.INCOMING) {
                        RemoteDirection.ALL.name
                    } else {
                        RemoteDirection.INCOMING.name
                    }
                },
            )
        }
        if (realtimeConfig != null && !connected) {
            Text(
                text = tr(
                    "实时通道未连接，设备在线状态可能不准确。",
                    "Realtime channel is disconnected; device presence may be stale.",
                    "リアルタイムチャンネルが未接続です。端末のオンライン状態が古い可能性があります。",
                    "실시간 채널이 연결되지 않아 기기 온라인 상태가 오래되었을 수 있습니다.",
                    "即時通道未連線，裝置線上狀態可能不準確。",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            realtimeConfig == null -> PikoEmptyState(
                title = tr("未配置实时通道", "Realtime channel not configured", "リアルタイムチャンネルが未設定です", "실시간 채널이 설정되지 않았습니다", "未設定即時通道"),
                description = tr(
                    "远程下载需要同一账号的设备通过 MQTT 互通。配置后，其他设备才能出现在这里。",
                    "Remote downloads need devices on the same account to communicate over MQTT. Configure it to see other devices here.",
                    "リモートダウンロードには同じアカウントの端末が MQTT で通信する必要があります。設定すると他の端末がここに表示されます。",
                    "원격 다운로드에는 같은 계정의 기기가 MQTT로 통신해야 합니다. 설정하면 다른 기기가 여기에 표시됩니다.",
                    "遠端下載需要同一帳號的裝置透過 MQTT 互通。設定後，其他裝置才會顯示在這裡。",
                ),
                icon = Icons.Outlined.DevicesOther,
                actionText = tr("配置实时通道", "Configure channel", "チャンネルを設定", "채널 설정", "設定即時通道"),
                onActionClick = { showRealtimeConfig = true },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            !enabled -> PikoEmptyState(
                title = tr("远程下载未开启", "Remote downloads are off", "リモートダウンロードがオフです", "원격 다운로드가 꺼져 있습니다", "遠端下載未開啟"),
                description = tr(
                    "开启后，本机才能接收其他设备发来的下载，也能把文件发送到其他设备。",
                    "Turn this on to receive downloads from other devices and send files to them.",
                    "オンにすると、他の端末からダウンロードを受け取ったり、ファイルを送信したりできます。",
                    "켜면 다른 기기에서 다운로드를 받거나 파일을 보낼 수 있습니다.",
                    "開啟後，本機才能接收其他裝置送來的下載，也能把檔案傳送到其他裝置。",
                ),
                icon = Icons.Outlined.DevicesOther,
                actionText = tr("开启远程下载", "Turn on remote downloads", "リモートダウンロードをオン", "원격 다운로드 켜기", "開啟遠端下載"),
                onActionClick = { scope.launch { services.preferences.setRemoteDownloadEnabled(true) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            otherDevices.isEmpty() -> PikoEmptyState(
                title = tr("暂无其他设备", "No other devices", "他の端末はありません", "다른 기기가 없습니다", "尚無其他裝置"),
                description = tr(
                    "请在另一台设备登录同一账号，并在设置 → 下载中开启远程下载。",
                    "Sign in on another device with the same account and enable remote downloads in Settings → Downloads.",
                    "同じアカウントで別の端末にログインし、設定 → ダウンロードでリモートダウンロードをオンにしてください。",
                    "같은 계정으로 다른 기기에 로그인한 뒤 설정 → 다운로드에서 원격 다운로드를 켜세요.",
                    "請在另一台裝置登入同一帳號，並在設定 → 下載中開啟遠端下載。",
                ),
                icon = Icons.Outlined.DevicesOther,
                actionText = tr("刷新", "Refresh", "更新", "새로고침", "重新整理"),
                onActionClick = { services.remoteDownloads.refreshNow() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            visibleItems.isEmpty() -> PikoEmptyState(
                title = tr("暂无远程任务", "No remote tasks", "リモートタスクはありません", "원격 작업이 없습니다", "尚無遠端工作"),
                description = tr(
                    "在网盘里选择文件，使用「下载到设备」即可把任务发送到其他设备。",
                    "Select files in the drive and use Download to device to send them to another device.",
                    "ドライブでファイルを選択し、「端末にダウンロード」から他の端末へ送信できます。",
                    "드라이브에서 파일을 선택하고 '기기로 다운로드'로 다른 기기에 보낼 수 있습니다.",
                    "在雲端硬碟選擇檔案，使用「下載至裝置」即可把工作傳送到其他裝置。",
                ),
                icon = Icons.Outlined.DevicesOther,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            else -> RemoteTaskList(
                items = visibleItems,
                services = services,
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message, withDismissAction = true) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    if (showRealtimeConfig) {
        RemoteRealtimeDialog(
            initial = realtimeConfig,
            onDismiss = { showRealtimeConfig = false },
            onTest = services::testRemoteRealtime,
            onSave = services::saveRemoteRealtimeConfig,
            onClear = services::clearRemoteRealtimeConfig,
        )
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun RemoteTaskList(
    items: List<RemoteDownloadItem>,
    services: dev.piko.ui.PikoServices,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val inProgress = items.filter { it.status !in TERMINAL_STATUSES && !it.status.needsAttention }
    val attention = items.filter { it.status.needsAttention }
    val completed = items.filter { it.status in TERMINAL_STATUSES }

    fun runAction(action: suspend () -> Result<Unit>, success: String) {
        scope.launch {
            val result = action()
            onMessage(
                result.exceptionOrNull()?.message
                    ?: if (result.isSuccess) success else tr("操作失败", "Action failed", "操作に失敗しました", "작업 실패", "操作失敗"),
            )
        }
    }

    fun pause(item: RemoteDownloadItem): (() -> Unit)? =
        if (item.outgoing && item.status == RemoteDownloadStatus.DOWNLOADING) {
            { runAction({ services.remoteDownloads.pause(item.requestId) }, tr("已请求暂停", "Pause requested", "一時停止を要求しました", "일시 중지를 요청했습니다", "已要求暫停")) }
        } else {
            null
        }

    fun resume(item: RemoteDownloadItem): (() -> Unit)? =
        if (item.outgoing && item.status == RemoteDownloadStatus.PAUSED) {
            { runAction({ services.remoteDownloads.resume(item.requestId) }, tr("已请求继续", "Resume requested", "再開を要求しました", "계속을 요청했습니다", "已要求繼續")) }
        } else {
            null
        }

    fun retry(item: RemoteDownloadItem): (() -> Unit)? =
        if (item.outgoing && item.status == RemoteDownloadStatus.FAILED) {
            { runAction({ services.remoteDownloads.retry(item.requestId) }, tr("已请求重试", "Retry requested", "再試行を要求しました", "재시도를 요청했습니다", "已要求重試")) }
        } else {
            null
        }

    fun cancel(item: RemoteDownloadItem): (() -> Unit)? =
        if (item.outgoing && item.status in CANCELLABLE_STATUSES) {
            { runAction({ services.remoteDownloads.cancel(item.requestId) }, tr("已请求取消", "Cancellation requested", "キャンセルを要求しました", "취소를 요청했습니다", "已要求取消")) }
        } else {
            null
        }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        remoteSection(tr("需要处理", "Needs attention", "対応が必要", "처리 필요", "需要處理"), attention, attention = true) { item ->
            RemoteTaskRow(
                item = item,
                onPause = pause(item),
                onResume = resume(item),
                onRetry = retry(item),
                onCancel = cancel(item),
            )
        }
        remoteSection(tr("进行中", "In progress", "処理中", "진행 중", "進行中"), inProgress) { item ->
            RemoteTaskRow(
                item = item,
                onPause = pause(item),
                onResume = resume(item),
                onCancel = cancel(item),
            )
        }
        remoteSection(tr("已完成", "Completed", "完了", "완료", "已完成"), completed) { item ->
            RemoteTaskRow(item = item)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.remoteSection(
    title: String,
    sectionItems: List<RemoteDownloadItem>,
    attention: Boolean = false,
    content: @Composable (RemoteDownloadItem) -> Unit,
) {
    if (sectionItems.isEmpty()) return
    item(key = "remote-header:$title", contentType = "remote-header") {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Text(
                text = tr("$title（${sectionItems.size}）", "$title (${sectionItems.size})", "$title（${sectionItems.size}）", "$title (${sectionItems.size})", "$title（${sectionItems.size}）"),
                style = MaterialTheme.typography.titleSmall,
                color = if (attention) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    items(sectionItems, key = { it.requestId }) { item ->
        content(item)
    }
}

private val TERMINAL_STATUSES = setOf(
    RemoteDownloadStatus.COMPLETED,
    RemoteDownloadStatus.CANCELLED,
)
