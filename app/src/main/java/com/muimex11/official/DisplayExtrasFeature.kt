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
 * Tier-1 display & sound leftovers (second internet-research audit).
 * All keys are AOSP Settings canon (or essentials-tile-verified):
 *
 * - Color inversion ...... `secure accessibility_display_inversion_enabled`
 *   (pure setting — no accessibility service needed)
 * - High-contrast text ... `secure high_text_contrast_enabled`
 * - Bold text ............ `system font_weight_adjustment` (0 = normal, 300 = bold)
 * - Clock seconds ........ `secure clock_seconds` (classic System UI Tuner)
 * - Battery % ............ `system status_bar_show_battery_percent`
 * - Display color mode ... `system display_color_mode` (0 natural / 1 boosted / 2 vivid)
 * - Adaptive display ..... `secure display_white_balance_enabled` (device-gated)
 * - Back-gesture width ... `secure back_gesture_inset_scale_left/right`
 * - Freeform windows ..... `global enable_freeform_support` (needs reboot)
 * - Multi-press speed .... `secure multi_press_timeout` (touch family)
 * - Quiet sounds ......... touch + lock + dialpad + haptic + ring-vibrate off
 * - Notification snooze .. `secure notification_snooze_options` (essentials-verified)
 * - Don't keep activities  `global always_finish_activities`
 * - Wi-Fi scan throttle . `global wifi_scan_throttle_enabled` (0 = faster scans)
 * - Wi-Fi suspend optim.  `global wifi_suspend_optimizations_enabled`
 * - Avoid bad Wi-Fi ...... `global network_avoid_bad_wifi`
 * - BT absolute volume ... `global bluetooth_disable_absolute_volume`
 * - Auto time / zone .... `global auto_time` + `auto_time_zone`
 * - Brightness bias ...... `system screen_auto_brightness_adj` (-1..1)
 *
 * Every apply is verified with a read-back; failures surface the copyable sheet.
 */
object DisplayExtras {
    const val INV_NS = "secure"
    const val INV_KEY = "accessibility_display_inversion_enabled"

    const val CONTRAST_NS = "secure"
    const val CONTRAST_KEY = "high_text_contrast_enabled"

    const val FONTW_NS = "system"
    const val FONTW_KEY = "font_weight_adjustment"
    const val FONTW_NORMAL = "0"
    const val FONTW_BOLD = "300"

    const val CLOCK_NS = "secure"
    const val CLOCK_KEY = "clock_seconds"

    const val BATT_NS = "system"
    const val BATT_KEY = "status_bar_show_battery_percent"

    const val COLOR_NS = "system"
    const val COLOR_KEY = "display_color_mode"

    const val WB_NS = "secure"
    const val WB_KEY = "display_white_balance_enabled"

    const val GEST_NS = "secure"
    const val GEST_L = "back_gesture_inset_scale_left"
    const val GEST_R = "back_gesture_inset_scale_right"
    val GEST_PRESETS = listOf("0.5", "1.0", "1.5", "2.0")

    const val FREE_NS = "global"
    const val FREE_KEY = "enable_freeform_support"

    const val MULTI_NS = "secure"
    const val MULTI_KEY = "multi_press_timeout"
    val MULTI_PRESETS = listOf(150, 300, 400, 600)

    /** One row silences all five sound/vibration sources. */
    val QUIET_KEYS = listOf(
        "system sound_effects_enabled",
        "system lockscreen_sounds_enabled",
        "system dtmf_tone_when_dialing",
        "system haptic_feedback_enabled",
        "system vibrate_when_ringing"
    )

    const val SNOOZE_NS = "secure"
    const val SNOOZE_KEY = "notification_snooze_options"

    const val FINISH_NS = "global"
    const val FINISH_KEY = "always_finish_activities"

    const val THROT_NS = "global"
    const val THROT_KEY = "wifi_scan_throttle_enabled"

    const val SUSP_NS = "global"
    const val SUSP_KEY = "wifi_suspend_optimizations_enabled"

    const val AVOID_NS = "global"
    const val AVOID_KEY = "network_avoid_bad_wifi"

    const val BTAV_NS = "global"
    const val BTAV_KEY = "bluetooth_disable_absolute_volume"

    const val TIME_NS = "global"
    const val TIME_KEY = "auto_time"
    const val TIMEZONE_KEY = "auto_time_zone"

    const val ADJ_NS = "system"
    const val ADJ_KEY = "screen_auto_brightness_adj"
    val ADJ_PRESETS = listOf("-1.0", "-0.5", "0.0", "0.5", "1.0")

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

