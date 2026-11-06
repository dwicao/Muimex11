package com.muimex11.official

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch-A comfort & display tweaks (internet-research audit).
 *
 * Every key verified against AOSP Settings.java or an open-source
 * implementation (DozeTap, BetterNightLight, WiFi-portal not needed here,
 * essentials tiles for mono/bubbles/tap-to-wake/brightness-mode):
 *
 * - Screen timeout ........ `system screen_off_timeout` (ms; INT_MAX = never)
 * - Night Light ........... `secure night_display_activated/_color_temperature/_auto_mode`
 *   (temp is device-clamped Kelvin — presets, never a hardcoded slider max)
 * - Font scale ............ `system font_scale` (clamped 0.85–1.3, >1.5 breaks layouts)
 * - Adaptive brightness ... `system screen_brightness_mode` (1 = auto)
 * - Mono audio ............ `system master_mono`
 * - Chat bubbles .......... `global notification_bubbles`
 * - Tap to wake ........... `secure doze_tap_gesture`
 * - Dark mode ............. `cmd uimode night yes|no|auto` (the sanctioned route;
 *   verified via settings read-back since the service owns `ui_night_mode`)
 * - Extra dim ............. `secure reduce_bright_colors_activated`
 * - Grayscale ............. `secure accessibility_display_daltonizer_enabled=1 + mode=0`
 * - Force dark ............ `setprop debug.hwui.force_dark` (needs app restart)
 * - Lock after timeout .... `secure lock_screen_lock_after_timeout` (0 = immediate)
 * - Lockscreen visibility . `secure lock_screen_show_notifications` +
 *   `secure lock_screen_allow_private_notifications`
 * - Ambient display ....... `secure doze_enabled` master + `wake_gesture_enabled` +
 *   `doze_pulse_on_pick_up` sub-gestures (hardware-dependent, silent no-op if absent)
 * - Camera double-press ... `secure camera_double_tap_power_gesture_disabled`
 *   (INVERTED: 1 = disabled, 0 = enabled)
 * - Ignore orientation .... `wm set-ignore-orientation-request` (prefs-tracked:
 *   `wm` prints nothing reliable to verify against)
 *
 * Every apply is verified with a read-back except the wm flag; failures
 * surface the full shell output in a copyable sheet.
 */
