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
        .button(getString(R.string.btn_cancel), SheetStyle.TEXT)
        .button(getString(R.string.backup_save_file), SheetStyle.TONAL) { exportSettingsBackupFile() }
        .button(getString(R.string.backup_load_file), SheetStyle.TONAL) { pickSettingsBackupFile() }
        .button(getString(R.string.btn_save), SheetStyle.PRIMARY) { exportSettingsBackup() }
        .show()
}

/** Export a copy to Download/Muimex11 (survives clipboard clears). */
private fun MainActivity.exportSettingsBackupFile() {
    try {
        val json = GameProfileStore.exportJson(this)
        val name = "muimex11-backup.json"
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Muimex11")
            }
            contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )?.also { u ->
                contentResolver.openOutputStream(u)?.use { it.write(json.toByteArray()) }
            }
        } else null
        if (uri != null) {
            UiToast.success(this, getString(R.string.backup_file_saved), Toast.LENGTH_LONG).show()
        } else {
            exportSettingsBackup()
        }
    } catch (e: Exception) {
        UiToast.error(this, getString(R.string.toast_error, e.message ?: ""), Toast.LENGTH_LONG).show()
    }
}

/** Import a previously exported backup file. */
private fun MainActivity.pickSettingsBackupFile() {
    onScriptPicked = { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val bytes = ins.readBytes()
                    if (bytes.size in 1..1_048_576) {
                        importSettingsBackup(String(bytes, Charsets.UTF_8))
                    } else {
                        UiToast.error(this, getString(R.string.backup_failed), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                UiToast.error(this, getString(R.string.backup_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    try {
        scriptPicker.launch(arrayOf("application/json", "*/*"))
    } catch (_: Exception) {}
}
