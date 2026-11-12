package dev.piko.shared.media.player

import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** VR 视角的限幅、拖动、缩放、文件名猜测，以及两条着色器传参路径。 */
class VrModeStateTest {
    @Test
    fun pitchStopsAtStraightUpAndDown() {
        val state = VrModeState().panned(0f, 10f)
        assertTrue(abs(state.pitchRadians - (PI / 2).toFloat()) < 1e-4f)
        assertEquals((PI / 2).toFloat(), state.panned(0f, 10f).pitchRadians, 1e-4f)
        assertEquals(-(PI / 2).toFloat(), state.panned(0f, -10f).pitchRadians, 1e-4f)
    }

    @Test
    fun yawWrapsAround() {
        val state = VrModeState(yawRadians = (PI * 1.8).toFloat()).normalized()
        assertTrue(state.yawRadians < 0f)
        assertTrue(abs(state.yawRadians + (PI * 0.2).toFloat()) < 1e-3f)
    }

    @Test
    fun zoomStaysInRange() {
        val close = VrModeState(fovDegrees = 100f).zoomed(100f)
        assertEquals(VrModeState.MIN_FOV_DEGREES, close.fovDegrees)
        val wide = VrModeState(fovDegrees = 100f).zoomed(0.0001f)
        assertEquals(VrModeState.MAX_FOV_DEGREES, wide.fovDegrees)
    }

    @Test
    fun resetViewClearsOrientation() {
        val moved = VrModeState().panned(1f, 0.5f).zoomed(2f)
        assertFalse(moved.isDefaultView)
        assertTrue(moved.resetView().isDefaultView)
    }

    @Test
    fun guessesCommonProjectionsFromName() {
        assertEquals(VrProjection.DualFisheye180, VrModeState.guessProjection("VR180_fisheye_x264.mp4"))
        assertEquals(VrProjection.DualHalfEquirectangular180, VrModeState.guessProjection("clip_180_sbs.mp4"))
        assertEquals(VrProjection.Equirectangular360, VrModeState.guessProjection("city_360.mp4"))
        assertEquals(VrProjection.DualVertEquirectangular360, VrModeState.guessProjection("show_360_tb.mp4"))
    }

    @Test
    fun togglingOnGuessesProjectionResetsViewAndTogglingOffKeepsIt() {
        val moved = VrModeState(
            projection = VrProjection.Equirectangular360,
            yawRadians = 0.8f,
            pitchRadians = 0.3f,
            fovDegrees = 60f,
        )
        val on = moved.toggled("clip_180_sbs.mp4")
        assertTrue(on.enabled)
        assertEquals(VrProjection.DualHalfEquirectangular180, on.projection)
        assertTrue(on.isDefaultView)

        val off = on.toggled("clip_180_sbs.mp4")
        assertFalse(off.enabled)
        assertEquals(on.projection, off.projection)
    }

    @Test
    fun clickingProjectionCyclesThroughAllFormatsAndKeepsTheView() {
        val start = VrModeState(
            enabled = true,
            projection = VrProjection.DualFisheye180,
            yawRadians = 0.4f,
            pitchRadians = -0.2f,
            fovDegrees = 80f,
        )
        val seen = mutableListOf(start.projection)
        var state = start
        repeat(VrProjection.entries.size) {
            state = state.nextProjection()
            seen += state.projection
        }
        // 一轮回到起点，且每种格式都出现了
        assertEquals(start.projection, state.projection)
        assertEquals(VrProjection.entries.toSet(), seen.toSet())
        // 只换投影，视角保持不动
        assertEquals(start.yawRadians, state.yawRadians)
        assertEquals(start.pitchRadians, state.pitchRadians)
        assertEquals(start.fovDegrees, state.fovDegrees)
    }

    @Test
    fun shaderEncodesParametersIntoRgba8TextureWithoutParamDirectives() {
        val mode = VrModeState(
            enabled = true,
            projection = VrProjection.DualHalfEquirectangular180,
            eye = VrEye.Right,
            yawRadians = 0.5f,
            pitchRadians = -0.25f,
            fovDegrees = 90f,
            fisheyeFovDegrees = 190f,
        )
        val hex = MpvVrShader.encodeParamsHex(mode)
        assertEquals(32, hex.length)
        val bytes = hex.chunked(2).map { it.toInt(16) }
        fun decodeU16(hi: Int, lo: Int, min: Float, max: Float): Float {
            val u = hi * 256 + lo
            return min + (u / 65535f) * (max - min)
        }
        assertEquals((90.0 * PI / 180.0).toFloat(), decodeU16(bytes[0], bytes[1], 0.1f, 3.0f), 1e-3f)
        assertEquals(0.5f, decodeU16(bytes[2], bytes[3], -6.2831855f, 6.2831855f), 1e-3f)
        assertEquals(-0.25f, decodeU16(bytes[4], bytes[5], -1.5707964f, 1.5707964f), 1e-3f)
        assertEquals(0f, decodeU16(bytes[6], bytes[7], -3.1415927f, 3.1415927f), 1e-3f)
        assertEquals((190.0 * PI / 180.0).toFloat(), decodeU16(bytes[8], bytes[9], 1.5707964f, 3.6651914f), 1e-3f)
        assertEquals(2, bytes[10])
        assertEquals(1, bytes[11])

        val source = MpvVrShader.shaderSourceOf(mode)
        // vo=gpu 与 vo=libmpv 不认识 //!PARAM，一旦混入就会整份着色器解析失败
        assertFalse("//!PARAM" in source)
        assertTrue("//!TEXTURE PIKO_VR_PARAMS" in source)
        assertTrue("//!HOOK MAINPRESUB" in source)
    }

