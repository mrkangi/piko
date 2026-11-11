package dev.piko.shared.media.player

import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/**
 * 播放后端的最小接口：共用的 [PlayerScreenState] 只通过它控制播放器。
 *
 * Android 由 libmpv 适配层实现，Desktop 由 MediaMP 的 mpv 后端实现。控件与画面表面
 * 仍由各端自己接，这里只管取流策略需要的读数与命令。
 *
 * 读数属性要由 Compose 的 State 支撑，共用层和视图层直接读，读取粒度与重组范围才对得上。
 */
interface PlaybackBackend {
    val positionMillis: Long

    /** 后端还不知道时长时为 0。 */
    val durationMillis: Long

    /** 已缓冲区间的终点（绝对时间），不支持时为 0。 */
    val bufferedPositionMillis: Long

    /** 意图中的播放态，即 playWhenReady：缓冲中也算在播。 */
    val isPlaying: Boolean

    /** 在加载或缓冲，画面暂时不会前进。 */
    val isBuffering: Boolean

    val speed: Float

    /** 为 false 时控件隐藏倍速入口。 */
    val supportsSpeed: Boolean

    /** 当前画面比例模式，后端不支持时为 null。 */
    val aspectRatio: PlayerAspectRatio?

    /** 显示宽高比（已计入旋转），画面参数未知时为 null。 */
    val videoAspect: Float?

    /**
     * 播放器自身的软件音量，0 到 1，1 是原始响度（mpv 的 100）。不放大：超过 100 会削波。
     * 后端不支持时为 null。Android 的音量手势调的是系统媒体音量，不走这里；桌面没有那一路，只能调它。
     */
    val volume: Float?

    /** 一次性事件。错误与播放结束不能用状态表达：重连期间同一个错误会被反复读到。 */
    val events: Flow<PlaybackBackendEvent>

    /** 当前文件的音轨，含外挂的。换文件后清空，重新读到之前为空。 */
    val audioTracks: List<MediaTrack> get() = emptyList()

    /** 当前文件的字幕轨，内封与外挂都在里面。 */
    val subtitleTracks: List<MediaTrack> get() = emptyList()

    val selectedAudioTrackId: String? get() = null

    /** 为 null 表示字幕关闭。 */
    val selectedSubtitleTrackId: String? get() = null

