package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private fun readDozeRemoved(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString(Prefs.KEY_DOZE_REMOVED, null) ?: return emptyList()
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveDozeRemoved(prefs: SharedPreferences, pkgs: List<String>) {
    val a = JSONArray()
    pkgs.forEach { a.put(it) }
    prefs.edit().putString(Prefs.KEY_DOZE_REMOVED, a.toString()).apply()
}

internal fun MainActivity.showDozeSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val wlDump = runAdbCommandWithResult("dumpsys deviceidle whitelist")
        val thirdDump = runAdbCommandWithResult("cmd package list packages -3")
        val whitelist = DozeParser.parseWhitelist(wlDump)
        val thirdParty = thirdDump.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = DozeParser.exemptThirdParty(whitelist, thirdParty)
        val removed = readDozeRemoved(prefs)
        withContext(Dispatchers.Main) {
            val body = LinearLayout(this@showDozeSheet).apply {
                orientation = LinearLayout.VERTICAL
            }
            val removedNote = if (removed.isNotEmpty())
                getString(R.string.doze_removed_fmt, removed.size) else ""
            body.addView(
                infoCard(
                    this@showDozeSheet,
                    getString(R.string.doze_state_fmt, whitelist.size, candidates.size) +
                        if (removedNote.isNotEmpty()) "\n$removedNote" else ""
                )
            )
            if (candidates.isNotEmpty()) {
                body.addView(
                    infoCard(
                        this@showDozeSheet,
                        getString(R.string.doze_candidates_fmt, candidates.take(8).joinToString(", ")) +
                            if (candidates.size > 8) getString(R.string.doze_more_fmt, candidates.size - 8) else ""
                    )
                )
            }
            val sheet = Sheet(this@showDozeSheet)
                .icon(R.drawable.ic_battery)
                .title(getString(R.string.doze_title))
                .message(getString(R.string.doze_message))
                .content(body)
                .cancelable(true)
                .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
            sheet.button(getString(R.string.doze_force_now), SheetStyle.TEXT) {
                runAdbCommand(
                    "dumpsys deviceidle force-idle",
                    getString(R.string.doze_forced)
                )
            }
            if (candidates.isNotEmpty()) {
                sheet.button(getString(R.string.doze_restrict), SheetStyle.PRIMARY) {
                    restrictDozeExemptions(prefs, candidates)
                }
            }
            if (removed.isNotEmpty()) {
                sheet.button(getString(R.string.doze_restore), SheetStyle.TONAL) {
                    restoreDozeExemptions(prefs, removed)
                }
            }
            sheet.show()
        }
    }
}

internal fun MainActivity.restrictDozeExemptions(prefs: SharedPreferences, pkgs: List<String>) {
    if (!hasShizukuPermission() || pkgs.isEmpty()) return
    runAdbCommand(DozeParser.buildRemoveCmd(pkgs), getString(R.string.doze_restricted_fmt, pkgs.size)) {
        saveDozeRemoved(this, (readDozeRemoved(this) + pkgs).distinct().sorted())
        fetchSystemStatuses()
    }
}

internal fun MainActivity.restoreDozeExemptions(prefs: SharedPreferences, pkgs: List<String>) {
    if (!hasShizukuPermission() || pkgs.isEmpty()) return
    runAdbCommand(DozeParser.buildRestoreCmd(pkgs), getString(R.string.doze_restored)) {
        saveDozeRemoved(this, emptyList())
        fetchSystemStatuses()
    }
}

internal fun dozeResetCmdForAll(prefs: SharedPreferences): String? {
    val raw = prefs.getString(Prefs.KEY_DOZE_REMOVED, null) ?: return null
    val pkgs = try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
    if (pkgs.isEmpty()) return null
    return DozeParser.buildRestoreCmd(pkgs)
}

internal fun clearDozePrefs(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_DOZE_REMOVED).apply()
}
