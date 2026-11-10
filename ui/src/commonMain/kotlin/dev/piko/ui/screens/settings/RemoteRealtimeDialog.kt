package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.download.RemoteRealtimeConfig
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import kotlinx.coroutines.launch

/** 远程下载的实时通道配置；远程下载依赖它，未配置时不能开启。 */
@Composable
internal fun RemoteRealtimeDialog(
    initial: RemoteRealtimeConfig?,
    onDismiss: () -> Unit,
    onTest: suspend (RemoteRealtimeConfig) -> Result<Unit>,
    onSave: suspend (RemoteRealtimeConfig) -> Result<Unit>,
    onClear: suspend () -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(initial) {
        val loaded = initial ?: return@LaunchedEffect
        if (url.isBlank() && username.isBlank() && password.isBlank()) {
            url = loaded.url
            username = loaded.username
            password = loaded.password
        }
    }

    fun current() = RemoteRealtimeConfig(url.trim(), username.trim(), password)
    fun report(result: Result<Unit>, successText: String) {
        failed = result.isFailure
        message = result.exceptionOrNull()?.message ?: successText
    }

    PikoDialog(
        onDismissRequest = { if (!busy && !testing) onDismiss() },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("保存", "Save", "保存", "저장", "儲存"),
                enabled = !busy && !testing,
                onClick = {
                    val config = current()
                    if (!config.isConfigured) {
                        failed = true
                        message = tr("请填写服务器地址、用户名和密码", "Enter the server URL, username and password", "サーバー URL、ユーザー名、パスワードを入力してください", "서버 주소, 사용자 이름, 비밀번호를 입력하세요", "請填寫伺服器位址、使用者名稱與密碼")
                        return@PikoDialogConfirm
                    }
                    busy = true
                    message = null
                    scope.launch {
                        val result = onSave(config)
                        busy = false
                        if (result.isSuccess) onDismiss() else report(result, "")
                    }
                },
            )
        },
        dismissButton = {
            TextButton(enabled = !busy && !testing, onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        title = { Text(tr("实时同步通道", "Realtime channel", "リアルタイム同期", "실시간 동기화", "即時同步通道")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr(
                        "远程下载的请求、进度与设备在线状态都依赖这条 MQTT 通道；未配置时不能开启远程下载。",
                        "Remote-download requests, progress and device presence all use this MQTT channel. Remote downloads cannot be enabled without it.",
                        "リモートダウンロードのリクエスト、進捗、端末のオンライン状態はすべてこの MQTT チャンネルを使います。未設定では有効にできません。",
                        "원격 다운로드 요청, 진행률, 기기 온라인 상태가 모두 이 MQTT 채널을 사용합니다. 설정하지 않으면 사용할 수 없습니다.",
                        "遠端下載的請求、進度與裝置線上狀態都依賴這條 MQTT 通道；未設定時無法開啟遠端下載。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text(tr("通信地址", "Server URL", "サーバー URL", "서버 주소", "伺服器位址")) },
                    placeholder = { Text("mqtts://host:8883") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text(tr("用户名", "Username", "ユーザー名", "사용자 이름", "使用者名稱")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(tr("密码", "Password", "パスワード", "비밀번호", "密碼")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !busy && !testing && current().isConfigured,
                        onClick = {
                            testing = true
                            message = null
                            scope.launch {
                                val result = onTest(current())
                                testing = false
                                report(result, tr("连接成功", "Connected", "接続しました", "연결되었습니다", "連線成功"))
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(tr("测试连接", "Test connection", "接続テスト", "연결 테스트", "測試連線"))
                        }
                    }
                    if (initial != null) {
                        TextButton(
                            enabled = !busy && !testing,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = onClear()
                                    busy = false
                                    if (result.isSuccess) onDismiss() else report(result, "")
                                }
                            },
                        ) {
                            Text(tr("清除配置", "Clear", "クリア", "지우기", "清除設定"))
                        }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
