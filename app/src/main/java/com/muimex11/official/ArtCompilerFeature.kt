package com.muimex11.official

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure ART / dex2oat command builders and package tracking logic. */
object ArtCompilerLogic {
    enum class CompileProfile(val flag: String) {
        SPEED_PROFILE("speed-profile"),
        SPEED("speed"),
        EVERYTHING("everything"),
        VERIFY("verify")
    }

    fun buildCompileCommand(pkg: String, profile: CompileProfile): String? {
        val safe = ShellSanitizer.cleanPkg(pkg) ?: return null
        return "cmd package compile -m ${profile.flag} -f $safe"
    }

    fun buildResetCommand(pkg: String): String? {
        val safe = ShellSanitizer.cleanPkg(pkg) ?: return null
        return "cmd package compile --reset $safe"
    }

    fun buildResetAllCommand(pkgs: Set<String>): String? {
        val cmds = pkgs.mapNotNull { buildResetCommand(it) }
        return if (cmds.isEmpty()) null else cmds.joinToString(" && ")
    }
}

internal fun MainActivity.artCompilerStatusText(prefs: SharedPreferences): String {
    val set = prefs.getStringSet(Prefs.KEY_ART_COMPILED_PKGS, emptySet()) ?: emptySet()
    return if (set.isEmpty()) getString(R.string.status_ready)
    else getString(R.string.art_status_fmt, set.size)
}

internal fun MainActivity.resetArtCompiler(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val tracked = prefs.getStringSet(Prefs.KEY_ART_COMPILED_PKGS, emptySet()) ?: emptySet()
    val cmd = ArtCompilerLogic.buildResetAllCommand(tracked)
    if (cmd == null) {
        prefs.edit().remove(Prefs.KEY_ART_COMPILED_PKGS).apply()
        findViewById<TextView>(R.id.tvStatusArtCompiler)?.text = getString(R.string.status_ready)
        UiToast.info(this, getString(R.string.art_reset_done), Toast.LENGTH_SHORT).show()
        return
    }
    runAdbCommand(cmd, getString(R.string.art_reset_done)) {
        prefs.edit().remove(Prefs.KEY_ART_COMPILED_PKGS).apply()
        fetchSystemStatuses()
    }
}

internal fun MainActivity.showArtCompilerSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_boost)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }
        } catch (_: Exception) {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showArtCompilerSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentArtCompilerPicker(prefs, apps)
            }
        }
    }
}

private fun MainActivity.presentArtCompilerPicker(prefs: SharedPreferences, apps: List<AppEntry>) {
    val tracked = prefs.getStringSet(Prefs.KEY_ART_COMPILED_PKGS, emptySet()) ?: emptySet()
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    list.addView(optionRow(
        this,
        getString(R.string.art_bg_dexopt),
        getString(R.string.art_bg_dexopt_desc),
        selected = false
    ) {
        runAdbCommand("cmd package bg-dexopt-job", getString(R.string.art_bg_dexopt_done)) {
            fetchSystemStatuses()
        }
    })

    apps.take(90).forEach { app ->
        val sub = if (app.pkg in tracked) "${app.pkg} · ${getString(R.string.art_tag_compiled)}" else app.pkg
        list.addView(optionRow(this, app.label, sub, selected = app.pkg in tracked) {
            showArtProfileSheet(prefs, app.pkg, app.label)
        })
    }
    if (apps.size > 90) {
        list.addView(infoCard(this, getString(R.string.showing_n, 90, apps.size)))
    }

    Sheet(this)
        .icon(R.drawable.ic_boost)
        .title(getString(R.string.title_art_compiler))
        .message(getString(R.string.art_sheet_msg))
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showArtProfileSheet(prefs: SharedPreferences, pkg: String, label: String) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_boost)
        .title(label)
        .message(pkg)

    val profiles = listOf(
        ArtCompilerLogic.CompileProfile.SPEED to R.string.art_prof_speed to R.string.art_prof_speed_desc,
        ArtCompilerLogic.CompileProfile.SPEED_PROFILE to R.string.art_prof_profile to R.string.art_prof_profile_desc,
        ArtCompilerLogic.CompileProfile.EVERYTHING to R.string.art_prof_everything to R.string.art_prof_everything_desc,
        ArtCompilerLogic.CompileProfile.VERIFY to R.string.art_prof_verify to R.string.art_prof_verify_desc
    )

    profiles.forEach { (pair, descRes) ->
        val (profile, titleRes) = pair
        list.addView(optionRow(this, getString(titleRes), getString(descRes)) {
            sheet.dismiss()
            val cmd = ArtCompilerLogic.buildCompileCommand(pkg, profile) ?: return@optionRow
            runArtCompileWithProgress(prefs, pkg, label, cmd, getString(titleRes), markCompiled = true)
        })
    }

    list.addView(optionRow(this, getString(R.string.art_prof_reset), getString(R.string.art_prof_reset_desc)) {
        sheet.dismiss()
        val cmd = ArtCompilerLogic.buildResetCommand(pkg) ?: return@optionRow
        runArtCompileWithProgress(prefs, pkg, label, cmd, getString(R.string.art_prof_reset), markCompiled = false)
    })

    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.runArtCompileWithProgress(
    prefs: SharedPreferences,
    pkg: String,
    label: String,
    cmd: String,
    modeLabel: String,
    markCompiled: Boolean
) {
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_boost)
        .title(label)
        .message(getString(R.string.art_running_fmt, modeLabel))
    progress.show(indeterminate = true)

    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(cmd, timeoutMs = 60_000L)
        withContext(Dispatchers.Main) {
            progress.dismiss()
            if (res.ok) {
                val cur = (prefs.getStringSet(Prefs.KEY_ART_COMPILED_PKGS, emptySet()) ?: emptySet()).toMutableSet()
                if (markCompiled) cur.add(pkg) else cur.remove(pkg)
                prefs.edit().putStringSet(Prefs.KEY_ART_COMPILED_PKGS, cur).apply()
                findViewById<TextView>(R.id.tvStatusArtCompiler)?.text = artCompilerStatusText(prefs)
                fetchSystemStatuses()
                UiToast.success(
                    this@runArtCompileWithProgress,
                    getString(R.string.art_done_fmt, label, modeLabel),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                UiToast.error(
                    this@runArtCompileWithProgress,
                    res.err.ifBlank { res.out }.ifBlank { getString(R.string.art_fail) },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
