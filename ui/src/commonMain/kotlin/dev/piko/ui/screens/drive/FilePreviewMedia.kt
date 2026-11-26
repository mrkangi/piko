package dev.piko.ui.screens.drive

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.media.player.AdoptedPlayback
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.MediaLoadingIndicator
import dev.piko.ui.components.PosterSpoilerBlur
import dev.piko.ui.components.SpoilerThumbnail
import dev.piko.ui.components.extensionLabel
import dev.piko.ui.components.formatTimeMs
import dev.piko.ui.components.previewImageCoverUrl
import dev.piko.ui.components.typeIcon
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PreviewBackend
import dev.piko.ui.platform.VideoPreviewHandoff
import dev.piko.ui.platform.VideoPreviewHandoffs
import dev.piko.ui.platform.VideoPreviewSupport
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.videoThumbnailUrlOf

/**
 * 预览播放器与播放详情页之间的交接。
 *
 * 普通字段记录点击那一刻的位置与已在播放的会话，避免为了读进度把整块面板按帧重组。
 * 交接成功后预览面板离开组合时跳过 release，由详情页接管同一个后端与代理流。
 */
internal class PreviewPlaybackSource(
    val target: PlaybackTarget,
    val prepared: PreparedPlayback?,
    val info: PlayableMediaInfo?,
    val localPath: String?,
    val usingProxy: Boolean,
)

internal class PreviewPlaybackHandoff {
    var positionMillis: Long = 0L
    var backend: PreviewBackend? = null
    var support: VideoPreviewSupport? = null
    var source: PreviewPlaybackSource? = null
    var handedOff: Boolean = false

    fun offer(fileId: String): Boolean {
        if (handedOff) return true
        val backend = backend ?: return false
        val support = support ?: return false
        val source = source ?: return false
        if (!support.supportsSeamlessHandoff) return false
        VideoPreviewHandoffs.offer(
            VideoPreviewHandoff(
                fileId = fileId,
                support = support,
                backend = backend,
                adopted = AdoptedPlayback(
                    prepared = source.prepared,
                    mediaInfo = source.info,
                    localPath = source.localPath,
                    usingProxy = source.usingProxy,
                ),
            ),
        )
        handedOff = true
        return true
    }
}

@Composable
internal fun FilePreviewHero(
    file: FileStat,
    kind: FilePreviewKind,
    onOpen: () -> Unit,
    onOpenVideo: (startMillis: Long) -> Unit,
    onToggleBlur: () -> Unit,
    playback: PreviewPlaybackHandoff,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black),
    ) {
        when (kind) {
            FilePreviewKind.Blurred -> BlurredHero(file, onToggleBlur)
            FilePreviewKind.Video -> VideoPreviewHero(file, onOpenVideo, playback)
            FilePreviewKind.Image -> ImagePreviewHero(file, onOpen)
            FilePreviewKind.Audio,
            FilePreviewKind.Folder,
            FilePreviewKind.Archive,
            FilePreviewKind.Document,
            FilePreviewKind.Generic,
            -> IconPreviewHero(file, kind)
        }
    }
}