object ComfortTweaks {
    const val TIMEOUT_NS = "system"
    const val TIMEOUT_KEY = "screen_off_timeout"
    val TIMEOUT_PRESETS = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000, Int.MAX_VALUE)
    const val TIMEOUT_NEVER = Int.MAX_VALUE

    const val NIGHT_NS = "secure"
    const val NIGHT_KEY = "night_display_activated"
    const val NIGHT_TEMP_KEY = "night_display_color_temperature"
    const val NIGHT_AUTO_KEY = "night_display_auto_mode"
    const val NIGHT_AUTO_OFF = "0"
    const val NIGHT_AUTO_TWILIGHT = "2"
    val NIGHT_TEMPS = listOf(1000, 2500, 3500, 4500, 6500)

    const val FONT_NS = "system"
    const val FONT_KEY = "font_scale"
    val FONT_PRESETS = listOf("0.85", "1.0", "1.15", "1.3")

    const val BRIGHT_NS = "system"
    const val BRIGHT_KEY = "screen_brightness_mode"

    const val MONO_NS = "system"
    const val MONO_KEY = "master_mono"

    const val BUBBLES_NS = "global"
    const val BUBBLES_KEY = "notification_bubbles"

    const val TAPWAKE_NS = "secure"
    const val TAPWAKE_KEY = "doze_tap_gesture"

    val UIMODE_EXPECT = mapOf("auto" to "0", "no" to "1", "yes" to "2")

    const val EXTRADIM_NS = "secure"
    const val EXTRADIM_KEY = "reduce_bright_colors_activated"

    const val DALTON_NS = "secure"
    const val DALTON_EN_KEY = "accessibility_display_daltonizer_enabled"
    const val DALTON_MODE_KEY = "accessibility_display_daltonizer"
    const val DALTON_GRAY = "0"

    const val FORCEDARK_PROP = "debug.hwui.force_dark"

    const val LOCKAFTER_NS = "secure"
    const val LOCKAFTER_KEY = "lock_screen_lock_after_timeout"
    val LOCKAFTER_PRESETS = listOf(0, 5_000, 15_000, 30_000, 60_000, 300_000)

    const val LSN_NS = "secure"
    const val LSN_SHOW_KEY = "lock_screen_show_notifications"
    const val LSN_PRIVATE_KEY = "lock_screen_allow_private_notifications"

    const val DOZE_NS = "secure"
    const val DOZE_KEY = "doze_enabled"
    const val LIFT_KEY = "wake_gesture_enabled"
    const val PICKUP_KEY = "doze_pulse_on_pick_up"

    const val CAMDOUBLE_NS = "secure"
    const val CAMDOUBLE_KEY = "camera_double_tap_power_gesture_disabled"

    const val PREF_IGNORE_ORIENT = "ComfortIgnoreOrient"

    /** Rows on the sheet (each counts toward the status summary). */
    const val TOTAL = 20

    fun buildSet(ns: String, key: String, value: String): String =
        "settings put $ns $key $value"

    fun buildDelete(ns: String, key: String): String =
        "settings delete $ns $key"

    fun isOn(raw: String?): Boolean = raw?.trim() == "1"

    fun isSet(raw: String?): Boolean {
        val t = raw?.trim()
        return !t.isNullOrEmpty() && !t.equals("null", ignoreCase = true)
    }

    fun buildUimode(mode: String): String = "cmd uimode night $mode"

    fun buildGray(on: Boolean): String = if (on) {
        "${buildSet(DALTON_NS, DALTON_EN_KEY, "1")} && ${buildSet(DALTON_NS, DALTON_MODE_KEY, DALTON_GRAY)}"
    } else {
        buildSet(DALTON_NS, DALTON_EN_KEY, "0")
    }

    fun buildForceDark(on: Boolean): String =
        "setprop $FORCEDARK_PROP ${if (on) "true" else "false"}"

    fun buildLockNotif(show: String, private: String): String =
        "${buildSet(LSN_NS, LSN_SHOW_KEY, show)} && ${buildSet(LSN_NS, LSN_PRIVATE_KEY, private)}"

    fun buildReset(): String = listOf(
        buildDelete(TIMEOUT_NS, TIMEOUT_KEY),
        buildSet(NIGHT_NS, NIGHT_KEY, "0"),
        buildSet(NIGHT_NS, NIGHT_AUTO_KEY, NIGHT_AUTO_OFF),
        buildDelete(NIGHT_NS, NIGHT_TEMP_KEY),
        buildSet(FONT_NS, FONT_KEY, "1.0"),
        buildSet(BRIGHT_NS, BRIGHT_KEY, "1"),
        buildSet(MONO_NS, MONO_KEY, "0"),
        buildSet(BUBBLES_NS, BUBBLES_KEY, "1"),
        buildSet(TAPWAKE_NS, TAPWAKE_KEY, "1"),
        buildUimode("auto"),
        buildSet(EXTRADIM_NS, EXTRADIM_KEY, "0"),
        buildSet(DALTON_NS, DALTON_EN_KEY, "0"),
        buildForceDark(false),
        buildDelete(LOCKAFTER_NS, LOCKAFTER_KEY),
        buildLockNotif("1", "1"),
        buildSet(DOZE_NS, DOZE_KEY, "1"),
        buildSet(DOZE_NS, LIFT_KEY, "1"),
        buildSet(DOZE_NS, PICKUP_KEY, "1"),
        buildSet(CAMDOUBLE_NS, CAMDOUBLE_KEY, "0"),
        "wm set-ignore-orientation-request false"
    ).joinToString(" && ")
}

