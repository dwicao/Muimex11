package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Copyable command-failure sheet. Replaces the old truncated error toast:
 * ROMs (e.g. Realme/ColorOS blocking `settings put system pointer_speed`)
 * fail with long shell traces that users need to copy for bug reports.
 */
internal fun MainActivity.showCommandErrorSheet(
    title: String,
    command: String,
    result: ShellResult,
    onRetry: (() -> Unit)? = null
) {
    val out = result.merged.trim().ifEmpty { "(no output)" }.take(4096)
    val body = "${getString(R.string.cmd_failed_command)}\n$command\n\n" +
        "${getString(R.string.cmd_failed_exit, result.code)}\n\n" +
        "${getString(R.string.cmd_failed_output)}\n$out"
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tv = TextView(this).apply {
        text = body
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    list.addView(ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
        )
        addView(tv)
    })
    Sheet(this)
        .icon(R.drawable.ic_terminal)
        .title(title)
        .message(getString(R.string.dlg_cmd_failed_msg))
        .content(list)
        .button(getString(R.string.shell_btn_copy), SheetStyle.TONAL, dismiss = false) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("muimex11-cmd-error", body))
                UiToast.success(this, getString(R.string.shell_copied), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        .apply {
            if (onRetry != null) {
                button(getString(R.string.btn_retry), SheetStyle.PRIMARY) { onRetry() }
            }
        }
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}
