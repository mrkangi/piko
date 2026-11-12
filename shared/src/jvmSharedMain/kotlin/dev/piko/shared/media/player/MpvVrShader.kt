package dev.piko.shared.media.player

import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 播放器内置的 360°/180° 反投影着色器。
 *
 * 挂在 `MAINPRESUB`：在字幕叠加之前把球面/鱼眼画面按当前视角重新采样，字幕仍然平铺在屏幕上，
 * 跟着视角一起转反而看不清。采样发生在 `vo=gpu` 的着色器里，硬解出的纹理由 GPU 直接读，
 * 既不经过 CPU 的 `v360` 滤镜，也不打断零拷贝硬解；改视角只更新参数纹理，mpv 会重绘当前帧，
 * 暂停时拖动也立刻生效。
 *
 * ### 两套传参方式
 * 拖动视角时每帧都会更新参数，如果每次都改写着色器文件，mpv 会重新读盘、重新编译整份 GLSL，
 * 手指还没抬起来就卡住了。因此按当前视频输出分别处理：
 *
 * - **`vo=gpu-next`（Android 进入 VR 时启用）**：用 `//!PARAM` 声明视角参数，每次只把新的
 *   键值串写进 `glsl-shader-opts`。着色器源码与文件路径都不变，libplacebo 把参数当 uniform
 *   更新，只重绘当前帧，不重编译。
 * - **`vo=gpu` / `vo=libmpv`（回退路径与桌面端 MediaMP）**：这两条传统渲染路径只认
 *   `user_shaders.c` 的指令，遇到 `//!PARAM` 会直接丢弃整份着色器。这里改用
 *   `//!TEXTURE PIKO_VR_PARAMS`（`4x1 rgba8`）把 7 个参数按 16-bit 定点编码进 16 字节纹理，
 *   再按路径轮转写出序号文件。它能用，但每次更新仍要重新读文件，不适合高频拖动。
 *
 * 两条路径共用同一份反投影 GLSL：参数读取放在 `#ifdef PIKO_VR_UNIFORM` 里，
 * 固定预处理器开关由 [paramShaderSourceOf] 打开。
 *
 * `video/out/gpu/video.c` 与 `vo_gpu_next.c` 都会按路径字符串缓存已读过的着色器文本，
 * 重复传入同一路径不会重新读盘；因此 [MpvVrShaderSession] 只在投影/尺寸真正变化时写新文件，
 * 参数更新不碰文件。
 */
object MpvVrShader {
    const val FILE_PREFIX = "piko_vr_"
    const val FILE_SUFFIX = ".glsl"

    private const val MIN_FOV_RAD = 0.1f
    private const val MAX_FOV_RAD = 3.0f
    private const val MIN_YAW_RAD = -6.2831855f
    private const val MAX_YAW_RAD = 6.2831855f
    private const val MIN_PITCH_RAD = -1.5707964f
    private const val MAX_PITCH_RAD = 1.5707964f
    private const val MIN_ROLL_RAD = -3.1415927f
    private const val MAX_ROLL_RAD = 3.1415927f
    private const val MIN_FISHEYE_RAD = 1.5707964f
    private const val MAX_FISHEYE_RAD = 3.6651914f

