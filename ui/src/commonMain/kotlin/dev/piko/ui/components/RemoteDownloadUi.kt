package dev.piko.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.download.RemoteDevice
import dev.piko.shared.download.RemoteDownloadItem
import dev.piko.shared.download.RemoteDownloadStatus
import dev.piko.shared.i18n.tr

internal val RemoteDownloadStatus.isTerminal: Boolean
    get() = this == RemoteDownloadStatus.COMPLETED || this == RemoteDownloadStatus.CANCELLED

internal val RemoteDownloadStatus.needsAttention: Boolean
    get() = this == RemoteDownloadStatus.FAILED || this == RemoteDownloadStatus.REJECTED || this == RemoteDownloadStatus.EXPIRED

internal val CANCELLABLE_STATUSES = setOf(
    RemoteDownloadStatus.QUEUED,
    RemoteDownloadStatus.ACCEPTED,
    RemoteDownloadStatus.DOWNLOADING,
    RemoteDownloadStatus.PAUSED,
)

internal fun RemoteDownloadStatus.label(): String = when (this) {
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

/** 发出任务看目标设备，接收任务看来源设备。 */
internal fun RemoteDownloadItem.otherDeviceId(): String =
    if (outgoing) request.targetDeviceId else request.sourceDeviceId

internal fun RemoteDownloadItem.otherDeviceName(): String =
    if (outgoing) targetDeviceName else sourceDeviceName

@Composable
internal fun RemoteDevicePicker(
    devices: List<RemoteDevice>,
    selectedDeviceId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.deviceId == selectedDeviceId }
    Box(modifier) {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = selected?.name ?: if (allowAll) {
                        tr("全部设备", "All devices", "すべての端末", "모든 기기", "所有裝置")
                    } else {
                        tr("选择设备", "Choose a device", "端末を選択", "기기 선택", "選擇裝置")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val allOffset = if (allowAll) 1 else 0
            val itemCount = devices.size + allOffset
            if (allowAll) {
                DropdownMenuItem(
                    text = { Text(tr("全部设备", "All devices", "すべての端末", "모든 기기", "所有裝置")) },
                    trailingIcon = if (selected == null) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    shape = menuItemShape(0, itemCount),
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
            }
            devices.forEachIndexed { index, device ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = deviceStatusLabel(device),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (device.online && device.receiverEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                    },
                    trailingIcon = if (device.deviceId == selectedDeviceId) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    shape = menuItemShape(index + allOffset, itemCount),
                    onClick = {
                        expanded = false
                        onSelect(device.deviceId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun deviceStatusLabel(device: RemoteDevice): String = when {
    !device.online && device.stale -> tr(
        "长期未出现，可能已卸载",
        "Not seen for a long time; may be uninstalled",
        "長期間未検出。アンインストールされた可能性があります",
        "오랫동안 나타나지 않음. 제거되었을 수 있습니다",
        "長期未出現，可能已解除安裝",
    )
    !device.online -> tr("离线", "Offline", "オフライン", "오프라인", "離線")
    device.receiverEnabled -> tr("在线，可接收", "Online, ready", "オンライン・受信可能", "온라인 · 수신 가능", "線上，可接收")
    else -> tr("在线，未开启接收", "Online, receiving is off", "オンライン・受信オフ", "온라인 · 받기 꺼짐", "線上，未開啟接收")
}

@Composable
internal fun RemoteTaskRow(
    item: RemoteDownloadItem,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DevicesOther, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = tr(
                        "${if (item.outgoing) "发往" else "来自"} ${item.otherDeviceName()}",
                        "${if (item.outgoing) "To" else "From"} ${item.otherDeviceName()}",
                        "${if (item.outgoing) "送信先" else "送信元"} ${item.otherDeviceName()}",
                        "${if (item.outgoing) "보냄" else "받음"} ${item.otherDeviceName()}",
                        "${if (item.outgoing) "發往" else "來自"} ${item.otherDeviceName()}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.status.label(),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item.request.qualityCap?.let { cap ->
            Text(
                text = if (cap > 0) "${cap}P" else tr("原画", "Original", "オリジナル", "원본", "原畫"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!item.outgoing) {
            Text(
                text = tr(
                    "接收任务由本机「下载」页管理",
                    "Received tasks are managed in Downloads",
                    "受信タスクは「ダウンロード」ページで管理します",
                    "받은 작업은 '다운로드' 페이지에서 관리합니다",
                    "接收工作由本機「下載」頁管理",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (item.status == RemoteDownloadStatus.DOWNLOADING || item.status == RemoteDownloadStatus.PAUSED) {
            Spacer(Modifier.height(6.dp))
            val fraction = if (item.totalBytes > 0L) {
                (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            if (item.totalBytes > 0L) {
                Text(
                    text = "${item.downloadedBytes.toReadableSize()} / ${item.totalBytes.toReadableSize()}" +
                        if (item.status == RemoteDownloadStatus.DOWNLOADING && item.speedBytesPerSec > 0L) {
                            " · ${item.speedBytesPerSec.toReadableSize()}/s"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (onPause != null || onResume != null || onRetry != null || onCancel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onPause?.let { TextButton(onClick = it) { Text(tr("暂停", "Pause", "一時停止", "일시 중지", "暫停")) } }
                onResume?.let { TextButton(onClick = it) { Text(tr("继续", "Resume", "再開", "계속", "繼續")) } }
                onRetry?.let { TextButton(onClick = it) { Text(tr("重试", "Retry", "再試行", "다시 시도", "重試")) } }
                onCancel?.let { TextButton(onClick = it) { Text(tr("取消", "Cancel", "キャンセル", "취소", "取消")) } }
            }
        }
    }
}
