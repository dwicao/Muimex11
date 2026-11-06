package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shows the Tasker/MacroDroid automation token. The token gates
 * [TaskerReceiver]: broadcasts without a matching `token` extra are
 * silently ignored, so keep it private like a password.
 */
internal fun MainActivity.showAutomationTokenSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    var token = prefs.getString(Prefs.KEY_AUTOMATION_TOKEN, null)
    if (token.isNullOrEmpty()) {
        token = AutomationAuth.newToken()
        prefs.edit().putString(Prefs.KEY_AUTOMATION_TOKEN, token).apply()
    }
    val shown = token
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(TextView(this).apply {
        text = shown
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }.let { tv ->
        ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
            )
            addView(tv)
        }
    })
    list.addView(infoCard(this, getString(R.string.automation_token_hint)))
    list.addView(infoCard(this, getString(R.string.automation_howto)))
    Sheet(this)
        .icon(R.drawable.ic_terminal, C_GOLD)
        .title(getString(R.string.automation_title))
        .message(getString(R.string.automation_msg, TaskerReceiver.ACTION_RUN))
        .content(list)
        .button(getString(R.string.shell_btn_copy), SheetStyle.TONAL) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("muimex11-automation-token", shown))
                UiToast.success(this, getString(R.string.shell_copied), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
        .button(getString(R.string.automation_new_token), SheetStyle.DANGER) {
            prefs.edit().putString(Prefs.KEY_AUTOMATION_TOKEN, AutomationAuth.newToken()).apply()
            showAutomationTokenSheet()
        }
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}
