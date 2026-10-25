package com.muimex11.official

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class BenchRun(
    val pkg: String,
    val label: String,
    val launchMs: Long,
    val fps: Int?,
    val ts: Long
)

internal fun readBenchRuns(prefs: SharedPreferences): List<BenchRun> {
    val raw = prefs.getString(Prefs.KEY_BENCH_RUNS, null) ?: return emptyList()
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            BenchRun(
                o.optString("pkg"), o.optString("label"),
                o.optLong("launchMs"), o.opt("fps")?.toString()?.toIntOrNull(),
                o.optLong("ts")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveBenchRun(prefs: SharedPreferences, run: BenchRun) {
    try {
        val raw = prefs.getString(Prefs.KEY_BENCH_RUNS, null)
        val a = if (raw != null) JSONArray(raw) else JSONArray()
        a.put(JSONObject().apply {
            put("pkg", run.pkg)
            put("label", run.label)
            put("launchMs", run.launchMs)
            if (run.fps != null) put("fps", run.fps)
            put("ts", run.ts)
        })
        while (a.length() > 20) a.remove(0)
        prefs.edit().putString(Prefs.KEY_BENCH_RUNS, a.toString()).apply()
    } catch (_: Exception) {}
}

internal fun MainActivity.showBenchmarkSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_framerate)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen && packageManager.getLaunchIntentForPackage(it.pkg) != null }
        } catch (e: Exception) {
            emptyList()
        }
        val runs = readBenchRuns(prefs).associateBy { it.pkg }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showBenchmarkSheet, getString(R.string.toast_no_games), Toast.LENGTH_LONG).show()
            } else {
                presentBenchmarkPicker(prefs, apps, runs)
            }
        }
    }
}

private fun MainActivity.presentBenchmarkPicker(
    prefs: SharedPreferences, apps: List<AppEntry>, runs: Map<String, BenchRun>
) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    apps.take(80).forEach { a ->
        val r = runs[a.pkg]
        val sub = if (r == null) a.pkg
        else getString(R.string.bench_last_fmt, a.pkg, r.launchMs, r.fps?.toString() ?: "--")
        list.addView(optionRow(this, a.label, sub) {
            showBenchmarkRunSheet(prefs, a.pkg, a.label)
        })
    }
    if (apps.size > 80) {
        list.addView(infoCard(this, getString(R.string.showing_n, 80, apps.size)))
    }
    list.addView(infoCard(this, getString(R.string.bench_hint)))
    Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(getString(R.string.bench_title))
        .message(getString(R.string.bench_message))
        .content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showBenchmarkRunSheet(prefs: SharedPreferences, pkg: String, label: String) {
    val prev = readBenchRuns(prefs).lastOrNull { it.pkg == pkg }
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (prev != null) {
        body.addView(infoCard(this, getString(R.string.bench_prev_fmt, prev.launchMs, prev.fps?.toString() ?: "--")))
    }
    body.addView(infoCard(this, getString(R.string.bench_run_desc)))
    Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(label)
        .message(pkg)
        .content(body)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.bench_run), SheetStyle.PRIMARY) {
            runBenchmark(prefs, pkg, label, prev)
        }
        .show()
}

private fun MainActivity.runBenchmark(
    prefs: SharedPreferences, pkg: String, label: String, prev: BenchRun?
) {
    if (!hasShizukuPermission()) return
    val comp = try {
        packageManager.getLaunchIntentForPackage(pkg)?.component
    } catch (_: Exception) {
        null
    }
    if (comp == null) {
        UiToast.error(this, getString(R.string.toast_launch_fail), Toast.LENGTH_SHORT).show()
        return
    }
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_framerate)
        .title(label)
        .message(getString(R.string.bench_phase_launch))
    progress.show(indeterminate = true)

    lifecycleScope.launch(Dispatchers.IO) {
        // Cold-start: force-stop, then `am start -W` 3x and average TotalTime.
        val samples = mutableListOf<Long>()
        repeat(3) { i ->
            runShell("am force-stop $pkg")
            delay(800)
            withContext(Dispatchers.Main) {
                progress.setIndeterminate(getString(R.string.bench_phase_launch_n, i + 1, 3))
            }
            val out = runAdbCommandWithResult("am start -W -n ${comp.packageName}/${comp.className}")
            BenchmarkCalc.parseAmStartW(out)?.let { samples += it }
            delay(1200)
        }
        val launchMs = BenchmarkCalc.avg(samples)

        // FPS: sample gfxinfo counters over 15s while the user plays.
        var fps: Int? = null
        withContext(Dispatchers.Main) {
            progress.setIndeterminate(getString(R.string.bench_phase_fps))
        }
        try {
            val t0 = gfxFrames(pkg)
            if (t0 != null) {
                repeat(15) { s ->
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        progress.update((s + 1) * 100 / 15, getString(R.string.bench_fps_count, 15 - s - 1))
                    }
                }
                val t1 = gfxFrames(pkg)
                if (t1 != null) fps = BenchmarkCalc.fps(t1, t0, 15_000)
            }
        } catch (_: Exception) {}

        val run = BenchRun(pkg, label, launchMs, fps, System.currentTimeMillis())
        saveBenchRun(prefs, run)
        withContext(Dispatchers.Main) {
            progress.dismiss()
            refreshBenchmarkStatus(prefs, run)
            fetchSystemStatuses()
            showBenchmarkResult(prev, run)
        }
    }
}

private fun MainActivity.gfxFrames(pkg: String): Long? {
    val out = runAdbCommandWithResult("dumpsys gfxinfo $pkg")
    return Regex("Total frames rendered:\\s*(\\d+)").find(out)
        ?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun MainActivity.showBenchmarkResult(prev: BenchRun?, run: BenchRun) {
    val fpsStr = run.fps?.toString() ?: "--"
    var msg = getString(R.string.bench_result_fmt, run.launchMs, fpsStr)
    if (prev != null) {
        val dLaunch = prev.launchMs - run.launchMs
        val dFps = (run.fps ?: 0) - (prev.fps ?: 0)
        msg += "\n" + getString(
            R.string.bench_delta_fmt,
            (if (dLaunch >= 0) "-" else "+") + kotlin.math.abs(dLaunch).toString(),
            (if (dFps >= 0) "+" else "") + dFps.toString()
        )
    }
    Sheet(this)
        .icon(R.drawable.ic_framerate)
        .title(getString(R.string.bench_done))
        .message(msg)
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}

internal fun MainActivity.refreshBenchmarkStatus(prefs: SharedPreferences, last: BenchRun? = null) {
    val tv = findViewById<TextView?>(R.id.tvStatusBenchmark) ?: return
    val r = last ?: readBenchRuns(prefs).lastOrNull()
    tv.text = if (r == null) getString(R.string.status_ready)
    else getString(R.string.status_bench_fmt, r.label, r.launchMs, r.fps?.toString() ?: "--")
}
