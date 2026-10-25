package com.muimex11.official

import android.content.SharedPreferences
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

private fun readSensorsState(prefs: SharedPreferences): Pair<Boolean, Boolean> {
    return try {
        val raw = prefs.getString(Prefs.KEY_SENSORS_STATE, null) ?: return false to false
        val o = JSONObject(raw)
        o.optBoolean("camera", false) to o.optBoolean("mic", false)
    } catch (_: Exception) {
        false to false
    }
}

private fun saveSensorsState(prefs: SharedPreferences, cameraOff: Boolean, micOff: Boolean) {
    prefs.edit().putString(
        Prefs.KEY_SENSORS_STATE,
        JSONObject().apply {
            put("camera", cameraOff)
            put("mic", micOff)
        }.toString()
    ).apply()
}

internal fun MainActivity.showSensorsSheet(prefs: SharedPreferences) {
    if (Build.VERSION.SDK_INT < 31) {
        UiToast.error(this, getString(R.string.toast_needs_a12), Toast.LENGTH_LONG).show()
        return
    }
    if (!hasShizukuPermission()) return
    val (camOff, micOff) = readSensorsState(prefs)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    list.addView(optionRow(
        this,
        getString(R.string.sensors_camera),
        getString(if (camOff) R.string.sensors_off_fmt else R.string.sensors_on_fmt),
        selected = camOff
    ) {
        setSensorBlocked(prefs, which = SensorCmd.CAMERA, block = !camOff)
    })
    list.addView(optionRow(
        this,
        getString(R.string.sensors_mic),
        getString(if (micOff) R.string.sensors_off_fmt else R.string.sensors_on_fmt),
        selected = micOff
    ) {
        setSensorBlocked(prefs, which = SensorCmd.MIC, block = !micOff)
    })
    list.addView(infoCard(this, getString(R.string.sensors_hint)))
    Sheet(this)
        .icon(R.drawable.ic_sensors)
        .title(getString(R.string.sensors_title))
        .message(getString(R.string.sensors_message))
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}

private fun MainActivity.setSensorBlocked(prefs: SharedPreferences, which: Int, block: Boolean) {
    val userId = android.os.Process.myUid() / 100000
    runAdbCommand(
        SensorCmd.buildSet(userId, which, block),
        getString(
            if (block) R.string.sensors_blocked_fmt else R.string.sensors_allowed_fmt,
            getString(if (which == SensorCmd.CAMERA) R.string.sensors_camera else R.string.sensors_mic)
        )
    ) {
        val (camOff, micOff) = readSensorsState(prefs)
        if (which == SensorCmd.CAMERA) saveSensorsState(prefs, block, micOff)
        else saveSensorsState(prefs, camOff, block)
        findViewById<TextView>(R.id.tvStatusSensors).text = sensorsStatusText(prefs)
        fetchSystemStatuses()
    }
}

internal fun MainActivity.sensorsStatusText(prefs: SharedPreferences): String {
    val (camOff, micOff) = readSensorsState(prefs)
    return when {
        camOff && micOff -> getString(R.string.status_sensors_both)
        camOff -> getString(R.string.status_sensors_cam)
        micOff -> getString(R.string.status_sensors_mic)
        else -> getString(R.string.status_default)
    }
}

internal fun sensorsResetCmd(prefs: SharedPreferences): String? {
    val (camOff, micOff) = readSensorsState(prefs)
    if (!camOff && !micOff) return null
    val userId = android.os.Process.myUid() / 100000
    return SensorCmd.buildUnblockBoth(userId)
}

internal fun clearSensorsPrefs(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_SENSORS_STATE).apply()
}
