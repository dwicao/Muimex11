package com.muimex11.official

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

    fun shouldUpdate(latestCode: Int, currentCode: Int): Boolean = latestCode > currentCode
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
    val OPS = listOf("CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")

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

/** Pure audio/latency preset helpers — unit-testable. Reversible settings only. */
object AudioPresets {
    const val PRESET_OFF = "off"
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
