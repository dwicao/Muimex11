package com.muimex11.official

/**
 * Typed status of a tweak card.
 *
 * Historically "is this tweak already applied?" was answered by matching a
 * substring of the *localized* text inside a TextView (`status.contains("Recently")`).
 * That made switching language, editing copy, or changing a plural silently
 * change behaviour — and made the state impossible to unit test.
 *
 * The UI now writes [StatusKind] into [StatusRegistry] whenever it renders or
 * mutates a card, and all decisions read the registry instead of the text.
 */
enum class StatusKind {
    /** Nothing rendered yet, or the card has no applied state (sheets, pickers). */
    UNKNOWN,

    /** Read back from the device and known to be at its default. */
    READY,

    /** Read back (or just confirmed) as applied. */
    APPLIED,

    /** Partially applied — e.g. 1 of 2 keys took on this ROM. */
    PARTIAL
}

/**
 * Session-scoped `viewId -> StatusKind` map.
 *
 * Pure Kotlin on purpose: no Android dependencies, so the state rules are
 * unit-testable. Values are re-derived from a real read-back on every
 * [MainActivity.fetchSystemStatuses]; one-shot actions (fstrim, kill-all) set
 * [StatusKind.APPLIED] directly because no read-back exists for them.
 */
object StatusRegistry {
    private val kinds = HashMap<Int, StatusKind>()

    fun set(viewId: Int, kind: StatusKind) {
        kinds[viewId] = kind
    }

    fun get(viewId: Int): StatusKind = kinds[viewId] ?: StatusKind.UNKNOWN

    fun isApplied(viewId: Int): Boolean {
        val k = get(viewId)
        return k == StatusKind.APPLIED || k == StatusKind.PARTIAL
    }

    fun clear() = kinds.clear()

    /** Test/inspection helper. */
    fun snapshot(): Map<Int, StatusKind> = kinds.toMap()

    /**
     * Classifies a raw `settings get` style read-back against the values that
     * mean "applied". Keeps the decision out of the UI layer.
     */
    fun classify(raw: String?, vararg appliedValues: String): StatusKind {
        val v = raw?.trim() ?: return StatusKind.UNKNOWN
        if (v.isEmpty() || v == "null") return StatusKind.UNKNOWN
        return if (appliedValues.any { it == v }) StatusKind.APPLIED else StatusKind.READY
    }
}
