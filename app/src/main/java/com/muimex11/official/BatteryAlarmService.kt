package com.muimex11.official

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder

/**
 * Charge alarm: fires a loud notification the moment the battery reaches the
 * chosen threshold while charging. For gamers who play plugged in and don't
 * want to sit at 100%.
 */
class BatteryAlarmService : Service() {

    private var fired = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            val threshold = prefs.getInt(Prefs.KEY_BATTERY_ALARM, 0)
            if (threshold <= 0) {
                stopSelf()
                return
            }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100) / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (!charging) {
                fired = false
                return
            }
            if (pct >= threshold && !fired) {
                fired = true
                fireAlarm(context, pct)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceNotif.startOverlayForeground(this, ServiceNotif.BATTERY_ID, R.string.battery_alarm_service)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun fireAlarm(context: Context, pct: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ALARM,
                        context.getString(R.string.notif_battery_alarm),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = context.getString(R.string.notif_battery_alarm_desc)
                        enableVibration(true)
                    }
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ALARM)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        nm.notify(
            ALARM_ID,
            builder
                .setContentTitle(context.getString(R.string.alarm_battery_full, pct))
                .setContentText(context.getString(R.string.alarm_battery_full_desc))
                .setSmallIcon(R.drawable.ic_notification)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build()
        )
    }

    companion object {
        const val CHANNEL_ALARM = "muimex_battery_alarm"
        const val ALARM_ID = 4301
    }
}