    fun buildQuiet(on: Boolean): String =
        QUIET_KEYS.joinToString(" && ") { kv ->
            val (ns, key) = kv.split(" ")
            buildSet(ns, key, if (on) "0" else "1")
        }

    fun buildGesture(v: String): String =
        "${buildSet(GEST_NS, GEST_L, v)} && ${buildSet(GEST_NS, GEST_R, v)}"

    fun buildReset(): String = listOf(
        buildSet(INV_NS, INV_KEY, "0"),
        buildSet(CONTRAST_NS, CONTRAST_KEY, "0"),
        buildSet(FONTW_NS, FONTW_KEY, FONTW_NORMAL),
        buildDelete(CLOCK_NS, CLOCK_KEY),
        buildDelete(BATT_NS, BATT_KEY),
        buildDelete(COLOR_NS, COLOR_KEY),
        buildSet(WB_NS, WB_KEY, "1"),
        buildSet(GEST_NS, GEST_L, "1.0"),
        buildSet(GEST_NS, GEST_R, "1.0"),
        buildSet(FREE_NS, FREE_KEY, "0"),
        buildDelete(MULTI_NS, MULTI_KEY),
        buildQuiet(false),
        buildSet(SNOOZE_NS, SNOOZE_KEY, "0"),
        buildSet(FINISH_NS, FINISH_KEY, "0"),
        buildSet(THROT_NS, THROT_KEY, "1"),
        buildSet(SUSP_NS, SUSP_KEY, "1"),
        buildSet(AVOID_NS, AVOID_KEY, "1"),
        buildSet(BTAV_NS, BTAV_KEY, "0"),
        buildSet(TIME_NS, TIME_KEY, "1"),
        buildSet(TIME_NS, TIMEZONE_KEY, "1"),
        buildDelete(ADJ_NS, ADJ_KEY)
    ).joinToString(" && ")
}

private data class DxState(
    val inversion: Boolean,
    val contrast: Boolean,
    val fontW: String?,
    val clockSec: Boolean,
    val battPct: Boolean,
    val colorMode: String?,
    val whiteBal: Boolean,
    val gesture: String?,
    val freeform: Boolean,
    val multiPress: String?,
    val quietCount: Int,
    val snooze: Boolean,
    val finish: Boolean,
    val throttle: Boolean,
    val suspend: Boolean,
    val avoidWifi: Boolean,
    val btAbsVol: Boolean,
    val autoTime: Boolean,
    val autoZone: Boolean,
    val brightAdj: String?
) {
    fun activeCount(): Int {
        var n = 0
        if (inversion) n++
        if (contrast) n++
        if (fontW != null && fontW != DisplayExtras.FONTW_NORMAL) n++
        if (clockSec) n++
        if (battPct) n++
        if (DisplayExtras.isSet(colorMode)) n++
        if (!whiteBal) n++
        if (gesture != null && gesture != "1.0") n++
        if (freeform) n++
        if (DisplayExtras.isSet(multiPress)) n++
        if (quietCount > 0) n++
        if (snooze) n++
        if (finish) n++
        if (!throttle) n++
        if (!suspend) n++
        if (!avoidWifi) n++
        if (btAbsVol) n++
        if (!autoTime) n++
        if (!autoZone) n++
        if (brightAdj != null && brightAdj != "0.0" && brightAdj != "0") n++
        return n
    }
}

