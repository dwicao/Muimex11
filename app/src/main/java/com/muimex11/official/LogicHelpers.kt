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
