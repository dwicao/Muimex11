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

private fun readDebloatRemoved(prefs: SharedPreferences): Set<String> {
    val raw = prefs.getString(Prefs.KEY_DEBLOAT_REMOVED, null) ?: return emptySet()
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

private fun saveDebloatRemoved(prefs: SharedPreferences, pkgs: Set<String>) {
    val a = JSONArray()
    pkgs.sorted().forEach { a.put(it) }
    prefs.edit().putString(Prefs.KEY_DEBLOAT_REMOVED, a.toString()).apply()
}

internal fun MainActivity.showDebloatSheet(prefs: SharedPreferences) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_freeze)
        .title(getString(R.string.debloat_title))
        .message(getString(R.string.debloat_message))
    list.addView(optionRow(this, getString(R.string.debloat_meta), getString(R.string.debloat_meta_desc)) {
        sheet.dismiss()
        confirmDebloatPreset(prefs, DebloatPresets.META)
    })
    list.addView(optionRow(this, getString(R.string.debloat_miui), getString(R.string.debloat_miui_desc)) {
        sheet.dismiss()
        confirmDebloatPreset(prefs, DebloatPresets.MIUI)
    })
    list.addView(optionRow(this, getString(R.string.debloat_samsung), getString(R.string.debloat_samsung_desc)) {
        sheet.dismiss()
        confirmDebloatPreset(prefs, DebloatPresets.SAMSUNG)
    })
    list.addView(infoCard(this, getString(R.string.debloat_hint)))
    sheet.content(list)
        .button(getString(R.string.btn_freeze_list), SheetStyle.TONAL) {
            showFreezeAppsSheet()
        }
        .button(getString(R.string.btn_cancel), SheetStyle.TEXT)
        .show()
}

private fun MainActivity.confirmDebloatPreset(prefs: SharedPreferences, preset: DebloatPresets.Preset) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_freeze)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.txt_please_wait))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val block = freezeBlocklist()
        val installed = preset.pkgs.filter { pkg ->
            if (isBlockedPackage(pkg, block)) return@filter false
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (installed.isEmpty()) {
                UiToast.info(this@confirmDebloatPreset, getString(R.string.debloat_none), Toast.LENGTH_LONG).show()
                return@withContext
            }
            Sheet(this@confirmDebloatPreset)
                .icon(R.drawable.ic_freeze)
                .title(getString(presetTitle(preset.id)))
                .message(installed.joinToString("\n") { "• $it" } + getString(R.string.txt_freeze_sys_tail))
                .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
                .button(getString(R.string.btn_freeze_anyway), SheetStyle.DANGER) {
                    applyFreezeChange(installed, freeze = true)
                    val cur = readDebloatRemoved(prefs)
                    saveDebloatRemoved(prefs, cur + installed.toSet())
                    refreshDebloatStatus(prefs)
                }
                .show()
        }
    }
}

private fun presetTitle(id: String): Int = when (id) {
    "miui" -> R.string.debloat_miui
    "samsung" -> R.string.debloat_samsung
    else -> R.string.debloat_meta
}

internal fun MainActivity.refreshDebloatStatus(prefs: SharedPreferences) {
    val tv = findViewById<TextView?>(R.id.tvStatusDebloat) ?: return
    val n = readDebloatRemoved(prefs).size
    tv.text = if (n == 0) getString(R.string.status_ready)
    else resources.getQuantityString(R.plurals.status_frozen_apps, n, n)
}

internal fun debloatRemovedCount(prefs: SharedPreferences): Int =
    readDebloatRemoved(prefs).size
