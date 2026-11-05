package com.muimex11.official

import android.content.SharedPreferences
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Graphics API switcher (Vulkan / OpenGL).
 *
 * Adapted from GAMA — Graphics API Manager for Android by palincat
 * (https://github.com/palincat/gama, MIT license). GAMA forces the GPU
 * rendering API with `setprop debug.hwui.renderer <skiavk|opengl>` over a
 * privileged backend and re-applies the saved choice after reboot.
 *
 * Muimex11 already owns a Shizuku choke point ([ShizukuRunner]), so this
 * port keeps only that core: detect via `getprop`, switch via `setprop` +
 * read-back verification, persist the choice with a synchronous commit
 * (a renderer switch can take SystemUI — and this process — down before an
 * async apply() reaches disk), and re-apply Vulkan after reboot via
 * [GraphicsBootReceiver].
 *
 * What is deliberately NOT ported from GAMA: root backend, aggressive
 * app-restart / launcher / keyboard restarts, QS tiles, Tasker automation,
 * WorkManager retries and notifications. OpenGL is the Android default, so
 * "reset" is just switching back to OpenGL.
 */
object GraphicsApi {
    const val VULKAN = "Vulkan"
    const val OPENGL = "OpenGL"
    const val UNKNOWN = "Unknown"

    const val PROP = "debug.hwui.renderer"
    const val VAL_VULKAN = "skiavk"
    const val VAL_OPENGL = "opengl"

    /** Wall-clock drift tolerance for boot-time comparison (see GAMA). */
    const val BOOT_TIME_DRIFT_MS = 5 * 60_000L

    private val switchMutex = Mutex()

    data class ApplyResult(
        val target: String,
        val previous: String,
        val verified: Boolean,
        val message: String
    )

    /** Shell value for a renderer, null for unknown targets. */
    fun propValueFor(target: String): String? = when (target) {
        VULKAN -> VAL_VULKAN
        OPENGL -> VAL_OPENGL
        else -> null
    }

    /**
     * Pure mapping of `getprop` output to a renderer. Unit-testable.
     * - contains "skiavk" -> Vulkan
     * - empty / "Success" (empty prop) / contains "opengl" -> OpenGL
     *   (absent prop = Android default = OpenGL)
     * - shell errors ("Error...") -> Unknown (caller falls back to saved pref;
     *   GAMA would scan hwui/dumpsys next — detectRenderer() does the hwui scan)
     */
    fun parseRendererProp(raw: String?): String {
        val t = (raw ?: "").trim()
        if (t.contains(VAL_VULKAN, ignoreCase = true)) return VULKAN
        if (t.isEmpty() || t == "Success") return OPENGL
        if (t.contains(VAL_OPENGL, ignoreCase = true)) return OPENGL
        if (t.startsWith("Error", ignoreCase = true)) return UNKNOWN
        return OPENGL
    }

    fun getSaved(prefs: SharedPreferences): String =
        prefs.getString(Prefs.KEY_GRAPHICS_RENDERER, OPENGL) ?: OPENGL

    fun getDesired(prefs: SharedPreferences): String =
        prefs.getString(Prefs.KEY_GRAPHICS_DESIRED, null) ?: getSaved(prefs)

    /**
     * Persist a switch synchronously (commit, not apply): SystemUI restarts
     * during renderer switches and can kill this process before async writes
     * land. Mirrors GAMA's RendererState.recordSwitch.
     */
    fun recordSwitch(
        prefs: SharedPreferences,
        renderer: String,
        atWallClockMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString(Prefs.KEY_GRAPHICS_RENDERER, renderer)
            .putString(Prefs.KEY_GRAPHICS_DESIRED, renderer)
            .putLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, atWallClockMs)
            .putLong(Prefs.KEY_GRAPHICS_LAST_UPTIME, SystemClock.elapsedRealtime())
            .putLong(Prefs.KEY_GRAPHICS_LAST_BOOT, currentBootTimeMs())
            .commit()
    }

    /** Refresh stamps after a successful boot re-apply (see GAMA stampRestore). */
    fun stampRestore(prefs: SharedPreferences, renderer: String) {
        prefs.edit()
            .putString(Prefs.KEY_GRAPHICS_RENDERER, renderer)
            .putLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, currentBootTimeMs())
            .putLong(Prefs.KEY_GRAPHICS_LAST_UPTIME, SystemClock.elapsedRealtime())
            .putLong(Prefs.KEY_GRAPHICS_LAST_BOOT, currentBootTimeMs())
            .commit()
    }

    /**
     * Post-boot fallback when no backend was ready: keep Vulkan as the desired
     * choice but show the real OpenGL default. Returns true when state changed.
     */
    fun recordBootRestoreUnavailable(prefs: SharedPreferences): Boolean {
        if (getDesired(prefs) != VULKAN || getSaved(prefs) != VULKAN) return false
        val boot = currentBootTimeMs()
        prefs.edit()
            .putString(Prefs.KEY_GRAPHICS_DESIRED, VULKAN)
            .putString(Prefs.KEY_GRAPHICS_RENDERER, OPENGL)
            .putLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, boot)
            .putLong(Prefs.KEY_GRAPHICS_LAST_UPTIME, SystemClock.elapsedRealtime())
            .putLong(Prefs.KEY_GRAPHICS_LAST_BOOT, boot)
            .commit()
        return true
    }

    /**
     * If a reboot definitely cleared an active Vulkan prop, record the real
     * OpenGL fallback while preserving Vulkan as the desired restore choice.
     */
    fun reconcileRebootReset(prefs: SharedPreferences): Boolean {
        if (getSaved(prefs) != VULKAN || wasRebootedSinceLastSwitch(prefs) != true) return false
        val boot = currentBootTimeMs()
        prefs.edit()
            .putString(Prefs.KEY_GRAPHICS_DESIRED, getDesired(prefs))
            .putString(Prefs.KEY_GRAPHICS_RENDERER, OPENGL)
            .putLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, boot)
            .putLong(Prefs.KEY_GRAPHICS_LAST_UPTIME, SystemClock.elapsedRealtime())
            .putLong(Prefs.KEY_GRAPHICS_LAST_BOOT, boot)
            .commit()
        return true
    }

    fun wasRebootedSinceLastSwitch(prefs: SharedPreferences): Boolean? =
        wasRebootedInternal(
            storedBootTimeMs = prefs.getLong(Prefs.KEY_GRAPHICS_LAST_BOOT, 0L),
            currentBootTimeMs = currentBootTimeMs(),
            storedUptimeMs = prefs.getLong(Prefs.KEY_GRAPHICS_LAST_UPTIME, 0L),
            currentUptimeMs = SystemClock.elapsedRealtime(),
            storedSwitchWallClockMs = prefs.getLong(Prefs.KEY_GRAPHICS_LAST_SWITCH, 0L),
            nowWallClockMs = System.currentTimeMillis()
        )

    /** Pure reboot-detection core — JVM-testable, no Android deps except params. */
    internal fun wasRebootedInternal(
        storedBootTimeMs: Long,
        currentBootTimeMs: Long,
        storedUptimeMs: Long,
        currentUptimeMs: Long,
        storedSwitchWallClockMs: Long,
        nowWallClockMs: Long
    ): Boolean? {
        if (storedBootTimeMs != 0L) {
            return kotlin.math.abs(currentBootTimeMs - storedBootTimeMs) > BOOT_TIME_DRIFT_MS
        }
        if (storedUptimeMs > 0L) {
            return currentUptimeMs < storedUptimeMs
        }
        if (storedSwitchWallClockMs > 0L) {
            val ageMs = nowWallClockMs - storedSwitchWallClockMs
            return currentBootTimeMs > storedSwitchWallClockMs && ageMs in 0..(12 * 60 * 60 * 1000L)
        }
        return null
    }

    private fun currentBootTimeMs(): Long =
        System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Live renderer from the system property; "Unknown" when no backend/errors. */
    suspend fun detectRenderer(): String = withContext(Dispatchers.IO) {
        if (!ShizukuRunner.isReady()) return@withContext UNKNOWN
        val raw = ShizukuRunner.query("getprop $PROP")
        val first = parseRendererProp(raw)
        if (first != UNKNOWN) return@withContext first
        // Second opinion: scan all hwui/renderer props (mirrors GAMA source 2).
        val all = ShizukuRunner.query("getprop | grep -Ei 'hwui|renderer'")
        if (all.contains(VAL_VULKAN, ignoreCase = true)) return@withContext VULKAN
        if (all.contains(VAL_OPENGL, ignoreCase = true)) return@withContext OPENGL
        UNKNOWN
    }

    /**
     * Switch the renderer: `setprop` + read-back verification + durable commit.
     * Serialized by a mutex so concurrent entry points (UI, boot receiver)
     * cannot split the property from its persisted state (GAMA RendererController).
     */
    suspend fun applyRenderer(target: String, prefs: SharedPreferences): ApplyResult =
        switchMutex.withLock {
            val propValue = propValueFor(target)
                ?: return ApplyResult(target, getSaved(prefs), false, "Unknown renderer: $target")
            val previous = getSaved(prefs)
            if (!ShizukuRunner.isReady()) {
                return ApplyResult(target, previous, false, "No privileged backend is available.")
            }
            val setRes = ShizukuRunner.exec("setprop $PROP $propValue")
            if (!setRes.ok) {
                // The shell can report a bogus failure while the prop actually
                // applied — trust a read-back before giving up (GAMA behavior).
                val readBack = ShizukuRunner.query("getprop $PROP").trim()
                if (!readBack.equals(propValue, ignoreCase = true)) {
                    val detail = setRes.err.ifBlank { setRes.out }.ifBlank { "exit ${setRes.code}" }
                    return ApplyResult(target, previous, false, "setprop failed: $detail")
                }
            }
            val verify = ShizukuRunner.query("getprop $PROP").trim()
            val verified = verify.equals(propValue, ignoreCase = true) ||
                (verify.isEmpty() && propValue == VAL_OPENGL) ||
                (verify == "Success" && propValue == VAL_OPENGL)
            if (!verified) {
                return ApplyResult(target, previous, false, "Renderer prop reads back as '$verify'")
            }
            // Commit BEFORE any optional restarts so a process kill cannot
            // leave the property and the prefs out of sync.
            recordSwitch(prefs, target)
            ApplyResult(target, previous, true, "Switched to $target")
        }
}

