package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch-C memory & background tweaks (internet-research audit).
 *
 * - Max cached processes  `settings put global activity_manager_constants
 *   max_cached_processes=N` (AOSP-verified key; clamped 8–64 — too low kills
 *   multitasking). The Background-process-limit dev option backend.
 * - Per-app standby ..... `am set-standby-bucket <pkg> <bucket>` + read-back
 *   via `am get-standby-bucket` (PULSE-verified public `am` route — cleaner
 *   than the UsageStatsManager binder). Buckets: active → restricted.
 * - Dexopt booster ...... `cmd package bg-dexopt-job` one-shot (heavy: runs
 *   while charging; cancellable via `cancel-bg-dexopt-job`).
 * - Wakeup report ....... `dumpsys alarm` excerpt (what woke the phone).
 *
 * Applies are verified; failures surface the copyable error sheet.
 */
object MemTweaks {
    const val MAXC_NS = "global"
    const val MAXC_KEY = "activity_manager_constants"
    const val MAXC_MIN = 8
    const val MAXC_MAX = 64
    val MAXC_PRESETS = listOf(8, 16, 32, 48, 64)

    val BUCKETS = listOf("active", "working_set", "frequent", "rare", "restricted")

    const val PREF_STANDBY = "MemStandbyPkgs"

    private val maxcRe = Regex("max_cached_processes\\s*=\\s*(\\d+)")

    fun buildMaxCached(n: Int): String =
        "settings put global $MAXC_KEY max_cached_processes=${n.coerceIn(MAXC_MIN, MAXC_MAX)}"

    fun buildMaxCachedDelete(): String =
        "settings delete global $MAXC_KEY"

    fun parseMaxCached(raw: String?): Int? =
        raw?.let { maxcRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun buildBucket(pkg: String, bucket: String): String =
        "am set-standby-bucket $pkg $bucket"

    fun buildBucketGet(pkg: String): String =
        "am get-standby-bucket $pkg"

    fun buildBucketReset(pkg: String): String =
        "am set-standby-bucket $pkg active"

    fun buildDexopt(): String = "cmd package bg-dexopt-job"

    fun buildDexoptCancel(): String = "cmd package cancel-bg-dexopt-job"

    fun safePkg(pkg: String): Boolean =
        pkg.matches(Regex("^[A-Za-z0-9._]+$")) && pkg.length <= 256
}

internal fun MainActivity.memStandbyPkgs(): MutableSet<String> =
    (getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .getStringSet(MemTweaks.PREF_STANDBY, emptySet()) ?: emptySet()).toMutableSet()

private fun MainActivity.saveMemStandby(set: Set<String>) {
    getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .edit().putStringSet(MemTweaks.PREF_STANDBY, HashSet(set)).apply()
}

internal fun memResetCmdForAll(prefs: android.content.SharedPreferences): String {
    val pkgs = prefs.getStringSet(MemTweaks.PREF_STANDBY, emptySet()) ?: emptySet()
    val parts = mutableListOf(MemTweaks.buildMaxCachedDelete())
    parts += pkgs.sorted().map { MemTweaks.buildBucketReset(it) }
    return parts.joinToString(" && ")
}

internal fun clearMemPrefs(prefs: android.content.SharedPreferences) {
    prefs.edit().remove(MemTweaks.PREF_STANDBY).apply()
}

internal fun MainActivity.memStatusText(prefs: android.content.SharedPreferences): String {
    val maxc = MemTweaks.parseMaxCached(
        runCatching { runAdbCommandWithResult("settings get global ${MemTweaks.MAXC_KEY}") }.getOrNull()
    )
    val n = prefs.getStringSet(MemTweaks.PREF_STANDBY, emptySet())?.size ?: 0
    val total = (if (maxc != null) 1 else 0) + n
    return if (total == 0) getString(R.string.status_default)
    else getString(R.string.mem_status_fmt, total)
}

internal fun MainActivity.showMemTweaksSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val maxc = MemTweaks.parseMaxCached(runAdbCommandWithResult("settings get global ${MemTweaks.MAXC_KEY}"))
        val standbyN = memStandbyPkgs().size
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderMemSheet(maxc, standbyN)
        }
    }
}

