package com.muimex11.official

import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure parsers and standby-bucket helpers for Wakelock & Background Drain Inspector. */
object WakelockLogic {
    data class WakeEntry(
        val pkg: String,
        val activeLocks: Int,
        val alarmWakeups: Int,
        val bucket: String
    ) {
        val score: Int get() = activeLocks * 10 + alarmWakeups
    }

    /** Parses `dumpsys power` wake locks (e.g. `PARTIAL_WAKE_LOCK '...' ACQ=... uid=10234 pkg=com.foo`). */
    fun parsePowerWakelocks(raw: String): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        val pkgPattern = Regex("""(?:pkg=|ws=WorkSource\{[^}]*\s+)([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)""")
        for (line in raw.lineSequence()) {
            val l = line.trim()
            if (!l.contains("WAKE_LOCK") && !l.contains("Wake Lock")) continue
            val m = pkgPattern.find(l) ?: continue
            val pkg = m.groupValues[1]
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        return counts
    }

    /** Parses `dumpsys alarm` wakeup stats (`... u0a123:com.pkg ... wakeups=...`). */
    fun parseAlarmWakeups(raw: String): Map<String, Int> {
        val wakeups = LinkedHashMap<String, Int>()
        val linePat = Regex("""u\d+a\d+:([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+).*?(\d+)\s+wakeups?""")
        val altPat = Regex("""pkg=([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+).*?wakeups[=:]\s*(\d+)""")
        for (line in raw.lineSequence()) {
            val m = linePat.find(line) ?: altPat.find(line) ?: continue
            val pkg = m.groupValues[1]
            val n = m.groupValues[2].toIntOrNull() ?: 0
            if (n > 0) {
                wakeups[pkg] = maxOf(wakeups[pkg] ?: 0, n)
            }
        }
        return wakeups
    }

