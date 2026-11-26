package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.WebDavMountInfo
import dev.piko.shared.i18n.tr
import kotlinx.coroutines.launch

/** 添加只读 WebDAV 目录。密码只交给平台机密存储，不写进挂载表。 */
@Composable
fun WebDavMountDialog(
    onDismiss: () -> Unit,
    onTest: suspend (WebDavMountInfo, String) -> Unit,
    onMount: suspend (WebDavMountInfo, String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun infoOrError(): WebDavMountInfo? {
        val parsed = WebDavMountInfo.parse(url)
        val info = parsed?.copy(username = username.trim())
        if (info == null) {
            failed = true
            message = tr(
                "请填写以 http:// 或 https:// 开头的目录地址",
                "Enter a folder address starting with http:// or https://",
                "http:// または https:// で始まるフォルダー住所を入力してください",
                "http:// 또는 https://로 시작하는 폴더 주소를 입력하세요",
                "請填寫以 http:// 或 https:// 開頭的目錄位址",
            )
        }
        return info
    }

    fun execute(action: suspend (WebDavMountInfo) -> Unit) {
        val info = infoOrError() ?: return
        busy = true
        failed = false
        message = null
        scope.launch {
            try {
                action(info)
            } catch (error: Throwable) {
                failed = true
                message = error.message ?: tr("WebDAV 操作失败", "WebDAV action failed", "WebDAV 操作に失敗しました", "WebDAV 작업 실패", "WebDAV 操作失敗")
            } finally {
                busy = false
            }
        }
    }

    PikoDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            PikoDialogConfirm(
                label = if (busy) tr("处理中…", "Working…", "処理中…", "처리 중…", "處理中…") else tr("挂载", "Mount", "マウント", "마운트", "掛載"),
                enabled = !busy,
                onClick = {
                    execute { info ->
                        onMount(info, displayName.ifBlank { info.defaultName }, password)
                        onDismiss()
                    }
                },
            )
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        title = { Text(tr("挂载 WebDAV", "Mount WebDAV", "WebDAV をマウント", "WebDAV 마운트", "掛載 WebDAV")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr(
                        "只读访问 WebDAV 目录，视频与图片经本机回环按需读取。自签名证书请改用 http 或受信任的证书。",
                        "Read-only WebDAV access; videos and images are streamed over a local loopback. For self-signed certificates, use http or a trusted certificate.",
                        "WebDAV フォルダーを読み取り専用で開きます。動画と画像はローカルループバック経由で読み込みます。自己署名証明書は http か信頼済み証明書を使用してください。",
                        "WebDAV 폴더를 읽기 전용으로 엽니다. 동영상과 이미지는 로컬 루프백으로 읽습니다. 자체 서명 인증서는 http 또는 신뢰된 인증서를 사용하세요.",
                        "唯讀存取 WebDAV 目錄，影片與圖片經本機回送按需讀取。自簽憑證請改用 http 或受信任的憑證。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text(tr("目录地址", "Folder address", "フォルダー住所", "폴더 주소", "目錄位址")) },
                    placeholder = { Text("https://example.com/dav/media") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text(tr("用户名（可选）", "Username (optional)", "ユーザー名（任意）", "사용자 이름(선택)", "使用者名稱（選填）")) },
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
                OutlinedTextField(
                    displayName,
                    { displayName = it },
                    label = { Text(tr("显示名称（可选）", "Display name (optional)", "表示名（任意）", "표시 이름(선택)", "顯示名稱（選填）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        execute { info ->
                            onTest(info, password)
                            message = tr("连接成功", "Connected", "接続できました", "연결되었습니다", "連線成功")
                        }
                    },
                ) {
                    Text(tr("测试连接", "Test connection", "接続テスト", "연결 테스트", "測試連線"))
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
