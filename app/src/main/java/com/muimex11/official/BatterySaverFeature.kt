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
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.bat_title))
        .message(getString(R.string.bat_message))
    if (current != BatterySaver.MODE_OFF && current.isNotEmpty()) {
        // Modes stay locked while one is active, but the charge alarm is
        // independent and remains configurable.
        val alarm = batteryAlarmThreshold(prefs)
        list.addView(optionRow(
            this,
            getString(R.string.balarm_title),
            getString(if (alarm > 0) R.string.balarm_on_desc else R.string.balarm_off_desc, alarm)
        ) {
            sheet.dismiss()
            showBatteryAlarmSheet()
        })
        sheet.content(list).button(getString(R.string.btn_close), SheetStyle.PRIMARY).show()
        return
    }
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
    list.addView(optionRow(this, getString(R.string.bat_trigger), getString(R.string.bat_trigger_desc)) {
        sheet.dismiss()
        showBatteryTriggerSheet()
    })
    list.addView(optionRow(this, getString(R.string.bat_doze), getString(R.string.bat_doze_desc)) {
        sheet.dismiss()
        showDozeSheet(prefs)
    })
    val schedOn = BatterySchedule.isEnabled(this)
    list.addView(optionRow(
        this,
        getString(R.string.bat_sched),
        getString(if (schedOn) R.string.bat_sched_on_desc else R.string.bat_sched_off_desc),
        selected = schedOn
    ) {
        sheet.dismiss()
        BatterySchedule.setEnabled(this, !schedOn)
        syncScreenSchedule()
        UiToast.success(
            this,
            getString(if (!schedOn) R.string.bat_sched_on else R.string.bat_sched_off),
            Toast.LENGTH_SHORT
        ).show()
    })
    if (schedOn) {
        val sm = BatterySchedule.mode(this)
        list.addView(optionRow(
            this,
            getString(R.string.bat_sched_mode),
            getString(if (sm == BatterySaver.MODE_EXTREME) R.string.bat_extreme else R.string.bat_saver)
        ) {
            sheet.dismiss()
            BatterySchedule.setMode(
                this,
                if (sm == BatterySaver.MODE_EXTREME) BatterySaver.MODE_SAVER else BatterySaver.MODE_EXTREME
            )
            showBatterySaverSheet(prefs)
        })
    }
    val alarm = batteryAlarmThreshold(prefs)
    list.addView(optionRow(
        this,
        getString(R.string.balarm_title),
        getString(if (alarm > 0) R.string.balarm_on_desc else R.string.balarm_off_desc, alarm)
    ) {
        sheet.dismiss()
        showBatteryAlarmSheet()
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
                        .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)
                        .apply()
                    syncThermalService(0)
                }
                findViewById<TextView>(R.id.tvStatusBatterySaver).text = batteryStatusText(this@applyBatterySaver)
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

internal fun MainActivity.showBatteryTriggerSheet() {
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.bat_trigger))
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val cur = runAdbCommandWithResult("settings get global low_power_trigger_level").trim()
            .takeIf { it != "null" && it.isNotEmpty() } ?: "0"
        withContext(Dispatchers.Main) {
            loading.dismiss()
            val list = LinearLayout(this@showBatteryTriggerSheet).apply { orientation = LinearLayout.VERTICAL }
            val sheet = Sheet(this@showBatteryTriggerSheet)
                .icon(R.drawable.ic_battery)
                .title(getString(R.string.bat_trigger))
                .message(getString(R.string.bat_trigger_msg))
            listOf("0", "5", "10", "15", "20", "25").forEach { v ->
                list.addView(optionRow(
                    this@showBatteryTriggerSheet,
                    if (v == "0") getString(R.string.bat_trigger_off)
                    else getString(R.string.bat_trigger_fmt, v),
                    null, selected = cur == v
                ) {
                    sheet.dismiss()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val cmd = "settings put global low_power_trigger_level $v"
                        val res = ShizukuRunner.exec(cmd)
                        val back = runAdbCommandWithResult("settings get global low_power_trigger_level").trim()
                        withContext(Dispatchers.Main) {
                            if (res.ok && back == v) {
                                UiToast.success(this@showBatteryTriggerSheet, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                            } else {
                                showCommandErrorSheet(
                                    getString(R.string.dlg_cmd_failed_title), cmd,
                                    if (res.ok) ShellResult(res.code, "$cmd\nread-back: '$back'") else res
                                ) { showBatteryTriggerSheet() }
                            }
                            fetchSystemStatuses()
                        }
                    }
                })
            }
            sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
        }
    }
}

internal fun MainActivity.resetBatterySaver(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val prev = readBatteryPrev(prefs)
    val cmd = BatterySaver.buildResetCmd(prev) + " && settings put global low_power_trigger_level 0"
    runAdbCommand(cmd, getString(R.string.bat_reset_done)) {
        prefs.edit()
            .putString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF)
            .remove(Prefs.KEY_BATTERY_PREV)
            .apply()
        findViewById<TextView>(R.id.tvStatusBatterySaver).text = batteryStatusText(this@resetBatterySaver)
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
        .putBoolean(Prefs.KEY_SCHEDULE_ACTIVE, false)
        .apply()
}
