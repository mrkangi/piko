package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.piko.shared.data.SmbMountInfo
import dev.piko.shared.i18n.tr
import kotlinx.coroutines.launch

/** 添加只读 SMB2/3 共享。密码只交给平台机密存储，不写进挂载表。 */
@Composable
fun SmbMountDialog(
    onDismiss: () -> Unit,
    onTest: suspend (SmbMountInfo, String) -> Unit,
    onMount: suspend (SmbMountInfo, String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun infoOrError(): SmbMountInfo? = runCatching {
        SmbMountInfo(
            host = host.trim().removePrefix("smb://").trimEnd('/'),
            port = port.toIntOrNull() ?: 445,
            share = share.trim(),
            path = path.trim(),
            domain = domain.trim(),
            username = username.trim(),
        )
    }.getOrNull().also {
        if (it == null) {
            failed = true
            message = tr("请检查主机、端口、共享名和子路径", "Check the host, port, share and path", "ホスト、ポート、共有名、パスを確認してください", "호스트, 포트, 공유 이름, 경로를 확인하세요", "請檢查主機、連接埠、共用名稱與子路徑")
        }
    }

    fun execute(action: suspend (SmbMountInfo) -> Unit) {
        val info = infoOrError() ?: return
        busy = true
        failed = false
        message = null
        scope.launch {
            try {
                action(info)
            } catch (error: Throwable) {
                failed = true
                message = error.message ?: tr("SMB 操作失败", "SMB action failed", "SMB 操作に失敗しました", "SMB 작업 실패", "SMB 操作失敗")
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
        title = { Text(tr("挂载 SMB 共享", "Mount SMB share", "SMB 共有をマウント", "SMB 공유 마운트", "掛載 SMB 共用")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr("只读访问 SMB2/3 共享，视频与图片经本机回环按需读取。", "Read-only SMB2/3 access; videos and images are streamed over a local loopback.", "SMB2/3 共有を読み取り専用で開きます。動画と画像はローカルループバック経由で読み込みます。", "SMB2/3 공유를 읽기 전용으로 엽니다. 동영상과 이미지는 로컬 루프백으로 읽습니다.", "唯讀存取 SMB2/3 共用，影片與圖片經本機回送按需讀取。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(host, { host = it }, label = { Text(tr("主机", "Host", "ホスト", "호스트", "主機")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(tr("端口", "Port", "ポート", "포트", "連接埠")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        share,
                        { share = it },
                        label = { Text(tr("共享名", "Share", "共有名", "공유 이름", "共用名稱")) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }
                OutlinedTextField(path, { path = it }, label = { Text(tr("子路径（可选）", "Subpath (optional)", "サブパス（任意）", "하위 경로(선택)", "子路徑（選填）")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text(tr("用户名（留空为访客）", "Username (blank for guest)", "ユーザー名（空欄はゲスト）", "사용자 이름(비우면 게스트)", "使用者名稱（留空為訪客）")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text(tr("密码", "Password", "パスワード", "비밀번호", "密碼")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(domain, { domain = it }, label = { Text(tr("域（可选）", "Domain (optional)", "ドメイン（任意）", "도메인(선택)", "網域（選填）")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(displayName, { displayName = it }, label = { Text(tr("显示名称（可选）", "Display name (optional)", "表示名（任意）", "표시 이름(선택)", "顯示名稱（選填）")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
