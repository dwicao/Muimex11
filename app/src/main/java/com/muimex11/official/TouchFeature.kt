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
        val rPtr = ShizukuRunner.exec(TouchTweak.buildPointer())
        val vPtr = TouchTweak.isPointerApplied(
            runAdbCommandWithResult("settings get system ${TouchTweak.POINTER_KEY}")
        )
        val rLp = ShizukuRunner.exec(TouchTweak.buildLongPress())
        val vLp = TouchTweak.isLongPressApplied(
            runAdbCommandWithResult("settings get secure ${TouchTweak.LONG_PRESS_KEY}")
        )
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            when (TouchTweak.appliedCount(vPtr, vLp)) {
                2 -> {
                    UiToast.success(this@improveTouchLatency, getString(R.string.msg_touch_improved), Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    val blocked = if (vPtr) TouchTweak.LONG_PRESS_KEY else TouchTweak.POINTER_KEY
                    val badRes = if (vPtr) rLp else rPtr
                    val badCmd = if (vPtr) TouchTweak.buildLongPress() else TouchTweak.buildPointer()
                    UiToast.info(
                        this@improveTouchLatency,
                        getString(R.string.msg_touch_partial, blocked), Toast.LENGTH_LONG
                    ).show()
                    showCommandErrorSheet(getString(R.string.dlg_touch_partial_title), badCmd, badRes)
                }
                else -> {
                    val combined = ShellResult(
                        rPtr.code,
                        "${TouchTweak.buildPointer()}\n${rPtr.merged}\n\n" +
                            "${TouchTweak.buildLongPress()}\n${rLp.merged}"
                    )
                    showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), getString(R.string.touch_both_cmds), combined)
                }
            }
            fetchSystemStatuses()
        }
    }
}
