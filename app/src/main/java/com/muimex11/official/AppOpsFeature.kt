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
import org.json.JSONObject

private fun readAppOpsRules(prefs: SharedPreferences): Map<String, Map<String, String>> {
    val raw = prefs.getString(Prefs.KEY_APPOPS_RULES, null) ?: return emptyMap()
    return try {
        val o = JSONObject(raw)
        val out = LinkedHashMap<String, Map<String, String>>()
        o.keys().forEach { pkg ->
            val v = o.optJSONObject(pkg) ?: return@forEach
            val m = LinkedHashMap<String, String>()
            AppOpsParser.OPS.forEach { op ->
                if (v.has(op)) m[op] = v.optString(op)
            }
            if (m.isNotEmpty()) out[pkg] = m
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun saveAppOpsRule(prefs: SharedPreferences, pkg: String, op: String, mode: String) {
    try {
        val raw = prefs.getString(Prefs.KEY_APPOPS_RULES, null)
        val o = if (raw != null) JSONObject(raw) else JSONObject()
        val v = o.optJSONObject(pkg) ?: JSONObject()
        v.put(op, mode)
        o.put(pkg, v)
        prefs.edit().putString(Prefs.KEY_APPOPS_RULES, o.toString()).apply()
    } catch (_: Exception) {}
}

internal fun MainActivity.showAppOpsSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_security)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }
        } catch (e: Exception) {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showAppOpsSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentAppOpsPicker(prefs, apps)
            }
        }
    }
}

private fun MainActivity.presentAppOpsPicker(prefs: SharedPreferences, apps: List<AppEntry>) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    apps.take(80).forEach { a ->
        list.addView(optionRow(this, a.label, a.pkg) {
            showAppOpsEditor(prefs, a.pkg, a.label)
        })
    }
    if (apps.size > 80) {
        list.addView(infoCard(this, getString(R.string.showing_n, 80, apps.size)))
    }
    Sheet(this)
        .icon(R.drawable.ic_security)
        .title(getString(R.string.appops_title))
        .message(getString(R.string.appops_message))
        .content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showAppOpsEditor(prefs: SharedPreferences, pkg: String, label: String) {
    if (!AppOpsParser.safePkg(pkg)) return
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_security)
        .title(label)
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val modes = AppOpsParser.parseGet(runAdbCommandWithResult("cmd appops get $pkg"))
        withContext(Dispatchers.Main) {
            loading.dismiss()
            val list = LinearLayout(this@showAppOpsEditor).apply {
                orientation = LinearLayout.VERTICAL
            }
            AppOpsParser.OPS.forEach { op ->
                val cur = modes[op] ?: AppOpsParser.MODE_DEFAULT
                list.addView(optionRow(
                    this@showAppOpsEditor,
                    opName(op),
                    getString(R.string.appops_state_fmt, cur),
                    selected = cur == AppOpsParser.MODE_IGNORE
                ) {
                    cycleAppOps(prefs, pkg, label, op, cur)
                })
            }
            list.addView(infoCard(this@showAppOpsEditor, getString(R.string.appops_hint)))
            Sheet(this@showAppOpsEditor)
                .icon(R.drawable.ic_security)
                .title(label)
                .message(pkg)
                .content(list)
                .button(getString(R.string.btn_reset), SheetStyle.TONAL) {
                    resetAppOpsPkg(prefs, pkg, label)
                }
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

private fun MainActivity.opName(op: String): String = when (op) {
    "CAMERA" -> getString(R.string.appops_camera)
    "RECORD_AUDIO" -> getString(R.string.appops_mic)
    "ACCESS_FINE_LOCATION" -> getString(R.string.appops_fine_loc)
    else -> getString(R.string.appops_coarse_loc)
}

/** Cycle allow -> ignore -> default -> allow. */
private fun MainActivity.cycleAppOps(
    prefs: SharedPreferences, pkg: String, label: String, op: String, cur: String
) {
    val next = when (cur) {
        AppOpsParser.MODE_ALLOW -> AppOpsParser.MODE_IGNORE
        AppOpsParser.MODE_IGNORE -> AppOpsParser.MODE_DEFAULT
        else -> AppOpsParser.MODE_ALLOW
    }
    runAdbCommand(
        AppOpsParser.buildSet(pkg, op, next),
        getString(R.string.appops_set_fmt, opName(op), next)
    ) {
        saveAppOpsRule(prefs, pkg, op, next)
        fetchSystemStatuses()
        showAppOpsEditor(prefs, pkg, label)
    }
}

private fun MainActivity.resetAppOpsPkg(prefs: SharedPreferences, pkg: String, label: String) {
    val cmd = AppOpsParser.OPS.joinToString(" && ") { AppOpsParser.buildReset(pkg, it) }
    runAdbCommand(cmd, getString(R.string.appops_reset_done)) {
        try {
            val raw = prefs.getString(Prefs.KEY_APPOPS_RULES, null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.remove(pkg)
                prefs.edit().putString(Prefs.KEY_APPOPS_RULES, o.toString()).apply()
            }
        } catch (_: Exception) {}
        fetchSystemStatuses()
        showAppOpsEditor(prefs, pkg, label)
    }
}

internal fun appOpsResetCmdForAll(prefs: SharedPreferences): String? {
    val rules = readAppOpsRules(prefs)
    if (rules.isEmpty()) return null
    val parts = mutableListOf<String>()
    rules.forEach { (pkg, m) ->
        if (!AppOpsParser.safePkg(pkg)) return@forEach
        m.keys.forEach { op ->
            if (op in AppOpsParser.OPS) parts += AppOpsParser.buildReset(pkg, op)
        }
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" && ")
}

internal fun appOpsRulesCount(prefs: SharedPreferences): Int =
    readAppOpsRules(prefs).values.sumOf { it.size }
