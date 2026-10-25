package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 0 = off, 1 = manual bypass, 2 = adaptive. */
internal fun thermalModeOf(prefs: SharedPreferences): Int = when {
    prefs.getBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false) -> 2
    prefs.getBoolean(Prefs.KEY_THERMAL_DISABLED, false) -> 1
    else -> 0
}

internal fun thermalThresholds(prefs: SharedPreferences): Pair<Int, Int> {
    val low = prefs.getInt(Prefs.KEY_THERMAL_ADAPT_LOW, ThermalPresets.BALANCED.low)
    val high = prefs.getInt(Prefs.KEY_THERMAL_ADAPT_HIGH, ThermalPresets.BALANCED.high)
    return if (ThermalPresets.isSane(low, high)) low to high
    else ThermalPresets.BALANCED.low to ThermalPresets.BALANCED.high
}

/** Tell the dock service to mirror a mode chosen here (shell already executed). */
internal fun MainActivity.syncThermalService(mode: Int) {
    if (!GameSpaceService.isRunning) return
    try {
        startService(
            Intent(this, GameSpaceService::class.java)
                .setAction(GameSpaceService.ACTION_THERMAL)
                .putExtra(GameSpaceService.EXTRA_THERMAL_MODE, mode)
        )
    } catch (_: Exception) {}
}

internal fun MainActivity.showThermalSheet(prefs: SharedPreferences) {
    val mode = thermalModeOf(prefs)
    val (low, high) = thermalThresholds(prefs)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_warning)
        .title(getString(R.string.dlg_thermal_title))
        .message(getString(R.string.dlg_thermal_msg))
    list.addView(optionRow(
        this,
        getString(R.string.thermal_off),
        getString(R.string.thermal_off_desc),
        selected = mode == 0
    ) {
        sheet.dismiss()
        applyThermalModePref(prefs, 0)
    })
    list.addView(optionRow(
        this,
        getString(R.string.thermal_manual),
        getString(R.string.thermal_manual_desc),
        selected = mode == 1
    ) {
        sheet.dismiss()
        applyThermalModePref(prefs, 1)
    })
    list.addView(optionRow(
        this,
        getString(R.string.thermal_adaptive),
        getString(R.string.thermal_adaptive_desc, low, high),
        selected = mode == 2
    ) {
        sheet.dismiss()
        applyThermalModePref(prefs, 2)
    })
    list.addView(optionRow(
        this,
        getString(R.string.thermal_threshold),
        getString(R.string.thermal_threshold_fmt, low, high)
    ) {
        sheet.dismiss()
        showThermalThresholdSheet(prefs)
    })
    list.addView(infoCard(this, getString(R.string.thermal_adaptive_note)))
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.showThermalThresholdSheet(prefs: SharedPreferences) {
    val (curLow, _) = thermalThresholds(prefs)
    val curId = ThermalPresets.ALL.firstOrNull { it.low == curLow }?.id ?: "balanced"
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_warning)
        .title(getString(R.string.thermal_threshold))
        .message(getString(R.string.thermal_threshold_msg))
    ThermalPresets.ALL.forEach { p ->
        list.addView(optionRow(
            this,
            getString(thermalPresetName(p.id)),
            getString(R.string.thermal_threshold_fmt, p.low, p.high),
            selected = p.id == curId
        ) {
            sheet.dismiss()
            prefs.edit()
                .putInt(Prefs.KEY_THERMAL_ADAPT_LOW, p.low)
                .putInt(Prefs.KEY_THERMAL_ADAPT_HIGH, p.high)
                .apply()
            UiToast.success(this, getString(R.string.thermal_threshold_set, p.low, p.high), Toast.LENGTH_SHORT).show()
            fetchSystemStatuses()
        })
    }
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun thermalPresetName(id: String): Int = when (id) {
    "conservative" -> R.string.thermal_preset_conservative
    "aggressive" -> R.string.thermal_preset_aggressive
    else -> R.string.thermal_preset_balanced
}

internal fun MainActivity.applyThermalModePref(prefs: SharedPreferences, mode: Int) {
    if (!hasShizukuPermission()) return
    // Adaptive governor lives in the dock service: it needs to be running.
    if (mode == 2 && !GameSpaceService.isRunning) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            UiToast.info(this, getString(R.string.toast_allow_overlay), Toast.LENGTH_LONG).show()
            return
        }
        try {
            startService(Intent(this, GameSpaceService::class.java))
        } catch (e: Exception) {
            UiToast.error(this, getString(R.string.svc_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        isGameSpaceEnabled = true
        prefs.edit().putBoolean(Prefs.KEY_GAMESPACE_ENABLED, true).apply()
    }
    if (mode == 0) {
        runAdbCommand("cmd thermalservice reset", getString(R.string.msg_thermal_restored)) {
            prefs.edit()
                .putBoolean(Prefs.KEY_THERMAL_DISABLED, false)
                .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)
                .apply()
            syncThermalService(0)
            findViewById<TextView>(R.id.tvStatusThermal).text = getString(R.string.status_default)
            fetchSystemStatuses()
        }
        return
    }
    if (mode == 1) {
        runAdbCommand("cmd thermalservice override-status 0", getString(R.string.msg_thermal_off)) {
            prefs.edit()
                .putBoolean(Prefs.KEY_THERMAL_DISABLED, true)
                .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)
                .apply()
            syncThermalService(1)
            findViewById<TextView>(R.id.tvStatusThermal).text = getString(R.string.status_throttling_disabled)
            fetchSystemStatuses()
        }
        return
    }
    // Adaptive: hand control to the dock governor (no immediate shell call needed).
    prefs.edit()
        .putBoolean(Prefs.KEY_THERMAL_DISABLED, false)
        .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, true)
        .apply()
    syncThermalService(2)
    val (low, high) = thermalThresholds(prefs)
    findViewById<TextView>(R.id.tvStatusThermal).text = getString(R.string.status_thermal_adaptive_fmt, low, high)
    UiToast.success(this, getString(R.string.thermal_adaptive_on, low, high), Toast.LENGTH_SHORT).show()
    fetchSystemStatuses()
}

internal fun MainActivity.resetThermal(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    runAdbCommand("cmd thermalservice reset", getString(R.string.msg_thermal_restored)) {
        prefs.edit()
            .putBoolean(Prefs.KEY_THERMAL_DISABLED, false)
            .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)
            .apply()
        syncThermalService(0)
        findViewById<TextView>(R.id.tvStatusThermal).text = getString(R.string.status_default)
        fetchSystemStatuses()
    }
}

internal fun MainActivity.thermalStatusText(prefs: SharedPreferences): String {
    if (prefs.getBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)) {
        val (low, high) = thermalThresholds(prefs)
        return getString(R.string.status_thermal_adaptive_fmt, low, high)
    }
    return if (prefs.getBoolean(Prefs.KEY_THERMAL_DISABLED, false)) getString(R.string.status_throttling_disabled)
    else getString(R.string.status_default)
}
