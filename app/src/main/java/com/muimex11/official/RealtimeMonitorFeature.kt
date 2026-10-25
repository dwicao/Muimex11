package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

internal fun MainActivity.toggleRealtimeMonitor() {
    if (!Settings.canDrawOverlays(this)) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
        UiToast.info(this, getString(R.string.toast_allow_overlay), Toast.LENGTH_LONG).show()
        return
    }

    val intent = Intent(this, RealtimeMonitorService::class.java)
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)

    if (isMonitorEnabled) {
        stopService(intent)
        isMonitorEnabled = false
        prefs.edit().putBoolean(Prefs.KEY_MONITOR_ENABLED, false).apply()
        findViewById<TextView>(R.id.tvStatusMonitor).text = getString(R.string.status_disabled)
        UiToast.success(this, getString(R.string.toast_monitor_off), Toast.LENGTH_SHORT).show()
    } else {
        startService(intent)
        isMonitorEnabled = true
        prefs.edit().putBoolean(Prefs.KEY_MONITOR_ENABLED, true).apply()
        findViewById<TextView>(R.id.tvStatusMonitor).text = getString(R.string.status_enabled)
        UiToast.success(this, getString(R.string.toast_monitor_on), Toast.LENGTH_SHORT).show()
    }
}
