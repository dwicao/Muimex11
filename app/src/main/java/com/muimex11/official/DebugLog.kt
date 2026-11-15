package com.muimex11.official

import android.util.Log

/**
 * Diagnostic sink for intentionally-swallowed failures.
 *
 * Many paths in this app must never crash the process (crash handler, boot
 * restore, polling loops) but should not vanish silently either. This logs
 * the exception to logcat so failures are diagnosable from bug reports;
 * callers decide the recovery behavior, the log only records that it
 * happened. Keep the `where` tag short and stable.
 */
internal fun logQuietly(where: String, e: Throwable) {
    try {
        Log.w("Muimex11", where, e)
    } catch (_: Throwable) {
        // Logging must never throw — not even from a crash boundary.
    }
}
