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
 * Read-only battery health from `dumpsys battery`: health, voltage,
 * temperature, technology and charge cycles (when the ROM reports them).
 * Pure parsing lives in [BatteryHealth]; this file is UI only.
 */
internal fun MainActivity.showBatteryHealthSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val info = BatteryHealth.parse(runAdbCommandWithResult("dumpsys battery"))
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (info == null) {
                UiToast.error(
                    this@showBatteryHealthSheet,
                    getString(R.string.toast_batthealth_fail), Toast.LENGTH_LONG
                ).show()
                return@withContext
            }
            val list = LinearLayout(this@showBatteryHealthSheet).apply {
                orientation = LinearLayout.VERTICAL
            }
            fun row(label: String, value: String) {
                list.addView(infoCard(
                    this@showBatteryHealthSheet,
                    getString(R.string.batthealth_row_fmt, label, value)
                ))
            }
            val vStr = info.voltageV?.let { String.format(java.util.Locale.getDefault(), "%.1f V", it) } ?: "—"
            val tStr = info.tempC?.let { String.format(java.util.Locale.getDefault(), "%.1f °C", it) } ?: "—"
            row(getString(R.string.batthealth_health), info.health)
            row(getString(R.string.batthealth_voltage), vStr)
            row(getString(R.string.batthealth_temp), tStr)
            row(getString(R.string.batthealth_cycles), info.cycles?.toString() ?: "—")
            row(getString(R.string.batthealth_tech), info.tech)
            Sheet(this@showBatteryHealthSheet)
                .icon(R.drawable.ic_battery)
                .title(getString(R.string.title_batthealth))
                .message(getString(R.string.desc_batthealth))
                .content(list)
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

internal fun MainActivity.battHealthStatusText(): String {
    val info = if (ShizukuRunner.isReady()) {
        BatteryHealth.parse(ShizukuRunner.query("dumpsys battery"))
    } else null
    return if (info == null) getString(R.string.status_unknown)
    else getString(R.string.status_batthealth_fmt, info.health)
}
