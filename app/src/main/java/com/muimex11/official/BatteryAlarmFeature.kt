package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Charge alarm rows (live in the Battery Saver sheet): notify once when the
 * battery reaches 80–100% while charging. The service reads the threshold
 * from prefs on every battery event, so changes apply without restart.
 */
internal fun batteryAlarmThreshold(prefs: android.content.SharedPreferences): Int =
    prefs.getInt(Prefs.KEY_BATTERY_ALARM, 0)

internal fun MainActivity.showBatteryAlarmSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val cur = batteryAlarmThreshold(prefs)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.balarm_title))
        .message(getString(R.string.balarm_msg))
    listOf(0 to getString(R.string.balarm_off), 80 to "80%", 85 to "85%", 90 to "90%", 95 to "95%", 100 to "100%")
        .forEach { (v, name) ->
            list.addView(optionRow(this, name, null, selected = cur == v) {
                sheet.dismiss()
                applyBatteryAlarm(prefs, v)
            })
        }
    list.addView(infoCard(this, getString(R.string.balarm_note)))
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.applyBatteryAlarm(prefs: android.content.SharedPreferences, threshold: Int) {
    if (threshold == 0) {
        try { stopService(Intent(this, BatteryAlarmService::class.java)) } catch (_: Exception) {}
        prefs.edit().putInt(Prefs.KEY_BATTERY_ALARM, 0).apply()
        UiToast.info(this, getString(R.string.balarm_off_done), Toast.LENGTH_SHORT).show()
        fetchSystemStatuses()
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        onNotifPermResult = { granted ->
            if (granted) {
                enableBatteryAlarm(prefs, threshold)
            } else {
                UiToast.error(this, getString(R.string.balarm_need_perm), Toast.LENGTH_LONG).show()
            }
        }
        try {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            onNotifPermResult = null
            UiToast.error(this, getString(R.string.balarm_need_perm), Toast.LENGTH_LONG).show()
        }
        return
    }
    enableBatteryAlarm(prefs, threshold)
}

private fun MainActivity.enableBatteryAlarm(prefs: android.content.SharedPreferences, threshold: Int) {
    prefs.edit().putInt(Prefs.KEY_BATTERY_ALARM, threshold).apply()
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, BatteryAlarmService::class.java))
        } else {
            startService(Intent(this, BatteryAlarmService::class.java))
        }
    } catch (e: Exception) {
        UiToast.error(this, getString(R.string.svc_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        return
    }
    UiToast.success(this, getString(R.string.balarm_set, threshold), Toast.LENGTH_SHORT).show()
    fetchSystemStatuses()
}

internal fun isBatteryAlarmOn(ctx: Context): Boolean =
    ctx.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .getInt(Prefs.KEY_BATTERY_ALARM, 0) > 0

/** Battery card status: saver mode plus the alarm threshold when armed. */
internal fun batteryStatusText(ctx: Context): String {
    val prefs = ctx.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val base = ctx.getString(BatterySaver.labelRes(prefsBatteryMode(prefs)))
    val alarm = prefs.getInt(Prefs.KEY_BATTERY_ALARM, 0)
    return if (alarm > 0) "$base · ${ctx.getString(R.string.status_battery_alarm_fmt, alarm)}"
    else base
}