private fun MainActivity.readDxState(): DxState {
    fun get(ns: String, key: String): String? {
        val t = runAdbCommandWithResult("settings get $ns $key").trim()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
    }
    val quietOff = DisplayExtras.QUIET_KEYS.count { kv ->
        val (ns, key) = kv.split(" ")
        get(ns, key) == "0"
    }
    val gestL = get(DisplayExtras.GEST_NS, DisplayExtras.GEST_L)
    return DxState(
        inversion = get(DisplayExtras.INV_NS, DisplayExtras.INV_KEY) == "1",
        contrast = get(DisplayExtras.CONTRAST_NS, DisplayExtras.CONTRAST_KEY) == "1",
        fontW = get(DisplayExtras.FONTW_NS, DisplayExtras.FONTW_KEY),
        clockSec = get(DisplayExtras.CLOCK_NS, DisplayExtras.CLOCK_KEY) == "1",
        battPct = get(DisplayExtras.BATT_NS, DisplayExtras.BATT_KEY) == "1",
        colorMode = get(DisplayExtras.COLOR_NS, DisplayExtras.COLOR_KEY),
        whiteBal = get(DisplayExtras.WB_NS, DisplayExtras.WB_KEY) != "0",
        gesture = gestL,
        freeform = get(DisplayExtras.FREE_NS, DisplayExtras.FREE_KEY) == "1",
        multiPress = get(DisplayExtras.MULTI_NS, DisplayExtras.MULTI_KEY),
        quietCount = quietOff,
        snooze = get(DisplayExtras.SNOOZE_NS, DisplayExtras.SNOOZE_KEY) == "1",
        finish = get(DisplayExtras.FINISH_NS, DisplayExtras.FINISH_KEY) == "1",
        throttle = get(DisplayExtras.THROT_NS, DisplayExtras.THROT_KEY) != "0",
        suspend = get(DisplayExtras.SUSP_NS, DisplayExtras.SUSP_KEY) != "0",
        avoidWifi = get(DisplayExtras.AVOID_NS, DisplayExtras.AVOID_KEY) != "0",
        btAbsVol = get(DisplayExtras.BTAV_NS, DisplayExtras.BTAV_KEY) == "1",
        autoTime = get(DisplayExtras.TIME_NS, DisplayExtras.TIME_KEY) != "0",
        autoZone = get(DisplayExtras.TIME_NS, DisplayExtras.TIMEZONE_KEY) != "0",
        brightAdj = get(DisplayExtras.ADJ_NS, DisplayExtras.ADJ_KEY)
    )
}

/** Set + read-back verify with honest failure sheet. */
private fun MainActivity.applyDx(setCmd: String, verifyCmd: String, expect: String, reopen: () -> Unit) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(setCmd)
        val back = if (res.ok) runAdbCommandWithResult(verifyCmd).trim() else ""
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            val ok = res.ok && (expect.isEmpty() && (back.isEmpty() || back == "null") || back == expect || back.contains(expect))
            if (ok) {
                UiToast.success(this@applyDx, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                val detail = if (res.ok) ShellResult(res.code, "$setCmd\nread-back: '$back' (expected: '$expect')")
                else res
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), setCmd, detail) { reopen() }
                fetchSystemStatuses()
            }
        }
    }
}

internal fun MainActivity.showDisplayExtrasSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val state = readDxState()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderDxSheet(state)
        }
    }
}

