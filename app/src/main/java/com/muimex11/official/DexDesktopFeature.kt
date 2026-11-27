package com.muimex11.official

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure command & state logic for DeX & External Desktop Environment Tweaks. */
object DexDesktopLogic {
    const val KEY_FORCE_DESKTOP = "force_desktop_mode_on_external_displays"
    const val KEY_FREEFORM = "enable_freeform_support"
    const val KEY_RESIZABLE = "force_resizable_activities"
    const val KEY_NON_RESIZABLE_MW = "enable_non_resizable_multi_window"
    const val KEY_STAY_ON = "stay_on_while_plugged_in"
    const val KEY_FONT_SCALE = "font_scale"
    const val KEY_POINTER_SPEED = "pointer_speed"

    data class DesktopState(
        val forceDesktop: Boolean,
        val freeform: Boolean,
        val forceResizable: Boolean,
        val nonResizableMw: Boolean,
        val stayAwake: Boolean,
        val fontScale: String,
        val pointerSpeed: String
    ) {
        fun appliedCount(): Int {
            var c = 0
            if (forceDesktop) c++
            if (freeform) c++
            if (forceResizable) c++
            if (nonResizableMw) c++
            if (stayAwake) c++
            val fs = fontScale.toFloatOrNull()
            if (fs != null && fs != 1.0f) c++
            val ps = pointerSpeed.toIntOrNull()
            if (ps != null && ps != 0) c++
            return c
        }
    }

    fun parseState(rawTagged: String): DesktopState {
        fun v(tag: String) = ShellBatcher.value(rawTagged, tag).trim()
        val font = v("font_scale").let { if (it.isEmpty() || it == "null") "1.0" else it }
        val ptr = v("ptr_speed").let { if (it.isEmpty() || it == "null") "0" else it }
        val stayRaw = v("stay_on").toIntOrNull() ?: 0
        return DesktopState(
            forceDesktop = v("force_desktop") == "1",
            freeform = v("freeform") == "1",
            forceResizable = v("resizable") == "1",
            nonResizableMw = v("non_res_mw") == "1",
            stayAwake = stayRaw > 0,
            fontScale = font,
            pointerSpeed = ptr
        )
    }

    fun buildProductivityPresetCommand(): String = listOf(
        "settings put global $KEY_FORCE_DESKTOP 1",
        "settings put global $KEY_FREEFORM 1",
        "settings put global $KEY_RESIZABLE 1",
        "settings put global $KEY_NON_RESIZABLE_MW 1",
        "settings put global $KEY_STAY_ON 7",
        "settings put system $KEY_FONT_SCALE 0.90",
        "settings put system $KEY_POINTER_SPEED 3"
    ).joinToString(" && ")

    fun buildResetCommand(): String = listOf(
        "settings put global $KEY_FORCE_DESKTOP 0",
        "settings put global $KEY_FREEFORM 0",
        "settings put global $KEY_RESIZABLE 0",
        "settings put global $KEY_NON_RESIZABLE_MW 0",
        "settings put global $KEY_STAY_ON 0",
        "settings put system $KEY_FONT_SCALE 1.0",
        "settings delete system $KEY_POINTER_SPEED"
    ).joinToString(" && ")
}

internal fun MainActivity.dexDesktopStatusPair(): Pair<String, Boolean> {
    val out = ShizukuRunner.runTagged(
        listOf(
            "force_desktop" to "settings get global ${DexDesktopLogic.KEY_FORCE_DESKTOP}",
            "freeform" to "settings get global ${DexDesktopLogic.KEY_FREEFORM}",
            "resizable" to "settings get global ${DexDesktopLogic.KEY_RESIZABLE}",
            "non_res_mw" to "settings get global ${DexDesktopLogic.KEY_NON_RESIZABLE_MW}",
            "stay_on" to "settings get global ${DexDesktopLogic.KEY_STAY_ON}",
            "font_scale" to "settings get system ${DexDesktopLogic.KEY_FONT_SCALE}",
            "ptr_speed" to "settings get system ${DexDesktopLogic.KEY_POINTER_SPEED}"
        )
    )
    val state = DexDesktopLogic.parseState(out)
    val count = state.appliedCount()
    val label = if (count == 0) getString(R.string.status_default)
    else getString(R.string.dexdesk_status_fmt, count)
    return label to (count > 0)
}

internal fun MainActivity.resetDexDesktop() {
    if (!hasShizukuPermission()) return
    runAdbCommand(DexDesktopLogic.buildResetCommand(), getString(R.string.dexdesk_reset_done)) {
        fetchSystemStatuses()
    }
}

internal fun MainActivity.showDexDesktopSheet() {
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_resolution)
        .title(getString(R.string.title_dex_desktop))
        .message(getString(R.string.dexdesk_reading))
    loading.show(indeterminate = true)

    lifecycleScope.launch(Dispatchers.IO) {
        val out = ShizukuRunner.runTagged(
            listOf(
                "force_desktop" to "settings get global ${DexDesktopLogic.KEY_FORCE_DESKTOP}",
                "freeform" to "settings get global ${DexDesktopLogic.KEY_FREEFORM}",
                "resizable" to "settings get global ${DexDesktopLogic.KEY_RESIZABLE}",
                "non_res_mw" to "settings get global ${DexDesktopLogic.KEY_NON_RESIZABLE_MW}",
                "stay_on" to "settings get global ${DexDesktopLogic.KEY_STAY_ON}",
                "font_scale" to "settings get system ${DexDesktopLogic.KEY_FONT_SCALE}",
                "ptr_speed" to "settings get system ${DexDesktopLogic.KEY_POINTER_SPEED}"
            )
        )
        val st = DexDesktopLogic.parseState(out)
        withContext(Dispatchers.Main) {
            loading.dismiss()
            presentDexDesktopMenu(st)
        }
    }
}

