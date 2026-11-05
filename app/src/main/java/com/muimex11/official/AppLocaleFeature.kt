package com.muimex11.official

import android.os.Build
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app language (API 33+) over the LocaleManager shell:
 * `cmd locale set-app-locales <pkg> --app-locales <tags>`.
 * Every change is verified with a read-back; ROMs without the shell command
 * fail safely instead of pretending to work. Inspired by Applingo (MIT).
 */
internal fun MainActivity.showAppLocaleSheet() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        UiToast.error(this, getString(R.string.toast_needs_a13), Toast.LENGTH_LONG).show()
        return
    }
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_language)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }.take(200)
        } catch (e: Exception) {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showAppLocaleSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentAppLocalePicker(apps)
            }
        }
    }
}

private fun MainActivity.presentAppLocalePicker(apps: List<AppEntry>) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    apps.take(80).forEach { a ->
        list.addView(optionRow(this, a.label, a.pkg) {
            showAppLocaleEditor(a.pkg, a.label)
        })
    }
    if (apps.size > 80) {
        list.addView(infoCard(this, getString(R.string.showing_n, 80, apps.size)))
    }
    Sheet(this)
        .icon(R.drawable.ic_language)
        .title(getString(R.string.applocale_title))
        .message(getString(R.string.applocale_msg))
        .content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .show()
}

private val LOCALE_TAGS = listOf(
    "en-US", "ja-JP", "ko-KR", "zh-CN", "zh-TW", "es-ES", "pt-BR", "pt-PT",
    "fr-FR", "de-DE", "it-IT", "ru-RU", "ar-SA", "hi-IN", "id-ID", "th-TH",
    "vi-VN", "tr-TR"
)

private fun MainActivity.showAppLocaleEditor(pkg: String, label: String) {
    val safe = ShellSanitizer.cleanPkg(pkg) ?: return
    if (!hasShizukuPermission()) return
    val loading = ProgressSheet(this)
        .icon(R.drawable.ic_language)
        .title(label)
        .message(getString(R.string.txt_please_wait))
    loading.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val current = AppLocale.parseGet(runAdbCommandWithResult(AppLocale.buildGet(safe)))
        withContext(Dispatchers.Main) {
            loading.dismiss()
            val list = LinearLayout(this@showAppLocaleEditor).apply {
                orientation = LinearLayout.VERTICAL
            }
            list.addView(optionRow(
                this@showAppLocaleEditor,
                getString(R.string.applocale_follow),
                getString(R.string.applocale_follow_desc),
                selected = current.isEmpty()
            ) {
                applyAppLocale(safe, label, AppLocale.FOLLOW_SYSTEM)
            })
            LOCALE_TAGS.forEach { tag ->
                list.addView(optionRow(
                    this@showAppLocaleEditor, tag,
                    if (current.equals(tag, ignoreCase = true)) getString(R.string.applocale_current) else null,
                    selected = current.equals(tag, ignoreCase = true)
                ) {
                    applyAppLocale(safe, label, tag)
                })
            }
            Sheet(this@showAppLocaleEditor)
                .icon(R.drawable.ic_language)
                .title(label)
                .message(pkg)
                .content(list)
                .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
                .show()
        }
    }
}

private fun MainActivity.applyAppLocale(pkg: String, label: String, tags: String) {
    val cmd = if (tags.isEmpty()) AppLocale.buildClear(pkg) else AppLocale.buildSet(pkg, tags)
        ?: return
    lifecycleScope.launch(Dispatchers.IO) {
        val setRes = ShizukuRunner.exec(cmd)
        val actual = AppLocale.parseGet(runAdbCommandWithResult(AppLocale.buildGet(pkg)))
        val ok = setRes.ok && AppLocale.matches(actual, tags)
        if (ok) {
            // Force-stop so the app picks up its new locales on next launch.
            ShizukuRunner.exec("am force-stop $pkg")
        }
        withContext(Dispatchers.Main) {
            if (ok) {
                val what = if (tags.isEmpty()) getString(R.string.applocale_follow) else tags
                UiToast.success(
                    this@applyAppLocale,
                    getString(R.string.msg_applocale_set, label, what), Toast.LENGTH_LONG
                ).show()
            } else {
                val detail = setRes.err.ifBlank { setRes.out }.ifBlank { "verify: '$actual'" }.take(120)
                UiToast.error(this@applyAppLocale, getString(R.string.msg_applocale_fail, detail), Toast.LENGTH_LONG).show()
            }
            fetchSystemStatuses()
        }
    }
}
