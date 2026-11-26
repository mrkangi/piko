package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.state.DriveItemName
import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.ActionTier
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.metaParts
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.prepareOnPointer
import io.github.nihildigit.pikpak.FileStat

internal enum class FilePreviewKind {
    Video,
    Image,
    Audio,
    Folder,
    Archive,
    Document,
    Generic,
    Blurred,
}

internal fun previewKindOf(file: FileStat, isBlurred: Boolean): FilePreviewKind {
    if (isBlurred) return FilePreviewKind.Blurred
    val category = file.fileCategory()
    return when {
        file.isPlayableVideo() -> FilePreviewKind.Video
        file.isPreviewableImage() -> FilePreviewKind.Image
        file.isFolder -> FilePreviewKind.Folder
        category == FileCategory.AUDIO -> FilePreviewKind.Audio
        category == FileCategory.ARCHIVE || file.isExtractableArchive -> FilePreviewKind.Archive
        category == FileCategory.DOCUMENT -> FilePreviewKind.Document
        else -> FilePreviewKind.Generic
    }
}

internal fun previewTypeLabel(file: FileStat): String {
    val category = file.fileCategory()
    return when {
        file.isFolder -> tr("文件夹", "Folder", "フォルダー", "폴더", "資料夾")
        file.isPlayableVideo() -> tr("视频", "Video", "動画", "동영상", "影片")
        file.isPreviewableImage() -> tr("图片", "Image", "画像", "이미지", "圖片")
        category == FileCategory.AUDIO -> tr("音频", "Audio", "音声", "오디오", "音訊")
        category == FileCategory.ARCHIVE || file.isExtractableArchive -> tr("压缩包", "Archive", "アーカイブ", "압축 파일", "壓縮檔")
        category == FileCategory.DOCUMENT -> tr("文档", "Document", "ドキュメント", "문서", "文件")
        else -> tr("文件", "File", "ファイル", "파일", "檔案")
    }
}

/**
 * 移动端长按条目的预览面板。视频与图片有实际内容，其余类型用大图标；操作仍由调用方从
 * [itemActions] 生成，这里只决定移动端的排法：4 个高频动作 + 双列次级动作 + 底部危险项。
 */
@Composable
internal fun FilePreviewSheet(
    name: DriveItemName,
    locationLabel: String?,
    actions: List<SheetAction>,
    isBlurred: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val file = name.file
    var previewRevealed by remember(file.id) { mutableStateOf(false) }
    val blurred = isBlurred && !previewRevealed
    val quick = previewQuickActions(file, actions, onOpen)
    val star = actions.firstOrNull { it.icon == Icons.Outlined.StarOutline || it.icon == Icons.Filled.Star }
    val remaining = actions.filterNot {
        it in quick ||
            it === star ||
            it.icon == Icons.Outlined.Visibility ||
            it.icon == Icons.Outlined.VisibilityOff
    }
    val danger = remaining.filter { it.destructive }
    val more = remaining.filterNot { it.destructive }

    PikoSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = true,
        maxHeightFraction = 0.86f,
    ) {
        val sheet = this
        Column(Modifier.fillMaxSize()) {
            if (!sheet.isSideSheet) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            tr("关闭预览", "Close preview", "プレビューを閉じる", "미리 보기 닫기", "關閉預覽"),
                            Modifier.size(20.dp),
                        )
                    }
                }
            }

            FilePreviewHero(
                file = file,
                kind = previewKindOf(file, blurred),
                onOpen = { sheet.hideThen(onOpen) },
                onToggleBlur = { previewRevealed = true },
            )

            PreviewInfo(name, locationLabel, star)

            val actionScroll = rememberScrollState()
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(actionScroll)) {
                    PreviewQuickRow(quick, onAction = { sheet.hideThen(it.onClick) })
                    Text(
                        tr("更多操作", "More actions", "その他の操作", "추가 작업", "更多操作"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 7.dp),
                    )
                    PreviewActionGrid(more, onAction = { sheet.hideThen(it.onClick) })
                    Spacer(Modifier.height(22.dp))
                }
                if (actionScroll.canScrollForward) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainerLow),
                                ),
                            ),
                    )
                }
            }

            if (danger.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    danger.forEach { action ->
                        PreviewDanger(action, onAction = { sheet.hideThen(action.onClick) })
                    }
                }
            }
        }
    }
}

