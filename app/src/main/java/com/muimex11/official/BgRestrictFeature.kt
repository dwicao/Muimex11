package com.muimex11.official

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class BgRule(val bucket: String, val hiber: Boolean)

private fun readBgRules(prefs: SharedPreferences): Map<String, BgRule> {
    val raw = prefs.getString(Prefs.KEY_BGRESTRICT_RULES, null) ?: return emptyMap()
    return try {
        val o = JSONObject(raw)
        val out = LinkedHashMap<String, BgRule>()
        o.keys().forEach { pkg ->
            val v = o.optJSONObject(pkg) ?: return@forEach
            out[pkg] = BgRule(
                v.optString("bucket", "active"),
                v.optBoolean("hiber", false)
            )
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun saveBgRule(prefs: SharedPreferences, pkg: String, bucket: String, hiber: Boolean) {
    try {
        val raw = prefs.getString(Prefs.KEY_BGRESTRICT_RULES, null)
        val o = if (raw != null) JSONObject(raw) else JSONObject()
        o.put(pkg, JSONObject().apply {
            put("bucket", bucket)
            put("hiber", hiber)
        })
        prefs.edit().putString(Prefs.KEY_BGRESTRICT_RULES, o.toString()).apply()
    } catch (_: Exception) {}
}

internal fun MainActivity.showBgRestrictSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_freeze)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen && !it.isSystem }
        } catch (e: Exception) {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showBgRestrictSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentBgRestrictPicker(prefs, apps)
            }
        }
    }
}

private fun MainActivity.presentBgRestrictPicker(prefs: SharedPreferences, apps: List<AppEntry>) {
    val rules = readBgRules(prefs)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    apps.take(80).forEach { a ->
        val r = rules[a.pkg]
        val sub = if (r == null) a.pkg
        else a.pkg + " · " + r.bucket + if (r.hiber) " · hibernated" else ""
        list.addView(optionRow(this, a.label, sub, selected = r != null) {
            showBgRestrictEditor(prefs, a.pkg, a.label)
        })
    }
    if (apps.size > 80) {
        list.addView(infoCard(this, getString(R.string.showing_n, 80, apps.size)))
    }
    Sheet(this)
        .icon(R.drawable.ic_freeze)
        .title(getString(R.string.bgrestrict_title))
        .message(getString(R.string.bgrestrict_message))
        .content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showBgRestrictEditor(prefs: SharedPreferences, pkg: String, label: String) {
    if (pkg.isEmpty()) return
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_freeze)
        .title(label)
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val curBucket = StandbyBuckets.parseGet(runAdbCommandWithResult(StandbyBuckets.buildGet(pkg)))
            ?: "active"
        val hiberSupported = Hibernation.sdkOk(android.os.Build.VERSION.SDK_INT) &&
            Hibernation.parseGet(runAdbCommandWithResult(Hibernation.buildGet(pkg))) != null
        val curHiber = if (hiberSupported) {
            Hibernation.parseGet(runAdbCommandWithResult(Hibernation.buildGet(pkg))) == true
        } else false
        withContext(Dispatchers.Main) {
            loading.dismiss()
            presentBgRestrictEditor(prefs, pkg, label, curBucket, curHiber, hiberSupported)
        }
    }
}

private fun MainActivity.presentBgRestrictEditor(
    prefs: SharedPreferences, pkg: String, label: String,
    curBucket: String, curHiber: Boolean, hiberSupported: Boolean
) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    StandbyBuckets.NAMES.forEach { name ->
        list.addView(optionRow(
            this, standbyName(name),
            if (name == curBucket) getString(R.string.bgrestrict_current) else "",
            selected = name == curBucket
        ) {
            applyBgBucket(prefs, pkg, label, name, curHiber, hiberSupported)
        })
    }
    if (hiberSupported) {
        list.addView(optionRow(
            this,
            getString(R.string.bgrestrict_hiber),
            getString(if (curHiber) R.string.sensors_off_fmt else R.string.sensors_on_fmt),
            selected = curHiber
        ) {
            applyBgHiber(prefs, pkg, label, curBucket, !curHiber)
        })
    } else {
        list.addView(infoCard(this, getString(R.string.bgrestrict_no_hiber)))
    }
    list.addView(infoCard(this, getString(R.string.bgrestrict_hint)))
    Sheet(this)
        .icon(R.drawable.ic_freeze)
        .title(label)
        .message(pkg)
        .content(list)
        .button(getString(R.string.btn_reset), SheetStyle.TONAL) {
            resetBgPkg(prefs, pkg, label)
        }
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}

