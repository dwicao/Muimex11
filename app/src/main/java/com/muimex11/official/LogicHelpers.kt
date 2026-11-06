package com.muimex11.official

import kotlin.math.roundToInt

/** Pure foreground-package parser — unit-testable, no Android deps. */
object FgPkgParser {
    private val fgRegex =
        Regex("(?:topResumedActivity|ResumedActivity|mFocusedApp)[^\\n]*u0\\s+(\\w[\\w.]*)/")

    private val SYSTEM_SKIP = setOf(
        "com.android.systemui",
        "com.google.android.googlequicksearchbox"
    )

    fun parse(dump: String, selfPkg: String): String? {
        return fgRegex.findAll(dump)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter {
                it != selfPkg &&
                    it !in SYSTEM_SKIP &&
                    !it.contains("launcher", ignoreCase = true) &&
                    !it.endsWith(".home")
            }
            .distinct()
            .firstOrNull()
    }

    /** FPS sanity gate used by GameSpaceService. */
    fun saneFps(lastCount: Long, total: Long, dtMs: Long): Boolean {
        if (lastCount < 0 || dtMs < 700) return false
        val frames = total - lastCount
        return frames in 1L..1500L
    }

    fun fps(total: Long, lastCount: Long, dtMs: Long): Int? {
        if (!saneFps(lastCount, total, dtMs)) return null
        return (((total - lastCount) * 1000f) / dtMs).coerceIn(0f, 360f).toInt()
    }
}

/** Pure resolution-cycle decision — unit-testable. */
object ResolutionController {
    enum class Step { TO_75, TO_50, RESET }

    fun next(ratio: Float): Step = when {
        ratio > 0.9f -> Step.TO_75
        ratio > 0.6f -> Step.TO_50
        else -> Step.RESET
    }
}

/** Pure update-metadata parser — unit-testable. */
object UpdateParser {
    data class Update(val code: Int, val name: String, val apkUrl: String, val notes: String, val mandatory: Boolean)

    fun isHttps(url: String): Boolean = url.startsWith("https://")

    /** Only GitHub release hosts are trusted for APK downloads. */
    fun isTrustedApkHost(url: String): Boolean = try {
        val h = java.net.URL(url).host.lowercase()
        h == "github.com" || h.endsWith(".github.com") ||
            h == "objects.githubusercontent.com" ||
            h == "release-assets.githubusercontent.com" ||
            h == "raw.githubusercontent.com"
    } catch (_: Exception) { false }

    fun isSafeApkUrl(url: String): Boolean = isHttps(url) && isTrustedApkHost(url)

    fun shouldUpdate(latestCode: Int, currentCode: Int): Boolean = latestCode > currentCode
}

/** Central shell-escaping gate — every dynamic pkg/arg must pass through here. */
object ShellSanitizer {
    private val PKG_RE = Regex("^[A-Za-z0-9._]+$")
    const val MAX_PKG_LEN = 256

    fun isSafePkg(pkg: String): Boolean =
        pkg.length in 1..MAX_PKG_LEN && PKG_RE.matches(pkg)

    /** Returns pkg if safe, null otherwise — callers must abort on null. */
    fun cleanPkg(pkg: String?): String? {
        if (pkg.isNullOrEmpty()) return null
        val t = pkg.trim()
        return if (isSafePkg(t)) t else null
    }

    /** Single-quote escape for settings values. */
    fun esc(v: String): String = v.replace("'", "'\\''")

    fun requirePkg(pkg: String, what: String = "package"): String =
        cleanPkg(pkg) ?: throw IllegalArgumentException("unsafe $what: $pkg")
}

/**
 * Battery-saver presets. Pure builders — no Android deps.
 * Only uses reversible `settings`/`cmd power`/`dumpsys deviceidle` keys.
 * No /sys writes: undervolting needs root + custom kernel, unavailable via Shizuku.
 */
object BatterySaver {
    const val MODE_OFF = "off"
    const val MODE_BALANCED = "balanced"
    const val MODE_SAVER = "saver"
    const val MODE_EXTREME = "extreme"

    /** Settings keys we snapshot before applying, so reset restores user values. */
    val SNAPSHOT_KEYS = listOf(
        "low_power",
        "adaptive_battery_management_enabled",
        "wifi_scan_always_enabled",
        "mobile_data_always_on",
        "ble_scan_always_enabled"
    )

