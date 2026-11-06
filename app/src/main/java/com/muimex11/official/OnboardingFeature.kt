package com.muimex11.official

import android.content.Context
import android.widget.LinearLayout

/**
 * One-time welcome sheet: Shizuku setup is the #1 reason new users think
 * the app is broken, so spell out the three steps up front.
 */
internal fun MainActivity.setupOnboardingIfNeeded() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    if (prefs.getBoolean(Prefs.KEY_ONBOARDED, false)) return
    prefs.edit().putBoolean(Prefs.KEY_ONBOARDED, true).apply()

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(infoCard(this, getString(R.string.ob_s1) + "\n" + getString(R.string.ob_s1d)))
    list.addView(infoCard(this, getString(R.string.ob_s2) + "\n" + getString(R.string.ob_s2d)))
    list.addView(infoCard(this, getString(R.string.ob_s3) + "\n" + getString(R.string.ob_s3d)))
    Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.ob_title))
        .message(getString(R.string.ob_msg))
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}
