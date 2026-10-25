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

class BatterySaverTest {
    @Test
    fun saverPresetValues() {
        val p = BatterySaver.presetFor(BatterySaver.MODE_SAVER)
        assertEquals("1", p["low_power"])
        assertEquals("1", p["adaptive_battery_management_enabled"])
        assertEquals("0", p["wifi_scan_always_enabled"])
        assertEquals("0", p["mobile_data_always_on"])
        assertEquals("0", p["ble_scan_always_enabled"])
    }

    @Test
    fun balancedRestoresDefaults() {
        val p = BatterySaver.presetFor(BatterySaver.MODE_BALANCED)
        assertEquals("0", p["low_power"])
        assertEquals("1", p["wifi_scan_always_enabled"])
    }

    @Test
    fun applyCmdReversibleKeys() {
        val cmd = BatterySaver.buildApplyCmd(BatterySaver.MODE_SAVER)
        BatterySaver.SNAPSHOT_KEYS.forEach { assertTrue(cmd.contains("settings put global $it")) }
        assertTrue(cmd.contains("cmd power set-adaptive-power-saver-enabled true"))
        assertTrue(cmd.contains("dumpsys deviceidle enable all"))
    }

    @Test
    fun extremeForcesIdle() {
        assertTrue(BatterySaver.buildApplyCmd(BatterySaver.MODE_EXTREME).contains("force-idle"))
    }

    @Test
    fun resetRestoresSnapshotOrDeletes() {
        val cmd = BatterySaver.buildResetCmd(
            mapOf(
                "low_power" to "0",
                "adaptive_battery_management_enabled" to "1",
                "wifi_scan_always_enabled" to "null",
                "mobile_data_always_on" to "",
                "ble_scan_always_enabled" to "0"
            )
        )
        assertTrue(cmd.contains("settings put global low_power '0'"))
        assertTrue(cmd.contains("settings delete global wifi_scan_always_enabled"))
        assertTrue(cmd.contains("settings delete global mobile_data_always_on"))
    }
}

class DozeParserTest {
    @Test
    fun parsesWhitelist() {
        val dump = """
            system-excidle,com.android.systemui
              com.example.game
              com.example.social, com.example.shop
        """.trimIndent()
        val wl = DozeParser.parseWhitelist(dump)
        assertTrue(wl.contains("com.android.systemui"))
        assertTrue(wl.contains("com.example.game"))
        assertTrue(wl.contains("com.example.social"))
        assertEquals(wl.sorted(), wl)
    }

    @Test
    fun findsExemptThirdParty() {
        val wl = listOf("com.android.systemui", "com.example.game")
        val third = listOf("package:com.example.game", "package:com.example.other")
        assertEquals(listOf("com.example.game"), DozeParser.exemptThirdParty(wl, third))
    }

    @Test
    fun buildRemoveRestore() {
        val pkgs = listOf("com.a", "com.b")
        assertTrue(DozeParser.buildRemoveCmd(pkgs).contains("whitelist -com.a"))
        assertTrue(DozeParser.buildRestoreCmd(pkgs).contains("whitelist +com.b"))
    }
}