    /** Preset values per mode (settings global namespace). */
    fun presetFor(mode: String): Map<String, String> = when (mode) {
        MODE_SAVER -> mapOf(
            "low_power" to "1",
            "adaptive_battery_management_enabled" to "1",
            "wifi_scan_always_enabled" to "0",
            "mobile_data_always_on" to "0",
            "ble_scan_always_enabled" to "0"
        )
        MODE_EXTREME -> mapOf(
            "low_power" to "1",
            "adaptive_battery_management_enabled" to "1",
            "wifi_scan_always_enabled" to "0",
            "mobile_data_always_on" to "0",
            "ble_scan_always_enabled" to "0"
        )
        else -> mapOf(
            "low_power" to "0",
            "adaptive_battery_management_enabled" to "1",
            "wifi_scan_always_enabled" to "1",
            "mobile_data_always_on" to "1",
            "ble_scan_always_enabled" to "1"
        )
    }

    /** Shell-escaped single value (digits only in our presets, but stay safe). */
    internal fun esc(v: String): String = v.replace("'", "'\\''")

    fun buildApplyCmd(mode: String): String {
        val p = presetFor(mode)
        val sets = SNAPSHOT_KEYS.joinToString("; ") { k -> "settings put global $k '${esc(p[k].orEmpty())}'" }
        val power = "cmd power set-adaptive-power-saver-enabled true"
        val idle = if (mode == MODE_EXTREME) {
            "dumpsys deviceidle enable all; dumpsys deviceidle force-idle"
        } else {
            "dumpsys deviceidle enable all"
        }
        return "$sets; $power; $idle"
    }

    /** prev: key -> raw `settings get` output ("1"/"0"/"null"/""). */
    fun buildResetCmd(prev: Map<String, String>): String {
        val sets = SNAPSHOT_KEYS.joinToString("; ") { k ->
            val v = prev[k].orEmpty().trim()
            if (v == "null" || v.isEmpty()) "settings delete global $k"
            else "settings put global $k '${esc(v)}'"
        }
        return "$sets; cmd power set-adaptive-power-saver-enabled true; dumpsys deviceidle enable all"
    }

    fun labelRes(mode: String): Int = when (mode) {
        MODE_SAVER -> R.string.status_battery_saver
        MODE_EXTREME -> R.string.status_battery_extreme
        MODE_BALANCED -> R.string.status_battery_balanced
        else -> R.string.status_default
    }
}

/** Pure Doze whitelist helpers — unit-testable. */
object DozeParser {
    private val pkgToken = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")

    /** Extract package names from `dumpsys deviceidle whitelist` output. */
    fun parseWhitelist(dump: String): List<String> =
        pkgToken.findAll(dump).map { it.value }.distinct().toList().sorted()

    /** Third-party (user-installed) packages that are Doze-exempt = best restrict candidates. */
    fun exemptThirdParty(whitelist: List<String>, thirdParty: List<String>): List<String> {
        val third = thirdParty.map { it.substringAfter("package:").trim() }
            .filter { it.isNotEmpty() }.toSet()
        return whitelist.filter { it in third }.sorted()
    }

    fun buildRemoveCmd(pkgs: List<String>): String =
        pkgs.joinToString(" && ") { "dumpsys deviceidle whitelist -$it" }

    fun buildRestoreCmd(pkgs: List<String>): String =
        pkgs.joinToString(" && ") { "dumpsys deviceidle whitelist +$it" }
}

/** Pure `cmd appops` helpers — unit-testable. No root needed. */
object AppOpsParser {
    const val MODE_ALLOW = "allow"
    const val MODE_IGNORE = "ignore"
    const val MODE_DEFAULT = "default"

    /** Ops exposed in the UI. */
    val OPS = listOf(
        "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "READ_CLIPBOARD", "POST_NOTIFICATION"
    )

    private val lineRe = Regex("^\\s*([A-Z_]+)\\s*:\\s*(\\w+)", RegexOption.MULTILINE)

    /** Parse `cmd appops get <pkg>` output into op -> mode. */
    fun parseGet(dump: String): Map<String, String> =
        lineRe.findAll(dump).associate { m ->
            m.groupValues[1] to m.groupValues[2].lowercase()
        }

    fun buildSet(pkg: String, op: String, mode: String): String =
        "cmd appops set $pkg $op $mode"

    fun buildReset(pkg: String, op: String): String =
        "cmd appops set $pkg $op $MODE_DEFAULT"

    fun safePkg(pkg: String): Boolean = pkg.matches(Regex("^[A-Za-z0-9._]+$")) &&
        OPS.isNotEmpty() && pkg.length <= 256
}

/** Conservative debloat presets — unit-testable. Only ads/telemetry/assistant pkgs. */
object DebloatPresets {
    data class Preset(val id: String, val pkgs: List<String>)

