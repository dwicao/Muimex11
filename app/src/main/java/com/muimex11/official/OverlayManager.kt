package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Single choke point for all floating-overlay services.
 * Prevents 5 parallel foreground notifications and duplicate start/stop
 * races between MainActivity toggles and in-panel close buttons.
 */
object OverlayManager {
    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    fun start(ctx: Context, key: String, cls: Class<*>): Boolean {
        if (!canDraw(ctx)) return false
        return try {
            val intent = Intent(ctx.applicationContext, cls)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.applicationContext.startForegroundService(intent)
            } else {
                ctx.applicationContext.startService(intent)
            }
            ctx.applicationContext.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
                .edit().putBoolean(key, true).apply()
            true
        } catch (_: Exception) { false }
    }

    fun stop(ctx: Context, key: String, cls: Class<*>) {
        try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, cls)) }
        catch (_: Exception) {}
        try {
            ctx.applicationContext.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
                .edit().putBoolean(key, false).apply()
        } catch (_: Exception) {}
    }

    fun stopAll(ctx: Context) {
        val app = ctx.applicationContext
        listOf(
            GameSpaceService::class.java,
            RealtimeMonitorService::class.java,
            CrosshairService::class.java,
            AutoClickService::class.java,
            FloatingResolutionService::class.java,
        ).forEach { cls ->
            try { app.stopService(Intent(app, cls)) } catch (_: Exception) {}
        }
    }
}
