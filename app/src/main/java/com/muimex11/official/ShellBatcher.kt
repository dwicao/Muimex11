package com.muimex11.official

/**
 * Builds and parses multi-command shell scripts that run in a **single**
 * Shizuku process.
 *
 * Every `ShizukuRunner.exec()` forks a process over the binder, so a status
 * refresh that needed 12 separate `settings get` calls paid 12 process
 * creations. One tagged script with `echo` markers cuts that to one.
 *
 * Pure Kotlin (no Android deps) so the wire format is unit-tested.
 */
object ShellBatcher {
    /** Marker prefix. Keys are restricted to shell-safe characters. */
    const val MARK = "@@muimex:"

    private const val END_KEY = "__end__"
    private val KEY_RE = Regex("^[A-Za-z0-9_]{1,64}$")
    private val MARKER_LINE = Regex("^@@muimex:([A-Za-z0-9_]+)@@$")

    fun isSafeKey(key: String): Boolean = KEY_RE.matches(key)

    /**
     * One `sh -c` script emitting a marker before each command's output.
     * Each command's stderr is folded into stdout so the marker/response
     * association survives ROMs that log noisy warnings.
     */
    fun build(reqs: List<Pair<String, String>>): String {
        require(reqs.isNotEmpty()) { "empty batch" }
        reqs.forEach { (key, _) ->
            require(isSafeKey(key)) { "unsafe batch key: $key" }
        }
        return buildString {
            reqs.forEach { (key, cmd) ->
                append("echo '").append(MARK).append(key).append("@@'\n")
                append("{ ").append(cmd.trim().trimEnd(';')).append(" ; } 2>&1\n")
            }
            append("echo '").append(MARK).append(END_KEY).append("@@'\n")
        }
    }

    /**
     * Splits the merged output back into `key -> text`. Unknown keys are
     * ignored, missing keys come back empty, so a partial failure degrades to
     * "no value" instead of corrupting a neighbouring section.
     */
    fun parse(output: String): Map<String, String> {
        val res = LinkedHashMap<String, String>()
        var current: String? = null
        val buf = StringBuilder()

        fun flush() {
            val key = current ?: return
            if (key != END_KEY) res[key] = buf.toString().trim()
            buf.setLength(0)
        }

        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val m = MARKER_LINE.matchEntire(line)
            if (m != null) {
                flush()
                current = m.groupValues[1]
            } else if (current != null) {
                buf.append(line).append('\n')
            }
        }
        flush()
        return res
    }

    /** Convenience: read a parsed value with a sane default. */
    fun value(parsed: Map<String, String>, key: String): String =
        parsed[key].orEmpty().trim()
}