    val META = Preset(
        "meta", listOf(
            "com.facebook.katana", "com.facebook.orca", "com.facebook.services",
            "com.facebook.system", "com.facebook.appmanager",
            "com.meta.appmanager", "com.meta.services", "com.meta.system"
        )
    )
    val MIUI = Preset(
        "miui", listOf(
            "com.miui.analytics", "com.miui.msa.global", "com.xiaomi.mipicks",
            "com.miui.yellowpage", "com.xiaomi.joyose", "com.miui.daemon"
        )
    )
    val SAMSUNG = Preset(
        "samsung", listOf(
            "com.samsung.android.bixby.agent", "com.samsung.android.bixby.service",
            "com.samsung.android.visionintelligence", "com.samsung.android.aremoji",
            "com.samsung.android.ardrawing"
        )
    )

    val ALL = listOf(META, MIUI, SAMSUNG)

    fun buildDisable(pkgs: List<String>, userId: Int): String =
        pkgs.joinToString(" && ") { "pm disable-user --user $userId $it" }

    fun buildEnable(pkgs: List<String>, userId: Int): String =
        pkgs.joinToString(" && ") { "pm enable --user $userId $it" }
}

/** Bounded adaptive-thermal threshold presets — unit-testable.
 * Free numeric input is deliberately NOT offered: a release point of 60C
 * would cook the device, an engage point near room temp degrades to a dumb
 * always-on bypass. Presets keep every combination inside a safe band. */
object ThermalPresets {
    data class Preset(val id: String, val low: Int, val high: Int)

    val CONSERVATIVE = Preset("conservative", 36, 43)
    val BALANCED = Preset("balanced", 38, 45)
    val AGGRESSIVE = Preset("aggressive", 40, 48)

    val ALL = listOf(CONSERVATIVE, BALANCED, AGGRESSIVE)

    fun isSane(low: Int, high: Int): Boolean =
        low in 30..50 && high in 35..55 && low + 3 <= high

    fun clamp(low: Int, high: Int): Pair<Int, Int> {
        val l = low.coerceIn(30, 50)
        val h = high.coerceIn(35, 55)
        return if (l + 3 <= h) l to h else BALANCED.low to BALANCED.high
    }
}

/** Pure audio/latency preset helpers — unit-testable. Reversible settings only. */
object AudioPresets {    const val PRESET_OFF = "off"
    const val PRESET_LOWLAT = "lowlat"

    /** namespace to keys snapshotted before applying. */
    val SNAPSHOT = listOf(
        "system:sound_effects_enabled",
        "system:haptic_feedback_enabled",
        "global:bluetooth_disable_absolute_volume"
    )

    fun key(ns: String, k: String) = "$ns:$k"

    /** Preset values. */
    fun presetFor(preset: String): Map<String, String> = when (preset) {
        PRESET_LOWLAT -> mapOf(
            key("system", "sound_effects_enabled") to "0",
            key("system", "haptic_feedback_enabled") to "0",
            key("global", "bluetooth_disable_absolute_volume") to "1"
        )
        else -> mapOf(
            key("system", "sound_effects_enabled") to "1",
            key("system", "haptic_feedback_enabled") to "1",
            key("global", "bluetooth_disable_absolute_volume") to "0"
        )
    }

    fun getCmd(ns: String, k: String): String = "settings get $ns $k"

    fun buildApply(preset: String): String {
        val p = presetFor(preset)
        return SNAPSHOT.joinToString("; ") { ck ->
            val (ns, k) = ck.split(":")
            "settings put $ns $k '${p[ck].orEmpty()}'"
        }
    }

    /** prev: composite "ns:key" -> raw `settings get` output. */
    fun buildReset(prev: Map<String, String>): String {
        return SNAPSHOT.joinToString("; ") { ck ->
            val (ns, k) = ck.split(":")
            val v = prev[ck].orEmpty().trim()
            if (v == "null" || v.isEmpty()) "settings delete $ns $k"
            else "settings put $ns $k '$v'"
        }
    }
}

/** Pure Android 12+ sensor-privacy helpers — unit-testable. */
object SensorCmd {
    const val MIC = 1
    const val CAMERA = 2

    fun sdkOk(sdkInt: Int): Boolean = sdkInt >= 31

    /** `block=true` = `enable` privacy (sensor OFF). Confusing AOSP naming kept explicit. */
    fun buildSet(userId: Int, sensor: Int, block: Boolean): String =
        if (block) "cmd sensor_privacy enable $userId $sensor"
        else "cmd sensor_privacy disable $userId $sensor"

    fun buildBlockBoth(userId: Int): String =
        "${buildSet(userId, CAMERA, true)}; ${buildSet(userId, MIC, true)}"

    fun buildUnblockBoth(userId: Int): String =
        "${buildSet(userId, CAMERA, false)}; ${buildSet(userId, MIC, false)}"
}

/**
 * Adaptive thermal governor — pure decision logic, unit-testable.
 * Instead of a dumb always-on bypass: engage the bypass only while cool,
 * release throttling control back to the system when hot. Hysteresis band
 * (engage <= 38C, release >= 45C) prevents flapping.
 */
