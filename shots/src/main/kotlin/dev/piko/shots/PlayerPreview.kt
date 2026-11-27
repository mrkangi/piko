package dev.piko.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.VrModeState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.navigation.Screen
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.screens.player.PlayerUpNextCard
import dev.piko.ui.screens.player.SeekPreviewController
import dev.piko.ui.screens.player.SeekPreviewExtractor
import dev.piko.ui.screens.player.SeekPreviewFrame
import dev.piko.ui.screens.player.SeekPreviewSource
import dev.piko.ui.screens.player.SeekPreviewSupport
import dev.piko.ui.screens.player.rememberPlayerFileActions
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.ThemeMode

/** 播放器控件的一种画面。没有播放后端，取流阶段与面板由这几项直接给出。 */
data class PlayerShot(
    val playing: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val resumedFromMillis: Long? = null,
    val fullscreen: Boolean = false,
    /** 后端支持 VR 时的初始视角：true 即已开启，底栏按开启态高亮。 */
    val vr: Boolean = false,
    /** 本地播放：详情页用「本地文件」标记，底栏不显示清晰度。 */
    val local: Boolean = false,
    /** 竖屏详情页：播放器占上半屏，下面是标题与后续视频。 */
    val halfDetail: Boolean = false,
)

private class FixedLevel(private var value: Float) : PlayerLevelControl {
    override fun current() = value
    override fun set(fraction: Float): Float = fraction.coerceIn(0f, 1f).also { value = it }
}

/**
 * 仅渲染播放器控件，不创建播放后端。数据照真实播放器喂齐：同分区的 12 集、两条音轨与字幕、详细信息、
 * 文件操作（分享与画质下载对话框按 [fileIdOf] 取到的网盘文件弹出）。
 */