private data class ComfortState(
    val timeout: String?,
    val night: Boolean,
    val nightTemp: String?,
    val nightAuto: String?,
    val font: String?,
    val brightAuto: Boolean,
    val mono: Boolean,
    val bubbles: Boolean,
    val tapWake: Boolean,
    val uimode: String?,
    val extraDim: Boolean,
    val gray: Boolean,
    val forceDark: Boolean,
    val lockAfter: String?,
    val lsnShow: String?,
    val lsnPrivate: String?,
    val doze: Boolean,
    val lift: Boolean,
    val pickup: Boolean,
    val camDoubleDisabled: Boolean,
    val ignoreOrient: Boolean
) {
    /** Lockscreen triple as a mode: all / content-hidden / none. */
    fun lockNotifMode(): Int = when {
        lsnShow == "1" && lsnPrivate == "1" -> 0
        lsnShow == "1" && lsnPrivate == "0" -> 1
        else -> 2
    }

    fun activeCount(): Int {
        var n = 0
        if (ComfortTweaks.isSet(timeout)) n++
        if (night) n++
        if (ComfortTweaks.isSet(nightTemp)) n++
        if (nightAuto != null && nightAuto != ComfortTweaks.NIGHT_AUTO_OFF) n++
        if (ComfortTweaks.isSet(font) && font != "1.0") n++
        if (!brightAuto) n++
        if (mono) n++
        if (!bubbles) n++
        if (!tapWake) n++
        if (uimode == "1" || uimode == "2") n++
        if (extraDim) n++
        if (gray) n++
        if (forceDark) n++
        if (ComfortTweaks.isSet(lockAfter)) n++
        if (lockNotifMode() != 0) n++
        if (!doze) n++
        if (!lift) n++
        if (!pickup) n++
        if (!camDoubleDisabled) n++
        if (ignoreOrient) n++
        return n
    }
}

private fun MainActivity.readComfortState(prefs: android.content.SharedPreferences): ComfortState {
    fun get(ns: String, key: String): String? {
        val t = runAdbCommandWithResult("settings get $ns $key").trim()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
    }
    val grayEn = get(ComfortTweaks.DALTON_NS, ComfortTweaks.DALTON_EN_KEY) == "1"
    val grayMode = get(ComfortTweaks.DALTON_NS, ComfortTweaks.DALTON_MODE_KEY)
    val fdRaw = runAdbCommandWithResult("getprop ${ComfortTweaks.FORCEDARK_PROP}").trim()
    return ComfortState(
        timeout = get(ComfortTweaks.TIMEOUT_NS, ComfortTweaks.TIMEOUT_KEY),
        night = get(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_KEY) == "1",
        nightTemp = get(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_TEMP_KEY),
        nightAuto = get(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_AUTO_KEY),
        font = get(ComfortTweaks.FONT_NS, ComfortTweaks.FONT_KEY),
        brightAuto = get(ComfortTweaks.BRIGHT_NS, ComfortTweaks.BRIGHT_KEY) != "0",
        mono = get(ComfortTweaks.MONO_NS, ComfortTweaks.MONO_KEY) == "1",
        bubbles = get(ComfortTweaks.BUBBLES_NS, ComfortTweaks.BUBBLES_KEY) != "0",
        tapWake = get(ComfortTweaks.TAPWAKE_NS, ComfortTweaks.TAPWAKE_KEY) != "0",
        uimode = get("secure", "ui_night_mode"),
        extraDim = get(ComfortTweaks.EXTRADIM_NS, ComfortTweaks.EXTRADIM_KEY) == "1",
        gray = grayEn && (grayMode == null || grayMode == ComfortTweaks.DALTON_GRAY),
        forceDark = fdRaw.equals("true", ignoreCase = true) || fdRaw == "1",
        lockAfter = get(ComfortTweaks.LOCKAFTER_NS, ComfortTweaks.LOCKAFTER_KEY),
        lsnShow = get(ComfortTweaks.LSN_NS, ComfortTweaks.LSN_SHOW_KEY),
        lsnPrivate = get(ComfortTweaks.LSN_NS, ComfortTweaks.LSN_PRIVATE_KEY),
        doze = get(ComfortTweaks.DOZE_NS, ComfortTweaks.DOZE_KEY) != "0",
        lift = get(ComfortTweaks.DOZE_NS, ComfortTweaks.LIFT_KEY) != "0",
        pickup = get(ComfortTweaks.DOZE_NS, ComfortTweaks.PICKUP_KEY) != "0",
        camDoubleDisabled = get(ComfortTweaks.CAMDOUBLE_NS, ComfortTweaks.CAMDOUBLE_KEY) != "0",
        ignoreOrient = prefs.getBoolean(ComfortTweaks.PREF_IGNORE_ORIENT, false)
    )
}

