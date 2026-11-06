package com.muimex11.official

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch-C per-game graphics interventions over `cmd game`
 * (AOSP GameManagerShellCommand-verified).
 *
 * - Modes: standard / performance / battery / custom per package.
 *   Battery mode only works if the game declares support — otherwise the
 *   shell prints "not supported" and the copyable error sheet shows it.
 * - Custom: `--downscale <0.3–0.9|disable>` ratio cap + `--fps <int|disable>`
 *   override, then `mode custom`. A genuine per-game downscale/FPS primitive
 *   beyond the existing Game Profiles (mode/resolution/orientation).
 * - `list-configs` gives the live config for the editor subtitle;
 *   `reset` is the clean undo (also drops the package from the tuned set).
 *
 * Tuned packages persist in prefs for the status line and reset-all.
 */
object GameTuning {
    const val PREF_TUNED = "GameTunedPkgs"

    val MODES = listOf("standard", "performance", "battery", "custom")
    val DOWNSCALES = listOf("disable", "0.9", "0.7", "0.5", "0.3")
    val FPS_PRESETS = listOf("disable", "30", "60", "90", "120")

    fun buildMode(pkg: String, mode: String): String =
        "cmd game mode $mode $pkg"

    fun buildCustom(pkg: String, downscale: String?, fps: String?): String {
        val parts = mutableListOf("cmd game set")
        if (!downscale.isNullOrEmpty() && downscale != "disable") parts += "--downscale $downscale"
        if (!fps.isNullOrEmpty() && fps != "disable") parts += "--fps $fps"
        parts += pkg
        return parts.joinToString(" ")
    }

    fun buildList(pkg: String): String = "cmd game list-configs $pkg"

    fun buildReset(pkg: String): String = "cmd game reset $pkg"

    fun safePkg(pkg: String): Boolean =
        pkg.matches(Regex("^[A-Za-z0-9._]+$")) && pkg.length <= 256
}

internal fun MainActivity.gameTunedPkgs(): MutableSet<String> =
    (getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .getStringSet(GameTuning.PREF_TUNED, emptySet()) ?: emptySet()).toMutableSet()

private fun MainActivity.saveGameTuned(set: Set<String>) {
    getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .edit().putStringSet(GameTuning.PREF_TUNED, HashSet(set)).apply()
}

internal fun gameTuningResetCmdForAll(prefs: android.content.SharedPreferences): String? {
    val pkgs = prefs.getStringSet(GameTuning.PREF_TUNED, emptySet()) ?: emptySet()
    if (pkgs.isEmpty()) return null
    return pkgs.sorted().joinToString(" && ") { GameTuning.buildReset(it) }
}

internal fun clearGameTuningPrefs(prefs: android.content.SharedPreferences) {
    prefs.edit().remove(GameTuning.PREF_TUNED).apply()
}

internal fun MainActivity.gameTuningStatusText(prefs: android.content.SharedPreferences): String {
    val n = prefs.getStringSet(GameTuning.PREF_TUNED, emptySet())?.size ?: 0
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.gametuning_status_fmt, n)
}

internal fun MainActivity.showGameTuningSheet() {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.txt_please_wait))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }
        } catch (e: Exception) {
            emptyList()
        }
        val tuned = gameTunedPkgs()
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showGameTuningSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
                return@withContext
            }
            val list = LinearLayout(this@showGameTuningSheet).apply { orientation = LinearLayout.VERTICAL }
            apps.take(60).forEach { a ->
                val mark = if (a.pkg in tuned) " ✓" else ""
                list.addView(optionRow(this@showGameTuningSheet, a.label, a.pkg + mark, selected = a.pkg in tuned) {
                    showGameTuningEditor(a.pkg, a.label)
                })
            }
            if (apps.size > 60) {
                list.addView(infoCard(this@showGameTuningSheet, getString(R.string.showing_n, 60, apps.size)))
            }
            Sheet(this@showGameTuningSheet)
                .icon(R.drawable.ic_gamepad)
                .title(getString(R.string.title_gametuning))
                .message(getString(R.string.desc_gametuning))
                .content(list)
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

private fun MainActivity.showGameTuningEditor(pkg: String, label: String) {
    if (!GameTuning.safePkg(pkg) || !hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(label)
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val cfg = runAdbCommandWithResult(GameTuning.buildList(pkg)).trim().take(600)
        withContext(Dispatchers.Main) {
            loading.dismiss()
            renderGameTuningEditor(pkg, label, cfg)
        }
    }
}