@Composable
internal fun PlayerPreview(
    env: ShotEnv,
    mode: ThemeMode,
    shot: PlayerShot,
    platform: PikoPlatform,
    request: Screen.VideoPlayer? = null,
    onBack: () -> Unit = {},
) {
    val pointers = remember { PointerSource() }
    CompositionLocalProvider(LocalPikoServices provides env.services, LocalPikoPlatform provides platform,
        LocalPointerSource provides pointers) {
        PikoTheme(Appearance(mode = mode)) {
            var playing by remember { mutableStateOf(shot.playing) }
            var position by remember { mutableStateOf(60_000L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var rotation by remember { mutableIntStateOf(0) }
            var subtitle by remember { mutableStateOf<String?>("s1") }
            var vrMode by remember { mutableStateOf(VrModeState(enabled = shot.vr)) }
            val episodes = remember(request) {
                if (request == null) playlist(env)
                else listOf(PlaylistEntry(fileId = request.fileId, name = request.fileName, label = request.fileName,
                    sectionKey = "preview", sectionLabel = "预览"))
            }
            val current = episodes.first()
            val actions = rememberPlayerFileActions(current.fileId, isLocalPlayback = shot.local) {}
            val seekPreview = remember { sampleSeekPreview() }
            val previewScope = rememberCoroutineScope()
            val seekPreviewController = remember(previewScope, seekPreview) {
                SeekPreviewController(previewScope, object : SeekPreviewSupport {
                    override suspend fun open(source: SeekPreviewSource): SeekPreviewExtractor =
                        object : SeekPreviewExtractor {
                            override suspend fun frameAt(positionMillis: Long, targetWidthPx: Int) = seekPreview
                            override fun close() = Unit
                        }
                })
            }
            val seekPreviewSource = remember(current.fileId, shot.local) {
                SeekPreviewSource(
                    fileId = current.fileId,
                    localPath = if (shot.local) "/mock/local-video.mp4" else null,
                    isLocal = shot.local,
                )
            }
            LaunchedEffect(seekPreviewController, seekPreviewSource) {
                seekPreviewController.bind(seekPreviewSource)
            }
            DisposableEffect(seekPreviewController) {
                onDispose { seekPreviewController.release() }
            }
            Box(Modifier.fillMaxSize().background(Color.Black).trackPointerSource(pointers)) {
                val controls: @Composable (Boolean, Modifier) -> Unit = { embedded, modifier ->
                    MobilePlayerControls(
                        title = current.name, isLocalPlayback = shot.local,
                        isPlaying = playing, isLoading = shot.loading, positionMillis = position,
                        durationMillis = 1_440_000, bufferedPositionMillis = 120_000,
                        seekPreviewController = seekPreviewController,
                        playbackSpeed = speed, aspectRatio = PlayerAspectRatio.Fit,
                        vrMode = vrMode,
                        onVrModeChange = { vrMode = it },
                        qualityOptions = if (shot.local) emptyList() else listOf("原画", "1080P", "720P"),
                        currentQuality = if (shot.local) null else "原画",
                        errorMessage = shot.error, resumedFromMillis = shot.resumedFromMillis,
                        onPlayPause = { playing = !playing }, onSeek = { position = it }, onSpeedChange = { speed = it },
                        onAspectRatioChange = {}, onQualityChange = {}, onRetry = {}, onRestartFromBeginning = {},
                        onBack = onBack, onToggleFullscreen = {},
                        isFullscreen = shot.fullscreen,
                        playlist = episodes, currentFileId = current.fileId, hasPrevious = false, hasNext = episodes.size > 1,
                        audioTracks = listOf(MediaTrack("a1", "日语", "jpn"), MediaTrack("a2", "评论音轨", "jpn")),
                        selectedAudioTrackId = "a1",
                        subtitleTracks = listOf(MediaTrack("s1", "简日双语", "chi"), MediaTrack("s2", "繁日双语", "chi")),
                        selectedSubtitleTrackId = subtitle, onSelectSubtitleTrack = { subtitle = it?.id },
                        // 桌面没有可调的屏幕亮度
                        brightness = if (platform.formFactor == FormFactor.Mobile) remember { FixedLevel(0.6f) } else null,
                        volume = remember { FixedLevel(0.7f) },
                        rotationDegrees = rotation, onRotationChange = { rotation = it },
                        onPickLocalSubtitle = {}, onPickDriveSubtitle = {},
                        fileActions = actions,
                        embedded = embedded,
                        modifier = modifier,
                        playbackStats = {
                            listOf(
                                PlaybackStatsSection("视频", listOf("编码" to "HEVC Main 10", "分辨率" to "1920×1080", "帧率" to "23.976")),
                                PlaybackStatsSection(
                                    "网络",
                                    if (shot.local) listOf("来源" to "本地文件", "缓冲" to "本地直读")
                                    else listOf("来源" to "原画直链", "缓冲" to "58 秒", "速度" to "12.4 MB/s"),
                                ),
                            )
                        },
                    )
                }
                if (shot.halfDetail) {
                    PlayerDetailPreview(
                        title = current.name,
                        local = shot.local,
                        upNext = episodes.drop(1),
                        player = { modifier -> controls(true, modifier) },
                    )
                } else {
                    controls(false, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 竖屏播放详情页的视觉骨架：上半屏内嵌播放器，下面是标题、操作和后续视频。 */
@Composable
private fun PlayerDetailPreview(
    title: String,
    local: Boolean,
    upNext: List<PlaylistEntry>,
    player: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val videoHeight = (maxWidth * 9f / 16f).coerceAtMost(maxHeight * 0.46f)
        val stageHeight = 24.dp + videoHeight
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stageHeight)
                    .background(Color.Black)
                    .zIndex(1f),
            ) {
                player(Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                )
                Text(
                    text = buildString {
                        append("第 01 集 · 本目录 12 个视频 · 时长 24:00")
                        if (local) append(" · 本地文件")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("下载", "分享", "在网盘中显示").forEach { label ->
                        androidx.compose.material3.Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("接下来播放", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "本目录 12 个视频",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    upNext.take(3).forEach { entry ->
                        PlayerUpNextCard(
                            entry = entry,
                            isSpoilerBlurred = true,
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 拖动进度截图的样例帧：黄昏山路，避免截图里出现纯色块，便于判断裁切与卡片比例。 */
private fun sampleSeekPreview(): SeekPreviewFrame {
    val width = 480
    val height = 270
    val image = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height).use { surface ->
        val canvas = surface.canvas
        canvas.clear(0xFF172436.toInt())
        org.jetbrains.skia.Paint().use { paint ->
            paint.color = 0xFF243D58.toInt()
            canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(0f, 0f, width.toFloat(), 150f), paint)
            paint.color = 0xFF3B566D.toInt()
            canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(0f, 150f, width.toFloat(), 205f), paint)

            paint.color = 0x33F6C98E
            canvas.drawCircle(382f, 66f, 52f, paint)
            paint.color = 0xFFF6D29A.toInt()
            canvas.drawCircle(382f, 66f, 28f, paint)

            paint.color = 0xFF1C3047.toInt()
            val back = org.jetbrains.skia.PathBuilder().apply {
                moveTo(0f, 185f)
                lineTo(66f, 118f)
                lineTo(122f, 165f)
                lineTo(196f, 91f)
                lineTo(282f, 171f)
                lineTo(356f, 119f)
                lineTo(432f, 173f)
                lineTo(480f, 142f)
                lineTo(480f, 270f)
                lineTo(0f, 270f)
                closePath()
            }.snapshot()
            canvas.drawPath(back, paint)

            paint.color = 0xFF0E1926.toInt()
            val front = org.jetbrains.skia.PathBuilder().apply {
                moveTo(0f, 220f)
                lineTo(88f, 174f)
                lineTo(166f, 215f)
                lineTo(250f, 162f)
                lineTo(342f, 222f)
                lineTo(416f, 190f)
                lineTo(480f, 218f)
                lineTo(480f, 270f)
                lineTo(0f, 270f)
                closePath()
            }.snapshot()
            canvas.drawPath(front, paint)

            paint.color = 0xFF09111B.toInt()
            canvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(0f, 244f, width.toFloat(), 270f), paint)
        }
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
    return SeekPreviewFrame(image, 16f / 9f)
}

/** 选集：「动画」里的 12 集，ID 取自假服务端，网盘字幕面板与文件操作才找得到文件。 */
private fun playlist(env: ShotEnv): List<PlaylistEntry> {
    return (1..12).map { ep ->
        val name = "[SweetSub] Frieren - %02d [WebRip 1080p HEVC-10bit AAC][CHS_JPN].mkv".format(ep)
        PlaylistEntry(fileId = env.server.node(name).id, name = name, label = "%02d".format(ep), sectionKey = "main", sectionLabel = "正片")
    }
}
