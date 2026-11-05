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
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
 * 首次打开时如果远程下载还没开，先显示开关；开启后协调器才读写 `.piko/remote-*` 账本。
 */
@Composable
fun RemoteDownloadDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
) {
    val services = LocalPikoServices.current
    val enabled by services.preferences.remoteDownloadEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
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
                                "同一 PikPak 账号的设备通过网盘 .piko 文件夹互通；文件仍由目标设备直接下载。",
                                "Devices on the same PikPak account communicate through the .piko folder; the target device downloads the file directly.",
                                "同じ PikPak アカウントの端末は .piko フォルダー経由で連携し、ファイルは対象端末が直接ダウンロードします。",
                                "같은 PikPak 계정의 기기는 .piko 폴더로 연결되며 파일은 대상 기기가 직접 다운로드합니다.",
                                "同一 PikPak 帳號的裝置透過 .piko 資料夾互通，檔案仍由目標裝置直接下載。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on -> scope.launch { services.preferences.setRemoteDownloadEnabled(on) } },
                    )
                }

                if (!enabled) {
                    Text(
                        tr("开启后本机才会出现在其他设备的下载目标中。", "Turn this on to make this device available to other devices.", "オンにすると、この端末が他の端末の保存先に表示されます。", "켜면 이 기기가 다른 기기의 다운로드 대상으로 표시됩니다.", "開啟後本機才會出現在其他裝置的下載目標中。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (hasTargets) {
                    if (targetFiles.isEmpty()) {
                        Text(tr("文件夹暂不支持远程下载。", "Remote download is available for files only.", "リモートダウンロードはファイルのみ対応しています。", "원격 다운로드는 파일만 지원합니다.", "遠端下載目前僅支援檔案。"), color = MaterialTheme.colorScheme.error)
                    } else if (otherDevices.isEmpty()) {
                        Text(
                            tr("暂无其他设备。请先在另一台设备登录同一账号并开启远程下载。", "No other devices yet. Sign in on another device with the same account and enable remote downloads there.", "他の端末がありません。同じアカウントで別の端末にログインし、リモートダウンロードを有効にしてください。", "다른 기기가 없습니다. 같은 계정으로 다른 기기에 로그인한 뒤 원격 다운로드를 켜세요.", "尚無其他裝置。請先在另一台裝置登入同一帳號並開啟遠端下載。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        otherDevices.forEach { device ->
                            val canSend = device.receiverEnabled && !sending
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
                                        if (device.receiverEnabled) {
                                            tr("可接收下载", "Ready to receive", "受信可能", "다운로드 받을 수 있음", "可接收下載")
                                        } else {
                                            tr("未开启接收", "Receiving is off", "受信オフ", "받기 꺼짐", "未開啟接收")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (device.receiverEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                if (outgoing.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(tr("远程任务", "Remote tasks", "リモートタスク", "원격 작업", "遠端工作"), style = MaterialTheme.typography.titleSmall)
                    outgoing.take(6).forEach { item ->
                        RemoteDownloadRow(
                            item = item,
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
private fun RemoteDownloadRow(item: RemoteDownloadItem, onCancel: (() -> Unit)?) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.status == RemoteDownloadStatus.DOWNLOADING || item.status == RemoteDownloadStatus.PAUSED) {
            Spacer(Modifier.height(4.dp))
            val fraction = if (item.totalBytes > 0L) (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        item.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
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