/** Status line for the Display card: live prop wins, saved pref is the fallback. */
internal fun MainActivity.graphicsStatusText(prefs: SharedPreferences, live: String?): String {
    val current = when (live) {
        GraphicsApi.VULKAN, GraphicsApi.OPENGL -> live
        else -> {
            val saved = GraphicsApi.getSaved(prefs)
            if (saved == GraphicsApi.VULKAN || saved == GraphicsApi.OPENGL) saved else GraphicsApi.OPENGL
        }
    }
    return if (current == GraphicsApi.VULKAN) getString(R.string.status_graphics_vulkan)
    else getString(R.string.status_graphics_opengl)
}

internal fun MainActivity.showGraphicsApiSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val saved = GraphicsApi.getDesired(prefs)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.dlg_graphics_title))
        .message(getString(R.string.dlg_graphics_msg))
    list.addView(optionRow(
        this,
        getString(R.string.graphics_vulkan),
        getString(R.string.graphics_vulkan_desc),
        selected = saved == GraphicsApi.VULKAN
    ) {
        sheet.dismiss()
        applyGraphicsRenderer(prefs, GraphicsApi.VULKAN)
    })
    list.addView(optionRow(
        this,
        getString(R.string.graphics_opengl),
        getString(R.string.graphics_opengl_desc),
        selected = saved == GraphicsApi.OPENGL
    ) {
        sheet.dismiss()
        applyGraphicsRenderer(prefs, GraphicsApi.OPENGL)
    })
    list.addView(infoCard(this, getString(R.string.graphics_note)))
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

