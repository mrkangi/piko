package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.i18n.tr
import dev.piko.shared.lan.LanHostStatus
import dev.piko.shared.lan.LanLocalSettings
import dev.piko.shared.lan.LanPeer
import dev.piko.ui.LocalPikoServices
import kotlinx.coroutines.launch

/**
 * 局域网共享：host 开关、共享目录发布、配对码，以及客户端已配对主机。
 * 只读共享；播放与浏览走同一套设备命名空间。
 */
@Composable
fun LanShareDialog(onDismiss: () -> Unit) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by services.lanShareSettings.flow.collectAsStateWithLifecycle()
    val hostStatus by (services.lanShareHost?.status?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<LanHostStatus?>(null) })
    val peers by (services.lanShares?.peers?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList<LanPeer>()) })
    val mounts by services.driveRepository.localMounts.flow.collectAsStateWithLifecycle(emptyList())
    val account = services.driveRepository.currentAccount
    val published = remember(mounts, account) { mounts.filter { it.account == account } }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(LanLocalSettings.DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    PikoDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("完成", "Done", "完了", "완료", "完成")) }
        },
        title = { Text(tr("局域网共享", "LAN sharing", "LAN 共有", "LAN 공유", "區域網路共享")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("作为主机共享", "Share as host", "ホストとして共有", "호스트로 공유", "作為主機分享"))
                        Text(
                            tr(
                                "让已配对设备只读浏览本机发布目录；文件不经过云端。",
                                "Paired devices can browse published local folders read-only. Files never go through the cloud.",
                                "ペアリング済みの端末が公開フォルダーを読み取り専用で閲覧できます。ファイルはクラウドを経由しません。",
                                "페어링된 기기가 공개 폴더를 읽기 전용으로 탐색합니다. 파일은 클라우드를 거치지 않습니다.",
                                "讓已配對裝置唯讀瀏覽本機公開目錄；檔案不經過雲端。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.enabled,
                        enabled = services.lanShareHost != null,
                        onCheckedChange = { on -> scope.launch { services.lanShareSettings.update { it.copy(enabled = on) } } },
                    )
                }

                if (services.lanShareHost == null) {
                    Text(
                        tr("当前平台暂不支持作为主机；可以配对桌面端主机。", "This platform cannot act as a host yet. You can pair with a desktop host.", "このプラットフォームはまだホストになれません。デスクトップのホストとペアリングできます。", "이 플랫폼은 아직 호스트가 될 수 없습니다. 데스크톱 호스트와 페어링할 수 있습니다.", "目前平台尚不支援作為主機；可以配對桌面端主機。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (settings.enabled) {
                    val status = hostStatus
                    if (status?.running == true) {
                        Text(
                            tr("主机地址", "Host address", "ホストアドレス", "호스트 주소", "主機位址"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${status.address}:${status.port}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { clipboard.setText(AnnotatedString("${status.address}:${status.port}")) }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = tr("复制地址", "Copy address", "アドレスをコピー", "주소 복사", "複製位址"))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("配对码", "Pairing code", "ペアリングコード", "페어링 코드", "配對碼"), style = MaterialTheme.typography.labelLarge)
                                Text(status.pairingCode, style = MaterialTheme.typography.headlineSmall)
                            }
                            IconButton(onClick = { services.lanShareHost?.regeneratePairingCode() }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = tr("换一个配对码", "New code", "コードを更新", "새 코드", "更換配對碼"))
                            }
                        }
                    } else {
                        Text(
                            status?.error ?: tr("正在启动主机…", "Starting host…", "ホストを起動中…", "호스트 시작 중…", "正在啟動主機…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(tr("发布目录", "Published folders", "公開フォルダー", "공개 폴더", "公開目錄"), style = MaterialTheme.typography.labelLarge)
                    if (published.isEmpty()) {
                        Text(
                            tr("还没有挂载目录。请先在网盘页挂载本地文件夹。", "No mounted folders yet. Mount a local folder from the drive page first.", "マウント済みフォルダーがありません。先にドライブ画面でローカルフォルダーをマウントしてください。", "마운트된 폴더가 없습니다. 먼저 드라이브 화면에서 로컬 폴더를 마운트하세요.", "尚無掛載目錄。請先在網盤頁掛載本機資料夾。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        published.forEach { mount ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mount.fallbackName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Switch(
                                    checked = mount.lanShared,
                                    onCheckedChange = { on -> scope.launch { services.driveRepository.localMounts.setLanShared(mount.location.id, on) } },
                                )
                            }
                        }
                    }
                }

                Text(tr("已配对主机", "Paired hosts", "ペアリング済みホスト", "페어링된 호스트", "已配對主機"), style = MaterialTheme.typography.labelLarge)
                if (peers.isEmpty()) {
                    Text(
                        tr("尚未配对。输入主机的地址、端口和配对码即可添加。", "Nothing paired yet. Enter the host address, port and pairing code.", "まだペアリングしていません。ホストのアドレス、ポート、コードを入力してください。", "아직 페어링되지 않았습니다. 호스트 주소, 포트, 코드를 입력하세요.", "尚未配對。輸入主機的位址、連接埠與配對碼即可新增。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    peers.forEach { peer ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Wifi, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${peer.address}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { services.lanShares?.let { scope.launch { it.refresh(peer.hostDeviceId) } } }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = tr("刷新", "Refresh", "更新", "새로고침", "重新整理"))
                            }
                            IconButton(onClick = { services.lanShares?.let { scope.launch { it.removePeer(peer.hostDeviceId) } } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = tr("取消配对", "Unpair", "ペアリング解除", "페어링 해제", "取消配對"))
                            }
                        }
                    }
                }

                if (services.lanShares != null) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(tr("主机地址", "Host address", "ホストアドレス", "호스트 주소", "主機位址")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit) },
                            label = { Text(tr("端口", "Port", "ポート", "포트", "連接埠")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text(tr("配对码", "Code", "コード", "코드", "配對碼")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        enabled = !busy && address.isNotBlank() && code.length == 6,
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                val result = runCatching {
                                    services.lanShares?.pair(address, port.toIntOrNull() ?: LanLocalSettings.DEFAULT_PORT, code)
                                }
                                message = result.fold(
                                    onSuccess = {
                                        address = ""
                                        code = ""
                                        tr("已添加主机", "Host added", "ホストを追加しました", "호스트를 추가했습니다", "已新增主機")
                                    },
                                    onFailure = { it.message ?: tr("配对失败", "Pairing failed", "ペアリングに失敗しました", "페어링 실패", "配對失敗") },
                                )
                                busy = false
                            }
                        },
                    ) {
                        Text(if (busy) tr("配对中…", "Pairing…", "ペアリング中…", "페어링 중…", "配對中…") else tr("添加主机", "Add host", "ホストを追加", "호스트 추가", "新增主機"))
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    tr("共享为只读，并使用主机生成的令牌访问；请只在可信局域网使用。", "Sharing is read-only and uses a token issued by the host. Use it only on trusted LANs.", "共有は読み取り専用で、ホストが発行したトークンを使います。信頼できる LAN でのみ使用してください。", "공유는 읽기 전용이며 호스트가 발급한 토큰을 사용합니다. 신뢰할 수 있는 LAN에서만 사용하세요.", "共享為唯讀，並使用主機產生的權杖存取；請僅在可信區域網路使用。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
    )
}