/** Set + read-back verify with honest failure sheet. Shared by all rows below. */
private fun MainActivity.applyComfort(
    setCmd: String, verifyCmd: String, expect: String?, reopen: () -> Unit
) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(setCmd)
        val readBack = if (res.ok) runAdbCommandWithResult(verifyCmd).trim() else ""
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            val ok = res.ok && (expect == null || readBack == expect ||
                (expect.isEmpty() && (readBack.isEmpty() || readBack.equals("null", ignoreCase = true))))
            if (ok) {
                UiToast.success(this@applyComfort, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                val detail = if (res.ok) ShellResult(res.code, "$setCmd\nread-back: '$readBack' (expected: '${expect ?: "(exec ok)"}')")
                else res
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), setCmd, detail) { reopen() }
                fetchSystemStatuses()
            }
        }
    }
}

internal fun MainActivity.showComfortTweaksSheet() {
    if (!hasShizukuPermission()) return
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    lifecycleScope.launch(Dispatchers.IO) {
        val state = readComfortState(prefs)
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderComfortSheet(prefs, state)
        }
    }
}

private fun MainActivity.renderComfortSheet(prefs: android.content.SharedPreferences, s: ComfortState) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.title_comfort))
        .message(getString(R.string.desc_comfort))

    fun toggle(title: String, desc: String, on: Boolean, set: String, verify: String, expect: String?) {
        list.addView(optionRow(this, title, desc, selected = on) {
            sheet.dismiss()
            applyComfort(set, verify, expect) { showComfortTweaksSheet() }
        })
    }
    fun setOf(ns: String, key: String, v: String) = ComfortTweaks.buildSet(ns, key, v)
    fun verifyOf(ns: String, key: String) = "settings get $ns $key"

    // Screen timeout (preset sub-sheet)
    list.addView(optionRow(
        this, getString(R.string.comfort_timeout), timeoutLabel(s.timeout), selected = ComfortTweaks.isSet(s.timeout)
    ) {
        sheet.dismiss()
        showTimeoutSheet(s.timeout)
    })

    // Night light: master + temperature + schedule
    toggle(
        getString(R.string.comfort_night), getString(R.string.comfort_night_desc), s.night,
        setOf(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_KEY, if (s.night) "0" else "1"),
        verifyOf(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_KEY), if (s.night) "0" else "1"
    )
    list.addView(optionRow(
        this, getString(R.string.comfort_night_temp),
        s.nightTemp?.let { getString(R.string.comfort_night_temp_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = ComfortTweaks.isSet(s.nightTemp)
    ) {
        sheet.dismiss()
        showNightTempSheet(s.nightTemp)
    })
    list.addView(optionRow(
        this, getString(R.string.comfort_night_auto), nightAutoLabel(s.nightAuto),
        selected = s.nightAuto != null && s.nightAuto != ComfortTweaks.NIGHT_AUTO_OFF
    ) {
        sheet.dismiss()
        showNightAutoSheet(s.nightAuto)
    })

    // Font scale (preset sub-sheet)
    list.addView(optionRow(
        this, getString(R.string.comfort_font),
        s.font?.let { getString(R.string.comfort_font_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = ComfortTweaks.isSet(s.font) && s.font != "1.0"
    ) {
        sheet.dismiss()
        showFontSheet(s.font)
    })

    toggle(
        getString(R.string.comfort_bright), getString(R.string.comfort_bright_desc), !s.brightAuto,
        setOf(ComfortTweaks.BRIGHT_NS, ComfortTweaks.BRIGHT_KEY, if (s.brightAuto) "0" else "1"),
        verifyOf(ComfortTweaks.BRIGHT_NS, ComfortTweaks.BRIGHT_KEY), if (s.brightAuto) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_mono), getString(R.string.comfort_mono_desc), s.mono,
        setOf(ComfortTweaks.MONO_NS, ComfortTweaks.MONO_KEY, if (s.mono) "0" else "1"),
        verifyOf(ComfortTweaks.MONO_NS, ComfortTweaks.MONO_KEY), if (s.mono) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_bubbles), getString(R.string.comfort_bubbles_desc), !s.bubbles,
        setOf(ComfortTweaks.BUBBLES_NS, ComfortTweaks.BUBBLES_KEY, if (s.bubbles) "0" else "1"),
        verifyOf(ComfortTweaks.BUBBLES_NS, ComfortTweaks.BUBBLES_KEY), if (s.bubbles) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_tapwake), getString(R.string.comfort_tapwake_desc), !s.tapWake,
        setOf(ComfortTweaks.TAPWAKE_NS, ComfortTweaks.TAPWAKE_KEY, if (s.tapWake) "0" else "1"),
        verifyOf(ComfortTweaks.TAPWAKE_NS, ComfortTweaks.TAPWAKE_KEY), if (s.tapWake) "0" else "1"
    )

    // Dark mode via cmd uimode (verified through the ui_night_mode setting)
    list.addView(optionRow(
        this, getString(R.string.comfort_uimode), uimodeLabel(s.uimode),
        selected = s.uimode == "1" || s.uimode == "2"
    ) {
        sheet.dismiss()
        showUimodeSheet(s.uimode)
    })

    toggle(
        getString(R.string.comfort_extradim), getString(R.string.comfort_extradim_desc), s.extraDim,
        setOf(ComfortTweaks.EXTRADIM_NS, ComfortTweaks.EXTRADIM_KEY, if (s.extraDim) "0" else "1"),
        verifyOf(ComfortTweaks.EXTRADIM_NS, ComfortTweaks.EXTRADIM_KEY), if (s.extraDim) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_gray), getString(R.string.comfort_gray_desc), s.gray,
        ComfortTweaks.buildGray(!s.gray),
        verifyOf(ComfortTweaks.DALTON_NS, ComfortTweaks.DALTON_EN_KEY), if (s.gray) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_forcedark), getString(R.string.comfort_forcedark_desc), s.forceDark,
        ComfortTweaks.buildForceDark(!s.forceDark),
        "getprop ${ComfortTweaks.FORCEDARK_PROP}", if (s.forceDark) "false" else "true"
    )

    // Lock-after-timeout (preset sub-sheet)
    list.addView(optionRow(
        this, getString(R.string.comfort_lockafter), lockAfterLabel(s.lockAfter),
        selected = ComfortTweaks.isSet(s.lockAfter)
    ) {
        sheet.dismiss()
        showLockAfterSheet(s.lockAfter)
    })

    // Lockscreen visibility (3-mode sub-sheet)
    list.addView(optionRow(
        this, getString(R.string.comfort_locknotif), lockNotifLabel(s.lockNotifMode()),
        selected = s.lockNotifMode() != 0
    ) {
        sheet.dismiss()
        showLockNotifSheet(s.lockNotifMode())
    })

    // Ambient display master + sub-gestures (hardware-dependent)
    toggle(
        getString(R.string.comfort_doze), getString(R.string.comfort_doze_desc), !s.doze,
        setOf(ComfortTweaks.DOZE_NS, ComfortTweaks.DOZE_KEY, if (s.doze) "0" else "1"),
        verifyOf(ComfortTweaks.DOZE_NS, ComfortTweaks.DOZE_KEY), if (s.doze) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_lift), getString(R.string.comfort_lift_desc), !s.lift,
        setOf(ComfortTweaks.DOZE_NS, ComfortTweaks.LIFT_KEY, if (s.lift) "0" else "1"),
        verifyOf(ComfortTweaks.DOZE_NS, ComfortTweaks.LIFT_KEY), if (s.lift) "0" else "1"
    )
    toggle(
        getString(R.string.comfort_pickup), getString(R.string.comfort_pickup_desc), !s.pickup,
        setOf(ComfortTweaks.DOZE_NS, ComfortTweaks.PICKUP_KEY, if (s.pickup) "0" else "1"),
        verifyOf(ComfortTweaks.DOZE_NS, ComfortTweaks.PICKUP_KEY), if (s.pickup) "0" else "1"
    )
    // Inverted semantics: the key DISABLES the gesture.
    toggle(
        getString(R.string.comfort_camdouble), getString(R.string.comfort_camdouble_desc), !s.camDoubleDisabled,
        setOf(ComfortTweaks.CAMDOUBLE_NS, ComfortTweaks.CAMDOUBLE_KEY, if (s.camDoubleDisabled) "0" else "1"),
        verifyOf(ComfortTweaks.CAMDOUBLE_NS, ComfortTweaks.CAMDOUBLE_KEY), if (s.camDoubleDisabled) "0" else "1"
    )

    // wm flag: prefs-tracked, exec success is the only signal.
    list.addView(optionRow(
        this, getString(R.string.comfort_ignoreorient), getString(R.string.comfort_ignoreorient_desc),
        selected = s.ignoreOrient
    ) {
        sheet.dismiss()
        applyIgnoreOrient(prefs, !s.ignoreOrient)
    })

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.timeoutLabel(raw: String?): String {
    val ms = raw?.toLongOrNull() ?: return getString(R.string.comfort_not_set)
    if (ms >= Int.MAX_VALUE) return getString(R.string.comfort_timeout_never)
    if (ms < 60_000) return getString(R.string.comfort_secs_fmt, ms / 1000)
    return getString(R.string.comfort_mins_fmt, ms / 60_000)
}