object AdaptiveThermal {
    const val ENGAGE_BELOW = 38f
    const val RELEASE_ABOVE = 45f

    fun decide(bypassed: Boolean, tempC: Float): Boolean = when {
        tempC >= RELEASE_ABOVE -> false
        tempC <= ENGAGE_BELOW -> true
        else -> bypassed
    }

    /**
     * Parse `cat` output of thermal_zone temp files, one millidegree value
     * per line. Returns the hottest sane zone in Celsius, null if none.
     */
    fun parseZoneTemps(dump: String): Float? {
        val temps = dump.lines()
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it in 20_000..120_000 }
        if (temps.isEmpty()) return null
        return temps.max() / 1000f
    }
}

/** Pure App Standby bucket helpers — unit-testable. */
object StandbyBuckets {
    val NAMES = listOf("active", "working_set", "frequent", "rare", "restricted")

    private val INT_TO_NAME = mapOf(
        5 to "exempted",
        10 to "active",
        20 to "working_set",
        30 to "frequent",
        40 to "rare",
        50 to "restricted"
    )

    /** Parse `am get-standby-bucket <pkg>` output (int or name) to canonical name. */
    fun parseGet(dump: String): String? {
        val t = dump.trim().lowercase()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return INT_TO_NAME[it] }
        val first = t.split(Regex("\\s+")).firstOrNull() ?: return null
        return if (first in NAMES || first == "exempted") first else null
    }

    fun buildGet(pkg: String): String = "am get-standby-bucket $pkg"

    fun buildSet(pkg: String, bucket: String): String =
        "am set-standby-bucket $pkg $bucket"
}

/** Pure app-hibernation helpers — unit-testable. Android 12+. */
object Hibernation {
    fun sdkOk(sdkInt: Int): Boolean = sdkInt >= 31

    /** Parse `cmd app_hibernation get-state <pkg>` ("true"/"false"); null = unsupported. */
    fun parseGet(dump: String): Boolean? {
        val t = dump.trim().lowercase()
        val first = t.split(Regex("\\s+")).firstOrNull() ?: return null
        return when (first) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun buildGet(pkg: String): String = "cmd app_hibernation get-state $pkg"

    fun buildSet(pkg: String, hibernate: Boolean): String =
        "cmd app_hibernation set-state $pkg $hibernate"
}

/** Pure benchmark parsers — unit-testable. */
object BenchmarkCalc {
    private val totalRe = Regex("TotalTime:\\s*(\\d+)")

    /** Parse `am start -W` output to cold-start ms. */
    fun parseAmStartW(dump: String): Long? =
        totalRe.find(dump)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun avg(samples: List<Long>): Long =
        if (samples.isEmpty()) 0 else samples.sum() / samples.size

    /** FPS from gfxinfo frame counters (delegates to the tested sanity gate). */
    fun fps(total: Long, lastCount: Long, dtMs: Long): Int? =
        FgPkgParser.fps(total, lastCount, dtMs)
}

/** Per-user uninstall with factory restore — pure command builders. */
object UninstallCmd {
    /** Keep user data (-k) so a restore brings the app back as it was. */
    fun buildUninstall(userId: Int, pkg: String): String =
        "pm uninstall -k --user $userId $pkg"

    /** Re-installs a factory (system-image) app for the user. */
    fun buildRestore(pkg: String): String =
        "cmd package install-existing $pkg"

    /** Mirrors the freeze flow: exit 0 with no error markers in output. */
    fun succeeded(code: Int, out: String): Boolean {
        if (code != 0) return false
        val low = out.lowercase()
        return !low.contains("exception") && !low.contains("error") &&
            !low.contains("failed") && !low.contains("failure")
    }

    /** True when `pm list packages --user <uid> <pkg>` shows nothing. */
    fun isGone(listOutput: String): Boolean = listOutput.trim().isEmpty()
}

/** APK backup helpers — pure parsing/naming, shell does the copying. */
object ApkBackup {
    /** `pm path <pkg>` prints one `package:<path>` line per APK (base + splits). */
    fun parsePmPath(dump: String): List<String> =
        dump.lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && it.startsWith("/") }

    private fun safeFilePart(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).trim('_')
            .ifEmpty { "app" }

    fun destDir(pkg: String, versionName: String): String =
        "/sdcard/Download/Muimex11/${safeFilePart(pkg)}-${safeFilePart(versionName)}"

    fun buildCopyCmd(srcs: List<String>, destDir: String): String? {
        if (srcs.isEmpty()) return null
        val d = "'${destDir.replace("'", "'\\''")}'"
        val copies = srcs.joinToString(" && ") { s ->
            "cp '${s.replace("'", "'\\''")}' $d/"
        }
        return "mkdir -p $d && $copies"
    }
}