    /**
     * [subtitles] 是随文件一起加载的外挂字幕，加载后出现在 [subtitleTracks] 里。
     * 选哪条由后端按语言偏好决定，调用方有偏好时在 Ready 之后再调 [selectSubtitleTrack]。
     */
    suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean = true,
        subtitles: List<ExternalSubtitle> = emptyList(),
    )

    fun selectAudioTrack(id: String) = Unit

    /** [id] 为 null 时关闭字幕。 */
    fun selectSubtitleTrack(id: String?) = Unit

    /** 为 false 时控件不给手动挂字幕的入口。 */
    val canAddSubtitle: Boolean get() = false

    /**
     * 给正在播的文件再挂一条外挂字幕并选中它。文件还没加载好时推迟到加载之后，
     * 与 [open] 带的那几条一样；换文件后不再保留，要留由调用方下次 open 时再带上。
     */
    fun addSubtitle(subtitle: ExternalSubtitle) = Unit

    /** 为 false 时控件不给旋转入口。 */
    val supportsRotation: Boolean get() = false

    /**
     * 画面顺时针旋转 [degrees] 度（0、90、180、270），叠加在文件自带的旋转元数据之上，换文件后仍然生效。
     * [videoAspect] 随之按旋转后的画面更新。
     */
    fun setRotation(degrees: Int) = Unit

    /** 自定义画面裁剪选区（归一化坐标 0..1），仅在 [PlayerAspectRatio.RegionCrop] 下生效。 */
    val cropRegion: VideoCropRegion? get() = null
    fun setCropRegion(region: VideoCropRegion?) = Unit

    /**
     * 读一个 mpv 属性的字符串值，播放详细信息面板用，见 readPlaybackStats。两端都是 libmpv，属性名通用；
     * 读不到（还没加载、后端不是 mpv、已释放）时为 null。
     */
    fun mpvProperty(name: String): String? = null

    /**
     * 按当前这一档视频的平均字节速率调整预读预算，开播前调用一次。
     * 高码率需要更深的缓冲才能扛住取流抖动；不支持的平台忽略。
     */
    fun tuneBuffering(mediaBytesPerSecond: Long?) = Unit

    /**
     * 改用兼容硬解：仍由硬件解码，但把帧拷回内存由播放器排帧（Android 为 `mediacodec-copy`）。
     * 零拷贝输出在部分机型/码流上出帧节奏不匀，重新 [open] 后生效；不支持的后端忽略。
     */
    fun preferCompatibleDecoding() = Unit

    /** 当前解码方式。设置页只提供默认值，播放页可以按文件临时改，见 [setDecodeMode]。 */
    val decodeMode: VideoDecodeMode get() = VideoDecodeMode.Auto

    /** 播放中可以切换的解码方式；空表示这个后端不支持，播放页不显示入口。 */
    val decodeModeOptions: List<VideoDecodeMode> get() = emptyList()

    /** 切换解码方式，下一次 [open] 生效。不支持的后端忽略。 */
    fun setDecodeMode(mode: VideoDecodeMode) = Unit

    /** 为 true 时播放页给出 VR 视角入口，见 [VrModeState]。 */
    val supportsVrMode: Boolean get() = false

    /** 当前 VR 视角。不支持的后端恒为关闭。 */
    val vrMode: VrModeState get() = VrModeState()

    /**
     * 应用 VR 视角。开启时在 mpv 上挂 360°/180° 反投影着色器，视角只改着色器参数，
     * 不动解码与画面队列；关闭时卸载着色器并把画面比例还原成当前所选。
     */
    fun setVrMode(mode: VrModeState) = Unit

    fun stop()

    /**
     * [stop] 是否在独立播放线程上异步卸载输入（如 libmpv）。
     * 为 true 时，非挂起的释放路径需在后台等待 [stopAndAwaitIdle] 排空后再关闭本机代理会话，
     * 防止 demuxer 尚未收到 stop 就读到代理断流并触发 1ms 后的重连 404。
     */
    val requiresAsyncStopDrain: Boolean get() = false

    /** 停止当前播放并等待后端释放正在读取的输入流。 */
    suspend fun stopAndAwaitIdle() {
        stop()
    }

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    fun setSpeed(speed: Float)

    fun setAspectRatio(mode: PlayerAspectRatio)

    fun setVolume(volume: Float)
}

/**
 * 一条音轨或字幕轨。[id] 是后端内部的编号，只在同一个文件内有效，换集后要按语言与标题重新找。
 * [title] 与 [language] 是容器里写的原文，可能为空，也可能是「jpn」这类代码；显示名见 [trackDisplayName]。
 */
data class MediaTrack(
    val id: String,
    val title: String?,
    val language: String?,
    val isExternal: Boolean = false,
)

/** 随视频一起加载的外挂字幕。[url] 是后端能直接读的地址，[title] 用来在列表里区分。 */
data class ExternalSubtitle(val url: String, val title: String, val language: String?)

sealed interface PlaybackTarget {
    /** 本机文件的绝对路径。 */
    data class LocalFile(val path: String) : PlaybackTarget

    data class Url(val url: String) : PlaybackTarget
}

sealed interface PlaybackBackendEvent {
    /** 本次 open 的第一帧已经出来。 */
    data object Ready : PlaybackBackendEvent

    data object Ended : PlaybackBackendEvent

    /** 当前文件被迫中止。[detail] 是后端给出的原因，可能是英文。 */
    data class Error(val detail: String?) : PlaybackBackendEvent
}