private fun MainActivity.nightAutoLabel(raw: String?): String = when (raw) {
    ComfortTweaks.NIGHT_AUTO_TWILIGHT -> getString(R.string.comfort_night_auto_twilight)
    "1" -> getString(R.string.comfort_night_auto_custom)
    else -> getString(R.string.comfort_off)
}

private fun MainActivity.uimodeLabel(raw: String?): String = when (raw) {
    "2" -> getString(R.string.comfort_uimode_dark)
    "1" -> getString(R.string.comfort_uimode_light)
    else -> getString(R.string.comfort_uimode_auto)
}

private fun MainActivity.lockAfterLabel(raw: String?): String {
    val ms = raw?.toLongOrNull() ?: return getString(R.string.comfort_not_set)
    if (ms <= 0) return getString(R.string.comfort_lockafter_now)
    if (ms < 60_000) return getString(R.string.comfort_secs_fmt, ms / 1000)
    return getString(R.string.comfort_mins_fmt, ms / 60_000)
}

private fun MainActivity.lockNotifLabel(mode: Int): String = getString(when (mode) {
    1 -> R.string.comfort_locknotif_hidden
    2 -> R.string.comfort_locknotif_none
    else -> R.string.comfort_locknotif_all
})

private fun MainActivity.showTimeoutSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_timeout))
        .message(getString(R.string.comfort_timeout_msg))
    val labels = ComfortTweaks.TIMEOUT_PRESETS.map { timeoutLabel(it.toString()) }
    labels.chunked(4).forEach { chunk ->
        val start = labels.indexOf(chunk.first())
        list.addView(presetRow(this, chunk) { i ->
            val v = ComfortTweaks.TIMEOUT_PRESETS[start + i]
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildSet(ComfortTweaks.TIMEOUT_NS, ComfortTweaks.TIMEOUT_KEY, v.toString()),
                "settings get ${ComfortTweaks.TIMEOUT_NS} ${ComfortTweaks.TIMEOUT_KEY}", v.toString()
            ) { showComfortTweaksSheet() }
        })
    }
    list.addView(optionRow(this, getString(R.string.comfort_reset_default), getString(R.string.comfort_reset_default_desc)) {
        sheet.dismiss()
        applyComfort(
            ComfortTweaks.buildDelete(ComfortTweaks.TIMEOUT_NS, ComfortTweaks.TIMEOUT_KEY),
            "settings get ${ComfortTweaks.TIMEOUT_NS} ${ComfortTweaks.TIMEOUT_KEY}", ""
        ) { showComfortTweaksSheet() }
    })
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showNightTempSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_night_temp))
        .message(getString(R.string.comfort_night_temp_msg))
    ComfortTweaks.NIGHT_TEMPS.forEach { k ->
        list.addView(optionRow(this, getString(R.string.comfort_kelvin_fmt, k), null, selected = current == k.toString()) {
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildSet(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_TEMP_KEY, k.toString()),
                "settings get ${ComfortTweaks.NIGHT_NS} ${ComfortTweaks.NIGHT_TEMP_KEY}", k.toString()
            ) { showComfortTweaksSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showNightAutoSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_night_auto))
        .message(getString(R.string.comfort_night_auto_msg))
    listOf(
        ComfortTweaks.NIGHT_AUTO_OFF to R.string.comfort_off,
        ComfortTweaks.NIGHT_AUTO_TWILIGHT to R.string.comfort_night_auto_twilight
    ).forEach { (v, label) ->
        list.addView(optionRow(this, getString(label), null, selected = current == v) {
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildSet(ComfortTweaks.NIGHT_NS, ComfortTweaks.NIGHT_AUTO_KEY, v),
                "settings get ${ComfortTweaks.NIGHT_NS} ${ComfortTweaks.NIGHT_AUTO_KEY}", v
            ) { showComfortTweaksSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showFontSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_font))
        .message(getString(R.string.comfort_font_msg))
    list.addView(presetRow(this, ComfortTweaks.FONT_PRESETS) { i ->
        val v = ComfortTweaks.FONT_PRESETS[i]
        sheet.dismiss()
        applyComfort(
            ComfortTweaks.buildSet(ComfortTweaks.FONT_NS, ComfortTweaks.FONT_KEY, v),
            "settings get ${ComfortTweaks.FONT_NS} ${ComfortTweaks.FONT_KEY}", v
        ) { showComfortTweaksSheet() }
    })
    list.addView(infoCard(this, getString(R.string.comfort_font_note)))
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showUimodeSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_uimode))
        .message(getString(R.string.comfort_uimode_msg))
    listOf(
        Triple("auto", R.string.comfort_uimode_auto, "0"),
        Triple("no", R.string.comfort_uimode_light, "1"),
        Triple("yes", R.string.comfort_uimode_dark, "2")
    ).forEach { (mode, label, expect) ->
        list.addView(optionRow(this, getString(label), null, selected = current == expect) {
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildUimode(mode),
                "settings get secure ui_night_mode", expect
            ) { showComfortTweaksSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showLockAfterSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_lockafter))
        .message(getString(R.string.comfort_lockafter_msg))
    ComfortTweaks.LOCKAFTER_PRESETS.forEach { ms ->
        list.addView(optionRow(this, lockAfterLabel(ms.toString()), null, selected = current == ms.toString()) {
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildSet(ComfortTweaks.LOCKAFTER_NS, ComfortTweaks.LOCKAFTER_KEY, ms.toString()),
                "settings get ${ComfortTweaks.LOCKAFTER_NS} ${ComfortTweaks.LOCKAFTER_KEY}", ms.toString()
            ) { showComfortTweaksSheet() }
        })
    }
    list.addView(optionRow(this, getString(R.string.comfort_reset_default), getString(R.string.comfort_reset_default_desc)) {
        sheet.dismiss()
        applyComfort(
            ComfortTweaks.buildDelete(ComfortTweaks.LOCKAFTER_NS, ComfortTweaks.LOCKAFTER_KEY),
            "settings get ${ComfortTweaks.LOCKAFTER_NS} ${ComfortTweaks.LOCKAFTER_KEY}", ""
        ) { showComfortTweaksSheet() }
    })
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showLockNotifSheet(currentMode: Int) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.comfort_locknotif))
        .message(getString(R.string.comfort_locknotif_msg))
    listOf(
        Triple("1", "1", R.string.comfort_locknotif_all),
        Triple("1", "0", R.string.comfort_locknotif_hidden),
        Triple("0", "0", R.string.comfort_locknotif_none)
    ).forEachIndexed { i, (show, priv, label) ->
        list.addView(optionRow(this, getString(label), null, selected = currentMode == i) {
            sheet.dismiss()
            applyComfort(
                ComfortTweaks.buildLockNotif(show, priv),
                "settings get ${ComfortTweaks.LSN_NS} ${ComfortTweaks.LSN_SHOW_KEY}", show
            ) { showComfortTweaksSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.applyIgnoreOrient(prefs: android.content.SharedPreferences, on: Boolean) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec("wm set-ignore-orientation-request ${if (on) "true" else "false"}")
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (res.ok) {
                prefs.edit().putBoolean(ComfortTweaks.PREF_IGNORE_ORIENT, on).apply()
                UiToast.success(this@applyIgnoreOrient, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
            } else {
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), "wm set-ignore-orientation-request", res) {
                    showComfortTweaksSheet()
                }
            }
            fetchSystemStatuses()
        }
    }
}

internal fun MainActivity.resetComfortTweaks() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusComfort)?.text = getString(R.string.status_checking)
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    prefs.edit().remove(ComfortTweaks.PREF_IGNORE_ORIENT).apply()
    runAdbCommand(ComfortTweaks.buildReset(), getString(R.string.msg_comfort_reset)) { fetchSystemStatuses() }
}

/** Status-line summary: Default when nothing is active, otherwise "N of 20 on". */
internal fun MainActivity.comfortStatusText(): String {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val s = runCatching { readComfortState(prefs) }.getOrNull() ?: return getString(R.string.status_unknown)
    val n = s.activeCount()
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.comfort_status_fmt, n, ComfortTweaks.TOTAL)
}
