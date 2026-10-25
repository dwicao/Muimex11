package com.muimex11.official

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build

object ServiceNotif {
    const val CHANNEL_ID = "muimex_overlay"
    const val GAME_SPACE_ID = 4201
    const val MONITOR_ID = 4202
    const val CROSSHAIR_ID = 4203
    const val AUTOCLICK_ID = 4204
    const val RESOLUTION_ID = 4205

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notif_overlay_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = ctx.getString(R.string.notif_overlay_desc) }
        )
    }

    fun overlayNotif(ctx: Context, titleRes: Int): Notification {
        ensureChannel(ctx)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
        }
        return builder
            .setContentTitle(ctx.getString(titleRes))
            .setContentText(ctx.getString(R.string.notif_overlay_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    fun startOverlayForeground(service: Service, id: Int, titleRes: Int) {
        try {
            ensureChannel(service)
            val n = overlayNotif(service, titleRes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(
                    id,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                service.startForeground(id, n)
            }
        } catch (_: Exception) {
            // Overlay can still work without FGS on some ROMs; don't crash.
        }
    }
}
