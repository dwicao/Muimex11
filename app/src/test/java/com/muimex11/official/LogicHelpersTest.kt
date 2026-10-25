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

class AppOpsParserTest {
    @Test
    fun parsesGet() {
        val dump = """
            CAMERA: allow; time=+1d0h0m0s0ms ago; duration=-1
            RECORD_AUDIO: ignore; time=+2d0h0m0s0ms ago
            ACCESS_FINE_LOCATION: default
        """.trimIndent()
        val m = AppOpsParser.parseGet(dump)
        assertEquals("allow", m["CAMERA"])
        assertEquals("ignore", m["RECORD_AUDIO"])
        assertEquals("default", m["ACCESS_FINE_LOCATION"])
        assertNull(m["ACCESS_COARSE_LOCATION"])
    }

    @Test
    fun buildSetAndSafe() {
        assertTrue(AppOpsParser.buildSet("com.a", "CAMERA", "ignore").contains("cmd appops set com.a CAMERA ignore"))
        assertTrue(AppOpsParser.safePkg("com.example.game"))
        assertFalse(AppOpsParser.safePkg("com.example; rm -rf"))
        assertFalse(AppOpsParser.safePkg(""))
    }
}

class DebloatPresetsTest {
    @Test
    fun presetsOnlySafePkgs() {
        val all = DebloatPresets.ALL.flatMap { it.pkgs }
        val critical = listOf("android", "com.android.systemui", "com.google.android.gms", "com.android.phone")
        critical.forEach { c -> assertFalse(all.contains(c)) }
        assertTrue(DebloatPresets.META.pkgs.contains("com.facebook.katana"))
        assertTrue(DebloatPresets.MIUI.pkgs.contains("com.miui.msa.global"))
        assertTrue(DebloatPresets.SAMSUNG.pkgs.contains("com.samsung.android.bixby.agent"))
    }

    @Test
    fun buildCmds() {
        val cmd = DebloatPresets.buildDisable(listOf("com.a", "com.b"), 0)
        assertTrue(cmd.contains("pm disable-user --user 0 com.a"))
        assertTrue(DebloatPresets.buildEnable(listOf("com.a"), 0).contains("pm enable --user 0 com.a"))
    }
}

class AudioPresetsTest {
    @Test
    fun applyCoversSnapshot() {
        val cmd = AudioPresets.buildApply(AudioPresets.PRESET_LOWLAT)
        AudioPresets.SNAPSHOT.forEach { ck ->
            val (ns, k) = ck.split(":")
            assertTrue(cmd.contains("settings put $ns $k"))
        }
        assertTrue(cmd.contains("bluetooth_disable_absolute_volume '1'"))
    }

    @Test
    fun resetRestoresOrDeletes() {
        val cmd = AudioPresets.buildReset(
            mapOf(
                "system:sound_effects_enabled" to "1",
                "system:haptic_feedback_enabled" to "null",
                "global:bluetooth_disable_absolute_volume" to ""
            )
        )
        assertTrue(cmd.contains("settings put system sound_effects_enabled '1'"))
        assertTrue(cmd.contains("settings delete system haptic_feedback_enabled"))
        assertTrue(cmd.contains("settings delete global bluetooth_disable_absolute_volume"))
    }
}

class SensorCmdTest {
    @Test
    fun sdkGate() {
        assertFalse(SensorCmd.sdkOk(30))
        assertTrue(SensorCmd.sdkOk(31))
        assertTrue(SensorCmd.sdkOk(34))
    }

    @Test
    fun buildBlockUnblock() {
        assertEquals("cmd sensor_privacy enable 0 2", SensorCmd.buildSet(0, SensorCmd.CAMERA, true))
        assertEquals("cmd sensor_privacy disable 0 1", SensorCmd.buildSet(0, SensorCmd.MIC, false))
        val both = SensorCmd.buildBlockBoth(0)
        assertTrue(both.contains("enable 0 2") && both.contains("enable 0 1"))
    }
}

