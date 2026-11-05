package com.muimex11.official

import org.junit.Assert.*
import org.junit.Test

class GraphicsApiTest {
    @Test
    fun propValueMapping() {
        assertEquals("skiavk", GraphicsApi.propValueFor(GraphicsApi.VULKAN))
        assertEquals("opengl", GraphicsApi.propValueFor(GraphicsApi.OPENGL))
        assertNull(GraphicsApi.propValueFor("DirectX"))
    }

    @Test
    fun parsesGetprop() {
        assertEquals(GraphicsApi.VULKAN, GraphicsApi.parseRendererProp("skiavk"))
        assertEquals(GraphicsApi.VULKAN, GraphicsApi.parseRendererProp("  SKIAVK\n"))
        assertEquals(GraphicsApi.OPENGL, GraphicsApi.parseRendererProp(""))
        assertEquals(GraphicsApi.OPENGL, GraphicsApi.parseRendererProp("Success"))
        assertEquals(GraphicsApi.OPENGL, GraphicsApi.parseRendererProp("opengl"))
        assertEquals(GraphicsApi.OPENGL, GraphicsApi.parseRendererProp("some-other-value"))
        assertEquals(GraphicsApi.UNKNOWN, GraphicsApi.parseRendererProp("Error: Shizuku not available"))
        assertEquals(GraphicsApi.OPENGL, GraphicsApi.parseRendererProp(null)) // absent = Android default
    }

    @Test
    fun rebootDetectionBootStamp() {
        // Same boot, small drift -> not rebooted.
        assertEquals(
            false,
            GraphicsApi.wasRebootedInternal(
                storedBootTimeMs = 1_000_000L, currentBootTimeMs = 1_000_100L,
                storedUptimeMs = 50_000L, currentUptimeMs = 60_000L,
                storedSwitchWallClockMs = 999_000L, nowWallClockMs = 1_060_000L
            )
        )
        // Clearly different boot -> rebooted.
        assertEquals(
            true,
            GraphicsApi.wasRebootedInternal(
                storedBootTimeMs = 1_000_000L, currentBootTimeMs = 1_000_000L + 10 * 60_000L,
                storedUptimeMs = 50_000L, currentUptimeMs = 60_000L,
                storedSwitchWallClockMs = 999_000L, nowWallClockMs = 1_060_000L
            )
        )
    }

    @Test
    fun rebootDetectionLegacyUptime() {
        // Current uptime smaller than at switch time -> rebooted.
        assertEquals(
            true,
            GraphicsApi.wasRebootedInternal(
                storedBootTimeMs = 0L, currentBootTimeMs = 2_000_000L,
                storedUptimeMs = 100_000L, currentUptimeMs = 10_000L,
                storedSwitchWallClockMs = 0L, nowWallClockMs = 2_000_000L
            )
        )
        assertEquals(
            false,
            GraphicsApi.wasRebootedInternal(
                storedBootTimeMs = 0L, currentBootTimeMs = 2_000_000L,
                storedUptimeMs = 10_000L, currentUptimeMs = 100_000L,
                storedSwitchWallClockMs = 0L, nowWallClockMs = 2_000_000L
            )
        )
    }

    @Test
    fun rebootDetectionNoDataIsNull() {
        assertNull(
            GraphicsApi.wasRebootedInternal(0L, 2_000_000L, 0L, 5_000L, 0L, 2_000_000L)
        )
    }
}