/** Reboot menu — pure command builders, all verified Shizuku-shell safe. */
object RebootCmd {
    const val SYSTEM = 0
    const val RECOVERY = 1
    const val BOOTLOADER = 2
    const val SYSTEM_UI = 3
    const val SCREEN_OFF = 4

    fun build(mode: Int): String? = when (mode) {
        SYSTEM -> "svc power reboot"
        RECOVERY -> "svc power reboot recovery"
        BOOTLOADER -> "svc power reboot bootloader"
        SYSTEM_UI -> "am force-stop com.android.systemui"
        SCREEN_OFF -> "input keyevent 223"
        else -> null
    }
}

/** Orientation lock — pure builders/parsers around system rotation settings. */
object OrientationMode {
    const val AUTO = 0
    const val PORTRAIT = 1
    const val LANDSCAPE = 2
    const val REVERSE_PORTRAIT = 3
    const val REVERSE_LANDSCAPE = 4

    fun buildApply(mode: Int): String? = when (mode) {
        AUTO -> "settings put system accelerometer_rotation 1"
        PORTRAIT -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 0"
        LANDSCAPE -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 1"
        REVERSE_PORTRAIT -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 2"
        REVERSE_LANDSCAPE -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 3"
        else -> null
    }

    /** Map live settings to a mode; unknown combos fall back to AUTO display. */
    fun parse(auto: String?, user: String?): Int {
        if (auto?.trim() == "1") return AUTO
        return when (user?.trim()) {
            "0" -> PORTRAIT
            "1" -> LANDSCAPE
            "2" -> REVERSE_PORTRAIT
            "3" -> REVERSE_LANDSCAPE
            else -> AUTO
        }
    }

    fun isValid(mode: Int): Boolean = mode in AUTO..REVERSE_LANDSCAPE
}

/** Touch latency — pure builders/validators. Split per key so a ROM that
 * blocks one key (e.g. Realme/ColorOS blocking pointer_speed) still gets
 * the other, with an honest partial status instead of a silent all-or-nothing. */
object TouchTweak {
    const val POINTER_KEY = "pointer_speed"
    const val LONG_PRESS_KEY = "long_press_timeout"
    const val LONG_PRESS_VALUE = "250"
    const val LONG_PRESS_RESET = "400"

    /** Highest-first: the ROM keeps the first value its validator accepts. */
    val POINTER_PROBE = listOf("7", "6", "5", "4", "3", "2", "1")

    fun buildPointer(value: String): String = "settings put system $POINTER_KEY $value"

    fun buildLongPress(): String = "settings put secure $LONG_PRESS_KEY $LONG_PRESS_VALUE"

    fun buildReset(): String =
        "settings delete system $POINTER_KEY && settings put secure $LONG_PRESS_KEY $LONG_PRESS_RESET"

    /** Any positive level counts: the device really is above the 0 default. */
    fun parsePointerLevel(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it != 0 }

    fun isPointerApplied(raw: String?): Boolean =
        (parsePointerLevel(raw) ?: 0) > 0

    fun isLongPressApplied(raw: String?): Boolean = raw?.trim() == LONG_PRESS_VALUE

    /** 0 = none, 1 = partial, 2 = full. */
    fun appliedCount(pointer: Boolean, longPress: Boolean): Int =
        (if (pointer) 1 else 0) + (if (longPress) 1 else 0)
}

/** List/grid/compact view-mode cycle — pure so the order is unit-tested. */
object ViewMode {
    const val LIST = "list"
    const val GRID = "grid"
    const val COMPACT = "compact"

    fun next(mode: String): String = when (mode) {
        LIST -> GRID
        GRID -> COMPACT
        else -> LIST
    }

    fun isGridLike(mode: String): Boolean = mode == GRID || mode == COMPACT
}

/**
 * Per-app language via the LocaleManager shell (API 33+).
 * Exact AOSP syntax: `cmd locale set-app-locales <pkg> --app-locales <tags>`.
 * Every mutation must be verified with [parseGet] — ROMs without the shell
 * command fail the read-back instead of lying about success.
 */
object AppLocale {
    /** AOSP default when no app-locales are set. */
    const val FOLLOW_SYSTEM = ""

    fun buildGet(pkg: String): String = "cmd locale get-app-locales $pkg"

    fun buildSet(pkg: String, tags: String): String? {
        if (!isValidTags(tags)) return null
        return "cmd locale set-app-locales $pkg --app-locales $tags"
    }

    fun buildClear(pkg: String): String =
        "cmd locale set-app-locales $pkg --app-locales \"\""

