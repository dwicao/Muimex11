package com.muimex11.official

import android.content.Context
import android.hardware.display.DisplayManager
import android.text.InputType
import android.view.Display
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.supportedRefreshRates(): List<Int> = try {
    val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val modes = dm.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes
    if (modes == null) emptyList()
    else RefreshRates.supportedFromModes(modes.map { it.refreshRate })
} catch (_: Exception) {
    emptyList()
}

internal fun MainActivity.showRefreshRateDialog() {
    val (minLayout, minInput) = styledField(this, getString(R.string.hint_min_hz), InputType.TYPE_CLASS_NUMBER, topMarginDp = 0)
    val (maxLayout, maxInput) = styledField(this, getString(R.string.hint_max_hz), InputType.TYPE_CLASS_NUMBER, topMarginDp = 0)

    val fields = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        minLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        maxLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(10) }
        addView(minLayout)
        addView(maxLayout)
    }

    // Presets follow the panel's real modes (DisplayManager), not a hardcoded
    // list: asking for an unsupported rate makes some ROMs (e.g. Realme/
    // ColorOS) silently revert peak_refresh_rate to the nearest supported one.
    val supported = supportedRefreshRates()
    val presetRates = if (supported.isNotEmpty()) supported else listOf(60, 90, 120, 144)
    val presets = presetRow(this, presetRates.map { "$it Hz" }) { i ->
        minInput.setText(presetRates[i].toString())
        maxInput.setText(presetRates[i].toString())
    }

    val sheet = Sheet(this)
    sheet.icon(R.drawable.ic_tune)
        .title(getString(R.string.dlg_refresh))
        .message(getString(R.string.dlg_refresh_msg))
        .content(presets)
        .content(fields)
    if (supported.isNotEmpty()) {
        sheet.content(infoCard(this, getString(R.string.dlg_refresh_supported, supported.joinToString(" · ") { "$it Hz" })))
    }
    sheet.button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.btn_apply), SheetStyle.PRIMARY, dismiss = false) {
            val min = minInput.text.toString().trim().toIntOrNull()
            val max = maxInput.text.toString().trim().toIntOrNull()
            when {
                min == null || max == null -> UiToast.warning(this, getString(R.string.toast_valid_numbers), Toast.LENGTH_SHORT).show()
                min !in 30..240 || max !in 30..240 -> UiToast.warning(this, getString(R.string.toast_hz_range), Toast.LENGTH_SHORT).show()
                min > max -> UiToast.warning(this, getString(R.string.toast_min_gt_max), Toast.LENGTH_SHORT).show()
                else -> {
                    sheet.dismiss()
                    applyRefreshRate(min, max, supported)
                }
            }
        }
        .show()
}

private fun MainActivity.applyRefreshRate(min: Int, max: Int, supported: List<Int>) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        ShizukuRunner.exec(RefreshRates.buildSet(min, max))
        val gotMin = runAdbCommandWithResult("settings get system min_refresh_rate").trim()
        val gotMax = runAdbCommandWithResult("settings get system peak_refresh_rate").trim()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (gotMin == min.toString() && gotMax == max.toString()) {
                UiToast.success(this@applyRefreshRate, getString(R.string.toast_hz_set, min, max), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                showRefreshRevertedSheet(min, max, gotMin, gotMax, supported)
                fetchSystemStatuses()
            }
        }
    }
}

/**
 * The setprop calls succeeded but the ROM restored different values
 * (e.g. Realme forcing peak back to 60 when 30 Hz has no display mode).
 * Explain, and offer the nearest supported rate one tap away.
 */
private fun MainActivity.showRefreshRevertedSheet(
    wantMin: Int, wantMax: Int, gotMin: String, gotMax: String, supported: List<Int>
) {
    val nearestMin = RefreshRates.nearestSupported(wantMin, supported)
    val nearestMax = RefreshRates.nearestSupported(wantMax, supported)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_warning)
        .title(getString(R.string.dlg_refresh_reverted_title))
        .message(
            getString(R.string.dlg_refresh_reverted_msg, "$wantMin - $wantMax Hz", "$gotMin - $gotMax Hz")
        )
    if (nearestMin != null && nearestMax != null && (nearestMin != wantMin || nearestMax != wantMax)) {
        list.addView(optionRow(
            this,
            getString(R.string.refresh_apply_nearest, "$nearestMin - $nearestMax Hz"),
            getString(R.string.refresh_apply_nearest_desc)
        ) {
            sheet.dismiss()
            applyRefreshRate(nearestMin, nearestMax, supported)
        })
        sheet.content(list)
    }
    sheet.button(getString(R.string.btn_close), SheetStyle.PRIMARY).show()
}