private fun MainActivity.renderMemSheet(maxc: Int?, standbyN: Int) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.title_mem))
        .message(getString(R.string.desc_mem))

    list.addView(optionRow(
        this, getString(R.string.mem_maxcached),
        maxc?.let { getString(R.string.mem_maxcached_fmt, it) } ?: getString(R.string.comfort_not_set),
        selected = maxc != null
    ) {
        sheet.dismiss()
        showMaxCachedSheet(maxc)
    })

    list.addView(optionRow(
        this, getString(R.string.mem_standby),
        if (standbyN == 0) getString(R.string.mem_standby_desc)
        else getString(R.string.mem_standby_fmt, standbyN),
        selected = standbyN > 0
    ) {
        sheet.dismiss()
        showStandbyPicker()
    })

    list.addView(optionRow(
        this, getString(R.string.mem_dexopt), getString(R.string.mem_dexopt_desc)
    ) {
        sheet.dismiss()
        confirmDexopt()
    })

    list.addView(optionRow(
        this, getString(R.string.mem_alarm), getString(R.string.mem_alarm_desc)
    ) {
        sheet.dismiss()
        showAlarmReport()
    })

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showMaxCachedSheet(current: Int?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.mem_maxcached))
        .message(getString(R.string.mem_maxcached_msg))
    MemTweaks.MAXC_PRESETS.forEach { n ->
        list.addView(optionRow(
            this, getString(R.string.mem_processes_fmt, n),
            getString(
                when {
                    n <= 16 -> R.string.mem_maxcached_light
                    n <= 32 -> R.string.mem_maxcached_balanced
                    else -> R.string.mem_maxcached_high
                }
            ),
            selected = current == n
        ) {
            sheet.dismiss()
            applyMemSet(
                MemTweaks.buildMaxCached(n),
                "settings get global ${MemTweaks.MAXC_KEY}",
                "max_cached_processes=$n"
            ) { showMemTweaksSheet() }
        })
    }
    list.addView(optionRow(this, getString(R.string.comfort_reset_default), getString(R.string.mem_maxcached_default_desc)) {
        sheet.dismiss()
        applyMemSet(
            MemTweaks.buildMaxCachedDelete(),
            "settings get global ${MemTweaks.MAXC_KEY}", ""
        ) { showMemTweaksSheet() }
    })
    list.addView(infoCard(this, getString(R.string.mem_maxcached_note)))
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

/** Set + read-back verify (exact match, or containment for kv-list keys). */
private fun MainActivity.applyMemSet(
    setCmd: String, verifyCmd: String, expect: String, reopen: () -> Unit
) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(setCmd)
        val back = if (res.ok) runAdbCommandWithResult(verifyCmd).trim() else ""
        val ok = res.ok && (expect.isEmpty() && (back.isEmpty() || back == "null") || back.contains(expect))
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (ok) {
                UiToast.success(this@applyMemSet, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                val detail = if (res.ok) ShellResult(res.code, "$setCmd\nread-back: '$back' (expected: '$expect')")
                else res
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), setCmd, detail) { reopen() }
                fetchSystemStatuses()
            }
        }
    }
}

private fun MainActivity.showStandbyPicker() {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.txt_please_wait))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }
        } catch (e: Exception) {
            emptyList()
        }
        val restricted = memStandbyPkgs()
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showStandbyPicker, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
                return@withContext
            }
            val list = LinearLayout(this@showStandbyPicker).apply { orientation = LinearLayout.VERTICAL }
            apps.take(60).forEach { a ->
                val mark = if (a.pkg in restricted) " ✓" else ""
                list.addView(optionRow(this@showStandbyPicker, a.label, a.pkg + mark, selected = a.pkg in restricted) {
                    showStandbyEditor(a.pkg, a.label)
                })
            }
            if (apps.size > 60) {
                list.addView(infoCard(this@showStandbyPicker, getString(R.string.showing_n, 60, apps.size)))
            }
            Sheet(this@showStandbyPicker)
                .icon(R.drawable.ic_battery)
                .title(getString(R.string.mem_standby))
                .message(getString(R.string.mem_standby_msg))
                .content(list)
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