class AdaptiveThermalTest {
    @Test
    fun hysteresis() {
        // Hot always releases, cold always engages.
        assertFalse(AdaptiveThermal.decide(true, 46f))
        assertFalse(AdaptiveThermal.decide(false, 45f))
        assertTrue(AdaptiveThermal.decide(false, 37f))
        assertTrue(AdaptiveThermal.decide(true, 38f))
        // Inside the band the previous state sticks (no flapping).
        assertTrue(AdaptiveThermal.decide(true, 42f))
        assertFalse(AdaptiveThermal.decide(false, 42f))
    }

    @Test
    fun parsesHottestZone() {
        val dump = "35000\n47000\nnot_a_number\n-5\n200000\n"
        assertEquals(47f, AdaptiveThermal.parseZoneTemps(dump)!!, 0.001f)
    }

    @Test
    fun parseEmptyIsNull() {
        assertNull(AdaptiveThermal.parseZoneTemps("oops\n-1\n"))
        assertNull(AdaptiveThermal.parseZoneTemps(""))
    }
}

class StandbyBucketsTest {
    @Test
    fun parsesIntAndName() {
        assertEquals("rare", StandbyBuckets.parseGet("40\n"))
        assertEquals("active", StandbyBuckets.parseGet("10"))
        assertEquals("restricted", StandbyBuckets.parseGet("RESTRICTED"))
        assertEquals("rare", StandbyBuckets.parseGet("rare"))
        assertNull(StandbyBuckets.parseGet(""))
        assertNull(StandbyBuckets.parseGet("banana"))
    }

    @Test
    fun buildCmds() {
        assertTrue(StandbyBuckets.buildSet("com.a", "restricted").contains("am set-standby-bucket com.a restricted"))
        assertTrue(StandbyBuckets.buildGet("com.a").contains("am get-standby-bucket com.a"))
    }
}

class HibernationTest {
    @Test
    fun parsesState() {
        assertEquals(true, Hibernation.parseGet("true\n"))
        assertEquals(false, Hibernation.parseGet("false "))
        assertNull(Hibernation.parseGet("cmd: Failure calling service"))
        assertNull(Hibernation.parseGet(""))
    }

    @Test
    fun sdkGateAndBuild() {
        assertFalse(Hibernation.sdkOk(30))
        assertTrue(Hibernation.sdkOk(31))
        assertEquals("cmd app_hibernation set-state com.a true", Hibernation.buildSet("com.a", true))
    }
}

class BenchmarkCalcTest {    @Test
    fun parsesAmStartW() {
        val dump = "Starting: Intent {}\nStatus: ok\nActivity: com.a/.Main\nThisTime: 120\nTotalTime: 345\nWaitTime: 360\nComplete\n"
        assertEquals(345L, BenchmarkCalc.parseAmStartW(dump))
        assertNull(BenchmarkCalc.parseAmStartW("no timing here"))
    }

    @Test
    fun avgAndFps() {
        assertEquals(200L, BenchmarkCalc.avg(listOf(100L, 200L, 300L)))
        assertEquals(0L, BenchmarkCalc.avg(emptyList()))
        assertEquals(60, BenchmarkCalc.fps(900, 0, 15_000))
        assertNull(BenchmarkCalc.fps(50_000, 0, 15_000))
    }
}

class ThermalPresetsTest {
    @Test
    fun presetsAreSane() {
        ThermalPresets.ALL.forEach {
            assertTrue(ThermalPresets.isSane(it.low, it.high))
        }
        assertEquals(38, ThermalPresets.BALANCED.low)
        assertEquals(45, ThermalPresets.BALANCED.high)
    }

    @Test
    fun rejectsUnsafe() {
        assertFalse(ThermalPresets.isSane(60, 65))
        assertFalse(ThermalPresets.isSane(25, 90))
        assertFalse(ThermalPresets.isSane(44, 45))
        // clamp falls back to balanced when unfixable
        assertEquals(38 to 45, ThermalPresets.clamp(60, 20))
    }
}