private fun MainActivity.standbyName(bucket: String): String = when (bucket) {
    "active" -> getString(R.string.standby_active)
    "working_set" -> getString(R.string.standby_working)
    "frequent" -> getString(R.string.standby_frequent)
    "rare" -> getString(R.string.standby_rare)
    else -> getString(R.string.standby_restricted)
}

private fun MainActivity.applyBgBucket(
    prefs: SharedPreferences, pkg: String, label: String,
    bucket: String, curHiber: Boolean, hiberSupported: Boolean
) {
    if (!AppOpsParser.safePkg(pkg) || bucket !in StandbyBuckets.NAMES) return
    runAdbCommand(
        StandbyBuckets.buildSet(pkg, bucket),
        getString(R.string.bgrestrict_set_fmt, label, bucket)
    ) {
        saveBgRule(prefs, pkg, bucket, curHiber && hiberSupported)
        refreshBgRestrictStatus(prefs)
        fetchSystemStatuses()
        showBgRestrictEditor(prefs, pkg, label)
    }
}

private fun MainActivity.applyBgHiber(
    prefs: SharedPreferences, pkg: String, label: String,
    curBucket: String, hiber: Boolean
) {
    if (!AppOpsParser.safePkg(pkg)) return
    runAdbCommand(
        Hibernation.buildSet(pkg, hiber),
        getString(if (hiber) R.string.bgrestrict_hiber_on else R.string.bgrestrict_hiber_off, label)
    ) {
        saveBgRule(prefs, pkg, curBucket, hiber)
        refreshBgRestrictStatus(prefs)
        fetchSystemStatuses()
        showBgRestrictEditor(prefs, pkg, label)
    }
}

private fun MainActivity.resetBgPkg(prefs: SharedPreferences, pkg: String, label: String) {
    if (!AppOpsParser.safePkg(pkg)) return
    runAdbCommand(
        "${StandbyBuckets.buildSet(pkg, "active")} && ${Hibernation.buildSet(pkg, false)}",
        getString(R.string.appops_reset_done)
    ) {
        try {
            val raw = prefs.getString(Prefs.KEY_BGRESTRICT_RULES, null)
            if (raw != null) {
                val o = JSONObject(raw)
                o.remove(pkg)
                prefs.edit().putString(Prefs.KEY_BGRESTRICT_RULES, o.toString()).apply()
            }
        } catch (_: Exception) {}
        refreshBgRestrictStatus(prefs)
        fetchSystemStatuses()
        showBgRestrictEditor(prefs, pkg, label)
    }
}

internal fun MainActivity.refreshBgRestrictStatus(prefs: SharedPreferences) {
    val tv = findViewById<TextView?>(R.id.tvStatusBgRestrict) ?: return
    val n = readBgRules(prefs).values.count { it.bucket == "rare" || it.bucket == "restricted" || it.hiber }
    tv.text = if (n == 0) getString(R.string.status_ready)
    else getString(R.string.status_value, getString(R.string.bgrestrict_fmt, n))
}

internal fun bgRestrictCount(prefs: SharedPreferences): Int =
    readBgRules(prefs).values.count { it.bucket == "rare" || it.bucket == "restricted" || it.hiber }

internal fun bgRestrictResetCmdForAll(prefs: SharedPreferences): String? {
    val rules = readBgRules(prefs)
    if (rules.isEmpty()) return null
    val parts = mutableListOf<String>()
    rules.forEach { (pkg, r) ->
        if (!AppOpsParser.safePkg(pkg)) return@forEach
        parts += StandbyBuckets.buildSet(pkg, "active")
        if (r.hiber) parts += Hibernation.buildSet(pkg, false)
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" && ")
}

internal fun clearBgRestrictPrefs(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_BGRESTRICT_RULES).apply()
}