    /**
     * 把 [mode] 编码成 `4x1 rgba8` 纹理的 32 位十六进制串（16 字节）：
     * - Texel 0 (`r,g` = fov, `b,a` = yaw)
     * - Texel 1 (`r,g` = pitch, `b,a` = roll)
     * - Texel 2 (`r,g` = fisheye_fov, `b` = input_projection, `a` = eye)
     * - Texel 3 (预留全 0)
     */
    fun encodeParamsHex(mode: VrModeState): String {
        val state = mode.normalized()
        val fovU16 = packU16(state.fovDegrees.toRadians(), MIN_FOV_RAD, MAX_FOV_RAD)
        val yawU16 = packU16(state.yawRadians, MIN_YAW_RAD, MAX_YAW_RAD)
        val pitchU16 = packU16(state.pitchRadians, MIN_PITCH_RAD, MAX_PITCH_RAD)
        val rollU16 = packU16(state.rollRadians, MIN_ROLL_RAD, MAX_ROLL_RAD)
        val fisheyeU16 = packU16(state.fisheyeFovDegrees.toRadians(), MIN_FISHEYE_RAD, MAX_FISHEYE_RAD)
        val bytes = byteArrayOf(
            (fovU16 ushr 8).toByte(),
            fovU16.toByte(),
            (yawU16 ushr 8).toByte(),
            yawU16.toByte(),
            (pitchU16 ushr 8).toByte(),
            pitchU16.toByte(),
            (rollU16 ushr 8).toByte(),
            rollU16.toByte(),
            (fisheyeU16 ushr 8).toByte(),
            fisheyeU16.toByte(),
            state.projection.shaderValue.toByte(),
            state.eye.shaderValue.toByte(),
            0, 0, 0, 0,
        )
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 生成完整的着色器文件内容：顶部 `//!TEXTURE` 随视角变化，下方 `//!HOOK` 源码恒定不变。
     *
     * [passWidth]/[passHeight] 非空时给 `MAINPRESUB` 指定渲染尺寸。该 hook 在放大之前执行，
     * 不给尺寸就是按源分辨率跑：8K 片源等于每帧处理三千多万像素，再缩回屏幕。
     * 按屏幕的 1.25 倍渲染即可，画质几乎无损，像素量少一个数量级。
     *
     * 这是给 `vo=gpu` / `vo=libmpv` 的回退版本；Android 的正常路径见 [paramShaderSourceOf]。
     */
    fun shaderSourceOf(mode: VrModeState, passWidth: Int? = null, passHeight: Int? = null): String =
        buildString(hookBody.length + 256) {
            append("//!TEXTURE PIKO_VR_PARAMS\n")
            append("//!SIZE 4 1\n")
            append("//!FORMAT rgba8\n")
            append("//!FILTER NEAREST\n")
            append("//!BORDER CLAMP\n")
            append(encodeParamsHex(mode)).append("\n\n")
            append(hookSource(uniformParams = false, passWidth = passWidth, passHeight = passHeight))
        }

    /**
     * 生成 `vo=gpu-next` 用的着色器：源码只与 pass 尺寸有关，视角参数由 [shaderOptsOf] 下发。
     *
     * 同一份源码可以一直复用，拖动视角不需要写文件、也不需要重新编译。
     */
    fun paramShaderSourceOf(passWidth: Int? = null, passHeight: Int? = null): String =
        buildString(paramDeclarations.length + hookBody.length + 256) {
            append(paramDeclarations)
            append(hookSource(uniformParams = true, passWidth = passWidth, passHeight = passHeight))
        }

    /** 把当前视角编码成 `glsl-shader-opts` 的键值串。只在 `vo=gpu-next` 下有实际作用。 */
    fun shaderOptsOf(mode: VrModeState): String {
        val state = mode.normalized()
        fun Float.opt(): String = String.format(Locale.ROOT, "%.6f", this)
        return listOf(
            "piko_fov=${state.fovDegrees.toRadians().opt()}",
            "piko_yaw=${state.yawRadians.opt()}",
            "piko_pitch=${state.pitchRadians.opt()}",
            "piko_roll=${state.rollRadians.opt()}",
            "piko_fisheye_fov=${state.fisheyeFovDegrees.toRadians().opt()}",
            "piko_projection=${state.projection.shaderValue}",
            "piko_eye=${state.eye.shaderValue}",
        ).joinToString(",")
    }

    /**
     * `//!PARAM` 必须写在整份文件的开头。初始值与 [VrModeState] 的默认值一致：
     * 即使后端还没下发 `glsl-shader-opts`，第一帧也是正常的 180° 鱼眼视角。
     */
    private val paramDeclarations: String = """
        //!PARAM piko_fov
        //!DESC Horizontal field of view in radians
        //!TYPE float
        //!MINIMUM 0.1
        //!MAXIMUM 3.0
        1.832596

        //!PARAM piko_yaw
        //!DESC Camera yaw in radians
        //!TYPE float
        //!MINIMUM -6.2831855
        //!MAXIMUM 6.2831855
        0.0

        //!PARAM piko_pitch
        //!DESC Camera pitch in radians
        //!TYPE float
        //!MINIMUM -1.5707964
        //!MAXIMUM 1.5707964
        0.0

        //!PARAM piko_roll
        //!DESC Camera roll in radians
        //!TYPE float
        //!MINIMUM -3.1415927
        //!MAXIMUM 3.1415927
        0.0

        //!PARAM piko_fisheye_fov
        //!DESC Fisheye lens field of view in radians
        //!TYPE float
        //!MINIMUM 1.5707964
        //!MAXIMUM 3.6651914
        3.1415927

        //!PARAM piko_projection
        //!DESC Input projection (0=equirect, 1=dual fisheye, 2=dual half equirect, 3=half equirect, 4=dual vert equirect)
        //!TYPE int
        1

        //!PARAM piko_eye
        //!DESC Source eye (0=left, 1=right, 2=both)
        //!TYPE int
        0

    """.trimIndent() + "\n\n"

    private fun hookSource(uniformParams: Boolean, passWidth: Int?, passHeight: Int?): String =
        buildString(hookBody.length + 256) {
            append("//!HOOK MAINPRESUB\n")
            append("//!BIND HOOKED\n")
            if (!uniformParams) append("//!BIND PIKO_VR_PARAMS\n")
            append("//!DESC Piko VR projection\n")
            if (passWidth != null && passHeight != null && passWidth > 0 && passHeight > 0) {
                append("//!WIDTH $passWidth\n")
                append("//!HEIGHT $passHeight\n")
            }
            if (uniformParams) append("#define PIKO_VR_UNIFORM 1\n")
            append(hookBody)
        }

    private val VrProjection.shaderValue: Int
        get() = when (this) {
            VrProjection.Equirectangular360 -> 0
            VrProjection.DualFisheye180 -> 1
            VrProjection.DualHalfEquirectangular180 -> 2
            VrProjection.HalfEquirectangular180 -> 3
            VrProjection.DualVertEquirectangular360 -> 4
        }

    private val VrEye.shaderValue: Int
        get() = when (this) {
            VrEye.Left -> 0
            VrEye.Right -> 1
            VrEye.Both -> 2
        }

    private fun Float.toRadians(): Float = (this * Math.PI / 180.0).toFloat()

    private fun packU16(value: Float, min: Float, max: Float): Int {
        val normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        return (normalized * 65535f).roundToInt().coerceIn(0, 65535)
    }

    private val hookBody: String = """
        #define PIKO_PI 3.1415926535897932
        #define PROJ_EQUIRECT 0
        #define PROJ_DUAL_FISHEYE 1
        #define PROJ_DUAL_HALF_EQUIRECT 2
        #define PROJ_HALF_EQUIRECT 3
        #define PROJ_DUAL_VERT_EQUIRECT 4
        #define EYE_LEFT 0
        #define EYE_RIGHT 1
        #define EYE_BOTH 2

        float decode_u16(float hi, float lo, float min_v, float max_v) {
            float u = floor(hi * 255.0 + 0.5) * 256.0 + floor(lo * 255.0 + 0.5);
            return min_v + (u / 65535.0) * (max_v - min_v);
        }

        bool is_stereo(int input_projection) {
            return input_projection == PROJ_DUAL_FISHEYE ||
                   input_projection == PROJ_DUAL_HALF_EQUIRECT ||
                   input_projection == PROJ_DUAL_VERT_EQUIRECT;
        }

        vec2 sample_equirectangular(vec3 dir) {
            float lon = atan(dir.x, dir.z);
            float lat = asin(clamp(dir.y, -1.0, 1.0));
            return vec2((lon + PIKO_PI) / (2.0 * PIKO_PI), (lat + PIKO_PI * 0.5) / PIKO_PI);
        }

        vec2 sample_half_equirectangular(vec3 dir) {
            if (dir.z < 0.0) return vec2(-1000.0);
            float lon = atan(dir.x, dir.z);
            float lat = asin(clamp(dir.y, -1.0, 1.0));
            return vec2((lon + PIKO_PI * 0.5) / PIKO_PI, (lat + PIKO_PI * 0.5) / PIKO_PI);
        }

        vec2 sample_dual_half_equirectangular(vec3 dir, int source_eye) {
            if (dir.z < 0.0) return vec2(-1000.0);
            float lon = atan(dir.x, dir.z);
            float lat = asin(clamp(dir.y, -1.0, 1.0));
            float u = (lon + PIKO_PI * 0.5) / (2.0 * PIKO_PI);
            float v = (lat + PIKO_PI * 0.5) / PIKO_PI;
            bool left_eye = source_eye == EYE_LEFT;
            float inset = 0.5 / HOOKED_size.x;
            u += left_eye ? 0.0 : 0.5;
            u = clamp(u, left_eye ? inset : 0.5 + inset, left_eye ? 0.5 - inset : 1.0 - inset);
            v = clamp(v, 0.5 / HOOKED_size.y, 1.0 - 0.5 / HOOKED_size.y);
            return vec2(u, v);
        }

        vec2 sample_dual_vert_equirectangular(vec3 dir, int source_eye) {
            float lon = atan(dir.x, dir.z);
            float lat = asin(clamp(dir.y, -1.0, 1.0));
            float u = (lon + PIKO_PI) / (2.0 * PIKO_PI);
            float v = (lat + PIKO_PI * 0.5) / PIKO_PI * 0.5;
            bool left_eye = source_eye == EYE_LEFT;
            float inset = 0.5 / HOOKED_size.y;
            v += left_eye ? 0.0 : 0.5;
            v = clamp(v, left_eye ? inset : 0.5 + inset, left_eye ? 0.5 - inset : 1.0 - inset);
            return vec2(u, v);
        }

        vec2 sample_dual_fisheye(vec3 dir, int source_eye, float fisheye_fov) {
            vec3 d = normalize(dir);
            float theta = acos(clamp(d.z, -1.0, 1.0));
            float phi = atan(d.y, d.x);
            float r = theta / (fisheye_fov * 0.5);
            if (r > 1.0) return vec2(-1000.0);
            vec2 pos = vec2(cos(phi), sin(phi)) * r;
            float eye_offset = (source_eye == EYE_LEFT) ? 0.0 : 0.5;
            return vec2(eye_offset + 0.25 + pos.x * 0.25, 0.5 + pos.y * 0.5);
        }

        vec4 render(
            vec2 uv,
            int source_eye,
            int eye_mode,
            float fov,
            float yaw,
            float pitch,
            float roll,
            float fisheye_fov,
            int input_projection
        ) {
            float aspect = target_size.x / max(target_size.y, 1.0);
            if (eye_mode == EYE_BOTH && is_stereo(input_projection))
                aspect *= 0.5;

            float fov_scale_x = tan(fov * 0.5);
            float fov_scale_y = fov_scale_x / aspect;
            vec3 view_dir = normalize(vec3(uv * vec2(fov_scale_x, fov_scale_y), 1.0));

            float cy = cos(yaw), sy = sin(yaw);
            float cp = cos(pitch), sp = sin(pitch);
            float cr = cos(roll), sr = sin(roll);
            mat3 rot_yaw = mat3(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy);
            mat3 rot_pitch = mat3(1.0, 0.0, 0.0, 0.0, cp, sp, 0.0, -sp, cp);
            mat3 rot_roll = mat3(cr, sr, 0.0, -sr, cr, 0.0, 0.0, 0.0, 1.0);
            vec3 dir = rot_yaw * rot_pitch * rot_roll * view_dir;

            vec2 coord;
            if (input_projection == PROJ_DUAL_FISHEYE)
                coord = sample_dual_fisheye(dir, source_eye, fisheye_fov);
            else if (input_projection == PROJ_DUAL_HALF_EQUIRECT)
                coord = sample_dual_half_equirectangular(dir, source_eye);
            else if (input_projection == PROJ_DUAL_VERT_EQUIRECT)
                coord = sample_dual_vert_equirectangular(dir, source_eye);
            else if (input_projection == PROJ_HALF_EQUIRECT)
                coord = sample_half_equirectangular(dir);
            else
                coord = sample_equirectangular(dir);

            if (coord.x < -999.0) return vec4(0.0, 0.0, 0.0, 1.0);
            return HOOKED_tex(coord);
        }

        vec4 hook() {
        #ifdef PIKO_VR_UNIFORM
            float fov = piko_fov;
            float yaw = piko_yaw;
            float pitch = piko_pitch;
            float roll = piko_roll;
            float fisheye_fov = piko_fisheye_fov;
            int input_projection = piko_projection;
            int eye = piko_eye;
        #else
            vec4 t0 = texture(PIKO_VR_PARAMS, vec2(0.125, 0.5));
            vec4 t1 = texture(PIKO_VR_PARAMS, vec2(0.375, 0.5));
            vec4 t2 = texture(PIKO_VR_PARAMS, vec2(0.625, 0.5));
            float fov = decode_u16(t0.r, t0.g, 0.1, 3.0);
            float yaw = decode_u16(t0.b, t0.a, -6.2831855, 6.2831855);
            float pitch = decode_u16(t1.r, t1.g, -1.5707964, 1.5707964);
            float roll = decode_u16(t1.b, t1.a, -3.1415927, 3.1415927);
            float fisheye_fov = decode_u16(t2.r, t2.g, 1.5707964, 3.6651914);
            int input_projection = int(floor(t2.b * 255.0 + 0.5));
            int eye = int(floor(t2.a * 255.0 + 0.5));
        #endif

            vec2 uv = HOOKED_pos;
            if (eye == EYE_BOTH && is_stereo(input_projection)) {
                int source_eye = (uv.x < 0.5) ? EYE_LEFT : EYE_RIGHT;
                if (source_eye == EYE_RIGHT) uv.x -= 0.5;
                uv.x *= 2.0;
                return render(uv * 2.0 - 1.0, source_eye, eye, fov, yaw, pitch, roll, fisheye_fov, input_projection);
            }
            return render(uv * 2.0 - 1.0, eye, eye, fov, yaw, pitch, roll, fisheye_fov, input_projection);
        }
    """.trimIndent()
}

/**
 * 管理单个播放器实例的 VR 着色器文件轮转。
 *
 * `vo=gpu`（及 `vo=libmpv`）在 `gl_video_setup_hooks` 里按文件路径字符串缓存已读过的着色器内容；
 * 同一路径写新内容不会触发重读。这里每次更新生成新的序号文件名，保留最近 [RETAINED_FILES] 份防止
 * 渲染线程异步读取时文件已被删除，其余旧文件即时清理。
 */
class MpvVrShaderSession(private val dir: File) {
    private val recentFiles = ArrayDeque<File>(RETAINED_FILES + 1)
    private var sequence = 0L
    private var paramFile: File? = null
    private var paramWidth: Int? = null
    private var paramHeight: Int? = null