    fun parseStandbyBuckets(raw: String): Map<String, String> {
        val buckets = LinkedHashMap<String, String>()
        // `am get-standby-bucket` outputs lines like: com.example.app 10 (ACTIVE) or just integer
        val pat = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)\s+(\d+|[a-z_]+)""")
        for (line in raw.lineSequence()) {
            val m = pat.find(line.trim()) ?: continue
            val pkg = m.groupValues[1]
            val code = m.groupValues[2].lowercase()
            val label = when (code) {
                "5", "exempted" -> "exempted"
                "10", "active" -> "active"
                "20", "working_set" -> "working_set"
                "30", "frequent" -> "frequent"
                "40", "rare" -> "rare"
                "45", "restricted" -> "restricted"
                "50", "never" -> "never"
                else -> code
            }
            buckets[pkg] = label
        }
        return buckets
    }

    fun mergeTopEntries(
        powerLocks: Map<String, Int>,
        alarms: Map<String, Int>,
        buckets: Map<String, String>
    ): List<WakeEntry> {
        val keys = (powerLocks.keys + alarms.keys).toSet()
        return keys
            .filter { !it.startsWith("android") && !it.startsWith("com.android.systemui") }
            .map { pkg ->
                WakeEntry(
                    pkg = pkg,
                    activeLocks = powerLocks[pkg] ?: 0,
                    alarmWakeups = alarms[pkg] ?: 0,
                    bucket = buckets[pkg] ?: "unknown"
                )
            }
            .sortedWith(compareByDescending<WakeEntry> { it.score }.thenBy { it.pkg })
    }

    fun buildBucketCommand(pkg: String, bucket: String): String? {
        val safe = ShellSanitizer.cleanPkg(pkg) ?: return null
        val allowed = setOf("active", "working_set", "frequent", "rare", "restricted")
        if (bucket !in allowed) return null
        return "am set-standby-bucket $safe $bucket"
    }

    fun buildResetAllCommand(pkgs: Set<String>): String? {
        val cmds = pkgs.mapNotNull { buildBucketCommand(it, "working_set") }
        return if (cmds.isEmpty()) null else cmds.joinToString(" && ")
    }
}

internal fun MainActivity.wakelockStatusText(prefs: SharedPreferences): String {
    val set = prefs.getStringSet(Prefs.KEY_WAKELOCK_RESTRICTED, emptySet()) ?: emptySet()
    return if (set.isEmpty()) getString(R.string.status_ready)
    else getString(R.string.wake_status_fmt, set.size)
}

internal fun MainActivity.resetWakelockInspector(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val tracked = prefs.getStringSet(Prefs.KEY_WAKELOCK_RESTRICTED, emptySet()) ?: emptySet()
    val cmd = WakelockLogic.buildResetAllCommand(tracked)
    if (cmd == null) {
        prefs.edit().remove(Prefs.KEY_WAKELOCK_RESTRICTED).apply()
        findViewById<TextView>(R.id.tvStatusWakelock)?.text = getString(R.string.status_ready)
        UiToast.info(this, getString(R.string.wake_reset_done), Toast.LENGTH_SHORT).show()
        return
    }
    runAdbCommand(cmd, getString(R.string.wake_reset_done)) {
        prefs.edit().remove(Prefs.KEY_WAKELOCK_RESTRICTED).apply()
        fetchSystemStatuses()
    }
}

internal fun MainActivity.showWakelockInspectorSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.title_wakelock))
        .message(getString(R.string.wake_scanning))
    progress.show(indeterminate = true)

    lifecycleScope.launch(Dispatchers.IO) {
        val powerOut = ShizukuRunner.query("dumpsys power | grep -i -E 'WAKE_LOCK|Wake Lock' | head -n 80")
        val alarmOut = ShizukuRunner.query("dumpsys alarm | grep -i -E 'wakeup|Alarm Stats' | head -n 120")
        val bucketsOut = ShizukuRunner.query("am get-standby-bucket")
        val merged = WakelockLogic.mergeTopEntries(
            WakelockLogic.parsePowerWakelocks(powerOut),
            WakelockLogic.parseAlarmWakeups(alarmOut),
            WakelockLogic.parseStandbyBuckets(bucketsOut)
        )
        withContext(Dispatchers.Main) {
            progress.dismiss()
            presentWakelockResults(prefs, merged)
        }
    }
}

private fun MainActivity.presentWakelockResults(
    prefs: SharedPreferences,
    entries: List<WakelockLogic.WakeEntry>
) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (entries.isEmpty()) {
        body.addView(infoCard(this, getString(R.string.wake_none_found)))
    } else {
        body.addView(infoCard(this, getString(R.string.wake_found_summary, entries.size), Ui.accent(this)))
        entries.take(40).forEach { e ->
            val sevColor = when {
                e.score >= 30 -> Color.parseColor("#E53935")
                e.score >= 10 -> Color.parseColor("#E09B00")
                else -> Color.parseColor("#2E9E5B")
            }
            val sub = getString(R.string.wake_row_fmt, e.activeLocks, e.alarmWakeups, e.bucket)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                background = Ui.ripple(this@presentWakelockResults, Ui.tonalFill(this@presentWakelockResults), dp(14))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }

                val texts = LinearLayout(this@presentWakelockResults).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                }
                texts.addView(TextView(this@presentWakelockResults).apply {
                    text = e.pkg
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Ui.onSurface(this@presentWakelockResults))
                })
                texts.addView(TextView(this@presentWakelockResults).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(Ui.onSurfaceVariant(this@presentWakelockResults))
                    setPadding(0, dp(2), 0, 0)
                })
                addView(texts)

                addView(TextView(this@presentWakelockResults).apply {
                    text = e.bucket
                    textSize = 11.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(sevColor)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = Ui.rounded(ColorUtils.setAlphaComponent(sevColor, 0x1F), dp(8))
                })

                setOnClickListener {
                    showWakelockActionSheet(prefs, e)
                }
            }
            body.addView(row)
        }
    }

    Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.title_wakelock))
        .message(getString(R.string.wake_sheet_msg))
        .content(body)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showWakelockActionSheet(
    prefs: SharedPreferences,
    entry: WakelockLogic.WakeEntry
) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(entry.pkg)
        .message(getString(R.string.wake_row_fmt, entry.activeLocks, entry.alarmWakeups, entry.bucket))

    fun applyBucket(bucket: String, labelRes: Int, trackRestrict: Boolean) {
        sheet.dismiss()
        val cmd = WakelockLogic.buildBucketCommand(entry.pkg, bucket) ?: return
        runAdbCommand(cmd, getString(R.string.wake_bucket_set, getString(labelRes))) {
            val cur = (prefs.getStringSet(Prefs.KEY_WAKELOCK_RESTRICTED, emptySet()) ?: emptySet()).toMutableSet()
            if (trackRestrict) cur.add(entry.pkg) else cur.remove(entry.pkg)
            prefs.edit().putStringSet(Prefs.KEY_WAKELOCK_RESTRICTED, cur).apply()
            fetchSystemStatuses()
        }
    }

    list.addView(optionRow(this, getString(R.string.wake_set_restricted), getString(R.string.wake_set_restricted_desc)) {
        applyBucket("restricted", R.string.wake_set_restricted, true)
    })
    list.addView(optionRow(this, getString(R.string.wake_set_rare), getString(R.string.wake_set_rare_desc)) {
        applyBucket("rare", R.string.wake_set_rare, true)
    })
    list.addView(optionRow(this, getString(R.string.wake_set_working), getString(R.string.wake_set_working_desc)) {
        applyBucket("working_set", R.string.wake_set_working, false)
    })
    list.addView(optionRow(this, getString(R.string.wake_force_stop), getString(R.string.wake_force_stop_desc)) {
        sheet.dismiss()
        val safe = ShellSanitizer.cleanPkg(entry.pkg) ?: return@optionRow
        runAdbCommand("am force-stop $safe", getString(R.string.wake_force_stopped, safe)) {
            fetchSystemStatuses()
        }
    })

    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}
