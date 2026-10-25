package com.muimex11.official

import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun readAudioPrev(prefs: SharedPreferences): Map<String, String> {
    val raw = prefs.getString(Prefs.KEY_AUDIO_PREV, null) ?: return emptyMap()
    return try {
        val o = JSONObject(raw)
        AudioPresets.SNAPSHOT.associateWith { o.optString(it, "") }
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun saveAudioPrev(prefs: SharedPreferences, prev: Map<String, String>) {
    val o = JSONObject()
    prev.forEach { (k, v) -> o.put(k, v) }
    prefs.edit().putString(Prefs.KEY_AUDIO_PREV, o.toString()).apply()
}

internal fun MainActivity.showAudioSheet(prefs: SharedPreferences) {
    val cur = prefs.getString(Prefs.KEY_AUDIO_PRESET, AudioPresets.PRESET_OFF).orEmpty()
    if (cur != AudioPresets.PRESET_OFF && cur.isNotEmpty()) {
        UiToast.info(this, getString(R.string.toast_audio_already), Toast.LENGTH_SHORT).show()
        return
    }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_audio)
        .title(getString(R.string.audio_title))
        .message(getString(R.string.audio_message))
    list.addView(optionRow(this, getString(R.string.audio_lowlat), getString(R.string.audio_lowlat_desc)) {
        sheet.dismiss()
        applyAudioPreset(prefs, AudioPresets.PRESET_LOWLAT)
    })
    list.addView(infoCard(this, getString(R.string.audio_hint)))
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TEXT).show()
}

internal fun MainActivity.applyAudioPreset(prefs: SharedPreferences, preset: String) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        if (prefs.getString(Prefs.KEY_AUDIO_PREV, null) == null) {
            val prev = AudioPresets.SNAPSHOT.associateWith { ck ->
                val (ns, k) = ck.split(":")
                runAdbCommandWithResult("settings get $ns $k").trim()
            }
            saveAudioPrev(prefs, prev)
        }
        val cmd = AudioPresets.buildApply(preset)
        withContext(Dispatchers.Main) {
            runAdbCommand(cmd, getString(R.string.audio_done)) {
                prefs.edit().putString(Prefs.KEY_AUDIO_PRESET, preset).apply()
                findViewById<TextView>(R.id.tvStatusAudio).text = getString(R.string.status_audio_lowlat)
                fetchSystemStatuses()
            }
        }
    }
}

internal fun MainActivity.resetAudioPreset(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val cmd = AudioPresets.buildReset(readAudioPrev(prefs))
    runAdbCommand(cmd, getString(R.string.audio_reset_done)) {
        prefs.edit()
            .putString(Prefs.KEY_AUDIO_PRESET, AudioPresets.PRESET_OFF)
            .remove(Prefs.KEY_AUDIO_PREV)
            .apply()
        findViewById<TextView>(R.id.tvStatusAudio).text = getString(R.string.status_default)
        fetchSystemStatuses()
    }
}

internal fun audioResetCmdForAll(prefs: SharedPreferences): String =
    AudioPresets.buildReset(readAudioPrev(prefs))

internal fun clearAudioPrefs(prefs: SharedPreferences) {
    prefs.edit()
        .putString(Prefs.KEY_AUDIO_PRESET, AudioPresets.PRESET_OFF)
        .remove(Prefs.KEY_AUDIO_PREV)
        .apply()
}