private fun previewQuickActions(
    file: FileStat,
    actions: List<SheetAction>,
    onOpen: () -> Unit,
): List<SheetAction> {
    val category = file.fileCategory()
    val archive = category == FileCategory.ARCHIVE || file.isExtractableArchive
    val download = actions.actionByIcon(Icons.Outlined.Download)
    val share = actions.actionByIcon(Icons.Outlined.Share)
    val rename = actions.actionByIcon(Icons.Outlined.DriveFileRenameOutline)
    val select = actions.firstOrNull { it.group == ActionGroup.Select }
    val extract = actions.firstOrNull {
        it.icon == Icons.Outlined.Unarchive && it.group == ActionGroup.Open && !it.destructive
    }
    val open = when {
        file.isFolder || archive -> SheetAction(
            Icons.Outlined.FolderOpen,
            tr("打开", "Open", "開く", "열기", "開啟"),
            onOpen,
            group = ActionGroup.Open,
            tier = ActionTier.Quick,
            shortLabel = tr("打开", "Open", "開く", "열기", "開啟"),
        )
        file.isPreviewableImage() && file.previewImageCoverUrl().isNotBlank() -> SheetAction(
            Icons.Outlined.OpenInFull,
            tr("查看原图", "View original", "元の画像を表示", "원본 보기", "檢視原圖"),
            onOpen,
            group = ActionGroup.Open,
            tier = ActionTier.Quick,
            shortLabel = tr("查看原图", "View original", "元の画像を表示", "원본 보기", "檢視原圖"),
        )
        category == FileCategory.DOCUMENT || category == FileCategory.SUBTITLE -> null
        else -> SheetAction(
            Icons.Outlined.FolderOpen,
            tr("打开", "Open", "開く", "열기", "開啟"),
            onOpen,
            group = ActionGroup.Open,
            tier = ActionTier.Quick,
            shortLabel = tr("打开", "Open", "開く", "열기", "開啟"),
        )
    }

    val ordered = when {
        file.isFolder -> listOfNotNull(open, share, rename, select)
        archive -> listOfNotNull(open, extract, download, select)
        file.isPreviewableImage() -> listOfNotNull(open, download, share, select)
        else -> listOfNotNull(download, share, rename, select)
    }
    val extras = actions.filter {
        it.tier == ActionTier.Quick &&
            !it.destructive &&
            it !in ordered &&
            it.icon != Icons.Outlined.StarOutline &&
            it.icon != Icons.Filled.Star
    }
    return (ordered + extras).distinctBy { it.label }.take(4)
}

private fun List<SheetAction>.actionByIcon(icon: ImageVector): SheetAction? =
    firstOrNull { it.icon == icon && !it.destructive }

@Composable
private fun PreviewInfo(name: DriveItemName, locationLabel: String?, star: SheetAction?) {
    val file = name.file
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    previewTypeLabel(file),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
                file.modifiedTime.take(10).takeIf { it.isNotEmpty() }?.let {
                    Text("·", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                name.headingText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (name.headingOriginal ?: locationLabel)?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (star != null) {
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = star.onClick,
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
                contentColor = colors.onSurface,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(star.icon, star.label, Modifier.size(20.dp))
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        file.metaParts().forEach { PreviewMetaChip(it) }
    }
}

@Composable
private fun PreviewMetaChip(label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewQuickRow(actions: List<SheetAction>, onAction: (SheetAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEachIndexed { index, action ->
            val primary = index == 0
            Surface(
                onClick = { onAction(action) },
                shape = RoundedCornerShape(19.dp),
                color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).height(72.dp).prepareOnPointer(action),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(action.icon, null, Modifier.size(23.dp))
                    Spacer(Modifier.height(5.dp))
                    Text(
                        action.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewActionGrid(actions: List<SheetAction>, onAction: (SheetAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    PreviewActionTile(action, onAction, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PreviewActionTile(
    action: SheetAction,
    onAction: (SheetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onAction(action) },
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = modifier.height(48.dp).prepareOnPointer(action),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(action.icon, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(9.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                Modifier.size(15.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun PreviewDanger(action: SheetAction, onAction: () -> Unit) {
    Surface(
        onClick = onAction,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(action.icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(action.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