    fun isValidTags(tags: String): Boolean {
        if (tags.isBlank() || tags.length > 64) return false
        return tags.split(",").all { t ->
            t.trim().matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z]{2,4})?(-[A-Za-z0-9]+)?$"))
        }
    }

    /** Normalize `get-app-locales` output to a canonical tag list ("" = system). */
    fun parseGet(raw: String?): String {
        val t = (raw ?: "").trim().removePrefix("[").removeSuffix("]").trim()
        if (t.isEmpty() || t.equals("null", ignoreCase = true)) return FOLLOW_SYSTEM
        if (t.startsWith("Error", ignoreCase = true)) return FOLLOW_SYSTEM
        return t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
    }

    fun matches(raw: String?, tags: String): Boolean =
        parseGet(raw).equals(tags.trim(), ignoreCase = true)
}

/** One-tap AppOps batch preset — pure command joinery over AppOpsParser. */
object AppOpsBatch {
    /** Build a single chained `cmd appops set <pkg> <op> ignore` command. */
    fun buildCmd(pkgs: List<String>): String? {
        val parts = pkgs.mapNotNull { ShellSanitizer.cleanPkg(it) }
            .distinct()
            .flatMap { pkg -> AppOpsParser.OPS.map { op -> AppOpsParser.buildSet(pkg, op, AppOpsParser.MODE_IGNORE) } }
        if (parts.isEmpty()) return null
        return parts.joinToString(" && ")
    }

    fun ruleEntries(pkgs: List<String>): Map<String, Map<String, String>> =
        pkgs.mapNotNull { ShellSanitizer.cleanPkg(it) }.distinct()
            .associateWith { AppOpsParser.OPS.associateWith { AppOpsParser.MODE_IGNORE } }
}

/** Keep screen awake while gaming — snapshot/restore of screen_off_timeout. */
object KeepAwake {
    /** ~24 days: the conventional "never time out" value. */
    const val NEVER_MS = "2147483647"
    const val KEY = "screen_off_timeout"

    fun buildSet(): String = "settings put system $KEY $NEVER_MS"

    fun buildRestore(prev: String?): String {
        val p = (prev ?: "").trim()
        return if (p.isEmpty() || p == "null") "settings delete system $KEY"
        else "settings put system $KEY $p"
    }
}

/**
 * Static danger scan for user-supplied shell scripts. Conservative on purpose:
 * only flags patterns that are destructive with no plausible benign use on
 * the matched path (wiping /, dd to block devices, mkfs, fork bombs,
 * flashing, chmod 777 on /). `rm -rf /sdcard/foo` does NOT match.
 */
object DangerScan {
    data class Hit(val pattern: String, val line: String)

    private val rules: List<Pair<String, Regex>> = listOf(
        "rm -rf /" to Regex("""(^|[;&|]\s*)(sudo\s+)?rm\s+[^\n|;&]*\s/(?:\s|$|[;&*])"""),
        "dd to block device" to Regex("""\bdd\b[^\n|;&]*of=/dev/"""),
        "mkfs" to Regex("""(^|[;&|]\s*)(sudo\s+)?mkfs[\s.]"""),
        "fork bomb" to Regex(""":\(\)\s*\{\s*:\s*\|\s*:&\s*\}"""),
        "fastboot/flash" to Regex("""fastboot\s+flash|\bflash_image\b"""),
        "chmod 777 /" to Regex("""chmod\s+(-R\s+)?777\s+/(?:\s|$)""")
    )

    fun scan(text: String, maxHits: Int = 8): List<Hit> {
        val out = mutableListOf<Hit>()
        text.lines().take(2000).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            rules.forEach { (name, re) ->
                if (out.size >= maxHits) return out
                if (re.containsMatchIn(line)) out += Hit(name, line.take(160))
            }
        }
        return out.distinct()
    }
}

/** Token guard for a future automation (Tasker/MacroDroid) API. Pure + tested. */
object AutomationAuth {
    fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    fun isWellFormed(t: String?): Boolean =
        t != null && t.length >= 16 && t.all { it.isLetterOrDigit() }

    fun matches(stored: String?, provided: String?): Boolean {
        if (!isWellFormed(stored) || !isWellFormed(provided)) return false
        return java.security.MessageDigest.isEqual(
            stored!!.toByteArray(), provided!!.toByteArray()
        )
    }
}

/**
 * "Who spied on me" timeline from `dumpsys appops`. The dump format varies
 * across OEMs/versions, so parsing is best-effort: unknown lines are skipped,
 * never fatal. Timestamps are `time=+<dur> ago` relative to dump time.
 */
object AppOpsTimeline {
    data class Access(val op: String, val pkg: String, val timeMs: Long)

    val WATCHED = setOf(
        "CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION", "READ_CLIPBOARD"
    )

