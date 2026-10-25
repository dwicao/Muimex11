package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/** Settings backup / restore via clipboard JSON (profiles + auto-boost + dock state). */
internal fun MainActivity.exportSettingsBackup() {
    try {
        val json = GameProfileStore.exportJson(this)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("muimex-backup", json))
        UiToast.success(this, getString(R.string.backup_exported), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        UiToast.error(this, getString(R.string.toast_error, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.importSettingsBackup(json: String) {
    val count = GameProfileStore.importJson(this, json)
    if (count >= 0) {
        UiToast.success(this, getString(R.string.backup_imported, count), Toast.LENGTH_SHORT).show()
    } else {
        UiToast.error(this, getString(R.string.backup_failed), Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.showBackupSheet() {
    if (isFinishing || isDestroyed) return
    Sheet(this)
        .icon(R.drawable.ic_download)
        .title(getString(R.string.lbl_tweaks))
        .message(GameProfileStore.exportJson(this).take(400))
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.btn_save), SheetStyle.PRIMARY) { exportSettingsBackup() }
        .show()
}