private fun MainActivity.renderGameTuningEditor(pkg: String, label: String, cfg: String) {
    val tuned = gameTunedPkgs()
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (cfg.isNotEmpty() && !cfg.equals("null", ignoreCase = true)) {
        list.addView(infoCard(this, cfg))
    } else {
        list.addView(infoCard(this, getString(R.string.gametuning_no_cfg)))
    }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(label)
        .message(pkg)
    GameTuning.MODES.forEach { mode ->
        // No row is pre-selected: the live mode is shown in the config card above.
        list.addView(optionRow(
            this,
            getString(
                when (mode) {
                    "performance" -> R.string.gametuning_perf
                    "battery" -> R.string.gametuning_battery
                    "custom" -> R.string.gametuning_custom
                    else -> R.string.gametuning_standard
                }
            ),
            getString(
                when (mode) {
                    "performance" -> R.string.gametuning_perf_desc
                    "battery" -> R.string.gametuning_battery_desc
                    "custom" -> R.string.gametuning_custom_desc
                    else -> R.string.gametuning_standard_desc
                }
            )
        ) {
            sheet.dismiss()
            if (mode == "custom") showGameCustomSheet(pkg, label)
            else applyGameMode(pkg, mode)
        })
    }
    if (pkg in tuned) {
        list.addView(optionRow(this, getString(R.string.gametuning_reset_pkg), getString(R.string.gametuning_reset_pkg_desc)) {
            sheet.dismiss()
            resetGameTuningPkg(pkg)
        })
    }
    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showGameCustomSheet(pkg: String, label: String) {
    var downscale = "disable"
    var fps = "disable"
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(label)
        .message(getString(R.string.gametuning_custom_msg))
    fun refresh() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = getString(R.string.gametuning_downscale_title)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.onSurface(this@showGameCustomSheet))
        })
        list.addView(presetRow(this, GameTuning.DOWNSCALES) { i ->
            downscale = GameTuning.DOWNSCALES[i]
            refresh()
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.gametuning_fps_title, fps)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.onSurface(this@showGameCustomSheet))
        })
        list.addView(presetRow(this, GameTuning.FPS_PRESETS) { i ->
            fps = GameTuning.FPS_PRESETS[i]
            refresh()
        })
        list.addView(infoCard(this, getString(R.string.gametuning_custom_note, downscale, fps)))
    }
    refresh()
    sheet.content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.gametuning_apply), SheetStyle.PRIMARY, dismiss = false) {
            sheet.dismiss()
            applyGameCustom(pkg, downscale, fps)
        }
        .show()
}

private fun MainActivity.applyGameMode(pkg: String, mode: String) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val setRes = ShizukuRunner.exec(GameTuning.buildMode(pkg, mode))
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (setRes.ok) {
                val s = gameTunedPkgs()
                if (mode == "standard") s.remove(pkg) else s.add(pkg)
                saveGameTuned(s)
                UiToast.success(this@applyGameMode, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
            } else {
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), GameTuning.buildMode(pkg, mode), setRes) {
                    showGameTuningEditor(pkg, pkg)
                }
            }
            fetchSystemStatuses()
        }
    }
}

private fun MainActivity.applyGameCustom(pkg: String, downscale: String, fps: String) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val setCmd = GameTuning.buildCustom(pkg, downscale, fps)
        val setRes = ShizukuRunner.exec(setCmd)
        val modeRes = if (setRes.ok) ShizukuRunner.exec(GameTuning.buildMode(pkg, "custom")) else setRes
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (setRes.ok && modeRes.ok) {
                val s = gameTunedPkgs()
                s.add(pkg)
                saveGameTuned(s)
                UiToast.success(this@applyGameCustom, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
            } else {
                val bad = if (!setRes.ok) setRes else modeRes
                val badCmd = if (!setRes.ok) setCmd else GameTuning.buildMode(pkg, "custom")
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), badCmd, bad) {
                    showGameTuningEditor(pkg, pkg)
                }
            }
            fetchSystemStatuses()
        }
    }
}

private fun MainActivity.resetGameTuningPkg(pkg: String) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(GameTuning.buildReset(pkg))
        withContext(Dispatchers.Main) {
            if (res.ok) {
                val s = gameTunedPkgs()
                s.remove(pkg)
                saveGameTuned(s)
                UiToast.success(this@resetGameTuningPkg, getString(R.string.msg_gametuning_reset), Toast.LENGTH_SHORT).show()
            } else {
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), GameTuning.buildReset(pkg), res) {}
            }
            fetchSystemStatuses()
        }
    }
}

internal fun MainActivity.resetGameTuning() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusGameTuning)?.text = getString(R.string.status_checking)
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val cmd = gameTuningResetCmdForAll(prefs) ?: run {
        clearGameTuningPrefs(prefs)
        fetchSystemStatuses()
        return
    }
    runAdbCommand(cmd, getString(R.string.msg_gametuning_reset)) {
        clearGameTuningPrefs(prefs)
        fetchSystemStatuses()
    }
}