    private val pkgHeader = Regex("^\\s*[Pp]ackage\\s+([A-Za-z0-9._]+)")
    private val opToken = Regex("^\\s*([A-Z_]+)(?:\\(\\d+\\))?\\s*:")
    private val timeToken = Regex("time=\\+([^\\s;]+)")

    private val durToken = Regex("(\\d+(?:\\.\\d+)?)(d|h|ms|m|s)")

    /** Android duration strings like 1d2h3m, 500ms, 4.5s → millis. */
    fun parseDurationAgo(raw: String): Long? {
        var total = 0L
        var matched = false
        // ms must win over m: regex alternation tries d|h|ms|m|s in order.
        durToken.findAll(raw).forEach { m ->
            matched = true
            val v = m.groupValues[1].toDoubleOrNull() ?: return@forEach
            total += when (m.groupValues[2]) {
                "d" -> (v * 86_400_000L).toLong()
                "h" -> (v * 3_600_000L).toLong()
                "ms" -> v.toLong()
                "m" -> (v * 60_000L).toLong()
                else -> (v * 1000L).toLong()
            }
        }
        return if (matched) total else null
    }

    fun parseDump(dump: String, nowMs: Long, maxEntries: Int = 150): List<Access> {
        val out = mutableListOf<Access>()
        var pkg = ""
        dump.lines().forEach { line ->
            pkgHeader.find(line)?.let { pkg = it.groupValues[1]; return@forEach }
            if (pkg.isEmpty()) return@forEach
            val op = opToken.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
            if (op !in WATCHED) return@forEach
            val dur = timeToken.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
            val agoMs = parseDurationAgo(dur) ?: return@forEach
            out += Access(op, pkg, (nowMs - agoMs).coerceAtLeast(0L))
            if (out.size >= maxEntries) return out.sortedByDescending { it.timeMs }
        }
        return out.sortedByDescending { it.timeMs }
    }
}

/**
 * Tasker/MacroDroid automation commands (pure parsing; the receiver enforces
 * the token). v1 actions: `renderer` (value vulkan|opengl) and `game_boost`.
 */
object TaskerCmd {
    const val ACTION_RENDERER = "renderer"
    const val ACTION_BOOST = "game_boost"

    fun parseRenderer(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "vulkan", "skiavk" -> "Vulkan"
        "opengl", "opengl-es", "skiagl" -> "OpenGL"
        else -> null
    }

    fun parseAction(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        ACTION_RENDERER -> ACTION_RENDERER
        ACTION_BOOST -> ACTION_BOOST
        else -> null
    }
}

/** Ping sampling for the latency overlay — pure output parsing. */
object PingProbe {
    const val PRIMARY_HOST = "8.8.8.8"
    const val FALLBACK_HOST = "1.1.1.1"

    fun build(host: String): String = "ping -c 1 -W 2 $host"

    private val timeRe = Regex("time=(\\d+(?:\\.\\d+)?)\\s*ms")

    /** Round-trip ms from `ping -c1` output, or null when unreachable. */
    fun parseMs(output: String): Double? =
        timeRe.find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    /** Jitter proxy: absolute delta vs the previous sample. */
    fun jitterMs(prevMs: Double?, curMs: Double): Double? =
        if (prevMs == null) null else kotlin.math.abs(curMs - prevMs)
}

/**
 * Per-app background-data block over `cmd netpolicy` (verified against AOSP
 * NetworkPolicyManagerShellCommand: add/remove/list restrict-background-blacklist,
 * get/set restrict-background). POLICY_REJECT_METERED_BACKGROUND == the stock
 * Settings "Background data: off" toggle: enforced on metered networks, no
 * Data Saver needed, no root. Every mutation must be verified with [parseList].
 */
object NetPolicy {
    fun buildAdd(uid: Int): String? =
        if (uid > 0) "cmd netpolicy add restrict-background-blacklist $uid" else null

    fun buildRemove(uid: Int): String? =
        if (uid > 0) "cmd netpolicy remove restrict-background-blacklist $uid" else null

    const val CMD_LIST = "cmd netpolicy list restrict-background-blacklist"
    const val CMD_GET_SAVER = "cmd netpolicy get restrict-background"

    fun buildSetSaver(on: Boolean): String =
        "cmd netpolicy set restrict-background ${if (on) "true" else "false"}"

    /** `Restrict background blacklisted UIDs: 10123 10145` (or `: none`). */
    fun parseList(raw: String?): Set<Int> {
        if (raw.isNullOrBlank() || raw.startsWith("Error", ignoreCase = true)) return emptySet()
        return Regex("\\b\\d+\\b").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toSet()
    }

    /** `Restricted mode status: enabled|disabled` (Data Saver master). */
    fun isSaverOn(raw: String?): Boolean = raw?.contains("enabled", ignoreCase = true) == true
}

