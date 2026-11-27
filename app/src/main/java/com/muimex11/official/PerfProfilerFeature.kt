package com.muimex11.official

import android.content.SharedPreferences
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Pure frame-time, thermal delta, CPU clock, and session persistence logic. */
object PerfProfilerLogic {
    data class PerfSession(
        val pkg: String,
        val label: String,
        val durationSec: Int,
        val avgFps: Int,
        val jankPct: Float,
        val p99Ms: Int,
        val tempStartC: Float,
        val tempEndC: Float,
        val avgBigMhz: Int,
        val ts: Long
    ) {
        val tempDeltaC: Float get() = ((tempEndC - tempStartC) * 10f).roundToInt() / 10f
    }

    data class GfxMetrics(
        val totalFrames: Long,
        val jankyFrames: Long,
        val p99Ms: Int
    )

    fun parseGfxMetrics(raw: String): GfxMetrics? {
        val total = Regex("""Total frames rendered:\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null
        val janky = Regex("""Janky frames:\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val p99 = Regex("""99th percentile:\s*(\d+)ms""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return GfxMetrics(total, janky, p99)
    }

    fun parseBatteryTempC(raw: String): Float? {
        val tenths = Regex("""temperature:\s*(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return tenths / 10f
    }

    fun computeFpsAndJank(
        startTotal: Long,
        endTotal: Long,
        startJanky: Long,
        endJanky: Long,
        elapsedMs: Long
    ): Pair<Int, Float> {
        if (elapsedMs <= 0) return 0 to 0f
        val dFrames = (endTotal - startTotal).coerceAtLeast(0L)
        val dJank = (endJanky - startJanky).coerceAtLeast(0L)
        val fps = ((dFrames * 1000.0) / elapsedMs).roundToInt().coerceIn(0, 240)
        val jankPct = if (dFrames <= 0L) 0f else ((dJank * 1000f) / dFrames).roundToInt() / 10f
        return fps to jankPct.coerceIn(0f, 100f)
    }

    fun serializeSessions(sessions: List<PerfSession>): String =
        sessions.takeLast(15).joinToString("\n") { s ->
            val safeLabel = s.label.replace("|", "/").replace("\n", " ")
            listOf(
                s.pkg,
                safeLabel,
                s.durationSec,
                s.avgFps,
                s.jankPct,
                s.p99Ms,
                s.tempStartC,
                s.tempEndC,
                s.avgBigMhz,
                s.ts
            ).joinToString("|")
        }

    fun deserializeSessions(raw: String?): List<PerfSession> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split("|")
            if (p.size < 10) return@mapNotNull null
            PerfSession(
                pkg = p[0],
                label = p[1],
                durationSec = p[2].toIntOrNull() ?: return@mapNotNull null,
                avgFps = p[3].toIntOrNull() ?: 0,
                jankPct = p[4].toFloatOrNull() ?: 0f,
                p99Ms = p[5].toIntOrNull() ?: 0,
                tempStartC = p[6].toFloatOrNull() ?: 0f,
                tempEndC = p[7].toFloatOrNull() ?: 0f,
                avgBigMhz = p[8].toIntOrNull() ?: 0,
                ts = p[9].toLongOrNull() ?: 0L
            )
        }.toList()
    }
}

internal fun MainActivity.perfProfilerStatusText(prefs: SharedPreferences): String {
    val last = PerfProfilerLogic.deserializeSessions(prefs.getString(Prefs.KEY_PERF_SESSIONS, null)).lastOrNull()
        ?: return getString(R.string.status_ready)
    return getString(R.string.profiler_status_fmt, last.label, last.avgFps, last.jankPct)
}

internal fun MainActivity.resetPerfProfiler(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_PERF_SESSIONS).apply()
    findViewById<TextView>(R.id.tvStatusPerfProfiler)?.text = getString(R.string.status_ready)
    UiToast.info(this, getString(R.string.profiler_reset_done), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showPerfProfilerSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_framerate)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen && packageManager.getLaunchIntentForPackage(it.pkg) != null }
        } catch (_: Exception) {
            emptyList()
        }
        val history = PerfProfilerLogic.deserializeSessions(prefs.getString(Prefs.KEY_PERF_SESSIONS, null))
            .associateBy { it.pkg }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showPerfProfilerSheet, getString(R.string.toast_no_games), Toast.LENGTH_LONG).show()
            } else {
                presentProfilerAppPicker(prefs, apps, history)
            }
        }
    }
}

