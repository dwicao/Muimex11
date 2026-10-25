package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun prefsBatteryMode(prefs: SharedPreferences): String =
    prefs.getString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF).orEmpty()

private fun readBatteryPrev(prefs: SharedPreferences): Map<String, String> {
    val raw = prefs.getString(Prefs.KEY_BATTERY_PREV, null) ?: return emptyMap()
    return try {
        val o = JSONObject(raw)
        BatterySaver.SNAPSHOT_KEYS.associateWith { o.optString(it, "") }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun saveBatteryPrev(prefs: SharedPreferences, prev: Map<String, String>) {
    val o = JSONObject()
    prev.forEach { (k, v) -> o.put(k, v) }
    prefs.edit().putString(Prefs.KEY_BATTERY_PREV, o.toString()).apply()
}

internal fun MainActivity.showBatterySaverSheet(prefs: SharedPreferences) {
    val current = prefsBatteryMode(prefs)
    if (current != BatterySaver.MODE_OFF && current.isNotEmpty()) {
        UiToast.info(this, getString(R.string.toast_battery_already), Toast.LENGTH_SHORT).show()
        return
    }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.bat_title))
        .message(getString(R.string.bat_message))
    list.addView(optionRow(this, getString(R.string.bat_balanced), getString(R.string.bat_balanced_desc)) {
        sheet.dismiss()
        applyBatterySaver(prefs, BatterySaver.MODE_BALANCED)
    })
    list.addView(optionRow(this, getString(R.string.bat_saver), getString(R.string.bat_saver_desc)) {
        sheet.dismiss()
        applyBatterySaver(prefs, BatterySaver.MODE_SAVER)
    })
    list.addView(optionRow(this, getString(R.string.bat_extreme), getString(R.string.bat_extreme_desc)) {
        sheet.dismiss()
        applyBatterySaver(prefs, BatterySaver.MODE_EXTREME)
    })
    list.addView(optionRow(this, getString(R.string.bat_doze), getString(R.string.bat_doze_desc)) {
        sheet.dismiss()
        showDozeSheet(prefs)
    })
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TEXT).show()
}

internal fun MainActivity.applyBatterySaver(prefs: SharedPreferences, mode: String) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        // Snapshot current values first so reset restores the user's own setup.
        if (prefs.getString(Prefs.KEY_BATTERY_PREV, null) == null) {
            val prev = BatterySaver.SNAPSHOT_KEYS.associateWith { k ->
                runAdbCommandWithResult("settings get global $k").trim()
            }
            saveBatteryPrev(prefs, prev)
        }
        // Extreme also undoes perf-hungry states so saving actually sticks.
        val extra = if (mode == BatterySaver.MODE_EXTREME) {
            "cmd power set-fixed-performance-mode-enabled false; cmd thermalservice reset; "
        } else ""
        val cmd = extra + BatterySaver.buildApplyCmd(mode)
        withContext(Dispatchers.Main) {
            runAdbCommand(cmd, getString(BatterySaverLabelToast(mode))) {
                prefs.edit().putString(Prefs.KEY_BATTERY_MODE, mode).apply()
                if (mode == BatterySaver.MODE_EXTREME) {
                    prefs.edit()
                        .putBoolean(Prefs.KEY_FIXED_PERF, false)
                        .putBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false)
                        .putBoolean(Prefs.KEY_THERMAL_DISABLED, false)
                        .apply()
                }
                findViewById<TextView>(R.id.tvStatusBatterySaver).text = getString(BatterySaver.labelRes(mode))
                fetchSystemStatuses()
            }
        }
    }
}

private fun BatterySaverLabelToast(mode: String): Int = when (mode) {
    BatterySaver.MODE_SAVER -> R.string.bat_done_saver
    BatterySaver.MODE_EXTREME -> R.string.bat_done_extreme
    else -> R.string.bat_done_balanced
}

internal fun MainActivity.resetBatterySaver(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val prev = readBatteryPrev(prefs)
    val cmd = BatterySaver.buildResetCmd(prev)
    runAdbCommand(cmd, getString(R.string.bat_reset_done)) {
        prefs.edit()
            .putString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF)
            .remove(Prefs.KEY_BATTERY_PREV)
            .apply()
        findViewById<TextView>(R.id.tvStatusBatterySaver).text = getString(R.string.status_default)
        fetchSystemStatuses()
    }
}

internal fun batteryResetCmdForAll(prefs: SharedPreferences): String {
    val raw = prefs.getString(Prefs.KEY_BATTERY_PREV, null)
    val prev: Map<String, String> = if (raw == null) {
        emptyMap()
    } else {
        try {
            val o = JSONObject(raw)
            BatterySaver.SNAPSHOT_KEYS.associateWith { o.optString(it, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }
    return BatterySaver.buildResetCmd(prev)
}

internal fun clearBatteryPrefs(prefs: SharedPreferences) {
    prefs.edit()
        .putString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF)
        .remove(Prefs.KEY_BATTERY_PREV)
        .apply()
}
