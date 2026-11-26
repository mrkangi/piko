package dev.piko.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.components.SheetAction

/**
 * 竖屏视频详情页：上方固定内嵌播放器，下方是标题、文件操作与同目录后续视频。
 *
 * 播放器宽度铺满，高度按画面比例算并封顶；播放器区顶部额外留出状态栏，画面本身贴在
 * 黑色底栏之下，避免状态栏图标压住视频。正文使用与网盘紧凑海报墙相同的多列网格。
 */
@Composable
internal fun VideoDetailPage(
    title: String,
    metadata: String,
    currentFileId: String,
    actions: List<SheetAction>,
    upNext: List<PlaylistEntry>,
    isSpoilerBlurred: Boolean,
    playlistLoading: Boolean,
    videoAspect: Float?,
    snackbarHostState: SnackbarHostState,
    onSelectEntry: (PlaylistEntry) -> Unit,
    player: @Composable (stageModifier: Modifier, videoModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gridState = rememberLazyGridState()
    val columns = rememberPlayerUpNextColumns()
    LaunchedEffect(currentFileId) { gridState.scrollToItem(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val aspect = videoAspect?.takeIf { it.isFinite() && it > 0.05f } ?: (16f / 9f)
        val videoHeight = (maxWidth / aspect)
            .coerceAtMost((maxHeight * 0.46f).coerceAtLeast(160.dp))
        val stageHeight = statusBarTop + videoHeight

        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .background(Color.Black),
            ) {
                player(
                    Modifier.fillMaxSize(),
                    Modifier
                        .fillMaxWidth()
                        .height(videoHeight)
                        .align(Alignment.BottomCenter),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    bottom = 16.dp + navigationBottom,
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DetailHeader(
                        title = title,
                        metadata = metadata,
                        actions = actions,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    UpNextHeader(
                        count = upNext.size,
                        loading = playlistLoading,
                    )
                }
                when {
                    playlistLoading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        DetailMessage(tr("正在读取同目录视频…", "Loading videos in this folder…", "同じフォルダーの動画を読み込み中…", "같은 폴더의 동영상을 불러오는 중…", "正在讀取同資料夾的影片…"))
                    }
                    upNext.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        DetailMessage(tr("这个文件夹里没有其他视频", "No other videos in this folder", "このフォルダーに他の動画はありません", "이 폴더에 다른 동영상이 없습니다", "這個資料夾裡沒有其他影片"))
                    }
                    else -> items(upNext, key = { it.fileId }) { entry ->
                        PlayerUpNextCard(
                            entry = entry,
                            isSpoilerBlurred = isSpoilerBlurred,
                            onClick = { onSelectEntry(entry) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = navigationBottom + 16.dp),
        )
    }
}

@Composable
private fun DetailHeader(
    title: String,
    metadata: String,
    actions: List<SheetAction>,
) {
    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (metadata.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { action ->
                    AssistChip(
                        onClick = action.onClick,
                        label = {
                            Text(
                                text = action.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextHeader(count: Int, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tr("接下来播放", "Up next", "次の動画", "다음 동영상", "接下來播放"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        if (!loading && count > 0) {
            Text(
                text = tr("本目录 ${count + 1} 个视频", "${enCount(count + 1, "video")} in this folder", "このフォルダーに動画 ${count + 1} 本", "이 폴더의 동영상 ${count + 1}개", "本資料夾 ${count + 1} 部影片"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DetailMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
