package com.muimex11.official

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-applies the saved Vulkan choice after reboot.
 *
 * Android clears `debug.hwui.renderer` on reboot, so without this the device
 * silently falls back to OpenGL. Only Vulkan needs restoring — OpenGL *is*
 * the default. Adapted from GAMA's BootReceiver (MIT, palincat/gama), but
 * without the WorkManager dependency: Shizuku usually needs 30–120 s after
 * boot (wireless-debugging handshake), so this polls [ShizukuRunner.isReady]
 * on a background thread for up to ~90 s before giving up.
 */
class GraphicsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        if (prefs.getLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, 0L) == 0L) return
        if (GraphicsApi.getDesired(prefs) != GraphicsApi.VULKAN) return

        val pending = goAsync()
        Thread {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 90_000L
                var ready = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    try {
                        if (ShizukuRunner.isReady()) {
                            // Binder can answer ~300 ms before the remote process
                            // spawner accepts commands; settle briefly (GAMA).
                            Thread.sleep(500L)
                            ready = ShizukuRunner.isReady()
                            if (ready) break
                        }
                    } catch (_: Exception) { /* keep polling */ }
                    Thread.sleep(2_000L)
                }
                if (!ready) {
                    GraphicsApi.recordBootRestoreUnavailable(prefs)
                    return@Thread
                }
                // Direct setprop + verify (no Activity/UI here).
                val setRes = ShizukuRunner.exec("setprop ${GraphicsApi.PROP} ${GraphicsApi.VAL_VULKAN}")
                val readBack = ShizukuRunner.query("getprop ${GraphicsApi.PROP}").trim()
                if (setRes.ok || readBack.equals(GraphicsApi.VAL_VULKAN, ignoreCase = true)) {
                    GraphicsApi.stampRestore(prefs, GraphicsApi.VULKAN)
                }
            } catch (_: Exception) { /* best effort on boot */ }
            finally { try { pending.finish() } catch (_: Exception) {} }
        }.apply { isDaemon = true }.start()
    }
}