private fun MainActivity.presentProfilerAppPicker(
    prefs: SharedPreferences,
    apps: List<AppEntry>,
    lastByPkg: Map<String, PerfProfilerLogic.PerfSession>
) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(getString(R.string.title_perf_profiler))
        .message(getString(R.string.profiler_sheet_msg))

    apps.take(80).forEach { app ->
        val prev = lastByPkg[app.pkg]
        val sub = if (prev == null) app.pkg
        else getString(R.string.profiler_last_fmt, app.pkg, prev.avgFps, prev.jankPct, prev.p99Ms)
        list.addView(optionRow(this, app.label, sub) {
            sheet.dismiss()
            showProfilerDurationSheet(prefs, app.pkg, app.label, prev)
        })
    }
    if (apps.size > 80) {
        list.addView(infoCard(this, getString(R.string.showing_n, 80, apps.size)))
    }

    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showProfilerDurationSheet(
    prefs: SharedPreferences,
    pkg: String,
    label: String,
    prev: PerfProfilerLogic.PerfSession?
) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (prev != null) {
        list.addView(
            infoCard(
                this,
                getString(R.string.profiler_prev_card, prev.avgFps, prev.jankPct, prev.p99Ms, prev.tempStartC, prev.tempEndC)
            )
        )
    }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(label)
        .message(getString(R.string.profiler_pick_dur, pkg))

    listOf(15, 30, 60).forEach { sec ->
        list.addView(optionRow(this, getString(R.string.profiler_dur_sec, sec), getString(R.string.profiler_dur_desc)) {
            sheet.dismiss()
            runPerfProfilerSession(prefs, pkg, label, sec, prev)
        })
    }
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.runPerfProfilerSession(
    prefs: SharedPreferences,
    pkg: String,
    label: String,
    durationSec: Int,
    prev: PerfProfilerLogic.PerfSession?
) {
    val safePkg = ShellSanitizer.cleanPkg(pkg) ?: return
    val comp = try {
        packageManager.getLaunchIntentForPackage(safePkg)?.component
    } catch (_: Exception) {
        null
    } ?: run {
        UiToast.error(this, getString(R.string.toast_launch_fail), Toast.LENGTH_SHORT).show()
        return
    }
    val safeComp = ShellSanitizer.cleanPkg(comp.packageName) ?: return

    var job: Job? = null
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_framerate)
        .title(label)
        .message(getString(R.string.profiler_launching))
        .cancelButton(getString(R.string.btn_cancel)) { job?.cancel() }
    progress.show(indeterminate = true)

    job = lifecycleScope.launch(Dispatchers.IO) {
        try {
            runAdbCommandWithResult("am start -W -n $safeComp/${comp.className}")
            delay(1_500)

            val startTemp = PerfProfilerLogic.parseBatteryTempC(ShizukuRunner.query("dumpsys battery")) ?: 0f
            val startGfx = PerfProfilerLogic.parseGfxMetrics(ShizukuRunner.query("dumpsys gfxinfo $safePkg"))
            val freqSamples = ArrayList<Int>(durationSec)
            val tStart = System.nanoTime()

            repeat(durationSec) { sec ->
                ensureActive()
                delay(1_000)
                val maxCpuKhz = ShizukuRunner.query("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null")
                    .lineSequence()
                    .mapNotNull { it.trim().toIntOrNull() }
                    .maxOrNull()
                if (maxCpuKhz != null && maxCpuKhz > 0) {
                    freqSamples.add(maxCpuKhz / 1000)
                }
                withContext(Dispatchers.Main) {
                    progress.update(((sec + 1) * 100) / durationSec, getString(R.string.profiler_sampling_fmt, sec + 1, durationSec))
                }
            }

            val elapsedMs = ((System.nanoTime() - tStart) / 1_000_000L).coerceAtLeast(1_000L)
            val endGfx = PerfProfilerLogic.parseGfxMetrics(ShizukuRunner.query("dumpsys gfxinfo $safePkg"))
            val endTemp = PerfProfilerLogic.parseBatteryTempC(ShizukuRunner.query("dumpsys battery")) ?: startTemp

            val (fps, jank) = if (startGfx != null && endGfx != null) {
                PerfProfilerLogic.computeFpsAndJank(
                    startGfx.totalFrames,
                    endGfx.totalFrames,
                    startGfx.jankyFrames,
                    endGfx.jankyFrames,
                    elapsedMs
                )
            } else {
                0 to 0f
            }

            val session = PerfProfilerLogic.PerfSession(
                pkg = safePkg,
                label = label,
                durationSec = durationSec,
                avgFps = fps,
                jankPct = jank,
                p99Ms = endGfx?.p99Ms ?: 0,
                tempStartC = startTemp,
                tempEndC = endTemp,
                avgBigMhz = if (freqSamples.isEmpty()) 0 else freqSamples.average().roundToInt(),
                ts = System.currentTimeMillis()
            )
            val existing = PerfProfilerLogic.deserializeSessions(prefs.getString(Prefs.KEY_PERF_SESSIONS, null))
            prefs.edit().putString(
                Prefs.KEY_PERF_SESSIONS,
                PerfProfilerLogic.serializeSessions(existing + session)
            ).apply()

            withContext(Dispatchers.Main) {
                progress.dismiss()
                findViewById<TextView>(R.id.tvStatusPerfProfiler)?.text = perfProfilerStatusText(prefs)
                fetchSystemStatuses()
                showProfilerResultDialog(prefs, prev, session)
            }
        } catch (_: CancellationException) {
            withContext(Dispatchers.Main) { progress.dismiss() }
        }
    }
}

