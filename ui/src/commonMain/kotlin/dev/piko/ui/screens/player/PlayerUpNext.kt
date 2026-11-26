package dev.piko.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.preferredVersion
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.screens.drive.DriveViewMode
import dev.piko.ui.screens.drive.posterTileColumns

/**
 * 播放详情页的后续视频。
 *
 * 以当前视频为起点，先排本分区的后续条目，再排同目录的其他分区，最后回卷到本分区之前的条目。
 * 同一内容的多个版本只留一个：优先沿用当前版本，没有就取主版本。这样推荐列表不会从目录第一项
 * 重新开始，也不会被 HDR、720p 这类同集版本占满。
 */
fun upNextEntries(playlist: List<PlaylistEntry>, currentFileId: String): List<PlaylistEntry> {
    if (playlist.isEmpty()) return emptyList()
    val current = playlist.firstOrNull { it.fileId == currentFileId }
    val groups = playlist
        .groupBy { it.groupKey }
        .values
        .map { group -> preferredVersion(group, current?.versionLabel) }
    if (current == null) return groups
    val sameSection = groups.filter { it.sectionKey == current.sectionKey }
    val currentIndex = sameSection.indexOfFirst { it.groupKey == current.groupKey }
    if (currentIndex < 0) return groups

    return sameSection.drop(currentIndex + 1) +
        groups.filter { it.sectionKey != current.sectionKey } +
        sameSection.take(currentIndex)
}

/** 推荐网格的列数：跟随网盘海报墙的小/中/大尺寸设置，而不是另用一套固定卡宽。 */
@Composable
fun rememberPlayerUpNextColumns(): Int {
    val tileSize by LocalPikoServices.current.preferences
        .driveTileSizeFlow(DriveViewMode.POSTER.name)
        .collectAsStateWithLifecycle(initialValue = "")
    return posterTileColumns(tileSize, currentWidthClass())
}

/**
 * 详情页里的一张后续视频卡。沿用紧凑网盘海报墙的 4:3 封面与单行标题压底结构，
 * 只负责呈现和点击，排序与版本选择由 [upNextEntries] 先算好。
 */
@Composable
fun PlayerUpNextCard(
    entry: PlaylistEntry,
    isSpoilerBlurred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    val title = entry.label.ifBlank { entry.name.substringBeforeLast('.') }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .semantics { contentDescription = entry.name },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (entry.thumbnailUrl.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            } else {
                SpoilerThumbnail(
                    model = entry.thumbnailUrl,
                    isBlurred = isSpoilerBlurred,
                    blur = PosterSpoilerBlur,
                    modifier = Modifier.fillMaxSize(),
                    showBlurIcon = false,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
