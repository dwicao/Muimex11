package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orientation lock card (Display category): force auto/portrait/landscape
 * via system rotation settings. Per-game forcing lives in Game Profiles.
 */
internal fun MainActivity.showOrientationSheet(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val saved = prefs.getInt(Prefs.KEY_ORIENTATION_MODE, OrientationMode.AUTO)
        .takeIf { OrientationMode.isValid(it) } ?: OrientationMode.AUTO
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dlg_orient_title))
        .message(getString(R.string.dlg_orient_msg))
    listOf(
        OrientationMode.AUTO to getString(R.string.orient_auto),
        OrientationMode.PORTRAIT to getString(R.string.orient_portrait),
        OrientationMode.LANDSCAPE to getString(R.string.orient_landscape),
        OrientationMode.REVERSE_PORTRAIT to getString(R.string.orient_rportrait),
        OrientationMode.REVERSE_LANDSCAPE to getString(R.string.orient_rlandscape)
    ).forEach { (mode, name) ->
        list.addView(optionRow(
            this, name,
            if (mode == OrientationMode.AUTO) getString(R.string.orient_auto_desc) else null,
            selected = saved == mode
        ) {
            sheet.dismiss()
            applyOrientationMode(prefs, mode)
        })
    }
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

internal fun MainActivity.applyOrientationMode(prefs: SharedPreferences, mode: Int) {
    val cmd = OrientationMode.buildApply(mode) ?: return
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusOrientation)?.text = getString(R.string.status_checking)
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(cmd)
        withContext(Dispatchers.Main) {
            if (res.ok) {
                prefs.edit().putInt(Prefs.KEY_ORIENTATION_MODE, mode).apply()
                UiToast.success(
                    this@applyOrientationMode,
                    getString(R.string.msg_orient_set, orientationModeName(mode)), Toast.LENGTH_SHORT
                ).show()
            } else {
                val msg = if (res.err.isNotBlank()) res.err.take(120) else getString(R.string.toast_cmd_failed, res.code)
                UiToast.error(this@applyOrientationMode, msg, Toast.LENGTH_SHORT).show()
            }
            fetchSystemStatuses()
        }
    }
}

internal fun MainActivity.resetOrientationMode(prefs: SharedPreferences) {
    applyOrientationMode(prefs, OrientationMode.AUTO)
}

internal fun MainActivity.orientationModeName(mode: Int): String = when (mode) {
    OrientationMode.PORTRAIT -> getString(R.string.orient_portrait)
    OrientationMode.LANDSCAPE -> getString(R.string.orient_landscape)
    OrientationMode.REVERSE_PORTRAIT -> getString(R.string.orient_rportrait)
    OrientationMode.REVERSE_LANDSCAPE -> getString(R.string.orient_rlandscape)
    else -> getString(R.string.orient_auto)
}

/** Live settings win when Shizuku is up; the saved choice is the fallback. */
internal fun MainActivity.orientationStatusText(prefs: SharedPreferences): String {
    val live = if (ShizukuRunner.isReady()) {
        OrientationMode.parse(
            ShizukuRunner.query("settings get system accelerometer_rotation"),
            ShizukuRunner.query("settings get system user_rotation")
        )
    } else {
        prefs.getInt(Prefs.KEY_ORIENTATION_MODE, OrientationMode.AUTO)
            .takeIf { OrientationMode.isValid(it) } ?: OrientationMode.AUTO
    }
    return getString(R.string.status_orient_fmt, orientationModeName(live))
}
