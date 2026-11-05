package com.muimex11.official

import android.content.Context
import android.content.pm.PackageManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tolerates Shizuku-manager forks: the official manager is preferred, but a
 * compatible fork (e.g. Shevery, same client protocol) is used when the
 * official one is not installed. Pattern adapted from GAMA's ShizukuBackend.
 */
object ShizukuMgr {
    const val OFFICIAL = "moe.shizuku.privileged.api"
    const val SHEVERY = "com.hamondev.shevery"

    private val KNOWN = listOf(OFFICIAL, SHEVERY)

    fun installed(context: Context): String? =
        KNOWN.firstOrNull { isInstalled(context, it) }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launchIntent(context: Context) =
        installed(context)?.let { pkg ->
            try {
                context.packageManager.getLaunchIntentForPackage(pkg)
            } catch (_: Exception) { null }
        }
}

/**
 * Shizuku recovery helper: wireless debugging often drops after reboot and
 * takes Shizuku down with it. This re-enables `adb_wifi` and opens whichever
 * Shizuku manager is installed so the user can re-pair in seconds.
 */
internal fun MainActivity.showShizukuRecoverySheet() {
    if (!hasShizukuPermission()) return
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_security)
        .title(getString(R.string.dlg_srec_title))
        .message(getString(R.string.dlg_srec_msg))
    list.addView(optionRow(
        this,
        getString(R.string.srec_enable_open),
        getString(R.string.srec_enable_open_desc)
    ) {
        sheet.dismiss()
        enableWirelessDebuggingAndOpen()
    })
    list.addView(optionRow(
        this,
        getString(R.string.srec_open_only),
        getString(R.string.srec_open_only_desc)
    ) {
        sheet.dismiss()
        openShizukuManager()
    })
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.enableWirelessDebuggingAndOpen() {
    lifecycleScope.launch(Dispatchers.IO) {
        ShizukuRunner.exec("settings put global adb_wifi_enabled 1")
        withContext(Dispatchers.Main) { openShizukuManager() }
    }
}

private fun MainActivity.openShizukuManager() {
    try {
        ShizukuMgr.launchIntent(this)?.let { startActivity(it); return }
    } catch (_: Exception) {}
    UiToast.info(this, getString(R.string.toast_srec_no_manager), Toast.LENGTH_LONG).show()
}

/** Recovery status for the Tools card: manager state + wireless debugging. */
internal fun MainActivity.shizukuRecoveryStatusText(): String {
    if (ShizukuRunner.isReady()) return getString(R.string.status_srec_ready)
    // No backend: query fails empty, which correctly reads as "off".
    val wifi = ShizukuRunner.query("settings get global adb_wifi_enabled").trim()
    return if (wifi == "1") getString(R.string.status_srec_wifi)
    else getString(R.string.status_srec_off)
}
