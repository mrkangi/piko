package dev.piko.shared.media.player

import java.io.File

/**
 * 播放器内置的 360°/180° 反投影着色器。
 *
 * 挂在 `MAINPRESUB`：在字幕叠加之前把球面/鱼眼画面按当前视角重新采样，字幕仍然平铺在屏幕上，
 * 跟着视角一起转反而看不清。采样发生在 `vo=gpu` 的着色器里，硬解出的纹理由 GPU 直接读，
 * 既不经过 CPU 的 `v360` 滤镜，也不打断零拷贝硬解；改视角只是改 uniform，mpv 会重绘当前帧，
 * 暂停时拖动也立刻生效。
 *
 * 实现参考 kasper93/mpv360（MIT）。相对原版做了三处调整：旋转矩阵移到函数内计算（GLSL ES
 * 不允许用 uniform 初始化全局变量）、`asin` 入参限幅（浮点误差会让移动 GPU 出 NaN 黑点）、
 * 左右眼交界半像素内缩（避免双线性插值把相邻眼睛的像素混进来）。首版只保留双线性采样。
 */
object MpvVrShader {
    const val FILE_NAME = "piko_vr.glsl"

    /** `glsl-shader-opts` 里的键前缀，与文件名去掉扩展名一致。 */
    private const val KEY = "piko_vr"

    /** 把着色器写进 [dir]，内容没变就不重写。返回可交给 mpv 的路径。 */
    fun writeTo(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, FILE_NAME)
        if (!file.exists() || file.readText() != source) file.writeText(source)
        return file
    }

    /** 生成整串 `glsl-shader-opts`，一次属性写入代替逐条追加，避免键值列表越滚越长。 */
    fun optionsOf(mode: VrModeState): String {
        val state = mode.normalized()
        return buildString {
            append(KEY).append("/fov=").append(state.fovDegrees.toRadiansText())
            append(',').append(KEY).append("/yaw=").append(state.yawRadians.toShaderText())
            append(',').append(KEY).append("/pitch=").append(state.pitchRadians.toShaderText())
            append(',').append(KEY).append("/roll=").append(state.rollRadians.toShaderText())
            append(',').append(KEY).append("/fisheye_fov=").append(state.fisheyeFovDegrees.toRadiansText())
            append(',').append(KEY).append("/input_projection=").append(state.projection.shaderValue)
            append(',').append(KEY).append("/eye=").append(state.eye.shaderValue)
        }
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

    private fun Float.toRadiansText(): String = (this * Math.PI / 180.0).toShaderText()

    // 固定六位小数：每帧都在写，省掉 BigDecimal 的分配；mpv 的键值解析对长度没有要求
    private fun Double.toShaderText(): String = String.format(java.util.Locale.US, "%.6f", this)
    private fun Float.toShaderText(): String = toDouble().toShaderText()

    private val source: String = """
        //!PARAM fov
        //!TYPE float
        //!MINIMUM 0.1
        //!MAXIMUM 3.0
        1.832596

        //!PARAM yaw
        //!TYPE float
        //!MINIMUM -6.283185
        //!MAXIMUM 6.283185
        0.0

        //!PARAM pitch
        //!TYPE float
        //!MINIMUM -1.570796
        //!MAXIMUM 1.570796
        0.0

        //!PARAM roll
        //!TYPE float
        //!MINIMUM -3.141593
        //!MAXIMUM 3.141593
        0.0

        //!PARAM fisheye_fov
        //!TYPE float
        //!MINIMUM 1.570796
        //!MAXIMUM 3.665191
        3.141593

        //!PARAM input_projection
        //!TYPE ENUM int
        equirectangular
        dual_fisheye
        dual_half_equirectangular
        half_equirectangular
        dual_vert_equirectangular

        //!PARAM eye
        //!TYPE ENUM int
        left
        right
        both

        //!HOOK MAINPRESUB
        //!BIND HOOKED
        //!DESC Piko VR projection

        #define PIKO_PI 3.1415926535897932

        bool is_stereo() {
            return input_projection == dual_fisheye ||
                   input_projection == dual_half_equirectangular ||
                   input_projection == dual_vert_equirectangular;
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
            bool left_eye = source_eye == left;
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
            bool left_eye = source_eye == left;
            float inset = 0.5 / HOOKED_size.y;
            v += left_eye ? 0.0 : 0.5;
            v = clamp(v, left_eye ? inset : 0.5 + inset, left_eye ? 0.5 - inset : 1.0 - inset);
            return vec2(u, v);
        }

        vec2 sample_dual_fisheye(vec3 dir, int source_eye) {
            vec3 d = normalize(dir);
            float theta = acos(clamp(d.z, -1.0, 1.0));
            float phi = atan(d.y, d.x);
            float r = theta / (fisheye_fov * 0.5);
            if (r > 1.0) return vec2(-1000.0);
            vec2 pos = vec2(cos(phi), sin(phi)) * r;
            float eye_offset = (source_eye == left) ? 0.0 : 0.5;
            return vec2(eye_offset + 0.25 + pos.x * 0.25, 0.5 + pos.y * 0.5);
        }

        vec4 render(vec2 uv, int source_eye) {
            float aspect = target_size.x / target_size.y;
            if (source_eye == both && is_stereo())
                aspect *= 0.5;

            float fov_scale_x = tan(fov * 0.5);
            float fov_scale_y = fov_scale_x / aspect;
            vec3 view_dir = normalize(vec3(uv * vec2(fov_scale_x, fov_scale_y), 1.0));

            float cy = cos(yaw), sy = sin(yaw);
            float cp = cos(pitch), sp = sin(pitch);
            float cr = cos(roll), sr = sin(roll);
            mat3 rot_yaw = mat3(cy, 0.0, -sy, 0.0, 1.0, 0.0, sy, 0.0, cy);
            mat3 rot_pitch = mat3(1.0, 0.0, 0.0, 0.0, cp, sp, 0.0, -sp, cp);
            mat3 rot_roll = mat3(cr, sr, 0.0, -sr, cr, 0.0, 0.0, 0.0, 1.0);
            vec3 dir = rot_yaw * rot_pitch * rot_roll * view_dir;

            vec2 coord;
            if (input_projection == dual_fisheye)
                coord = sample_dual_fisheye(dir, source_eye);
            else if (input_projection == dual_half_equirectangular)
                coord = sample_dual_half_equirectangular(dir, source_eye);
            else if (input_projection == dual_vert_equirectangular)
                coord = sample_dual_vert_equirectangular(dir, source_eye);
            else if (input_projection == half_equirectangular)
                coord = sample_half_equirectangular(dir);
            else
                coord = sample_equirectangular(dir);

            if (coord.x < -999.0) return vec4(0.0, 0.0, 0.0, 1.0);
            return HOOKED_tex(coord);
        }

        vec4 hook() {
            vec2 uv = HOOKED_pos;
            if (eye == both && is_stereo()) {
                int source_eye = (uv.x < 0.5) ? left : right;
                if (source_eye == right) uv.x -= 0.5;
                uv.x *= 2.0;
                return render(uv * 2.0 - 1.0, source_eye);
            }
            return render(uv * 2.0 - 1.0, eye);
        }
    """.trimIndent()
}
