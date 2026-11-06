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
    fun trustedHostsOnly() {
        assertTrue(UpdateParser.isSafeApkUrl("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(UpdateParser.isSafeApkUrl("https://objects.githubusercontent.com/foo.apk"))
        assertFalse(UpdateParser.isSafeApkUrl("http://github.com/o/r/a.apk"))
        assertFalse(UpdateParser.isSafeApkUrl("https://evil.com/a.apk"))
        assertFalse(UpdateParser.isSafeApkUrl("https://github.com.evil.com/a.apk"))
    }

    @Test
    fun shouldUpdate() {
        assertTrue(UpdateParser.shouldUpdate(32, 31))
        assertFalse(UpdateParser.shouldUpdate(31, 31))
        assertFalse(UpdateParser.shouldUpdate(30, 31))
    }
}

class ShellSanitizerTest {
    @Test
    fun acceptsNormalPkgs() {
        assertEquals("com.example.game", ShellSanitizer.cleanPkg("com.example.game"))
        assertEquals("com.example.game", ShellSanitizer.cleanPkg("  com.example.game  "))
    }

    @Test
    fun rejectsInjection() {
        assertNull(ShellSanitizer.cleanPkg("com.a; rm -rf /"))
        assertNull(ShellSanitizer.cleanPkg("com.a && id"))
        assertNull(ShellSanitizer.cleanPkg("com.a\nam force-stop x"))
        assertNull(ShellSanitizer.cleanPkg(""))
        assertNull(ShellSanitizer.cleanPkg(null))
        assertNull(ShellSanitizer.cleanPkg("a".repeat(300)))
    }

    @Test
    fun escQuotes() {
        assertEquals("a'\\''b", ShellSanitizer.esc("a'b"))
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

class UninstallCmdTest {
    @Test
    fun buildCommands() {
        assertEquals(
            "pm uninstall -k --user 0 com.example.bloat",
            UninstallCmd.buildUninstall(0, "com.example.bloat")
        )
        assertEquals(
            "cmd package install-existing com.example.bloat",
            UninstallCmd.buildRestore("com.example.bloat")
        )
    }

    @Test
    fun successHeuristics() {
        assertTrue(UninstallCmd.succeeded(0, "Success"))
        assertTrue(UninstallCmd.succeeded(0, ""))
        assertTrue(UninstallCmd.succeeded(0, "Package com.x installed for user: 0"))
        assertFalse(UninstallCmd.succeeded(1, "Success"))
        assertFalse(UninstallCmd.succeeded(0, "Failure [DELETE_FAILED_INTERNAL_ERROR]"))
        assertFalse(UninstallCmd.succeeded(0, "Failure [not installed for 0]"))
        assertFalse(UninstallCmd.succeeded(0, "Exception occurred while executing"))
        assertFalse(UninstallCmd.succeeded(0, "Error: unknown package"))
        assertTrue(UninstallCmd.isGone("  \n"))
        assertFalse(UninstallCmd.isGone("package:com.example.bloat"))
    }
}

class ApkBackupTest {
    @Test
    fun parsesPmPath() {
        val dump = "package:/data/app/~~abc==/com.example-xyz/base.apk\n" +
            "package:/data/app/~~abc==/com.example-xyz/split_config.arm64_v8a.apk\n" +
            "garbage line\npackage:relative.apk\n"
        val out = ApkBackup.parsePmPath(dump)
        assertEquals(2, out.size)
        assertTrue(out[0].endsWith("base.apk"))
        assertTrue(out[1].endsWith("split_config.arm64_v8a.apk"))
        assertTrue(ApkBackup.parsePmPath("").isEmpty())
    }

    @Test
    fun destAndCopyCmd() {
        val dest = ApkBackup.destDir("com.example.game", "1.2.3")
        assertEquals("/sdcard/Download/Muimex11/com.example.game-1.2.3", dest)
        // hostile version names cannot break out of the path
        assertEquals(
            "/sdcard/Download/Muimex11/com.a-1.0_x",
            ApkBackup.destDir("com.a", "1.0/x")
        )
        val cmd = ApkBackup.buildCopyCmd(listOf("/a/base.apk", "/a/split.apk"), dest)!!
        assertTrue(cmd.startsWith("mkdir -p '$dest'"))
        assertTrue(cmd.contains("cp '/a/base.apk' '$dest'/"))
        assertNull(ApkBackup.buildCopyCmd(emptyList(), dest))
    }
}

class RebootCmdTest {
    @Test
    fun buildModes() {
        assertEquals("svc power reboot", RebootCmd.build(RebootCmd.SYSTEM))
        assertEquals("svc power reboot recovery", RebootCmd.build(RebootCmd.RECOVERY))
        assertEquals("svc power reboot bootloader", RebootCmd.build(RebootCmd.BOOTLOADER))
        assertEquals("am force-stop com.android.systemui", RebootCmd.build(RebootCmd.SYSTEM_UI))
        assertNull(RebootCmd.build(99))
    }
}

class OrientationModeTest {
    @Test
    fun buildApply() {
        assertEquals(
            "settings put system accelerometer_rotation 1",
            OrientationMode.buildApply(OrientationMode.AUTO)
        )
        assertTrue(
            OrientationMode.buildApply(OrientationMode.LANDSCAPE)!!
                .contains("user_rotation 1")
        )
        assertTrue(
            OrientationMode.buildApply(OrientationMode.REVERSE_PORTRAIT)!!
                .contains("user_rotation 2")
        )
        assertNull(OrientationMode.buildApply(99))
    }

    @Test
    fun parseLive() {
        assertEquals(OrientationMode.AUTO, OrientationMode.parse("1", "0"))
        assertEquals(OrientationMode.PORTRAIT, OrientationMode.parse("0", "0"))
        assertEquals(OrientationMode.LANDSCAPE, OrientationMode.parse("0", "1"))
        assertEquals(OrientationMode.REVERSE_LANDSCAPE, OrientationMode.parse("0", "3"))
        assertEquals(OrientationMode.AUTO, OrientationMode.parse("null", "null"))
        assertEquals(OrientationMode.AUTO, OrientationMode.parse(null, null))
        assertTrue(OrientationMode.isValid(OrientationMode.REVERSE_PORTRAIT))
        assertFalse(OrientationMode.isValid(7))
    }
}

class AppLocaleTest {
    @Test
    fun buildCommands() {
        assertEquals(
            "cmd locale get-app-locales com.example.game",
            AppLocale.buildGet("com.example.game")
        )
        assertEquals(
            "cmd locale set-app-locales com.example.game --app-locales ja-JP",
            AppLocale.buildSet("com.example.game", "ja-JP")
        )
        assertEquals(
            "cmd locale set-app-locales com.example.game --app-locales \"\"",
            AppLocale.buildClear("com.example.game")
        )
        assertNull(AppLocale.buildSet("com.a", "not a tag!!"))
        assertNull(AppLocale.buildSet("com.a", ""))
    }

    @Test
    fun parsesGet() {
        assertEquals("ja-JP", AppLocale.parseGet("ja-JP\n"))
        assertEquals("en-US,ja-JP", AppLocale.parseGet("[en-US, ja-JP]"))
        assertEquals("", AppLocale.parseGet(""))
        assertEquals("", AppLocale.parseGet("[]"))
        assertEquals("", AppLocale.parseGet("null"))
        assertEquals("", AppLocale.parseGet(null))
        assertEquals("", AppLocale.parseGet("Error: not found"))
        assertTrue(AppLocale.matches("JA-jp ", "ja-JP"))
        assertFalse(AppLocale.matches("en-US", "ja-JP"))
    }

    @Test
    fun tagValidation() {
        assertTrue(AppLocale.isValidTags("ja-JP"))
        assertTrue(AppLocale.isValidTags("en-US,pt-BR"))
        assertTrue(AppLocale.isValidTags("zh-Hans-CN"))
        assertFalse(AppLocale.isValidTags(""))
        assertFalse(AppLocale.isValidTags("en_US"))
        assertFalse(AppLocale.isValidTags("e"))
        assertFalse(AppLocale.isValidTags("ja-JP; rm -rf"))
    }
}

class AppOpsBatchTest {
    @Test
    fun buildCmd() {
        val cmd = AppOpsBatch.buildCmd(listOf("com.a", "com.b"))!!
        assertTrue(cmd.contains("cmd appops set com.a CAMERA ignore"))
        assertTrue(cmd.contains("cmd appops set com.b ACCESS_COARSE_LOCATION ignore"))
        // 2 pkgs x 4 ops = 8 sets joined with &&
        assertEquals(7, cmd.split(" && ").size - 1)
        assertNull(AppOpsBatch.buildCmd(emptyList()))
        assertNull(AppOpsBatch.buildCmd(listOf("not a pkg!!")))
    }

    @Test
    fun ruleEntries() {
        val m = AppOpsBatch.ruleEntries(listOf("com.a", "com.a", "bad pkg!!"))
        assertEquals(1, m.size)
        assertEquals("ignore", m["com.a"]?.get("CAMERA"))
        assertEquals(4, m["com.a"]?.size)
    }
}

class KeepAwakeTest {
    @Test
    fun buildCommands() {
        assertEquals(
            "settings put system screen_off_timeout 2147483647",
            KeepAwake.buildSet()
        )
        assertEquals(
            "settings put system screen_off_timeout 60000",
            KeepAwake.buildRestore("60000")
        )
        assertEquals(
            "settings delete system screen_off_timeout",
            KeepAwake.buildRestore("null")
        )
        assertEquals(
            "settings delete system screen_off_timeout",
            KeepAwake.buildRestore("")
        )
    }
}

class ViewModeTest {
    @Test
    fun cyclesListGridCompact() {
        assertEquals(ViewMode.GRID, ViewMode.next(ViewMode.LIST))
        assertEquals(ViewMode.COMPACT, ViewMode.next(ViewMode.GRID))
        assertEquals(ViewMode.LIST, ViewMode.next(ViewMode.COMPACT))
        assertEquals(ViewMode.LIST, ViewMode.next("bogus"))
    }

    @Test
    fun gridLike() {
        assertFalse(ViewMode.isGridLike(ViewMode.LIST))
        assertTrue(ViewMode.isGridLike(ViewMode.GRID))
        assertTrue(ViewMode.isGridLike(ViewMode.COMPACT))
    }
}

class TouchTweakTest {
    @Test
    fun buildCommands() {
        assertEquals("settings put system pointer_speed 7", TouchTweak.buildPointer("7"))
        assertEquals("settings put secure long_press_timeout 250", TouchTweak.buildLongPress())
        assertEquals(
            "settings delete system pointer_speed && settings put secure long_press_timeout 400",
            TouchTweak.buildReset()
        )
    }

    @Test
    fun appliedLevels() {
        // Any positive level counts — ROM validators cap the max (Realme).
        assertTrue(TouchTweak.isPointerApplied("7\n"))
        assertTrue(TouchTweak.isPointerApplied("5"))
        assertFalse(TouchTweak.isPointerApplied("0"))
        assertFalse(TouchTweak.isPointerApplied("-2"))
        assertFalse(TouchTweak.isPointerApplied("null"))
        assertFalse(TouchTweak.isPointerApplied(null))
        assertEquals(3, TouchTweak.parsePointerLevel(" 3 "))
        assertNull(TouchTweak.parsePointerLevel("0"))
        assertNull(TouchTweak.parsePointerLevel("abc"))
        assertTrue(TouchTweak.isLongPressApplied("250"))
        assertFalse(TouchTweak.isLongPressApplied("400"))
        assertEquals(2, TouchTweak.appliedCount(true, true))
        assertEquals(1, TouchTweak.appliedCount(true, false))
        assertEquals(1, TouchTweak.appliedCount(false, true))
        assertEquals(0, TouchTweak.appliedCount(false, false))
    }
}