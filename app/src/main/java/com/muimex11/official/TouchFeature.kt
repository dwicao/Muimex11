package com.muimex11.official

import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Touch latency, applied per key with read-back verification.
 * Some ROMs (e.g. Realme/ColorOS) throw on `settings put system
 * pointer_speed` while `secure long_press_timeout` works fine — so each key
 * is tried independently and a partial result is reported honestly instead
 * of failing the whole tweak. Failures surface the full shell output in a
 * copyable sheet for bug reports.
 */
internal fun MainActivity.improveTouchLatency() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusTouch)?.text = getString(R.string.status_checking)
    lifecycleScope.launch(Dispatchers.IO) {
        // Probe pointer_speed highest-first: ROM validators (e.g. Realme's
        // SettingsProvider) reject out-of-range values with exit 255, so the
        // first value that reads back is the one the ROM accepts.
        val (ptrApplied, ptrCode, ptrProbeOut) = probePointerSpeed()
        val rPtrDesc = "settings put system ${TouchTweak.POINTER_KEY} " +
            TouchTweak.POINTER_PROBE.joinToString(" / ")
        val rLp = ShizukuRunner.exec(TouchTweak.buildLongPress())
        val vLp = TouchTweak.isLongPressApplied(
            runAdbCommandWithResult("settings get secure ${TouchTweak.LONG_PRESS_KEY}")
        )
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            val vPtr = ptrApplied != null
            when (TouchTweak.appliedCount(vPtr, vLp)) {
                2 -> {
                    UiToast.success(this@improveTouchLatency, getString(R.string.msg_touch_improved), Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    val blocked: String
                    val badCmd: String
                    val badOut: String
                    if (vPtr) {
                        blocked = TouchTweak.LONG_PRESS_KEY
                        badCmd = TouchTweak.buildLongPress()
                        badOut = rLp.merged
                    } else {
                        blocked = "${TouchTweak.POINTER_KEY} (tried ${TouchTweak.POINTER_PROBE.joinToString(", ")})"
                        badCmd = rPtrDesc
                        badOut = ptrProbeOut
                    }
                    UiToast.info(
                        this@improveTouchLatency,
                        getString(R.string.msg_touch_partial, blocked), Toast.LENGTH_LONG
                    ).show()
                    showCommandErrorSheet(
                        getString(R.string.dlg_touch_partial_title), badCmd,
                        ShellResult(if (vPtr) rLp.code else ptrCode, badOut)
                    ) { improveTouchLatency() }
                }
                else -> {
                    val combined = ShellResult(
                        rLp.code,
                        "$rPtrDesc\n$ptrProbeOut\n\n" +
                            "${TouchTweak.buildLongPress()}\n${rLp.merged}"
                    )
                    showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), getString(R.string.touch_both_cmds), combined) { improveTouchLatency() }
                }
            }
            fetchSystemStatuses()
        }
    }
}

/**
 * Highest-first probe; returns the level that stuck (or the already-boosted
 * current level) plus the last probe's exit code and output for diagnostics.
 */
private fun MainActivity.probePointerSpeed(): Triple<String?, Int, String> {
    val current = runAdbCommandWithResult("settings get system ${TouchTweak.POINTER_KEY}")
    if (TouchTweak.isPointerApplied(current)) return Triple(current.trim(), 0, "")
    var lastCode = -1
    var lastOut = ""
    for (v in TouchTweak.POINTER_PROBE) {
        val r = ShizukuRunner.exec(TouchTweak.buildPointer(v))
        lastCode = r.code
        lastOut = r.merged
        val readBack = runAdbCommandWithResult("settings get system ${TouchTweak.POINTER_KEY}")
        if (readBack.trim() == v) return Triple(v, r.code, lastOut)
    }
    return Triple(null, lastCode, lastOut)
}
