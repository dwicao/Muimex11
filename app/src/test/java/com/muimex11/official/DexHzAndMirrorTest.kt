package com.muimex11.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexHzLogicTest {

    @Test
    fun compatLevelClassification() {
        val full = DexHzLogic.Caps(
            highRes = true, monitorTiming = true, monitorTimingVerified = true,
            phoneRates = true, heat = true, codes = "0x10/0x11", root = false,
            monitorModes = true, modeMethod = "start", displayConfig = true
        )
        assertEquals(DexHzLogic.CompatLevel.FULL, full.level())

        val partial = full.copy(monitorTiming = false)
        assertEquals(DexHzLogic.CompatLevel.PARTIAL, partial.level())

        val limited = partial.copy(phoneRates = false)
        assertEquals(DexHzLogic.CompatLevel.LIMITED, limited.level())

        val unavail = limited.copy(highRes = false)
        assertEquals(DexHzLogic.CompatLevel.UNAVAILABLE, unavail.level())
    }

    @Test
    fun smoothDivisors() {
        assertTrue(DexHzLogic.smooth(240f, 240f))
        assertTrue(DexHzLogic.smooth(240f, 120f))
        assertTrue(DexHzLogic.smooth(240f, 80f))
        assertTrue(DexHzLogic.smooth(240f, 60f))
        assertTrue(DexHzLogic.smooth(240f, 48f))
        assertTrue(DexHzLogic.smooth(240f, 30f))
        assertTrue(DexHzLogic.smooth(240f, 24f))
        assertFalse(DexHzLogic.smooth(144f, 120f))
        assertTrue(DexHzLogic.smooth(144f, 72f))
    }

    @Test
    fun formatTargetStrings() {
        assertEquals("Fastest", DexHzLogic.formatTarget(0f))
        assertEquals("Fastest", DexHzLogic.formatTarget(1000f))
        assertEquals("120 Hz", DexHzLogic.formatTarget(120f))
    }

    @Test
    fun parseStateJson() {
        val raw = """
            {
              "running": true,
              "reconnecting": false,
              "targetHz": 1000,
              "appHz": 240,
              "uid": 2000,
              "device": "samsung SM-S928U",
              "stops": [24, 30, 48, 60, 80, 120, 240],
              "caps": {
                "highRes": true,
                "monitorTiming": true,
                "monitorTimingVerified": true,
                "phoneRates": true,
                "heat": true,
                "codes": "0x3ea/0x3eb",
                "root": false,
                "monitorModes": true,
                "modeMethod": "start",
                "displayConfig": true
              },
              "monitor": {
                "id": 46309467,
                "name": "Odyssey G40B",
                "width": 1920,
                "height": 1080,
                "linkHz": 240.0,
                "fastestHz": 240.0,
                "fasterListed": false,
                "modeKey": "1920:1080:240",
                "modeChoices": [
                  {"width": 1920, "height": 1080, "hz": 120}
                ]
              }
            }
        """.trimIndent()
        val state = DexHzLogic.parseState(raw)
        assertNotNull(state)
        assertTrue(state!!.running)
        assertEquals(240f, state.appHz, 0.01f)
        assertEquals(DexHzLogic.CompatLevel.FULL, state.caps?.level())
        assertEquals(listOf(24, 30, 48, 60, 80, 120, 240), state.stops)
        assertEquals("Odyssey G40B", state.monitor?.name)
        assertEquals(1, state.monitor?.modeChoices?.size)
        assertNull(DexHzLogic.parseState(""))
    }
}

class MirrorLogicTest {

    @Test
    fun parsesWmSizeAndDensity() {
        val sizeOut = """
            Physical size: 1080x2640
            Override size: 1080x1920
        """.trimIndent()
        val densityOut = """
            Physical density: 420
            Override density: 240
        """.trimIndent()
        val st = MirrorLogic.parseWmOutput(sizeOut, densityOut)
        assertEquals(1080, st.physicalWidth)
        assertEquals(2640, st.physicalHeight)
        assertEquals(1080, st.overrideWidth)
        assertEquals(1920, st.overrideHeight)
        assertEquals(420, st.physicalDensity)
        assertEquals(240, st.overrideDensity)
        assertTrue(st.is16x9Active)
        assertTrue(st.isOverrideApplied)
    }

    @Test
    fun nativeWhenNoOverride() {
        val st = MirrorLogic.parseWmOutput("Physical size: 1080x2400", "Physical density: 450")
        assertNull(st.overrideWidth)
        assertNull(st.overrideDensity)
        assertFalse(st.is16x9Active)
        assertFalse(st.isOverrideApplied)
        assertEquals(1080, st.currentWidth)
        assertEquals(450, st.currentDensity)
    }

    @Test
    fun ratioAndCommands() {
        assertTrue(MirrorLogic.is16x9Ratio(1080, 1920))
        assertTrue(MirrorLogic.is16x9Ratio(1920, 1080))
        assertTrue(MirrorLogic.is16x9Ratio(1440, 2560))
        assertFalse(MirrorLogic.is16x9Ratio(1080, 2640))
        assertEquals(100, MirrorLogic.clampDpi(50))
        assertEquals(400, MirrorLogic.clampDpi(600))
        assertEquals("wm size 1080x1920 && wm density 240", MirrorLogic.buildApplyCmd(1080, 1920, 240))
        assertEquals("wm density 280", MirrorLogic.buildDensityCmd(280))
        assertEquals("wm size reset && wm density reset", MirrorLogic.buildResetCmd())
    }
}