    val isAvailable: Boolean = runCatching {
        if (!dir.exists()) dir.mkdirs()
        dir.isDirectory && dir.canWrite()
    }.getOrDefault(false)

    init {
        cleanupStaleFiles()
    }

    /** 写出当前 [mode] 对应的着色器文件并返回绝对路径；写失败返回 null。 */
    @Synchronized
    fun writeNext(mode: VrModeState, passWidth: Int? = null, passHeight: Int? = null): String? {
        if (!isAvailable) return null
        // 从 gpu-next 的动态参数路径切回纹理路径：之后不能再复用之前的参数文件
        paramFile = null
        val nextSeq = ++sequence
        val file = File(dir, "${MpvVrShader.FILE_PREFIX}${nextSeq}${MpvVrShader.FILE_SUFFIX}")
        runCatching { file.writeText(MpvVrShader.shaderSourceOf(mode, passWidth, passHeight)) }.onFailure { return null }
        recentFiles.addLast(file)
        while (recentFiles.size > RETAINED_FILES) {
            runCatching { recentFiles.removeFirst().delete() }
        }
        return file.absolutePath
    }

    /**
     * 写出 `vo=gpu-next` 用的参数化着色器并返回路径；同一 pass 尺寸复用同一个文件。
     *
     * 只有进入 VR、旋转屏幕或改窗口尺寸时才会走到这里；拖动视角只更新 `glsl-shader-opts`。
     */
    @Synchronized
    fun writeParamShader(passWidth: Int? = null, passHeight: Int? = null): String? {
        if (!isAvailable) return null
        val existing = paramFile
        if (existing != null && existing.isFile && paramWidth == passWidth && paramHeight == passHeight) {
            return existing.absolutePath
        }
        val nextSeq = ++sequence
        val file = File(dir, "${MpvVrShader.FILE_PREFIX}${nextSeq}${MpvVrShader.FILE_SUFFIX}")
        runCatching { file.writeText(MpvVrShader.paramShaderSourceOf(passWidth, passHeight)) }.onFailure { return null }
        recentFiles.addLast(file)
        while (recentFiles.size > RETAINED_FILES) {
            runCatching { recentFiles.removeFirst().delete() }
        }
        paramFile = file
        paramWidth = passWidth
        paramHeight = passHeight
        return file.absolutePath
    }

    /** 退出 VR 或释放播放器时删除所有临时着色器文件。 */
    @Synchronized
    fun clear() {
        while (recentFiles.isNotEmpty()) {
            runCatching { recentFiles.removeFirst().delete() }
        }
        paramFile = null
        paramWidth = null
        paramHeight = null
    }

    private fun cleanupStaleFiles() {
        runCatching {
            dir.listFiles { f ->
                f.isFile && (
                    f.name == "piko_vr.glsl" ||
                        (f.name.startsWith(MpvVrShader.FILE_PREFIX) && f.name.endsWith(MpvVrShader.FILE_SUFFIX))
                    )
            }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val RETAINED_FILES = 4
    }
}