@Composable
private fun VideoPreviewHero(
    file: FileStat,
    onOpenDetail: (startMillis: Long) -> Unit,
    playback: PreviewPlaybackHandoff,
) {
    val support = LocalPikoPlatform.current.videoPreview
    if (support == null) {
        IconPreviewHero(file, FilePreviewKind.Video)
        return
    }
    val player = support.rememberPreviewBackend(fullFeatured = support.supportsSeamlessHandoff)
    val repository = LocalPikoServices.current.mediaRepository
    val mounts = LocalPikoServices.current.driveRepository.localMounts
    val cover = videoCoverOf(file)
    val source by produceState<PreviewPlaybackSource?>(null, file.id) {
        val prepared = repository.preparePlayback(file.id).getOrNull()
        if (prepared != null) {
            val url = (prepared.proxyUrl ?: prepared.info.currentUrl)
                .takeIf { it.isNotBlank() }
            if (url != null) {
                value = PreviewPlaybackSource(
                    target = PlaybackTarget.Url(url),
                    prepared = prepared,
                    info = prepared.info,
                    localPath = null,
                    usingProxy = prepared.proxyUrl != null,
                )
            } else {
                prepared.close()
            }
            awaitDispose { if (!playback.handedOff) prepared.close() }
        } else {
            value = runCatching {
                val mount = mounts.find(file.id)
                val path = mounts.resolve(file.id) ?: return@runCatching null
                val network = mount?.isNetworkMount == true
                PreviewPlaybackSource(
                    target = if (network) PlaybackTarget.Url(path) else PlaybackTarget.LocalFile(path),
                    prepared = null,
                    info = null,
                    localPath = path.takeUnless { network },
                    usingProxy = false,
                )
            }.getOrNull()
        }
    }
    var muted by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf(0L) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        val openSource = source
        if (openSource != null) {
            runCatching { player.open(openSource.target, startMillis = 0L, playWhenReady = false) }
        }
    }
    LaunchedEffect(muted) { player.setVolume(if (muted) 0f else 1f) }
    DisposableEffect(player) { onDispose { if (!playback.handedOff) player.release() } }

    val duration = player.durationMillis
    val position = player.positionMillis.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
    val shownPosition = if (scrubbing) scrubTarget else position
    val fraction = if (duration > 0L) shownPosition.toFloat() / duration else 0f
    val togglePlayback = {
        if (source != null) {
            if (player.isPlaying) player.pause() else player.play()
        }
    }
    SideEffect {
        playback.positionMillis = shownPosition
        playback.backend = player
        playback.support = support
        playback.source = source
    }
    Box(Modifier.fillMaxSize()) {
        if (cover.isNotEmpty()) {
            SpoilerThumbnail(cover, isBlurred = false, PosterSpoilerBlur, Modifier.fillMaxSize())
        }
        if (source != null) {
            support.Surface(
                player,
                Modifier.fillMaxSize().alpha(if (player.isBuffering) 0f else 1f),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(duration, source) {
                    if (duration <= 0L || source == null) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            scrubbing = true
                            wasPlayingBeforeScrub = player.isPlaying
                            if (player.isPlaying) player.pause()
                            scrubTarget = player.positionMillis.coerceIn(0L, duration)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scrubTarget = (scrubTarget + (dragAmount / size.width.toFloat() * duration.toFloat()).toLong())
                                .coerceIn(0L, duration)
                        },
                        onDragEnd = {
                            player.seekTo(scrubTarget)
                            if (wasPlayingBeforeScrub) player.play()
                            scrubbing = false
                        },
                        onDragCancel = {
                            if (wasPlayingBeforeScrub) player.play()
                            scrubbing = false
                        },
                    )
                }
                .pointerInput(duration, source) {
                    if (source == null) return@pointerInput
                    detectTapGestures(onDoubleTap = { togglePlayback() })
                },
        )
        if (source == null || player.isBuffering) {
            MediaLoadingIndicator(Modifier.align(Alignment.Center))
        }
        PreviewMediaChip(file.extensionLabel() ?: tr("视频", "Video", "動画", "동영상", "影片"), Modifier.align(Alignment.TopStart).padding(10.dp))
        PreviewExpandButton(
            onClick = { onOpenDetail(player.positionMillis.coerceAtLeast(0L)) },
            description = tr("播放详情", "Play details", "再生詳細", "재생 상세", "播放詳情"),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
        if (scrubbing) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.64f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    formatTimeMs(scrubTarget),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f))))
                .padding(start = 14.dp, end = 14.dp, top = 26.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = togglePlayback,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.42f),
                    contentColor = Color.White,
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (player.isPlaying) tr("暂停", "Pause", "一時停止", "일시정지", "暫停") else tr("播放", "Play", "再生", "재생", "播放"),
                            Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimeMs(shownPosition),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0L) formatTimeMs(duration) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = { muted = !muted },
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            if (muted) tr("取消静音", "Unmute", "ミュート解除", "음소거 해제", "取消靜音") else tr("静音", "Mute", "ミュート", "음소거", "靜音"),
                            Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
        }
    }
}