enum class PlayerAspectRatio { Fit, Crop, Stretch, RegionCrop }

/**
 * 视频解码方式。设置页里的是默认值，播放页可按单个视频临时改，改完重新打开当前文件。
 */
enum class VideoDecodeMode {
    /** 零拷贝硬件解码，性能最好，个别机型/码流出帧节奏不匀。 */
    Auto,

    /** 硬件解码后把帧拷回内存，由播放器排帧；比纯软件解码省电。 */
    Compatible,

    /** 完全软件解码，最稳但最费 CPU。 */
    Software,
}

/** 归一化（0f..1f）的画面区域选择框，用于区域裁剪铺满窗口。 */
data class VideoCropRegion(
    val left: Float = 0.1f,
    val top: Float = 0.1f,
    val right: Float = 0.9f,
    val bottom: Float = 0.9f,
    val containerAspect: Float = 0f,
) {
    private val safeLeft: Float get() = if (left.isFinite()) left.coerceIn(0f, 0.95f) else 0.1f
    private val safeTop: Float get() = if (top.isFinite()) top.coerceIn(0f, 0.95f) else 0.1f
    private val safeRight: Float get() = if (right.isFinite()) right.coerceIn(safeLeft + 0.05f, 1f) else 0.9f
    private val safeBottom: Float get() = if (bottom.isFinite()) bottom.coerceIn(safeTop + 0.05f, 1f) else 0.9f
    val safeContainerAspect: Float get() = if (containerAspect.isFinite() && containerAspect > 0f) containerAspect else 0f

    val width: Float get() = (safeRight - safeLeft).coerceIn(0.05f, 1f)
    val height: Float get() = (safeBottom - safeTop).coerceIn(0.05f, 1f)
    val centerX: Float get() = ((safeLeft + safeRight) * 0.5f).coerceIn(0f, 1f)
    val centerY: Float get() = ((safeTop + safeBottom) * 0.5f).coerceIn(0f, 1f)
}

/** VR 片源的投影格式。只列常见几种，够覆盖实拍 VR 与全景视频。 */
enum class VrProjection(val isStereo: Boolean) {
    /** 360° 单目等距柱状，最常见的全景视频。 */
    Equirectangular360(isStereo = false),

    /** 180° 左右双鱼眼，VR180 实拍最常见的格式：2:1 画面左右各一个圆。 */
    DualFisheye180(isStereo = true),

    /** 180° 左右半宽等距柱状，VR180 的另一种常见封装。 */
    DualHalfEquirectangular180(isStereo = true),

    /** 180° 单目等距柱状。 */
    HalfEquirectangular180(isStereo = false),

    /** 360° 上下双目等距柱状。 */
    DualVertEquirectangular360(isStereo = true),
}

/**
 * 双眼片源里取哪只眼看。手机与电脑都是平面屏幕，默认只取左眼，画面干净没有重影；
 * 双眼用于给 VR 盒子或 3D 显示器输出左右并排。
 */
enum class VrEye { Left, Right, Both }

/**
 * VR 视角：输入的投影格式、相机朝向与视野。
 *
 * 角度均为弧度，[fovDegrees] 与 [fisheyeFovDegrees] 用角度便于界面显示。所有取值都在
 * [normalized] 里限幅，模拟拖动时不会因为累加越界让画面翻面。
 */