internal fun MainActivity.applyGraphicsRenderer(prefs: SharedPreferences, target: String) {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusGraphics)?.text = getString(R.string.status_checking)
    lifecycleScope.launch(Dispatchers.IO) {
        val res = GraphicsApi.applyRenderer(target, prefs)
        withContext(Dispatchers.Main) {
            if (res.verified) {
                UiToast.success(this@applyGraphicsRenderer, getString(R.string.msg_graphics_set, target), Toast.LENGTH_SHORT).show()
            } else {
                UiToast.error(this@applyGraphicsRenderer, getString(R.string.msg_graphics_fail, res.message), Toast.LENGTH_LONG).show()
            }
            fetchSystemStatuses()
        }
    }
}

internal fun MainActivity.resetGraphicsRenderer(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = GraphicsApi.applyRenderer(GraphicsApi.OPENGL, prefs)
        withContext(Dispatchers.Main) {
            if (res.verified) {
                UiToast.success(this@resetGraphicsRenderer, getString(R.string.msg_graphics_reset), Toast.LENGTH_SHORT).show()
            } else {
                UiToast.error(this@resetGraphicsRenderer, getString(R.string.msg_graphics_fail, res.message), Toast.LENGTH_LONG).show()
            }
            fetchSystemStatuses()
        }
    }
}
