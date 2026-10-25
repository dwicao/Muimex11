package com.muimex11.official

import android.content.pm.PackageManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

internal fun MainActivity.checkShizukuStatus() {
    if (isShizukuInstalled()) {
        if (Shizuku.pingBinder()) {
            if (hasShizukuPermission()) {
                updateShizukuBannerUI(true)
                fetchSystemStatuses()
            } else {
                updateShizukuBannerUI(false)
            }
        } else {
            updateShizukuBannerUI(false)
            UiToast.warning(this, getString(R.string.toast_shizuku_not_running_launch), Toast.LENGTH_LONG).show()
            launchShizukuApp()
        }
    } else {
        updateShizukuBannerUI(false)
        showShizukuRequiredDialog()
    }
}

internal fun MainActivity.hasShizukuPermission(): Boolean {
    if (!Shizuku.pingBinder()) {
        UiToast.error(this, getString(R.string.toast_shizuku_not_running), Toast.LENGTH_SHORT).show()
        return false
    }
    return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        true
    } else {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            UiToast.info(this, getString(R.string.toast_open_shizuku), Toast.LENGTH_LONG).show()
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
        false
    }
}

internal fun MainActivity.isShizukuInstalled(): Boolean {
    return try {
        packageManager.getPackageInfo(AppConfig.SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

internal fun MainActivity.launchShizukuApp() {
    val intent = packageManager.getLaunchIntentForPackage(AppConfig.SHIZUKU_PACKAGE)
    if (intent != null) {
        startActivity(intent)
        finishAffinity()
    } else {
        UiToast.error(this, getString(R.string.toast_shizuku_launch_fail), Toast.LENGTH_SHORT).show()
        finishAffinity()
    }
}

internal fun MainActivity.runAdbCommandWithResult(command: String): String {
    return ShizukuRunner.query(command)
}

internal fun MainActivity.runShell(cmd: String): ShellResult =
    ShizukuRunner.exec(cmd)

internal fun MainActivity.runAdbCommand(command: String, successMessage: String, onSuccess: () -> Unit = {}) {
    if (!hasShizukuPermission()) return

    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(command)
        withContext(Dispatchers.Main) {
            if (res.ok) {
                UiToast.success(this@runAdbCommand, successMessage, Toast.LENGTH_SHORT).show()
                onSuccess()
            } else {
                val msg = if (res.err.isNotBlank()) "${res.code}: ${res.err.take(120)}"
                else getString(R.string.toast_cmd_failed, res.code)
                UiToast.error(this@runAdbCommand, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal fun MainActivity.showShizukuRequiredDialog() {
    if (isFinishing || isDestroyed) return
    Sheet(this)
        .icon(R.drawable.ic_download)
        .title(getString(R.string.dlg_shizuku_required))
        .message(getString(R.string.dlg_shizuku_required_msg))
        .cancelable(false)
        .button(getString(R.string.btn_exit), SheetStyle.TONAL) { finishAffinity() }
        .button(getString(R.string.btn_download), SheetStyle.PRIMARY) { downloadShizukuApk() }
        .show()
}

internal fun MainActivity.downloadShizukuApk() {
    downloadAndInstall(
        getString(R.string.dl_shizuku),
        AppConfig.SHIZUKU_APK_URL,
        "shizuku-v13.6.0.apk",
        finishAfterInstall = true
    )
}

internal fun MainActivity.updateShizukuBannerUI(isConnected: Boolean) {
    val cardShizuku = findViewById<MaterialCardView>(R.id.cardShizukuStatus)
    val tvShizuku = findViewById<TextView>(R.id.tvShizukuStatus)

    val dotColor = ContextCompat.getColor(this, if (isConnected) R.color.fps_good else R.color.fps_bad)
    tvShizuku.text = if (isConnected) "Shizuku" else getString(R.string.shizuku_offline)
    tvShizuku.setTextColor(ContextCompat.getColor(this, R.color.white))
    cardShizuku.setCardBackgroundColor(
        ContextCompat.getColor(this, if (isConnected) R.color.gs_tile_on_bg else R.color.gs_tile_off_bg)
    )
    cardShizuku.strokeColor = dotColor
    cardShizuku.strokeWidth = dp(2)

    AppCompatResources.getDrawable(this, R.drawable.ic_status_dot)?.let { d ->
        val dot = DrawableCompat.wrap(d.mutate())
        DrawableCompat.setTint(dot, dotColor)
        tvShizuku.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    cardShizuku.setOnClickListener {
        if (isConnected) {
            UiToast.success(this, getString(R.string.toast_shizuku_ok), Toast.LENGTH_SHORT).show()
        } else {
            checkShizukuStatus()
        }
    }
}