/** Heat response: drop to 60 Hz while hot, restore the snapshot when cool. */
object HeatResponse {    const val COOL_HZ = 60

    fun buildDrop(): String =
        "settings put system min_refresh_rate $COOL_HZ && settings put system peak_refresh_rate $COOL_HZ"

    fun buildRestore(prevMin: String?, prevMax: String?): String {
        fun one(key: String, v: String?): String {
            val p = (v ?: "").trim()
            return if (p.isEmpty() || p == "null") "settings delete system $key"
            else "settings put system $key $p"
        }
        return one("min_refresh_rate", prevMin) + " && " + one("peak_refresh_rate", prevMax)
    }
}

/** Live cell snapshot from `getprop` gsm.* keys — pure parsing. */
object CellInfo {
    data class Snapshot(val operator: String, val networkType: String, val simState: String)

    fun parseGetprop(dump: String): Snapshot {
        fun prop(key: String): String =
            Regex("^\\[?$key\\]?:?\\s*\\[([^\\]]*)\\]", RegexOption.MULTILINE)
                .find(dump)?.groupValues?.getOrNull(1).orEmpty().trim()
        // getprop prints `[key]: [value]`; dumpsys-style `key=value` also accepted.
        fun alt(key: String): String =
            Regex("(?m)^$key=(.+)$").find(dump)?.groupValues?.getOrNull(1).orEmpty().trim()
        val op = prop("gsm.operator.alpha").ifEmpty { alt("gsm.operator.alpha") }
        val net = prop("gsm.network.type").ifEmpty { alt("gsm.network.type") }
        val sim = prop("gsm.sim.state").ifEmpty { alt("gsm.sim.state") }
        return Snapshot(
            operator = op.ifEmpty { "—" },
            networkType = net.ifEmpty { "—" },
            simState = sim.ifEmpty { "—" }
        )
    }
}

/** Battery health from `dumpsys battery` — pure, defensive parsing. */
object BatteryHealth {
    data class Info(
        val level: Int,
        val health: String,
        val voltageV: Double?,
        val tempC: Double?,
        val tech: String,
        val cycles: Int?
    )

    private val healthNames = mapOf(
        1 to "Unknown", 2 to "Good", 3 to "Overheat", 4 to "Dead",
        5 to "Over voltage", 6 to "Failure", 7 to "Cold"
    )

    private fun kv(dump: String, key: String): String? =
        Regex("(?m)^\\s*$key\\s*[:=]\\s*(.+?)\\s*$").find(dump)?.groupValues?.getOrNull(1)

    fun parse(dump: String): Info? {
        val level = kv(dump, "level")?.trim()?.toIntOrNull() ?: return null
        val healthInt = kv(dump, "health")?.trim()?.toIntOrNull()
        val voltageMv = kv(dump, "voltage")?.trim()?.toIntOrNull()
        val tempTenth = kv(dump, "temperature")?.trim()?.toIntOrNull()
        val cycleKeys = listOf("mBatteryCycleCount", "cycle_count", "cyclecount", "battery_cycle_count")
        val cycles = cycleKeys.firstNotNullOfOrNull { k -> kv(dump, k)?.trim()?.toIntOrNull() }
        return Info(
            level = level,
            health = healthNames[healthInt] ?: "Unknown",
            voltageV = voltageMv?.let { it / 1000.0 },
            tempC = tempTenth?.let { it / 10.0 },
            tech = kv(dump, "technology")?.trim().orEmpty().ifEmpty { "—" },
            cycles = cycles
        )
    }
}

/**
 * Refresh-rate handling. Presets come from the OS-reported display modes
 * (Display.getSupportedModes), NOT a hardcoded list: panels that lack a
 * mode (e.g. no 30 Hz on many 120 Hz phones) make the ROM silently revert
 * `peak_refresh_rate` to the nearest supported value — Realme does this,
 * One UI tolerates it. Every apply is verified with a read-back.
 */
object RefreshRates {
    fun supportedFromModes(rates: List<Float>): List<Int> =
        rates.map { it.roundToInt() }.filter { it in 1..1000 }.distinct().sorted()

    fun nearestSupported(want: Int, supported: List<Int>): Int? {
        if (supported.isEmpty()) return null
        var best = supported[0]
        var bestDiff = kotlin.math.abs(best - want)
        for (r in supported) {
            val d = kotlin.math.abs(r - want)
            if (d < bestDiff || (d == bestDiff && r > best)) {
                best = r
                bestDiff = d
            }
        }
        return best
    }

    fun buildSet(min: Int, max: Int): String =
        "settings put system min_refresh_rate $min && settings put system peak_refresh_rate $max"
}