@Composable
private fun ImagePreviewHero(file: FileStat, onOpen: () -> Unit) {
    val cover = file.previewImageCoverUrl()
    if (cover.isBlank()) {
        IconPreviewHero(file, FilePreviewKind.Image)
        return
    }
    Box(Modifier.fillMaxSize()) {
        SpoilerThumbnail(cover, isBlurred = false, PosterSpoilerBlur, Modifier.fillMaxSize())
        PreviewMediaChip(file.extensionLabel() ?: tr("图片", "Image", "画像", "이미지", "圖片"), Modifier.align(Alignment.TopStart).padding(10.dp))
        PreviewExpandButton(
            onOpen,
            tr("查看原图", "View original", "元の画像を表示", "원본 보기", "檢視原圖"),
            Modifier.align(Alignment.TopEnd).padding(10.dp),
        )
    }
}

@Composable
private fun BlurredHero(file: FileStat, onToggleBlur: () -> Unit) {
    val cover = if (file.isPreviewableImage()) file.previewImageCoverUrl() else videoCoverOf(file)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (cover.isNotBlank()) {
            SpoilerThumbnail(
                cover,
                isBlurred = true,
                PosterSpoilerBlur,
                Modifier.fillMaxSize(),
                showBlurIcon = false,
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.14f), contentColor = Color.White) {
                Icon(Icons.Outlined.Lock, null, Modifier.padding(11.dp).size(22.dp))
            }
            Text(
                tr("预览已隐藏", "Preview hidden", "プレビューは非表示です", "미리 보기가 숨겨져 있습니다", "預覽已隱藏"),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            FilledTonalButton(onClick = onToggleBlur) {
                Icon(Icons.Outlined.Visibility, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("显示预览", "Show preview", "プレビューを表示", "미리 보기 표시", "顯示預覽"))
            }
        }
    }
}

@Composable
private fun IconPreviewHero(file: FileStat, kind: FilePreviewKind) {
    val colors = MaterialTheme.colorScheme
    val dark = kind == FilePreviewKind.Audio
    val background = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF272237), Color(0xFF514266), Color(0xFF8B6878)))
    } else {
        Brush.linearGradient(listOf(colors.secondaryContainer, colors.surfaceContainerHighest))
    }
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
        if (dark) AudioWave(Modifier.fillMaxSize())
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (dark) Color.White.copy(alpha = 0.16f) else colors.surfaceContainerLow,
                contentColor = when {
                    dark -> Color.White
                    file.isFolder || file.isExtractableArchive -> colors.primary
                    else -> colors.onSurfaceVariant
                },
                shadowElevation = if (dark) 0.dp else 2.dp,
                modifier = Modifier.size(92.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(file.typeIcon(), null, Modifier.size(46.dp))
                }
            }
            Text(
                previewTypeLabel(file),
                style = MaterialTheme.typography.labelLarge,
                color = if (dark) Color.White.copy(alpha = 0.82f) else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AudioWave(modifier: Modifier) {
    Canvas(modifier) {
        val barWidth = 3.dp.toPx()
        val gap = 4.dp.toPx()
        val total = 34
        val startX = (size.width - total * (barWidth + gap) + gap) / 2f
        val centerY = size.height * 0.36f
        for (i in 0 until total) {
            val ratio = when (i % 8) {
                0 -> 0.22f
                1 -> 0.38f
                2 -> 0.62f
                3 -> 0.34f
                4 -> 0.78f
                5 -> 0.48f
                6 -> 0.88f
                else -> 0.56f
            }
            val h = size.height * 0.28f * ratio
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(startX + i * (barWidth + gap), centerY - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun PreviewMediaChip(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PreviewExpandButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.OpenInFull,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.38f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        Icon(
            icon,
            description,
            Modifier.padding(8.dp).size(18.dp),
        )
    }
}

private fun videoCoverOf(file: FileStat): String =
    file.thumbnailLink.ifBlank {
        if (!file.isPlayableVideo() || file.hash.isBlank()) "" else videoThumbnailUrlOf(file.hash)
    }