    @Test
    fun shaderCanCapThePassSize() {
        val mode = VrModeState(enabled = true, projection = VrProjection.DualFisheye180)
        val capped = MpvVrShader.shaderSourceOf(mode, passWidth = 1600, passHeight = 900)
        assertTrue("//!WIDTH 1600" in capped)
        assertTrue("//!HEIGHT 900" in capped)

        val uncapped = MpvVrShader.shaderSourceOf(mode)
        assertFalse("//!WIDTH" in uncapped)
        assertFalse("//!HEIGHT" in uncapped)
    }

    @Test
    fun paramShaderUsesUniformDirectivesAndIgnoresTheView() {
        val source = MpvVrShader.paramShaderSourceOf(passWidth = 1600, passHeight = 900)
        // gpu-next 的 //!PARAM 必须在文件开头，且每个参数都带类型与默认值
        assertTrue(source.startsWith("//!PARAM piko_fov"))
        assertTrue("//!TYPE float" in source)
        assertTrue("//!TYPE int" in source)
        assertTrue("//!WIDTH 1600" in source)
        assertTrue("//!HEIGHT 900" in source)
        assertTrue("#define PIKO_VR_UNIFORM 1" in source)
        // 参数化版本不能再带纹理，否则每次更新参数还是要重写文件
        assertFalse("//!TEXTURE" in source)
        // 源码与视角无关：拖动只更新 glsl-shader-opts
        assertEquals(source, MpvVrShader.paramShaderSourceOf(passWidth = 1600, passHeight = 900))
    }

    @Test
    fun shaderOptsCarryTheWholeViewAsPlainDecimals() {
        val mode = VrModeState(
            enabled = true,
            projection = VrProjection.DualVertEquirectangular360,
            eye = VrEye.Right,
            yawRadians = 0.5f,
            pitchRadians = -0.25f,
            fovDegrees = 90f,
            fisheyeFovDegrees = 190f,
        )
        val opts = MpvVrShader.shaderOptsOf(mode)
        assertTrue("piko_fov=1.570796" in opts)
        assertTrue("piko_yaw=0.500000" in opts)
        assertTrue("piko_pitch=-0.250000" in opts)
        assertTrue("piko_fisheye_fov=3.316126" in opts)
        assertTrue("piko_projection=4" in opts)
        assertTrue("piko_eye=1" in opts)
        // 不能用科学计数法：mpv 的键值串解析按普通十进制读
        assertFalse("E-" in opts.uppercase())
    }

    @Test
    fun paramShaderSessionReusesTheSameFileForTheSamePassSize() {
        val dir = Files.createTempDirectory("piko-vr-param-test").toFile()
        try {
            val session = MpvVrShaderSession(dir)
            val first = session.writeParamShader(1600, 900)
            val again = session.writeParamShader(1600, 900)
            assertNotNull(first)
            assertEquals(first, again)
            val resized = session.writeParamShader(1280, 720)
            assertNotNull(resized)
            assertTrue(resized != first)
            // 参数文件与纹理文件不能互相复用
            val texture = session.writeNext(VrModeState(enabled = true))
            assertNotNull(texture)
            assertTrue(session.writeParamShader(1280, 720) != resized)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shaderSessionRotatesFilesAndCleansUpOldOnes() {
        val dir = Files.createTempDirectory("piko-vr-test").toFile()
        try {
            val session = MpvVrShaderSession(dir)
            assertTrue(session.isAvailable)
            val paths = (1..6).map { idx ->
                session.writeNext(VrModeState(enabled = true, yawRadians = idx * 0.1f))
            }
            assertTrue(paths.all { it != null })
            assertEquals(6, paths.toSet().size)
            val remaining = dir.listFiles()?.filter { it.name.startsWith(MpvVrShader.FILE_PREFIX) }.orEmpty()
            assertTrue(remaining.size <= 4)
            assertNotNull(paths.last()?.let { last -> remaining.find { it.absolutePath == last } })
            session.clear()
            assertEquals(0, dir.listFiles()?.count { it.name.startsWith(MpvVrShader.FILE_PREFIX) } ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