private fun MainActivity.showStandbyEditor(pkg: String, label: String) {
    if (!MemTweaks.safePkg(pkg) || !hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(label)
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val cur = runAdbCommandWithResult(MemTweaks.buildBucketGet(pkg)).trim().lowercase()
        withContext(Dispatchers.Main) {
            loading.dismiss()
            val list = LinearLayout(this@showStandbyEditor).apply { orientation = LinearLayout.VERTICAL }
            MemTweaks.BUCKETS.forEach { b ->
                list.addView(optionRow(this@showStandbyEditor, b, null, selected = cur.contains(b)) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val res = ShizukuRunner.exec(MemTweaks.buildBucket(pkg, b))
                        val back = runAdbCommandWithResult(MemTweaks.buildBucketGet(pkg)).trim().lowercase()
                        withContext(Dispatchers.Main) {
                            if (res.ok && back.contains(b)) {
                                val s = memStandbyPkgs()
                                if (b == "active") s.remove(pkg) else s.add(pkg)
                                saveMemStandby(s)
                                UiToast.success(this@showStandbyEditor, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                            } else {
                                showCommandErrorSheet(
                                    getString(R.string.dlg_cmd_failed_title), MemTweaks.buildBucket(pkg, b),
                                    if (res.ok) ShellResult(res.code, "read-back: '$back'") else res
                                ) { showStandbyEditor(pkg, label) }
                            }
                            fetchSystemStatuses()
                        }
                    }
                })
            }
            list.addView(infoCard(this@showStandbyEditor, getString(R.string.mem_standby_hint)))
            Sheet(this@showStandbyEditor)
                .icon(R.drawable.ic_battery)
                .title(label)
                .message(pkg)
                .content(list)
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

private fun MainActivity.confirmDexopt() {
    if (!hasShizukuPermission()) return
    Sheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.mem_dexopt))
        .message(getString(R.string.mem_dexopt_warn))
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.mem_dexopt_go), SheetStyle.PRIMARY) { runDexopt() }
        .show()
}

private fun MainActivity.runDexopt() {
    if (!hasShizukuPermission()) return
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.mem_dexopt))
        .message(getString(R.string.mem_dexopt_running))
        .cancelButton(getString(R.string.btn_cancel)) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { ShizukuRunner.exec(MemTweaks.buildDexoptCancel(), 30_000L) }
            }
        }
        .show()
    progress.setIndeterminate(getString(R.string.mem_dexopt_running))
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(MemTweaks.buildDexopt(), 30 * 60_000L)
        withContext(Dispatchers.Main) {
            progress.dismiss()
            if (res.ok) {
                UiToast.success(this@runDexopt, getString(R.string.mem_dexopt_done), Toast.LENGTH_LONG).show()
            } else {
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), MemTweaks.buildDexopt(), res) {}
            }
            fetchSystemStatuses()
        }
    }
}

private fun MainActivity.showAlarmReport() {
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_battery)
        .title(getString(R.string.mem_alarm))
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val out = ShizukuRunner.exec("dumpsys alarm", 60_000L).out
            .lines().takeLast(160).joinToString("\n").take(12_000)
        withContext(Dispatchers.Main) {
            loading.dismiss()
            val tv = TextView(this@showAlarmReport).apply {
                text = out.ifBlank { getString(R.string.mem_alarm_empty) }
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            val list = LinearLayout(this@showAlarmReport).apply { orientation = LinearLayout.VERTICAL }
            list.addView(ScrollView(this@showAlarmReport).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
                )
                addView(tv)
            })
            Sheet(this@showAlarmReport)
                .icon(R.drawable.ic_battery)
                .title(getString(R.string.mem_alarm))
                .message(getString(R.string.mem_alarm_hint))
                .content(list)
                .button(getString(R.string.shell_btn_copy), SheetStyle.TONAL, dismiss = false) {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("muimex11-alarms", tv.text))
                        UiToast.success(this@showAlarmReport, getString(R.string.shell_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

internal fun MainActivity.resetMemTweaks() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusMem)?.text = getString(R.string.status_checking)
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val cmd = memResetCmdForAll(prefs)
    runAdbCommand(cmd, getString(R.string.msg_mem_reset)) {
        clearMemPrefs(prefs)
        fetchSystemStatuses()
    }
}
