package dev.piko.shared.media.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VR 视角的限幅、拖动、缩放与文件名猜测。命令串的格式由 mpv 的键值解析决定，改动时这里会先失败。 */
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
    fun shaderOptionsCarryEveryParameter() {
        val options = MpvVrShader.optionsOf(
            VrModeState(
                enabled = true,
                projection = VrProjection.DualHalfEquirectangular180,
                eye = VrEye.Right,
                yawRadians = 0.5f,
                pitchRadians = -0.25f,
                fovDegrees = 90f,
                fisheyeFovDegrees = 190f,
            ),
        )
        val values = options.split(',').associate { entry ->
            val (key, value) = entry.split('=')
            key.removePrefix("piko_vr/") to value
        }
        assertEquals(7, values.size)
        assertEquals("2", values["input_projection"])
        assertEquals("1", values["eye"])
        assertEquals("0.500000", values["yaw"])
        assertEquals("-0.250000", values["pitch"])
        assertEquals("1.570796", values["fov"])
        assertEquals("3.316126", values["fisheye_fov"])
    }
}
