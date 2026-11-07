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
        assertEquals("input keyevent 223", RebootCmd.build(RebootCmd.SCREEN_OFF))
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
        val n = AppOpsParser.OPS.size
        val cmd = AppOpsBatch.buildCmd(listOf("com.a", "com.b"))!!
        assertTrue(cmd.contains("cmd appops set com.a CAMERA ignore"))
        assertTrue(cmd.contains("cmd appops set com.b READ_CLIPBOARD ignore"))
        // 2 pkgs x N ops joined with &&
        assertEquals(2 * n - 1, cmd.split(" && ").size - 1)
        assertNull(AppOpsBatch.buildCmd(emptyList()))
        assertNull(AppOpsBatch.buildCmd(listOf("not a pkg!!")))
    }

    @Test
    fun ruleEntries() {
        val m = AppOpsBatch.ruleEntries(listOf("com.a", "com.a", "bad pkg!!"))
        assertEquals(1, m.size)
        assertEquals("ignore", m["com.a"]?.get("CAMERA"))
        assertEquals(AppOpsParser.OPS.size, m["com.a"]?.size)
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

class DangerScanTest {
    @Test
    fun flagsDestructive() {
        assertTrue(DangerScan.scan("rm -rf /").any { it.pattern == "rm -rf /" })
        assertTrue(DangerScan.scan("sudo rm -rf / --no-preserve-root").isNotEmpty())
        assertTrue(DangerScan.scan("dd if=x of=/dev/block/mmcblk0").any { it.pattern == "dd to block device" })
        assertTrue(DangerScan.scan("mkfs.ext4 /dev/sda1").any { it.pattern == "mkfs" })
        assertTrue(DangerScan.scan(":(){ :|:& };:").any { it.pattern == "fork bomb" })
        assertTrue(DangerScan.scan("fastboot flash boot boot.img").any { it.pattern == "fastboot/flash" })
        assertTrue(DangerScan.scan("chmod -R 777 /").any { it.pattern == "chmod 777 /" })
    }

    @Test
    fun ignoresBenign() {
        assertTrue(DangerScan.scan("rm -rf /sdcard/Download/tmp").isEmpty())
        assertTrue(DangerScan.scan("rm -rf /data/local/tmp/x").isEmpty())
        assertTrue(DangerScan.scan("dd if=/dev/zero of=/sdcard/out.bin bs=1M count=1").isEmpty())
        assertTrue(DangerScan.scan("# rm -rf / (commented)").isEmpty())
        assertTrue(DangerScan.scan("echo hello\nsettings put global foo 1").isEmpty())
        assertTrue(DangerScan.scan("").isEmpty())
    }

    @Test
    fun capsAndDedupes() {
        val big = (1..50).joinToString("\n") { "rm -rf / #$it" }
        assertTrue(DangerScan.scan(big).size <= 8)
    }
}

class AutomationAuthTest {
    @Test
    fun roundTrip() {
        val t = AutomationAuth.newToken()
        assertTrue(AutomationAuth.isWellFormed(t))
        assertTrue(AutomationAuth.matches(t, t))
        assertFalse(AutomationAuth.matches(t, t + "x"))
        assertFalse(AutomationAuth.matches(t, null))
        assertFalse(AutomationAuth.matches(null, t))
        assertFalse(AutomationAuth.matches("", ""))
        assertFalse(AutomationAuth.matches("short", "short"))
    }
}

class AppOpsTimelineTest {
    @Test
    fun parsesDurations() {
        assertEquals(90_000L, AppOpsTimeline.parseDurationAgo("1m30s"))
        assertEquals(3_600_000L + 120_000L, AppOpsTimeline.parseDurationAgo("1h2m"))
        assertEquals(86_400_000L, AppOpsTimeline.parseDurationAgo("1d"))
        assertEquals(500L, AppOpsTimeline.parseDurationAgo("500ms"))
        assertEquals(4500L, AppOpsTimeline.parseDurationAgo("4.5s"))
        assertNull(AppOpsTimeline.parseDurationAgo("never"))
        assertNull(AppOpsTimeline.parseDurationAgo(""))
    }

    @Test
    fun parsesDump() {
        val dump = """
            Uid 10123:
              Package com.evil.cam:
                CAMERA: allow; time=+1h2m ago; duration=+5s ago;
                RECORD_AUDIO: ignore; time=+2d ago;
              Package com.nice.app:
                CAMERA(26): allow; time=+30s ago;
                WIFI_SCAN: allow; time=+1m ago;
              Package com.other:
                ACCESS_FINE_LOCATION: allow; time=+10m5s ago;
        """.trimIndent()
        val now = 1_700_000_000_000L
        val out = AppOpsTimeline.parseDump(dump, now)
        // WIFI_SCAN not watched; 4 watched entries expected, newest first.
        assertEquals(4, out.size)
        assertEquals("CAMERA", out[0].op)
        assertEquals("com.nice.app", out[0].pkg)
        assertEquals(now - 30_000L, out[0].timeMs)
        assertTrue(out.all { it.op in AppOpsTimeline.WATCHED })
        assertTrue(out.zipWithNext().all { (a, b) -> a.timeMs >= b.timeMs })
    }

    @Test
    fun skipsUnknown() {
        assertTrue(AppOpsTimeline.parseDump("total garbage\nno headers", 1000L).isEmpty())
        assertTrue(AppOpsTimeline.parseDump("", 1000L).isEmpty())
    }
}

class TaskerCmdTest {
    @Test
    fun parsesActions() {
        assertEquals(TaskerCmd.ACTION_RENDERER, TaskerCmd.parseAction("renderer"))
        assertEquals(TaskerCmd.ACTION_BOOST, TaskerCmd.parseAction(" Game_Boost "))
        assertNull(TaskerCmd.parseAction("format"))
        assertNull(TaskerCmd.parseAction(null))
    }

    @Test
    fun parsesRendererValues() {
        assertEquals("Vulkan", TaskerCmd.parseRenderer("vulkan"))
        assertEquals("Vulkan", TaskerCmd.parseRenderer("SKIAVK"))
        assertEquals("OpenGL", TaskerCmd.parseRenderer("opengl"))
        assertNull(TaskerCmd.parseRenderer("directx"))
        assertNull(TaskerCmd.parseRenderer(null))
    }
}

class PingProbeTest {
    @Test
    fun buildCommand() {
        assertEquals("ping -c 1 -W 2 8.8.8.8", PingProbe.build(PingProbe.PRIMARY_HOST))
        assertEquals("ping -c 1 -W 2 1.1.1.1", PingProbe.build(PingProbe.FALLBACK_HOST))
    }

    @Test
    fun parsesRtt() {
        val ok = "PING 8.8.8.8: 56 data bytes\n64 bytes from 8.8.8.8: icmp_seq=0 ttl=116 time=23.7 ms\n"
        assertEquals(23.7, PingProbe.parseMs(ok)!!, 0.001)
        assertNull(PingProbe.parseMs("PING 8.8.8.8: connect: Network is unreachable\n"))
        assertNull(PingProbe.parseMs(""))
        assertEquals(0.0, PingProbe.jitterMs(23.7, 23.7)!!, 0.001)
        assertEquals(5.0, PingProbe.jitterMs(20.0, 25.0)!!, 0.001)
        assertNull(PingProbe.jitterMs(null, 25.0))
    }
}

class RefreshRatesTest {
    @Test
    fun mapsOsModes() {
        // Fractional panel rates round to whole Hz, deduped and sorted.
        assertEquals(
            listOf(60, 90, 120),
            RefreshRates.supportedFromModes(listOf(60.0f, 90.0f, 120.0f, 120.0f))
        )
        assertEquals(listOf(60, 120), RefreshRates.supportedFromModes(listOf(59.94f, 119.88f)))
        assertEquals(emptyList<Int>(), RefreshRates.supportedFromModes(emptyList()))
    }

    @Test
    fun nearestSupported() {
        val modes = listOf(60, 90, 120)
        assertEquals(60, RefreshRates.nearestSupported(30, modes))
        assertEquals(90, RefreshRates.nearestSupported(100, modes))
        assertEquals(120, RefreshRates.nearestSupported(120, modes))
        assertEquals(120, RefreshRates.nearestSupported(144, modes))
        // Tie prefers the higher rate.
        assertEquals(90, RefreshRates.nearestSupported(75, listOf(60, 90)))
        assertNull(RefreshRates.nearestSupported(60, emptyList()))
    }

    @Test
    fun buildCmd() {
        assertEquals(
            "settings put system min_refresh_rate 30 && settings put system peak_refresh_rate 30",
            RefreshRates.buildSet(30, 30)
        )
    }
}

class NetPolicyTest {
    @Test
    fun buildCommands() {
        assertEquals(
            "cmd netpolicy add restrict-background-blacklist 10123",
            NetPolicy.buildAdd(10123)
        )
        assertEquals(
            "cmd netpolicy remove restrict-background-blacklist 10123",
            NetPolicy.buildRemove(10123)
        )
        assertEquals("cmd netpolicy set restrict-background true", NetPolicy.buildSetSaver(true))
        assertEquals("cmd netpolicy set restrict-background false", NetPolicy.buildSetSaver(false))
        assertNull(NetPolicy.buildAdd(0))
        assertNull(NetPolicy.buildAdd(-5))
    }

    @Test
    fun parseList() {
        assertEquals(
            setOf(10123, 10145),
            NetPolicy.parseList("Restrict background blacklisted UIDs: 10123 10145 \n")
        )
        assertTrue(NetPolicy.parseList("Restrict background blacklisted UIDs: none\n").isEmpty())
        assertTrue(NetPolicy.parseList("").isEmpty())
        assertTrue(NetPolicy.parseList(null).isEmpty())
        assertTrue(NetPolicy.parseList("Error: unknown command\n").isEmpty())
    }

    @Test
    fun saverState() {
        assertTrue(NetPolicy.isSaverOn("Restricted mode status: enabled\n"))
        assertFalse(NetPolicy.isSaverOn("Restricted mode status: disabled\n"))
        assertFalse(NetPolicy.isSaverOn(""))
    }
}

class HeatResponseTest {
    @Test
    fun buildCommands() {
        assertEquals(
            "settings put system min_refresh_rate 60 && settings put system peak_refresh_rate 60",
            HeatResponse.buildDrop()
        )
        assertEquals(
            "settings put system min_refresh_rate 90 && settings put system peak_refresh_rate 120",
            HeatResponse.buildRestore("90", "120")
        )
        assertEquals(
            "settings delete system min_refresh_rate && settings delete system peak_refresh_rate",
            HeatResponse.buildRestore("null", "")
        )
    }
}

class CellInfoTest {
    @Test
    fun parsesGetprop() {
        val dump = "[gsm.operator.alpha]: [Smartfren]\n" +
            "[gsm.network.type]: [LTE]\n" +
            "[gsm.sim.state]: [LOADED]\n" +
            "[gsm.version.ril-impl]: [xxx]\n"
        val s = CellInfo.parseGetprop(dump)
        assertEquals("Smartfren", s.operator)
        assertEquals("LTE", s.networkType)
        assertEquals("LOADED", s.simState)
    }

    @Test
    fun missingIsDash() {
        val s = CellInfo.parseGetprop("unrelated: [x]\n")
        assertEquals("—", s.operator)
        assertEquals("—", s.networkType)
        assertEquals("—", s.simState)
    }
}

class BatteryHealthTest {
    @Test
    fun parsesDumpsys() {
        val dump = """
            Current Battery Service state:
              AC powered: false
              level: 85
              health: 2
              present: true
              voltage: 4187
              temperature: 312
              technology: Li-ion
              mBatteryCycleCount: 214
        """.trimIndent()
        val i = BatteryHealth.parse(dump)!!
        assertEquals(85, i.level)
        assertEquals("Good", i.health)
        assertEquals(4.187, i.voltageV!!, 0.001)
        assertEquals(31.2, i.tempC!!, 0.001)
        assertEquals("Li-ion", i.tech)
        assertEquals(214, i.cycles)
    }

    @Test
    fun missingCycleIsNull() {
        val dump = "level: 50\nhealth: 3\nvoltage: 4000\ntemperature: 400\ntechnology: Li-poly\n"
        val i = BatteryHealth.parse(dump)!!
        assertEquals("Overheat", i.health)
        assertNull(i.cycles)
    }

    @Test
    fun noLevelIsNull() {
        assertNull(BatteryHealth.parse("garbage\n"))
    }
}
class SysTweaksTest {
    @Test
    fun buildSet() {
        assertEquals("settings put global enable_msaa 1", SysTweaks.buildSet("global", "enable_msaa", "1"))
        assertEquals("settings put secure doze_always_on 0", SysTweaks.buildSet("secure", "doze_always_on", "0"))
    }

    @Test
    fun parseSwitches() {
        assertTrue(SysTweaks.isOn("1"))
        assertFalse(SysTweaks.isOn("0"))
        assertFalse(SysTweaks.isOn("null"))
        assertFalse(SysTweaks.isOn(""))
        assertFalse(SysTweaks.isOn(null))
        assertTrue(SysTweaks.isSafeVolSuppressed("2"))
        assertFalse(SysTweaks.isSafeVolSuppressed("0"))
        assertTrue(SysTweaks.isStayAwake("7"))
        assertFalse(SysTweaks.isStayAwake("0"))
    }

    @Test
    fun blacklistRoundTrip() {
        assertEquals(emptySet<String>(), SysTweaks.parseBlacklist(null))
        assertEquals(emptySet<String>(), SysTweaks.parseBlacklist("null"))
        assertEquals(emptySet<String>(), SysTweaks.parseBlacklist(""))
        assertEquals(setOf("clock", "nfc"), SysTweaks.parseBlacklist("clock,nfc"))
        assertEquals(setOf("clock", "nfc"), SysTweaks.parseBlacklist("nfc, clock,nfc "))
        // Built value is sorted and stable for read-back comparison.
        assertEquals("clock,nfc", SysTweaks.buildBlacklist(setOf("nfc", "clock")))
        assertEquals("", SysTweaks.buildBlacklist(emptySet()))
    }

    @Test
    fun buildAnim() {
        assertEquals(
            "settings put global window_animation_scale 0.0 && settings put global transition_animation_scale 0.0 && settings put global animator_duration_scale 0.0",
            SysTweaks.buildAnim("0.0")
        )
    }

    @Test
    fun buildResetCoversAllKeys() {
        val reset = SysTweaks.buildReset()
        listOf("enable_msaa", "force_msaa", "show_touches", "doze_always_on", "audio_safe_volume_state",
            "force_resizable_activities", "stay_on_while_plugged_in", "icon_blacklist",
            "window_animation_scale").forEach { key ->
            assertTrue("reset missing $key", reset.contains(key))
        }
    }
}

class ComfortTweaksTest {
    @Test
    fun buildSetAndDelete() {
        assertEquals("settings put system screen_off_timeout 30000", ComfortTweaks.buildSet("system", "screen_off_timeout", "30000"))
        assertEquals("settings delete system screen_off_timeout", ComfortTweaks.buildDelete("system", "screen_off_timeout"))
    }

    @Test
    fun timeoutPresets() {
        assertTrue(ComfortTweaks.TIMEOUT_PRESETS.contains(30_000))
        assertEquals(Int.MAX_VALUE, ComfortTweaks.TIMEOUT_NEVER)
    }

    @Test
    fun uimodeMapping() {
        assertEquals(mapOf("auto" to "0", "no" to "1", "yes" to "2"), ComfortTweaks.UIMODE_EXPECT)
        assertEquals("cmd uimode night yes", ComfortTweaks.buildUimode("yes"))
    }

    @Test
    fun grayBuild() {
        assertTrue(ComfortTweaks.buildGray(true).contains("accessibility_display_daltonizer_enabled 1"))
        assertTrue(ComfortTweaks.buildGray(true).contains("accessibility_display_daltonizer 0"))
        assertEquals("settings put secure accessibility_display_daltonizer_enabled 0", ComfortTweaks.buildGray(false))
    }

    @Test
    fun fontClampRange() {
        // Documented safe range; wider values break layouts.
        assertTrue(ComfortTweaks.FONT_PRESETS.all { it.toDouble() in 0.85..1.3 })
    }

    @Test
    fun lockNotifBuild() {
        assertEquals(
            "settings put secure lock_screen_show_notifications 1 && settings put secure lock_screen_allow_private_notifications 0",
            ComfortTweaks.buildLockNotif("1", "0")
        )
    }

    @Test
    fun buildResetCoversAll() {
        val reset = ComfortTweaks.buildReset()
        listOf("screen_off_timeout", "night_display_activated", "night_display_color_temperature",
            "night_display_auto_mode", "font_scale", "screen_brightness_mode", "master_mono",
            "notification_bubbles", "doze_tap_gesture", "cmd uimode night auto", "reduce_bright_colors_activated",
            "accessibility_display_daltonizer", "debug.hwui.force_dark", "lock_screen_lock_after_timeout",
            "lock_screen_show_notifications", "lock_screen_allow_private_notifications",
            "doze_enabled", "wake_gesture_enabled", "doze_pulse_on_pick_up",
            "camera_double_tap_power_gesture_disabled", "set-ignore-orientation-request").forEach { key ->
            assertTrue("reset missing $key", reset.contains(key))
        }
    }
}

class NetRadioTest {
    @Test
    fun captiveValues() {
        assertEquals("settings put global captive_portal_mode 0", NetRadio.buildSet("global", "captive_portal_mode", "0"))
    }

    @Test
    fun airplaneCmd() {
        assertEquals("cmd connectivity airplane-mode enable", NetRadio.buildAirplane(true))
        assertEquals("cmd connectivity airplane-mode disable", NetRadio.buildAirplane(false))
    }

    @Test
    fun ratProbe() {
        assertTrue(NetRadio.ratSupported("... set-allowed-network-types-for-users ..."))
        assertFalse(NetRadio.ratSupported("Can't find service: phone"))
        assertFalse(NetRadio.ratSupported(""))
    }

    @Test
    fun buildResetCoversAll() {
        val reset = NetRadio.buildReset()
        listOf("captive_portal_mode", "airplane-mode", "data_roaming", "svc nfc",
            "set-location-enabled", "allowed-network-types").forEach { key ->
            assertTrue("reset missing $key", reset.contains(key))
        }
    }
}

class MemTweaksTest {
    @Test
    fun maxCachedClamp() {
        assertTrue(MemTweaks.buildMaxCached(4).contains("max_cached_processes=8"))
        assertTrue(MemTweaks.buildMaxCached(999).contains("max_cached_processes=64"))
        assertTrue(MemTweaks.buildMaxCached(24).contains("max_cached_processes=24"))
    }

    @Test
    fun parseMaxCached() {
        assertEquals(24, MemTweaks.parseMaxCached("max_cached_processes=24"))
        assertEquals(48, MemTweaks.parseMaxCached("gc_timeout=5000,max_cached_processes=48"))
        assertNull(MemTweaks.parseMaxCached("null"))
        assertNull(MemTweaks.parseMaxCached(""))
        assertNull(MemTweaks.parseMaxCached(null))
    }

    @Test
    fun buckets() {
        assertEquals("am set-standby-bucket com.x restricted", MemTweaks.buildBucket("com.x", "restricted"))
        assertEquals("am get-standby-bucket com.x", MemTweaks.buildBucketGet("com.x"))
        assertTrue(MemTweaks.BUCKETS.containsAll(listOf("active", "working_set", "frequent", "rare", "restricted")))
        assertFalse(MemTweaks.safePkg("com.x; rm -rf"))
        assertTrue(MemTweaks.safePkg("com.example.game"))
    }
}

class GameTuningTest {
    @Test
    fun buildMode() {
        assertEquals("cmd game mode performance com.x", GameTuning.buildMode("com.x", "performance"))
        assertEquals("cmd game reset com.x", GameTuning.buildReset("com.x"))
    }

    @Test
    fun buildCustom() {
        assertEquals("cmd game set --downscale 0.7 --fps 60 com.x", GameTuning.buildCustom("com.x", "0.7", "60"))
        assertEquals("cmd game set com.x", GameTuning.buildCustom("com.x", "disable", "disable"))
        assertEquals("cmd game set --fps 90 com.x", GameTuning.buildCustom("com.x", null, "90"))
    }

    @Test
    fun safePkg() {
        assertTrue(GameTuning.safePkg("com.example.game"))
        assertFalse(GameTuning.safePkg("com.x && reboot"))
        assertFalse(GameTuning.safePkg(""))
    }
}

class DisplayExtrasTest {
    @Test
    fun buildSetAndDelete() {
        assertEquals("settings put secure clock_seconds 1", DisplayExtras.buildSet("secure", "clock_seconds", "1"))
        assertEquals("settings delete secure clock_seconds", DisplayExtras.buildDelete("secure", "clock_seconds"))
    }

    @Test
    fun quietBuildTogglesAllFive() {
        val on = DisplayExtras.buildQuiet(true)
        DisplayExtras.QUIET_KEYS.forEach { kv ->
            val (ns, key) = kv.split(" ")
            assertTrue(on.contains("settings put $ns $key 0"))
        }
        assertEquals(5, DisplayExtras.QUIET_KEYS.size)
        val off = DisplayExtras.buildQuiet(false)
        assertTrue(off.contains("settings put system sound_effects_enabled 1"))
    }

    @Test
    fun gestureBuildsBothSides() {
        val cmd = DisplayExtras.buildGesture("0.5")
        assertTrue(cmd.contains("back_gesture_inset_scale_left 0.5"))
        assertTrue(cmd.contains("back_gesture_inset_scale_right 0.5"))
    }

    @Test
    fun buildResetCoversAll() {
        val reset = DisplayExtras.buildReset()
        listOf("accessibility_display_inversion_enabled", "high_text_contrast_enabled",
            "font_weight_adjustment", "clock_seconds", "status_bar_show_battery_percent",
            "display_color_mode", "display_white_balance_enabled",
            "back_gesture_inset_scale_left", "back_gesture_inset_scale_right",
            "enable_freeform_support", "multi_press_timeout", "sound_effects_enabled",
            "lockscreen_sounds_enabled", "dtmf_tone_when_dialing", "haptic_feedback_enabled",
            "vibrate_when_ringing", "notification_snooze_options", "always_finish_activities",
            "wifi_scan_throttle_enabled", "wifi_suspend_optimizations_enabled",
            "network_avoid_bad_wifi", "bluetooth_disable_absolute_volume",
            "auto_time", "auto_time_zone", "screen_auto_brightness_adj").forEach { key ->
            assertTrue("reset missing $key", reset.contains(key))
        }
    }
}

class AppOpsRound2Test {
    @Test
    fun extraOpsResolve() {
        listOf("REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW",
            "WRITE_SETTINGS", "AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "SCHEDULE_EXACT_ALARM").forEach { op ->
            assertTrue(AppOpsParser.ALL_OPS.contains(op))
            assertTrue(AppOpsParser.buildSet("com.x", op, "ignore").contains(op))
            assertTrue(AppOpsParser.safePkg("com.x"))
        }
    }

    @Test
    fun batchUnchanged() {
        // One-tap batch stays the original six privacy ops.
        assertEquals(
            listOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
                "READ_CLIPBOARD", "POST_NOTIFICATION"),
            AppOpsParser.OPS
        )
    }
}

class SysTweaksMsaaTest {
    @Test
    fun dualKeyBuild() {
        val on = SysTweaks.buildMsaa(true)
        assertTrue(on.contains("settings put global enable_msaa 1"))
        assertTrue(on.contains("settings put global force_msaa 1"))
        val off = SysTweaks.buildMsaa(false)
        assertTrue(off.contains("settings put global enable_msaa 0"))
        assertTrue(off.contains("settings put global force_msaa 0"))
    }

    @Test
    fun dualKeyVerify() {
        val v = SysTweaks.buildMsaaVerify()
        assertTrue(v.contains("enable_msaa") && v.contains("force_msaa"))
    }
}