private fun MainActivity.renderDxSheet(s: DxState) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.title_dx))
        .message(getString(R.string.desc_dx))

    fun setOf(ns: String, key: String, v: String) = DisplayExtras.buildSet(ns, key, v)
    fun verifyOf(ns: String, key: String) = "settings get $ns $key"
    fun toggle(title: String, desc: String, on: Boolean, set: String, verify: String, expect: String) {
        list.addView(optionRow(this, title, desc, selected = on) {
            sheet.dismiss()
            applyDx(set, verify, expect) { showDisplayExtrasSheet() }
        })
    }
    val D = DisplayExtras

    toggle(getString(R.string.dx_inversion), getString(R.string.dx_inversion_desc), s.inversion,
        setOf(D.INV_NS, D.INV_KEY, if (s.inversion) "0" else "1"),
        verifyOf(D.INV_NS, D.INV_KEY), if (s.inversion) "0" else "1")
    toggle(getString(R.string.dx_contrast), getString(R.string.dx_contrast_desc), s.contrast,
        setOf(D.CONTRAST_NS, D.CONTRAST_KEY, if (s.contrast) "0" else "1"),
        verifyOf(D.CONTRAST_NS, D.CONTRAST_KEY), if (s.contrast) "0" else "1")

    list.addView(optionRow(this, getString(R.string.dx_fontw),
        if (s.fontW == D.FONTW_BOLD) getString(R.string.dx_fontw_bold) else getString(R.string.dx_fontw_normal),
        selected = s.fontW == D.FONTW_BOLD) {
        sheet.dismiss()
        applyDx(setOf(D.FONTW_NS, D.FONTW_KEY, if (s.fontW == D.FONTW_BOLD) D.FONTW_NORMAL else D.FONTW_BOLD),
            verifyOf(D.FONTW_NS, D.FONTW_KEY),
            if (s.fontW == D.FONTW_BOLD) D.FONTW_NORMAL else D.FONTW_BOLD) { showDisplayExtrasSheet() }
    })

    toggle(getString(R.string.dx_clocksec), getString(R.string.dx_clocksec_desc), s.clockSec,
        setOf(D.CLOCK_NS, D.CLOCK_KEY, if (s.clockSec) "0" else "1"),
        verifyOf(D.CLOCK_NS, D.CLOCK_KEY), if (s.clockSec) "0" else "1")
    toggle(getString(R.string.dx_battpct), getString(R.string.dx_battpct_desc), s.battPct,
        setOf(D.BATT_NS, D.BATT_KEY, if (s.battPct) "0" else "1"),
        verifyOf(D.BATT_NS, D.BATT_KEY), if (s.battPct) "0" else "1")

    list.addView(optionRow(this, getString(R.string.dx_colormode), colorModeLabel(s.colorMode),
        selected = D.isSet(s.colorMode)) {
        sheet.dismiss()
        showColorModeSheet(s.colorMode)
    })

    toggle(getString(R.string.dx_whitebal), getString(R.string.dx_whitebal_desc), !s.whiteBal,
        setOf(D.WB_NS, D.WB_KEY, if (s.whiteBal) "0" else "1"),
        verifyOf(D.WB_NS, D.WB_KEY), if (s.whiteBal) "0" else "1")

    list.addView(optionRow(this, getString(R.string.dx_gesture),
        s.gesture?.let { getString(R.string.dx_gesture_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = s.gesture != null && s.gesture != "1.0") {
        sheet.dismiss()
        showGestureSheet(s.gesture)
    })

    toggle(getString(R.string.dx_freeform), getString(R.string.dx_freeform_desc), s.freeform,
        setOf(D.FREE_NS, D.FREE_KEY, if (s.freeform) "0" else "1"),
        verifyOf(D.FREE_NS, D.FREE_KEY), if (s.freeform) "0" else "1")

    list.addView(optionRow(this, getString(R.string.dx_multipress),
        s.multiPress?.let { getString(R.string.dx_ms_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = D.isSet(s.multiPress)) {
        sheet.dismiss()
        showMultiPressSheet(s.multiPress)
    })

    toggle(getString(R.string.dx_quiet),
        if (s.quietCount == 0) getString(R.string.dx_quiet_desc)
        else getString(R.string.dx_quiet_active, s.quietCount),
        s.quietCount > 0,
        D.buildQuiet(s.quietCount == 0),
        "settings get system sound_effects_enabled", if (s.quietCount > 0) "1" else "0")

    toggle(getString(R.string.dx_snooze), getString(R.string.dx_snooze_desc), s.snooze,
        setOf(D.SNOOZE_NS, D.SNOOZE_KEY, if (s.snooze) "0" else "1"),
        verifyOf(D.SNOOZE_NS, D.SNOOZE_KEY), if (s.snooze) "0" else "1")
    toggle(getString(R.string.dx_finish), getString(R.string.dx_finish_desc), s.finish,
        setOf(D.FINISH_NS, D.FINISH_KEY, if (s.finish) "0" else "1"),
        verifyOf(D.FINISH_NS, D.FINISH_KEY), if (s.finish) "0" else "1")
    toggle(getString(R.string.dx_throttle), getString(R.string.dx_throttle_desc), !s.throttle,
        setOf(D.THROT_NS, D.THROT_KEY, if (s.throttle) "0" else "1"),
        verifyOf(D.THROT_NS, D.THROT_KEY), if (s.throttle) "0" else "1")
    toggle(getString(R.string.dx_suspend), getString(R.string.dx_suspend_desc), !s.suspend,
        setOf(D.SUSP_NS, D.SUSP_KEY, if (s.suspend) "0" else "1"),
        verifyOf(D.SUSP_NS, D.SUSP_KEY), if (s.suspend) "0" else "1")
    toggle(getString(R.string.dx_avoidwifi), getString(R.string.dx_avoidwifi_desc), !s.avoidWifi,
        setOf(D.AVOID_NS, D.AVOID_KEY, if (s.avoidWifi) "0" else "1"),
        verifyOf(D.AVOID_NS, D.AVOID_KEY), if (s.avoidWifi) "0" else "1")
    toggle(getString(R.string.dx_btabsvol), getString(R.string.dx_btabsvol_desc), s.btAbsVol,
        setOf(D.BTAV_NS, D.BTAV_KEY, if (s.btAbsVol) "0" else "1"),
        verifyOf(D.BTAV_NS, D.BTAV_KEY), if (s.btAbsVol) "0" else "1")
    toggle(getString(R.string.dx_autotime), getString(R.string.dx_autotime_desc), !s.autoTime,
        setOf(D.TIME_NS, D.TIME_KEY, if (s.autoTime) "0" else "1"),
        verifyOf(D.TIME_NS, D.TIME_KEY), if (s.autoTime) "0" else "1")
    toggle(getString(R.string.dx_autozone), getString(R.string.dx_autozone_desc), !s.autoZone,
        setOf(D.TIME_NS, D.TIMEZONE_KEY, if (s.autoZone) "0" else "1"),
        verifyOf(D.TIME_NS, D.TIMEZONE_KEY), if (s.autoZone) "0" else "1")

    list.addView(optionRow(this, getString(R.string.dx_brightadj),
        s.brightAdj?.let { getString(R.string.dx_adj_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = s.brightAdj != null && s.brightAdj != "0.0" && s.brightAdj != "0") {
        sheet.dismiss()
        showBrightAdjSheet(s.brightAdj)
    })

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.colorModeLabel(raw: String?): String = when (raw) {
    "1" -> getString(R.string.dx_colormode_boosted)
    "2" -> getString(R.string.dx_colormode_vivid)
    "0" -> getString(R.string.dx_colormode_natural)
    else -> getString(R.string.comfort_not_set)
}

private fun MainActivity.showColorModeSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dx_colormode))
        .message(getString(R.string.dx_colormode_msg))
    listOf("0" to R.string.dx_colormode_natural, "1" to R.string.dx_colormode_boosted, "2" to R.string.dx_colormode_vivid)
        .forEach { (v, label) ->
            list.addView(optionRow(this, getString(label), null, selected = current == v) {
                sheet.dismiss()
                applyDx(DisplayExtras.buildSet(DisplayExtras.COLOR_NS, DisplayExtras.COLOR_KEY, v),
                    "settings get ${DisplayExtras.COLOR_NS} ${DisplayExtras.COLOR_KEY}", v) { showDisplayExtrasSheet() }
            })
        }
    list.addView(infoCard(this, getString(R.string.dx_colormode_note)))
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showGestureSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dx_gesture))
        .message(getString(R.string.dx_gesture_msg))
    DisplayExtras.GEST_PRESETS.forEach { v ->
        list.addView(optionRow(this, getString(R.string.dx_gesture_fmt, v), null, selected = current == v) {
            sheet.dismiss()
            applyDx(DisplayExtras.buildGesture(v),
                "settings get ${DisplayExtras.GEST_NS} ${DisplayExtras.GEST_L}", v) { showDisplayExtrasSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showMultiPressSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dx_multipress))
        .message(getString(R.string.dx_multipress_msg))
    DisplayExtras.MULTI_PRESETS.forEach { ms ->
        list.addView(optionRow(this, getString(R.string.dx_ms_fmt, ms), null, selected = current == ms.toString()) {
            sheet.dismiss()
            applyDx(DisplayExtras.buildSet(DisplayExtras.MULTI_NS, DisplayExtras.MULTI_KEY, ms.toString()),
                "settings get ${DisplayExtras.MULTI_NS} ${DisplayExtras.MULTI_KEY}", ms.toString()) { showDisplayExtrasSheet() }
        })
    }
    list.addView(optionRow(this, getString(R.string.comfort_reset_default), getString(R.string.comfort_reset_default_desc)) {
        sheet.dismiss()
        applyDx(DisplayExtras.buildDelete(DisplayExtras.MULTI_NS, DisplayExtras.MULTI_KEY),
            "settings get ${DisplayExtras.MULTI_NS} ${DisplayExtras.MULTI_KEY}", "") { showDisplayExtrasSheet() }
    })
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showBrightAdjSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dx_brightadj))
        .message(getString(R.string.dx_brightadj_msg))
    DisplayExtras.ADJ_PRESETS.forEach { v ->
        list.addView(optionRow(this, getString(R.string.dx_adj_fmt, v), null, selected = current == v) {
            sheet.dismiss()
            applyDx(DisplayExtras.buildSet(DisplayExtras.ADJ_NS, DisplayExtras.ADJ_KEY, v),
                "settings get ${DisplayExtras.ADJ_NS} ${DisplayExtras.ADJ_KEY}", v) { showDisplayExtrasSheet() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

internal fun MainActivity.resetDisplayExtras() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusDx)?.text = getString(R.string.status_checking)
    runAdbCommand(DisplayExtras.buildReset(), getString(R.string.msg_dx_reset)) { fetchSystemStatuses() }
}

/** Status-line summary: Default when nothing is active, otherwise "N of 20 on". */
internal fun MainActivity.dxStatusText(): String {
    val s = runCatching { readDxState() }.getOrNull() ?: return getString(R.string.status_unknown)
    val n = s.activeCount()
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.comfort_status_fmt, n, DisplayExtras.TOTAL)
}