private fun MainActivity.showProfilerResultDialog(
    prefs: SharedPreferences,
    prev: PerfProfilerLogic.PerfSession?,
    cur: PerfProfilerLogic.PerfSession
) {
    val summary = buildString {
        appendLine(
            getString(
                R.string.profiler_result_body,
                cur.avgFps,
                cur.jankPct,
                cur.p99Ms,
                cur.avgBigMhz,
                cur.tempStartC,
                cur.tempEndC,
                cur.tempDeltaC
            )
        )
        if (prev != null) {
            val dFps = cur.avgFps - prev.avgFps
            val dJank = ((cur.jankPct - prev.jankPct) * 10f).roundToInt() / 10f
            append(
                getString(
                    R.string.profiler_delta_body,
                    (if (dFps >= 0) "+" else "") + dFps,
                    (if (dJank >= 0f) "+" else "") + dJank
                )
            )
        }
    }.trimEnd()

    Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(cur.label)
        .message(summary)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .button("CSV", SheetStyle.PRIMARY) {
            exportPerfProfilerCsv(prefs)
        }
        .show()
}

private fun MainActivity.exportPerfProfilerCsv(prefs: SharedPreferences) {
    val sessions = PerfProfilerLogic.deserializeSessions(prefs.getString(Prefs.KEY_PERF_SESSIONS, null))
    if (sessions.isEmpty()) {
        UiToast.info(this, getString(R.string.toast_no_games), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val file = File(dir, "muimex-profiler.csv")
        file.bufferedWriter().use { w ->
            w.appendLine("package,label,duration_sec,avg_fps,jank_pct,p99_ms,avg_big_mhz,temp_start_c,temp_end_c,timestamp")
            sessions.forEach { s ->
                val cleanLabel = s.label.replace(",", " ").replace("\n", " ")
                w.appendLine("${s.pkg},$cleanLabel,${s.durationSec},${s.avgFps},${s.jankPct},${s.p99Ms},${s.avgBigMhz},${s.tempStartC},${s.tempEndC},${s.ts}")
            }
        }
        UiToast.success(this, file.absolutePath, Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        UiToast.error(this, e.message ?: "", Toast.LENGTH_LONG).show()
    }
}
