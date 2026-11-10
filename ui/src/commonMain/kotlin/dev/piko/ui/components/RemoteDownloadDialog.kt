package dev.piko.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.download.RemoteDownloadItem
import dev.piko.shared.download.RemoteDownloadStatus
import dev.piko.shared.i18n.tr
import dev.piko.shared.upload.isUploading
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 选择远程下载目标设备，并查看本机发出/收到的远程任务。
 *
 * 首次打开时如果远程下载还没开，先显示开关；开启前必须已经配置 MQTT 实时通道。
 */
@Composable
fun RemoteDownloadDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
) {
    val services = LocalPikoServices.current
    val enabled by services.preferences.remoteDownloadEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val realtimeConfig by services.remoteRealtimeConfig.collectAsStateWithLifecycle(initialValue = null)
    val realtimeConnected by services.remoteRealtimeConnected.collectAsStateWithLifecycle(initialValue = false)
    val devices by services.remoteDownloads.devices.collectAsStateWithLifecycle()
    val tasks by services.remoteDownloads.remoteDownloads.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ownId = services.remoteDownloads.deviceId
    val targetFiles = remember(files) { files.filterNot { it.isFolder || it.isUploading } }
    val hasTargets = files.isNotEmpty()
    val otherDevices = devices.filter { it.deviceId != ownId }
    val outgoing = tasks.filter { it.outgoing }
    var message by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    PikoDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("完成", "Done", "完了", "완료", "完成"))
            }
        },
        title = { Text(tr("下载到设备", "Download to device", "端末にダウンロード", "기기로 다운로드", "下載至裝置")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("启用远程下载", "Enable remote downloads", "リモートダウンロードを有効にする", "원격 다운로드 사용", "啟用遠端下載"))
                        Text(
                            tr(
                                "同一 PikPak 账号的设备通过 MQTT 实时通道互通；文件仍由目标设备直接下载。",
                                "Devices on the same PikPak account communicate over the MQTT realtime channel; the target device downloads the file directly.",
                                "同じ PikPak アカウントの端末は MQTT リアルタイムチャンネルで連携し、ファイルは対象端末が直接ダウンロードします。",
                                "같은 PikPak 계정의 기기는 MQTT 실시간 채널로 연결되며 파일은 대상 기기가 직접 다운로드합니다.",
                                "同一 PikPak 帳號的裝置透過 MQTT 即時通道互通，檔案仍由目標裝置直接下載。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        enabled = realtimeConfig != null || enabled,
                        onCheckedChange = { on ->
                            if (on && realtimeConfig == null) {
                                message = tr("请先在设置中配置实时同步通道", "Configure the realtime channel in Settings first", "先に設定でリアルタイムチャンネルを設定してください", "먼저 설정에서 실시간 채널을 구성하세요", "請先在設定中設定即時同步通道")
                            } else {
                                scope.launch { services.preferences.setRemoteDownloadEnabled(on) }
                            }
                        },
                    )
                }

                if (realtimeConfig == null) {
                    Text(
                        tr("远程下载需要 MQTT 实时通道。请先在设置 → 下载 → 实时同步通道中完成配置。", "Remote downloads require the MQTT realtime channel. Configure it in Settings → Downloads → Realtime channel.", "リモートダウンロードには MQTT リアルタイムチャンネルが必要です。設定 → ダウンロード → リアルタイム同期で設定してください。", "원격 다운로드에는 MQTT 실시간 채널이 필요합니다. 설정 → 다운로드 → 실시간 동기화에서 구성하세요.", "遠端下載需要 MQTT 即時通道。請先在設定 → 下載 → 即時同步通道中完成設定。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!realtimeConnected) {
                    Text(
                        tr("实时通道未连接，设备在线状态可能不准确。请检查 MQTT 地址与账号密码。", "The realtime channel is disconnected, so device presence may be stale. Check the MQTT URL and credentials.", "リアルタイムチャンネルが未接続のため、端末のオンライン状態が古い可能性があります。MQTT アドレスと認証情報を確認してください。", "실시간 채널이 연결되지 않아 기기 온라인 상태가 오래되었을 수 있습니다. MQTT 주소와 계정 정보를 확인하세요.", "即時通道未連線，裝置線上狀態可能不準確。請檢查 MQTT 位址與帳號密碼。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!enabled) {
                    Text(
                        tr("开启后本机才会出现在其他设备的下载目标中。", "Turn this on to make this device available to other devices.", "オンにすると、この端末が他の端末の保存先に表示されます。", "켜면 이 기기가 다른 기기의 다운로드 대상으로 표시됩니다.", "開啟後本機才會出現在其他裝置的下載目標中。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (hasTargets && targetFiles.isEmpty()) {
                        Text(tr("文件夹暂不支持远程下载。", "Remote download is available for files only.", "リモートダウンロードはファイルのみ対応しています。", "원격 다운로드는 파일만 지원합니다.", "遠端下載目前僅支援檔案。"), color = MaterialTheme.colorScheme.error)
                    }
                    if (otherDevices.isEmpty()) {
                        Text(
                            tr("暂无其他设备。请先在另一台设备登录同一账号并开启远程下载。", "No other devices yet. Sign in on another device with the same account and enable remote downloads there.", "他の端末がありません。同じアカウントで別の端末にログインし、リモートダウンロードを有効にしてください。", "다른 기기가 없습니다. 같은 계정으로 다른 기기에 로그인한 뒤 원격 다운로드를 켜세요.", "尚無其他裝置。請先在另一台裝置登入同一帳號並開啟遠端下載。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        otherDevices.forEach { device ->
                            val canSend = targetFiles.isNotEmpty() && device.online && device.receiverEnabled && !sending
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSend) {
                                        sending = true
                                        message = null
                                        scope.launch {
                                            val result = services.remoteDownloads.sendDownload(targetFiles, device.deviceId)
                                            message = result.fold(
                                                onSuccess = {
                                                    tr("已发送到 ${device.name}", "Sent to ${device.name}", "${device.name} に送信しました", "${device.name}(으)로 보냈습니다", "已傳送至 ${device.name}")
                                                },
                                                onFailure = { it.message ?: tr("发送失败", "Failed to send", "送信に失敗しました", "보내지 못했습니다", "傳送失敗") },
                                            )
                                            sending = false
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.DevicesOther, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (!device.online) {
                                            if (device.stale) {
                                                tr("长期未出现，可能已卸载", "Not seen for a long time; may be uninstalled", "長期間未検出。アンインストールされた可能性があります", "오랫동안 나타나지 않음. 제거되었을 수 있습니다", "長期未出現，可能已解除安裝")
                                            } else {
                                                tr("离线", "Offline", "オフライン", "오프라인", "離線")
                                            }
                                        } else if (device.receiverEnabled) {
                                            tr("在线，可接收下载", "Online, ready to receive", "オンライン・受信可能", "온라인 · 다운로드 받을 수 있음", "線上，可接收下載")
                                        } else {
                                            tr("在线，未开启接收", "Online, receiving is off", "オンライン・受信オフ", "온라인 · 받기 꺼짐", "線上，未開啟接收")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (device.online && device.receiverEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (!device.online) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                services.remoteDownloads.forgetDevice(device.deviceId)
                                                message = tr("已移除 ${device.name}", "Removed ${device.name}", "${device.name} を削除しました", "${device.name}을(를) 제거했습니다", "已移除 ${device.name}")
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = tr("移除离线设备", "Remove offline device", "オフライン端末を削除", "오프라인 기기 제거", "移除離線裝置"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (outgoing.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(tr("远程任务", "Remote tasks", "リモートタスク", "원격 작업", "遠端工作"), style = MaterialTheme.typography.titleSmall)
                    outgoing.take(6).forEach { item ->
                        fun command(action: suspend () -> Result<Unit>, success: String) {
                            scope.launch {
                                val result = action()
                                message = result.exceptionOrNull()?.message
                                    ?: if (result.isSuccess) success else tr("操作失败", "Action failed", "操作に失敗しました", "작업 실패", "操作失敗")
                            }
                        }
                        RemoteDownloadRow(
                            item = item,
                            onPause = if (item.status == RemoteDownloadStatus.DOWNLOADING) {
                                {
                                    command({ services.remoteDownloads.pause(item.requestId) }, tr("已请求暂停", "Pause requested", "一時停止を要求しました", "일시 중지를 요청했습니다", "已要求暫停"))
                                }
                            } else null,
                            onResume = if (item.status == RemoteDownloadStatus.PAUSED) {
                                {
                                    command({ services.remoteDownloads.resume(item.requestId) }, tr("已请求继续", "Resume requested", "再開を要求しました", "계속을 요청했습니다", "已要求繼續"))
                                }
                            } else null,
                            onRetry = if (item.status == RemoteDownloadStatus.FAILED) {
                                {
                                    command({ services.remoteDownloads.retry(item.requestId) }, tr("已请求重试", "Retry requested", "再試行を要求しました", "재시도를 요청했습니다", "已要求重試"))
                                }
                            } else null,
                            onCancel = if (item.status in CANCELLABLE_STATUSES) {
                                {
                                    scope.launch {
                                        services.remoteDownloads.cancel(item.requestId)
                                        message = tr("已请求取消", "Cancellation requested", "キャンセルを要求しました", "취소를 요청했습니다", "已要求取消")
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
}

@Composable
private fun RemoteDownloadRow(
    item: RemoteDownloadItem,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.fileName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(
                item.status.label(),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == RemoteDownloadStatus.FAILED || item.status == RemoteDownloadStatus.REJECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            tr("到 ${item.targetDeviceName}", "to ${item.targetDeviceName}", "${item.targetDeviceName} へ", "${item.targetDeviceName}(으)로", "至 ${item.targetDeviceName}"),
            style = MaterialTheme.typography.bodySmall,
            color = if (item.targetOnline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (item.status == RemoteDownloadStatus.DOWNLOADING || item.status == RemoteDownloadStatus.PAUSED) {
            Spacer(Modifier.height(4.dp))
            val fraction = if (item.totalBytes > 0L) (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            if (item.status == RemoteDownloadStatus.DOWNLOADING && item.speedBytesPerSec > 0L) {
                Text(
                    "${item.speedBytesPerSec.toReadableSize()}/s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (onPause != null || onResume != null || onRetry != null || onCancel != null) {
            Row {
                onPause?.let { TextButton(onClick = it) { Text(tr("暂停", "Pause", "一時停止", "일시 중지", "暫停")) } }
                onResume?.let { TextButton(onClick = it) { Text(tr("继续", "Resume", "再開", "계속", "繼續")) } }
                onRetry?.let { TextButton(onClick = it) { Text(tr("重试", "Retry", "再試行", "다시 시도", "重試")) } }
                onCancel?.let { TextButton(onClick = it) { Text(tr("取消", "Cancel", "キャンセル", "취소", "取消")) } }
            }
        }
    }
}

private val CANCELLABLE_STATUSES = setOf(
    RemoteDownloadStatus.QUEUED,
    RemoteDownloadStatus.ACCEPTED,
    RemoteDownloadStatus.DOWNLOADING,
    RemoteDownloadStatus.PAUSED,
)

private fun RemoteDownloadStatus.label(): String = when (this) {
    RemoteDownloadStatus.QUEUED -> tr("等待设备", "Queued", "待機中", "대기 중", "等待裝置")
    RemoteDownloadStatus.ACCEPTED -> tr("已接受", "Accepted", "受け付けました", "수락됨", "已接受")
    RemoteDownloadStatus.DOWNLOADING -> tr("下载中", "Downloading", "ダウンロード中", "다운로드 중", "下載中")
    RemoteDownloadStatus.PAUSED -> tr("已暂停", "Paused", "一時停止", "일시 중지", "已暫停")
    RemoteDownloadStatus.COMPLETED -> tr("已完成", "Completed", "完了", "완료", "已完成")
    RemoteDownloadStatus.FAILED -> tr("失败", "Failed", "失敗", "실패", "失敗")
    RemoteDownloadStatus.CANCELLED -> tr("已取消", "Cancelled", "キャンセル済み", "취소됨", "已取消")
    RemoteDownloadStatus.REJECTED -> tr("已拒绝", "Rejected", "拒否", "거부됨", "已拒絕")
    RemoteDownloadStatus.EXPIRED -> tr("已过期", "Expired", "期限切れ", "만료됨", "已過期")
}
