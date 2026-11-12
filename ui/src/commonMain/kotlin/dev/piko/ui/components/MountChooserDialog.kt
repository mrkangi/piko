package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr

/** 选择挂载方式：统一的分段列表行，图标、标题、说明与行高都与其他列表一致。 */
@Composable
fun MountChooserDialog(
    onDismiss: () -> Unit,
    onLocalFolder: (() -> Unit)?,
    onSmbShare: () -> Unit,
    onJellyfin: () -> Unit,
    onPlex: () -> Unit,
) {
    val options = listOf(
        MountOption(
            icon = Icons.Outlined.FolderOpen,
            title = tr("本地文件夹", "Local folder", "ローカルフォルダー", "로컬 폴더", "本機資料夾"),
            supporting = tr("从本机存储中选择一个文件夹", "Pick a folder from this device", "この端末のフォルダーを選択", "이 기기의 폴더 선택", "從本機儲存空間選擇資料夾"),
            onClick = onLocalFolder,
        ),
        MountOption(
            icon = Icons.Outlined.Dns,
            title = tr("SMB 共享", "SMB share", "SMB 共有", "SMB 공유", "SMB 共用"),
            supporting = tr("连接 NAS 或电脑上的共享目录", "Connect to a share on a NAS or computer", "NAS やパソコンの共有フォルダーに接続", "NAS 또는 컴퓨터의 공유 폴더에 연결", "連線 NAS 或電腦上的共用目錄"),
            onClick = onSmbShare,
        ),
        MountOption(
            icon = Icons.Outlined.LiveTv,
            title = tr("Jellyfin 媒体服务器", "Jellyfin server", "Jellyfin サーバー", "Jellyfin 서버", "Jellyfin 媒體伺服器"),
            supporting = tr("只读浏览媒体库并播放", "Browse and play from a read-only library", "読み取り専用でライブラリを閲覧・再生", "읽기 전용으로 라이브러리 탐색 및 재생", "唯讀瀏覽媒體庫並播放"),
            onClick = onJellyfin,
        ),
        MountOption(
            icon = Icons.Outlined.LiveTv,
            title = tr("Plex 媒体服务器", "Plex server", "Plex サーバー", "Plex 서버", "Plex 媒體伺服器"),
            supporting = tr("使用 Plex 账号登录，只读原画播放", "Sign in with Plex; read-only original playback", "Plex アカウントでサインインし、オリジナル画質のみ再生", "Plex 계정으로 로그인하고 원본 화질만 재생", "使用 Plex 帳號登入，僅唯讀原畫播放"),
            onClick = onPlex,
        ),
    )
    PikoDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
            }
        },
        title = { Text(tr("挂载目录", "Mount folder", "フォルダーをマウント", "폴더 마운트", "掛載目錄")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                options.forEachIndexed { index, option ->
                    SegmentedListItem(
                        onClick = { option.onClick?.invoke() },
                        enabled = option.onClick != null,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = options.size),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        leadingContent = {
                            Icon(option.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    option.supporting,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        },
    )
}

private class MountOption(
    val icon: ImageVector,
    val title: String,
    val supporting: String,
    val onClick: (() -> Unit)?,
)