private fun MainActivity.presentDexDesktopMenu(initial: DexDesktopLogic.DesktopState) {
    val st = initial
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_resolution)
        .title(getString(R.string.title_dex_desktop))
        .message(getString(R.string.dexdesk_sheet_msg))

    fun applySetting(cmd: String, toast: String) {
        sheet.dismiss()
        runAdbCommand(cmd, toast) { fetchSystemStatuses() }
    }

    list.addView(
        optionRow(
            this,
            getString(R.string.dexdesk_preset_prod),
            getString(R.string.dexdesk_preset_prod_desc),
            selected = st.appliedCount() >= 5
        ) {
            applySetting(DexDesktopLogic.buildProductivityPresetCommand(), getString(R.string.dexdesk_preset_applied))
        }
    )

    val toggles = listOf(
        Triple(
            R.string.dexdesk_force_desktop,
            R.string.dexdesk_force_desktop_desc,
            Triple(DexDesktopLogic.KEY_FORCE_DESKTOP, "global", st.forceDesktop)
        ),
        Triple(
            R.string.dexdesk_freeform,
            R.string.dexdesk_freeform_desc,
            Triple(DexDesktopLogic.KEY_FREEFORM, "global", st.freeform)
        ),
        Triple(
            R.string.dexdesk_resizable,
            R.string.dexdesk_resizable_desc,
            Triple(DexDesktopLogic.KEY_RESIZABLE, "global", st.forceResizable)
        ),
        Triple(
            R.string.dexdesk_nonres_mw,
            R.string.dexdesk_nonres_mw_desc,
            Triple(DexDesktopLogic.KEY_NON_RESIZABLE_MW, "global", st.nonResizableMw)
        )
    )

    toggles.forEach { (titleRes, descRes, spec) ->
        val (key, ns, enabled) = spec
        list.addView(
            optionRow(this, getString(titleRes), getString(descRes), selected = enabled) {
                val next = if (enabled) "0" else "1"
                applySetting(
                    "settings put $ns $key $next",
                    getString(R.string.dexdesk_toggled_fmt, getString(titleRes))
                )
            }
        )
    }

    list.addView(
        optionRow(
            this,
            getString(R.string.dexdesk_stay_on),
            getString(R.string.dexdesk_stay_on_desc),
            selected = st.stayAwake
        ) {
            val next = if (st.stayAwake) "0" else "7"
            applySetting("settings put global ${DexDesktopLogic.KEY_STAY_ON} $next", getString(R.string.dexdesk_toggled_fmt, getString(R.string.dexdesk_stay_on)))
        }
    )

    list.addView(
        optionRow(
            this,
            getString(R.string.dexdesk_font_title),
            getString(R.string.dexdesk_font_desc, st.fontScale)
        ) {
            sheet.dismiss()
            showFontScalePicker(st.fontScale)
        }
    )

    list.addView(
        optionRow(
            this,
            getString(R.string.dexdesk_ptr_title),
            getString(R.string.dexdesk_ptr_desc, st.pointerSpeed)
        ) {
            sheet.dismiss()
            showPointerSpeedPicker(st.pointerSpeed)
        }
    )

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .button(getString(R.string.dexdesk_btn_reset), SheetStyle.DANGER) {
            resetDexDesktop()
        }
        .show()
}

private fun MainActivity.showFontScalePicker(current: String) {
    val scales = listOf("0.80", "0.85", "0.90", "1.00", "1.10", "1.20")
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_resolution)
        .title(getString(R.string.dexdesk_font_title))
        .message(getString(R.string.dexdesk_font_desc, current))

    scales.forEach { s ->
        val selected = current.toFloatOrNull() == s.toFloatOrNull()
        list.addView(optionRow(this, "${s}x", null, selected = selected) {
            sheet.dismiss()
            runAdbCommand(
                "settings put system ${DexDesktopLogic.KEY_FONT_SCALE} $s",
                getString(R.string.dexdesk_font_applied, s)
            ) { fetchSystemStatuses() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.showPointerSpeedPicker(current: String) {
    val speeds = listOf(-4, -2, 0, 2, 4, 6, 7)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_resolution)
        .title(getString(R.string.dexdesk_ptr_title))
        .message(getString(R.string.dexdesk_ptr_desc, current))

    speeds.forEach { sp ->
        val selected = current.toIntOrNull() == sp
        val label = if (sp == 0) getString(R.string.w_default) else if (sp > 0) "+$sp" else sp.toString()
        list.addView(optionRow(this, label, null, selected = selected) {
            sheet.dismiss()
            runAdbCommand(
                "settings put system ${DexDesktopLogic.KEY_POINTER_SPEED} $sp",
                getString(R.string.dexdesk_ptr_applied, label)
            ) { fetchSystemStatuses() }
        })
    }
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}
