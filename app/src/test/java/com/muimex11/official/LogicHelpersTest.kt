package com.muimex11.official

import org.junit.Assert.*
import org.junit.Test

class FgPkgParserTest {
    @Test
    fun parsesTopResumedActivity() {
        val dump = "topResumedActivity=ActivityRecord{u0 com.example.game/.Main t123}"
        assertEquals("com.example.game", FgPkgParser.parse(dump, "com.muimex11.official"))
    }

    @Test
    fun skipsSystemAndLauncher() {
        val dump = """
            mFocusedApp=ActivityRecord{u0 com.android.systemui/.recents.RecentsActivity t1}
            topResumedActivity=ActivityRecord{u0 com.android.launcher3/.Launcher t2}
            topResumedActivity=ActivityRecord{u0 com.real.game/.Main t3}
        """.trimIndent()
        assertEquals("com.real.game", FgPkgParser.parse(dump, "com.muimex11.official"))
    }

    @Test
    fun returnsNullWhenOnlySelf() {
        val dump = "topResumedActivity=ActivityRecord{u0 com.muimex11.official/.Main t1}"
        assertNull(FgPkgParser.parse(dump, "com.muimex11.official"))
    }

    @Test
    fun fpsSanityGate() {
        assertFalse(FgPkgParser.saneFps(-1, 100, 1000))
        assertFalse(FgPkgParser.saneFps(0, 10, 100)) // dt too small
        assertFalse(FgPkgParser.saneFps(0, 5000, 1000)) // too many frames
        assertTrue(FgPkgParser.saneFps(0, 60, 1000))
        assertEquals(60, FgPkgParser.fps(60, 0, 1000))
        assertNull(FgPkgParser.fps(5000, 0, 1000))
    }
}

class ResolutionControllerTest {
    @Test
    fun cycleSteps() {
        assertEquals(ResolutionController.Step.TO_75, ResolutionController.next(1.0f))
        assertEquals(ResolutionController.Step.TO_75, ResolutionController.next(0.95f))
        assertEquals(ResolutionController.Step.TO_50, ResolutionController.next(0.75f))
        assertEquals(ResolutionController.Step.RESET, ResolutionController.next(0.5f))
    }
}

class UpdateParserTest {
    @Test
    fun httpsOnly() {
        assertTrue(UpdateParser.isHttps("https://example.com/a.apk"))
        assertFalse(UpdateParser.isHttps("http://example.com/a.apk"))
    }

    @Test
    fun shouldUpdate() {
        assertTrue(UpdateParser.shouldUpdate(32, 31))
        assertFalse(UpdateParser.shouldUpdate(31, 31))
        assertFalse(UpdateParser.shouldUpdate(30, 31))
    }
}
