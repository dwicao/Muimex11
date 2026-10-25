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
