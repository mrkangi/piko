package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.shared.upload.isUploading
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 远程下载的发送面板。只负责选择目标设备、画质并发送；任务查看与控制移到传输页的「远程」tab。
 */
@Composable
fun RemoteSendDialog(
    files: List<FileStat>,
    onDismiss: () -> Unit,
) {
    val services = LocalPikoServices.current
    val enabled by services.preferences.remoteDownloadEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val realtimeConfig by services.remoteRealtimeConfig.collectAsStateWithLifecycle(initialValue = null)
    val realtimeConnected by services.remoteRealtimeConnected.collectAsStateWithLifecycle(initialValue = false)
    val devices by services.remoteDownloads.devices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ownId = services.remoteDownloads.deviceId
    val targetFiles = remember(files) { files.filterNot { it.isFolder || it.isUploading } }
    val otherDevices = devices.filter { it.deviceId != ownId }
    var selectedDeviceId by remember(files) {
        mutableStateOf(otherDevices.firstOrNull { it.online && it.receiverEnabled }?.deviceId)
    }
    var qualityCap by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val selectedDevice = otherDevices.firstOrNull { it.deviceId == selectedDeviceId }

    LaunchedEffect(otherDevices) {
        if (selectedDeviceId != null && otherDevices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = otherDevices.firstOrNull { it.online && it.receiverEnabled }?.deviceId
        }
    }

    val canSend = enabled && realtimeConnected && targetFiles.isNotEmpty() && !sending &&
        selectedDevice != null && selectedDevice.online && selectedDevice.receiverEnabled

    PikoDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PikoDialogConfirm(
                label = if (sending) {
                    tr("发送中…", "Sending…", "送信中…", "보내는 중…", "傳送中…")
                } else {
                    tr("发送", "Send", "送信", "보내기", "傳送")
                },
                enabled = canSend,
                onClick = {
                    val target = selectedDevice ?: return@PikoDialogConfirm
                    sending = true
                    message = null
                    scope.launch {
                        val result = services.remoteDownloads.sendDownload(targetFiles, target.deviceId, qualityCap)
                        message = result.fold(
                            onSuccess = {
                                tr("已发送到 ${target.name}", "Sent to ${target.name}", "${target.name} に送信しました", "${target.name}(으)로 보냈습니다", "已傳送至 ${target.name}")
                            },
                            onFailure = { it.message ?: tr("发送失败", "Failed to send", "送信に失敗しました", "보내지 못했습니다", "傳送失敗") },
                        )
                        sending = false
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        title = { Text(tr("下载到设备", "Download to device", "端末にダウンロード", "기기로 다운로드", "下載至裝置")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
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

                if (files.any { it.isFolder || it.isUploading } && targetFiles.isEmpty()) {
                    Text(
                        tr("文件夹暂不支持远程下载。", "Remote download is available for files only.", "リモートダウンロードはファイルのみ対応しています。", "원격 다운로드는 파일만 지원합니다.", "遠端下載目前僅支援檔案。"),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (targetFiles.isEmpty()) {
                    Text(
                        tr("没有可发送的文件。", "There are no files to send.", "送信できるファイルがありません。", "보낼 파일이 없습니다.", "沒有可傳送的檔案。"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!enabled) {
                    Text(
                        tr("开启后本机才会出现在其他设备的下载目标中。", "Turn this on to make this device available to other devices.", "オンにすると、この端末が他の端末の保存先に表示されます。", "켜면 이 기기가 다른 기기의 다운로드 대상으로 표시됩니다.", "開啟後本機才會出現在其他裝置的下載目標中。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (otherDevices.isEmpty()) {
                    Text(
                        tr("暂无其他设备。请先在另一台设备登录同一账号并开启远程下载。", "No other devices yet. Sign in on another device with the same account and enable remote downloads there.", "他の端末がありません。同じアカウントで別の端末にログインし、リモートダウンロードを有効にしてください。", "다른 기기가 없습니다. 같은 계정으로 다른 기기에 로그인한 뒤 원격 다운로드를 켜세요.", "尚無其他裝置。請先在另一台裝置登入同一帳號並開啟遠端下載。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(tr("目标设备", "Target device", "送信先", "대상 기기", "目標裝置"), style = MaterialTheme.typography.labelLarge)
                    RemoteDevicePicker(
                        devices = otherDevices,
                        selectedDeviceId = selectedDeviceId,
                        onSelect = { selectedDeviceId = it },
                        allowAll = false,
                    )
                    RemoteQualityPicker(
                        qualityCap = qualityCap,
                        onSelect = { qualityCap = it },
                    )
                    selectedDevice?.let { device ->
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
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
}

@Composable
private fun RemoteQualityPicker(
    qualityCap: Int?,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        null to tr("跟随目标设备", "Use target setting", "送信先の設定", "대상 기기 설정", "跟隨目標裝置"),
        0 to tr("原画", "Original", "オリジナル", "원본", "原畫"),
        1080 to "1080P",
        720 to "720P",
        480 to "480P",
    )
    val selectedLabel = options.firstOrNull { it.first == qualityCap }?.second ?: options.first().second
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("下载画质", "Download quality", "ダウンロード画質", "다운로드 화질", "下載畫質"), style = MaterialTheme.typography.labelLarge)
        Box {
            FilterChip(
                selected = qualityCap != null,
                onClick = { expanded = true },
                label = { Text(selectedLabel) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            )
            PikoDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        shape = menuItemShape(index, options.size),
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}