data class VrModeState(
    val enabled: Boolean = false,
    val projection: VrProjection = VrProjection.DualFisheye180,
    val eye: VrEye = VrEye.Left,
    val yawRadians: Float = 0f,
    val pitchRadians: Float = 0f,
    val rollRadians: Float = 0f,
    val fovDegrees: Float = DEFAULT_FOV_DEGREES,
    val fisheyeFovDegrees: Float = DEFAULT_FISHEYE_FOV_DEGREES,
) {
    val isDefaultView: Boolean
        get() = abs(yawRadians) < VIEW_EPSILON && abs(pitchRadians) < VIEW_EPSILON &&
            abs(rollRadians) < VIEW_EPSILON && abs(fovDegrees - DEFAULT_FOV_DEGREES) < 0.5f

    fun normalized(): VrModeState = copy(
        yawRadians = normalizeAngle(yawRadians),
        pitchRadians = pitchRadians.coerceIn(-HALF_PI, HALF_PI),
        rollRadians = normalizeAngle(rollRadians),
        fovDegrees = fovDegrees.coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES),
        fisheyeFovDegrees = fisheyeFovDegrees.coerceIn(MIN_FISHEYE_FOV_DEGREES, MAX_FISHEYE_FOV_DEGREES),
    )

    /** 拖动画面转视角。正的水平位移看向左边，正的垂直位移看向上方，与抓住画面拖动的手感一致。 */
    fun panned(deltaYawRadians: Float, deltaPitchRadians: Float): VrModeState =
        copy(
            yawRadians = normalizeAngle(yawRadians + deltaYawRadians),
            pitchRadians = (pitchRadians + deltaPitchRadians).coerceIn(-HALF_PI, HALF_PI),
        )

    /** 缩放视野。[scale] 大于 1 是拉近。 */
    fun zoomed(scale: Float): VrModeState =
        if (!scale.isFinite() || scale <= 0f) this
        else copy(fovDegrees = (fovDegrees / scale).coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES))

    fun resetView(): VrModeState = copy(
        yawRadians = 0f,
        pitchRadians = 0f,
        rollRadians = 0f,
        fovDegrees = DEFAULT_FOV_DEGREES,
    )

    /**
     * 在播放页底栏一键开关 VR。开启时按文件名猜投影并回正视角，
     * 与设置面板里的开关走同一条路径，避免两处行为不一致。
     */
    fun toggled(title: String): VrModeState =
        if (enabled) {
            copy(enabled = false)
        } else {
            copy(enabled = true, projection = guessProjection(title)).resetView()
        }

    companion object {
        const val DEFAULT_FOV_DEGREES = 105f
        const val MIN_FOV_DEGREES = 40f
        const val MAX_FOV_DEGREES = 150f
        const val DEFAULT_FISHEYE_FOV_DEGREES = 180f
        const val MIN_FISHEYE_FOV_DEGREES = 150f
        const val MAX_FISHEYE_FOV_DEGREES = 210f

        private const val PI = 3.1415927f
        private const val HALF_PI = PI / 2f
        private const val TWO_PI = PI * 2f
        private const val VIEW_EPSILON = 0.005f

        private fun normalizeAngle(angle: Float): Float {
            if (!angle.isFinite()) return 0f
            var value = angle % TWO_PI
            if (value > PI) value -= TWO_PI
            if (value < -PI) value += TWO_PI
            return value
        }

        /**
         * 按文件名猜投影格式，省得用户每次都要先打开设置选一遍。猜不中改一下即可，
         * 这里只处理最常见的几种标记。
         */
        fun guessProjection(fileName: String): VrProjection {
            val name = fileName.lowercase()
            val fisheye = "fisheye" in name || "_fs" in name || "-fs" in name || "鱼眼" in name || "魚眼" in name
            val sideBySide = "sbs" in name || "side-by-side" in name || "sidebyside" in name ||
                "_lr" in name || "-lr" in name || "left-right" in name
            val topBottom = "_tb" in name || "-tb" in name || "top-bottom" in name ||
                "over-under" in name || "_ou" in name || "-ou" in name
            return when {
                "360" in name && (topBottom || "3d" in name) -> VrProjection.DualVertEquirectangular360
                "360" in name -> VrProjection.Equirectangular360
                fisheye -> VrProjection.DualFisheye180
                sideBySide -> VrProjection.DualHalfEquirectangular180
                "180" in name -> VrProjection.DualFisheye180
                else -> VrProjection.DualFisheye180
            }
        }
    }
}
