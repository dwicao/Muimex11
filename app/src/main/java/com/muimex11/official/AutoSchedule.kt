package com.muimex11.official

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import rikka.shizuku.Shizuku

/** Screen on/off scheduler: applies the chosen battery-saver mode while the
 *  screen is off, restores on wake. Also pauses the schedule when a game with
 *  an auto-boost profile opens (called from GameSpaceService). */
object BatterySchedule {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(Prefs.KEY_SCHEDULE_SCREEN_OFF, false)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(Prefs.KEY_SCHEDULE_SCREEN_OFF, v).apply()
    }

    fun mode(ctx: Context): String =
        prefs(ctx).getString(Prefs.KEY_SCHEDULE_MODE, BatterySaver.MODE_SAVER)
            ?: BatterySaver.MODE_SAVER

    fun setMode(ctx: Context, m: String) {
        prefs(ctx).edit().putString(Prefs.KEY_SCHEDULE_MODE, m).apply()
    }

    fun isActive(ctx: Context): Boolean =
        prefs(ctx).getBoolean(Prefs.KEY_SCHEDULE_ACTIVE, false)

    private fun hasShizuku(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun snapshot(ctx: Context, q: (String) -> String): Map<String, String> {
        val p = prefs(ctx)
        p.getString(Prefs.KEY_BATTERY_PREV, null)?.let {
            return try {
                val o = org.json.JSONObject(it)
                BatterySaver.SNAPSHOT_KEYS.associateWith { k -> o.optString(k, "") }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val prev = BatterySaver.SNAPSHOT_KEYS.associateWith { k -> q("settings get global $k").trim() }
        val o = org.json.JSONObject()
        prev.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString(Prefs.KEY_BATTERY_PREV, o.toString()).apply()
        return prev
    }

    /** Screen turned off: save mode + snapshot, apply. Returns true if applied. */
    fun onScreenOff(ctx: Context): Boolean {
        return try {
            if (!isEnabled(ctx) || !hasShizuku()) return false
            val p = prefs(ctx)
            val cur = p.getString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF).orEmpty()
            if (cur != BatterySaver.MODE_OFF && cur.isNotEmpty()) return false
            val m = mode(ctx)
            val q: (String) -> String = { c -> ShizukuRunner.query(c) }
            snapshot(ctx, q)
            val extra = if (m == BatterySaver.MODE_EXTREME) {
                "cmd power set-fixed-performance-mode-enabled false; cmd thermalservice reset; "
            } else ""
            val ok = ShizukuRunner.execOk(extra + BatterySaver.buildApplyCmd(m))
            if (ok) {
                p.edit()
                    .putString(Prefs.KEY_BATTERY_MODE, m)
                    .putBoolean(Prefs.KEY_SCHEDULE_ACTIVE, true)
                    .apply()
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Screen turned on (or game opened): restore snapshot if WE applied it. */
    fun onScreenOn(ctx: Context): Boolean {
        return try {
            val p = prefs(ctx)
            if (!p.getBoolean(Prefs.KEY_SCHEDULE_ACTIVE, false)) return false
            if (!hasShizuku()) return false
            val raw = p.getString(Prefs.KEY_BATTERY_PREV, null)
            val prev: Map<String, String> = if (raw == null) emptyMap() else try {
                val o = org.json.JSONObject(raw)
                BatterySaver.SNAPSHOT_KEYS.associateWith { k -> o.optString(k, "") }
            } catch (_: Exception) {
                emptyMap()
            }
            ShizukuRunner.execOk(BatterySaver.buildResetCmd(prev))
            p.edit()
                .putString(Prefs.KEY_BATTERY_MODE, BatterySaver.MODE_OFF)
                .remove(Prefs.KEY_BATTERY_PREV)
                .putBoolean(Prefs.KEY_SCHEDULE_ACTIVE, false)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pauseForGame(ctx: Context): Boolean = onScreenOn(ctx)
}

class ScreenScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> BatterySchedule.onScreenOff(context.applicationContext)
                    Intent.ACTION_SCREEN_ON -> BatterySchedule.onScreenOn(context.applicationContext)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    pending.finish()
                } catch (_: Exception) {}
            }
        }.start()
    }
}

/** Register/unregister the dynamic screen receiver. Call from MainActivity. */
internal fun MainActivity.syncScreenSchedule() {
    try {
        val want = BatterySchedule.isEnabled(this)
        val cur = screenScheduleReceiver
        if (want && cur == null) {
            val r = ScreenScheduleReceiver()
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(r, f)
            screenScheduleReceiver = r
        } else if (!want && cur != null) {
            try {
                unregisterReceiver(cur)
            } catch (_: Exception) {}
            screenScheduleReceiver = null
        }
    } catch (_: Exception) {}
}

internal fun MainActivity.stopScreenSchedule() {
    try {
        screenScheduleReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
    screenScheduleReceiver = null
}
