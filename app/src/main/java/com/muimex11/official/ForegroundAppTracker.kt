package com.muimex11.official

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * Foreground-app resolution with battery-friendly priority:
 * 1. UsageStatsManager (no shell, 1 binder call) when permission granted.
 * 2. Single `dumpsys activity activities` via Shizuku as fallback.
 *
 * Replaces the old triple-dumpsys per second (activities + window + gfxinfo).
 */
object ForegroundAppTracker {
    fun currentPkg(ctx: Context, selfPkg: String, dumpsysDump: String? = null): String? {
        usageStatsPkg(ctx, selfPkg)?.let { return it }
        val dump = dumpsysDump ?: return null
        return FgPkgParser.parse(dump, selfPkg)
    }

    fun usageStatsPkg(ctx: Context, selfPkg: String): String? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return null
            val usm = ctx.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            // Short window keeps it cheap; queryAndAggregate not needed.
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now
            ) ?: return null
            stats.filter { (it.packageName != selfPkg) && (it.lastTimeUsed > 0) }
                .filter { !it.packageName.contains("launcher", ignoreCase = true) }
                .filter { !it.packageName.endsWith(".home") }
                .maxByOrNull { it.lastTimeUsed }
                ?.packageName
                ?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null // no PACKAGE_USAGE_STATS grant — caller falls back to dumpsys
        } catch (_: Exception) {
            null
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
            val usm = ctx.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return false
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now
            )
            stats != null // null = no access; empty list = access but no data yet
        } catch (_: SecurityException) { false }
        catch (_: Exception) { false }
    }
}
