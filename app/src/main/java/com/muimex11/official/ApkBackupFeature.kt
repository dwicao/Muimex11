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
 * APK backup: copies installed APKs (base + splits) to Download/Muimex11
 * via Shizuku (`pm path` + `cp`). Complements the APK installer card.
 */
internal fun MainActivity.showApkBackupSheet() {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_download)
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
                UiToast.info(this@showApkBackupSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentApkBackupList(apps)
            }
        }
    }
}

private fun MainActivity.presentApkBackupList(apps: List<AppEntry>) {
    val selected = mutableSetOf<String>()
    var query = ""
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)

    fun current(): List<AppEntry> {
        val base = if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
        return base
    }

    fun render() {
        list.removeAllViews()
        val rows = current()
        val shown = rows.take(60)
        if (shown.isEmpty()) {
            list.addView(infoCard(this, getString(R.string.txt_no_match)))
        }
        shown.forEach { a ->
            val icon = try { packageManager.getApplicationIcon(a.pkg) } catch (e: Exception) { null }
            list.addView(optionRow(
                this, a.label,
                if (a.isSystem) getString(R.string.sys_app_pkg, a.pkg) else a.pkg,
                icon = icon?.constantState?.newDrawable()?.mutate() ?: icon,
                tintIcon = false, selected = a.pkg in selected
            ) {
                if (!selected.remove(a.pkg)) selected.add(a.pkg)
                render()
                updateApkBackupButton(sheet, selected.size)
            })
        }
        if (rows.size > shown.size) {
            list.addView(TextView(this).apply {
                text = getString(R.string.showing_n, shown.size, rows.size)
                textSize = 12f
                setTextColor(Ui.onSurfaceVariant(this@presentApkBackupList))
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        fontify(list)
    }

    val (searchLayout, searchEdit) = styledField(
        this, getString(R.string.hint_search_apps),
        android.text.InputType.TYPE_CLASS_TEXT, topMarginDp = 10
    )
    searchEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            query = s?.toString()?.trim() ?: ""
            render()
        }
    })

    sheet.icon(R.drawable.ic_download)
        .title(getString(R.string.dlg_apkbackup_title))
        .message(getString(R.string.dlg_apkbackup_msg))
        .content(searchLayout)
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .button(getString(R.string.btn_backup), SheetStyle.PRIMARY, dismiss = false) {
            val picked = selected.toList()
            if (picked.isEmpty()) return@button
            sheet.dismiss()
            runApkBackup(picked)
        }
        .show()
    render()
    updateApkBackupButton(sheet, 0)
}

private fun MainActivity.updateApkBackupButton(sheet: Sheet, n: Int) {
    val primary = sheet.buttonViews.getOrNull(1) ?: return
    primary.isEnabled = n > 0
    primary.text = if (n == 0) getString(R.string.btn_backup)
    else getString(R.string.btn_backup_n, n)
}

private fun MainActivity.runApkBackup(pkgs: List<String>) {
    val valid = pkgs.mapNotNull { ShellSanitizer.cleanPkg(it) }
    if (valid.isEmpty() || !hasShizukuPermission()) return

    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_download)
        .title(getString(R.string.dlg_apkbackup_working))
        .message(getString(R.string.txt_please_wait))
    progress.show()

    lifecycleScope.launch(Dispatchers.IO) {
        var ok = 0
        var apkCount = 0
        val failed = mutableListOf<String>()
        valid.forEachIndexed { i, p ->
            withContext(Dispatchers.Main) {
                progress.update((i * 100) / valid.size, getString(R.string.progress_n_of, i + 1, valid.size))
            }
            val version = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(p, 0).versionName ?: "0"
            } catch (e: Exception) { "0" }
            val srcs = ApkBackup.parsePmPath(runShell("pm path $p").out)
            val dest = ApkBackup.destDir(p, version)
            val cmd = ApkBackup.buildCopyCmd(srcs, dest)
            if (cmd != null && runShell(cmd).code == 0) {
                ok++
                apkCount += srcs.size
            } else failed += p
        }
        if (ok > 0) {
            getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE).edit()
                .putInt(Prefs.KEY_APKBACKUP_COUNT, ok)
                .putLong(Prefs.KEY_APKBACKUP_TIME, System.currentTimeMillis())
                .apply()
        }
        withContext(Dispatchers.Main) {
            progress.dismiss()
            if (ok > 0) {
                UiToast.success(
                    this@runApkBackup,
                    getString(R.string.msg_apkbackup_done, ok, apkCount), Toast.LENGTH_LONG
                ).show()
            }
            if (failed.isNotEmpty()) {
                UiToast.warning(
                    this@runApkBackup,
                    resources.getQuantityString(R.plurals.failed_apps, failed.size, failed.size), Toast.LENGTH_LONG
                ).show()
            }
            fetchSystemStatuses()
        }
    }
}

internal fun MainActivity.apkBackupStatusText(): String {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val n = prefs.getInt(Prefs.KEY_APKBACKUP_COUNT, 0)
    return if (n <= 0) getString(R.string.status_ready)
    else getString(R.string.status_apkbackup_fmt, n)
}
