package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import kotlinx.coroutines.launch

/** 添加只读 Jellyfin 媒体服务器。令牌由 JellyfinRepository 交给平台机密存储。 */
@Composable
fun JellyfinDialog(
    onDismiss: () -> Unit,
    onLogin: suspend (url: String, name: String, username: String, password: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun login() {
        val address = url.trim()
        if (address.isEmpty() || username.isBlank() || password.isEmpty()) {
            failed = true
            message = tr("请填写服务器地址、用户名和密码", "Enter the server URL, username and password", "サーバー URL、ユーザー名、パスワードを入力してください", "서버 주소, 사용자 이름, 비밀번호를 입력하세요", "請填寫伺服器位址、使用者名稱與密碼")
            return
        }
        busy = true
        failed = false
        message = null
        scope.launch {
            try {
                onLogin(address, name, username, password)
                onDismiss()
            } catch (error: Throwable) {
                failed = true
                message = error.message ?: tr("Jellyfin 登录失败", "Jellyfin sign-in failed", "Jellyfin へのサインインに失敗しました", "Jellyfin 로그인에 실패했습니다", "Jellyfin 登入失敗")
            } finally {
                busy = false
            }
        }
    }

    PikoDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            PikoDialogConfirm(
                label = if (busy) tr("登录中…", "Signing in…", "サインイン中…", "로그인 중…", "登入中…") else tr("添加服务器", "Add server", "サーバーを追加", "서버 추가", "新增伺服器"),
                enabled = !busy,
                onClick = ::login,
            )
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        title = { Text(tr("添加 Jellyfin 服务器", "Add Jellyfin server", "Jellyfin サーバーを追加", "Jellyfin 서버 추가", "新增 Jellyfin 伺服器")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr("只读浏览媒体库并播放；访问令牌只保存在本机。", "Read-only library browsing and playback; the access token stays on this device.", "メディアライブラリを読み取り専用で閲覧・再生します。トークンはこの端末だけに保存します。", "미디어 라이브러리를 읽기 전용으로 탐색하고 재생합니다. 토큰은 이 기기에만 저장됩니다.", "唯讀瀏覽媒體庫並播放；存取權杖只保存在本機。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text(tr("服务器地址", "Server URL", "サーバー URL", "서버 주소", "伺服器位址")) },
                    placeholder = { Text("http://192.168.1.10:8096") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(tr("显示名称（可选）", "Display name (optional)", "表示名（任意）", "표시 이름(선택)", "顯示名稱（選填）")) },
                    singleLine = true,
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
