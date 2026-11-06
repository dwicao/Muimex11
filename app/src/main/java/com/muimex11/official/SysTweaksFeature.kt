package com.muimex11.official

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tier-1 system tweaks adopted from the Shizuku-tweak audit
 * (setbox modules + essentials wiki):
 *
 * - Force 4x MSAA ......... `settings put global enable_msaa 1`
 *   (setbox force-4x-msaa; complements the Vulkan/OpenGL switcher)
 * - Show touches .......... `settings put system show_touches 1`
 *   (setbox show-touches; handy for screen recordings)
 * - Always-on Display ..... `settings put secure doze_always_on 1`
 *   (essentials Display & Visual)
 * - Safe-volume warning ... `settings put secure audio_safe_volume_state 2`
 *   (essentials Sound & Haptics; 2 = INACTIVE/acknowledged in AOSP
 *   AudioService, suppresses the EU dialog without removing the limit)
 * - Force resizable ....... `settings put global force_resizable_activities 1`
 *   (setbox force-resizable-activities; split-screen for all apps)
 * - Stay awake on charge . `settings put global stay_on_while_plugged_in 7`
 *   (essentials Stay Awake tile; 7 = AC|USB|WIRELESS bitmask)
 * - Status-bar icons ..... `settings put secure icon_blacklist <slots>`
 *   (essentials Statusbar icons; classic System UI Tuner key)
 * - Animations off (0.0x) . all three `*_animation_scale` to 0.0
 *   (setbox disable-animation; extends the existing 0.5x fast mode)
 *
 * Every apply is verified with a read-back; failures surface the full
 * shell output in a copyable sheet instead of a truncated toast.
 */
object SysTweaks {
    const val MSAA_NS = "global"
    const val MSAA_KEY = "enable_msaa"

    const val TOUCHES_NS = "system"
    const val TOUCHES_KEY = "show_touches"

    const val AOD_NS = "secure"
    const val AOD_KEY = "doze_always_on"

    const val SAFEVOL_NS = "secure"
    const val SAFEVOL_KEY = "audio_safe_volume_state"
    const val SAFEVOL_SUPPRESSED = "2"
    const val SAFEVOL_DEFAULT = "0"

    const val RESIZABLE_NS = "global"
    const val RESIZABLE_KEY = "force_resizable_activities"

    const val STAY_NS = "global"
    const val STAY_KEY = "stay_on_while_plugged_in"
    const val STAY_ALL = "7"
    const val STAY_OFF = "0"

    const val ICONS_NS = "secure"
    const val ICONS_KEY = "icon_blacklist"

    /** Curated AOSP status-bar slots. ROMs vary; unknown names are harmless no-ops. */
    val ICON_SLOTS = listOf(
        "alarm_clock", "bluetooth", "wifi", "ethernet", "mobile",
        "airplane", "zen", "battery", "clock", "nfc",
        "cast", "hotspot", "vpn", "data_saver"
    )

    const val ANIM_OFF = "0.0"
    const val ANIM_DEFAULT = "1.0"

    /** Number of toggleable items on the sheet (6 switches + icons + anim-off). */
    const val TOTAL = 8

    fun buildSet(ns: String, key: String, value: String): String =
        "settings put $ns $key $value"

    fun isOn(raw: String?): Boolean = raw?.trim() == "1"

    fun isSafeVolSuppressed(raw: String?): Boolean =
        raw?.trim() == SAFEVOL_SUPPRESSED

    fun isStayAwake(raw: String?): Boolean = raw?.trim() == STAY_ALL

    fun parseBlacklist(raw: String?): Set<String> {
        val t = raw?.trim()
        // `settings get` prints the literal "null" when the key is unset.
        if (t.isNullOrEmpty() || t.equals("null", ignoreCase = true)) return emptySet()
        return t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun buildBlacklist(hidden: Set<String>): String =
        hidden.sorted().joinToString(",")

    fun buildAnim(scale: String): String =
        "settings put global window_animation_scale $scale && " +
            "settings put global transition_animation_scale $scale && " +
            "settings put global animator_duration_scale $scale"

    fun buildReset(): String = listOf(
        buildSet(MSAA_NS, MSAA_KEY, "0"),
        buildSet(TOUCHES_NS, TOUCHES_KEY, "0"),
        "settings delete $AOD_NS $AOD_KEY",
        buildSet(SAFEVOL_NS, SAFEVOL_KEY, SAFEVOL_DEFAULT),
        buildSet(RESIZABLE_NS, RESIZABLE_KEY, "0"),
        buildSet(STAY_NS, STAY_KEY, STAY_OFF),
        "settings delete $ICONS_NS $ICONS_KEY",
        buildAnim(ANIM_DEFAULT)
    ).joinToString(" && ")
}

/** Live on/off snapshot for the sheet + status line. */
private data class SysTweakState(
    val msaa: Boolean,
    val touches: Boolean,
    val aod: Boolean,
    val safeVol: Boolean,
    val resizable: Boolean,
    val stayAwake: Boolean,
    val hiddenIcons: Set<String>,
    val animOff: Boolean
) {
    fun activeCount(): Int =
        listOf(msaa, touches, aod, safeVol, resizable, stayAwake, animOff).count { it } +
            if (hiddenIcons.isNotEmpty()) 1 else 0
}

private fun MainActivity.readSysTweakState(): SysTweakState {
    val animScale = runAdbCommandWithResult("settings get global window_animation_scale")
    val animT = runAdbCommandWithResult("settings get global transition_animation_scale")
    val animD = runAdbCommandWithResult("settings get global animator_duration_scale")
    return SysTweakState(
        msaa = SysTweaks.isOn(runAdbCommandWithResult("settings get ${SysTweaks.MSAA_NS} ${SysTweaks.MSAA_KEY}")),
        touches = SysTweaks.isOn(runAdbCommandWithResult("settings get ${SysTweaks.TOUCHES_NS} ${SysTweaks.TOUCHES_KEY}")),
        aod = SysTweaks.isOn(runAdbCommandWithResult("settings get ${SysTweaks.AOD_NS} ${SysTweaks.AOD_KEY}")),
        safeVol = SysTweaks.isSafeVolSuppressed(runAdbCommandWithResult("settings get ${SysTweaks.SAFEVOL_NS} ${SysTweaks.SAFEVOL_KEY}")),
        resizable = SysTweaks.isOn(runAdbCommandWithResult("settings get ${SysTweaks.RESIZABLE_NS} ${SysTweaks.RESIZABLE_KEY}")),
        stayAwake = SysTweaks.isStayAwake(runAdbCommandWithResult("settings get ${SysTweaks.STAY_NS} ${SysTweaks.STAY_KEY}")),
        hiddenIcons = SysTweaks.parseBlacklist(runAdbCommandWithResult("settings get ${SysTweaks.ICONS_NS} ${SysTweaks.ICONS_KEY}")),
        animOff = animScale.trim() == SysTweaks.ANIM_OFF &&
            animT.trim() == SysTweaks.ANIM_OFF && animD.trim() == SysTweaks.ANIM_OFF
    )
}

internal fun MainActivity.showSysTweaksSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val state = readSysTweakState()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderSysTweaksSheet(state)
        }
    }
}

private fun MainActivity.renderSysTweaksSheet(state: SysTweakState) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.title_systweaks))
        .message(getString(R.string.desc_systweaks))

    fun toggleRow(
        title: String, desc: String, on: Boolean,
        setCmd: String, verifyCmd: String, expect: String
    ) {
        list.addView(optionRow(this, title, desc, selected = on) {
            sheet.dismiss()
            applySysToggle(setCmd, verifyCmd, expect) { showSysTweaksSheet() }
        })
    }

    toggleRow(
        getString(R.string.systweaks_msaa), getString(R.string.systweaks_msaa_desc), state.msaa,
        SysTweaks.buildSet(SysTweaks.MSAA_NS, SysTweaks.MSAA_KEY, if (state.msaa) "0" else "1"),
        "settings get ${SysTweaks.MSAA_NS} ${SysTweaks.MSAA_KEY}", if (state.msaa) "0" else "1"
    )
    toggleRow(
        getString(R.string.systweaks_touches), getString(R.string.systweaks_touches_desc), state.touches,
        SysTweaks.buildSet(SysTweaks.TOUCHES_NS, SysTweaks.TOUCHES_KEY, if (state.touches) "0" else "1"),
        "settings get ${SysTweaks.TOUCHES_NS} ${SysTweaks.TOUCHES_KEY}", if (state.touches) "0" else "1"
    )
    toggleRow(
        getString(R.string.systweaks_aod), getString(R.string.systweaks_aod_desc), state.aod,
        SysTweaks.buildSet(SysTweaks.AOD_NS, SysTweaks.AOD_KEY, if (state.aod) "0" else "1"),
        "settings get ${SysTweaks.AOD_NS} ${SysTweaks.AOD_KEY}", if (state.aod) "0" else "1"
    )
    toggleRow(
        getString(R.string.systweaks_safevol), getString(R.string.systweaks_safevol_desc), state.safeVol,
        SysTweaks.buildSet(
            SysTweaks.SAFEVOL_NS, SysTweaks.SAFEVOL_KEY,
            if (state.safeVol) SysTweaks.SAFEVOL_DEFAULT else SysTweaks.SAFEVOL_SUPPRESSED
        ),
        "settings get ${SysTweaks.SAFEVOL_NS} ${SysTweaks.SAFEVOL_KEY}",
        if (state.safeVol) SysTweaks.SAFEVOL_DEFAULT else SysTweaks.SAFEVOL_SUPPRESSED
    )
    toggleRow(
        getString(R.string.systweaks_resizable), getString(R.string.systweaks_resizable_desc), state.resizable,
        SysTweaks.buildSet(SysTweaks.RESIZABLE_NS, SysTweaks.RESIZABLE_KEY, if (state.resizable) "0" else "1"),
        "settings get ${SysTweaks.RESIZABLE_NS} ${SysTweaks.RESIZABLE_KEY}", if (state.resizable) "0" else "1"
    )
    toggleRow(
        getString(R.string.systweaks_stayawake), getString(R.string.systweaks_stayawake_desc), state.stayAwake,
        SysTweaks.buildSet(
            SysTweaks.STAY_NS, SysTweaks.STAY_KEY,
            if (state.stayAwake) SysTweaks.STAY_OFF else SysTweaks.STAY_ALL
        ),
        "settings get ${SysTweaks.STAY_NS} ${SysTweaks.STAY_KEY}",
        if (state.stayAwake) SysTweaks.STAY_OFF else SysTweaks.STAY_ALL
    )

    val iconsDesc = if (state.hiddenIcons.isEmpty()) getString(R.string.systweaks_icons_desc)
    else getString(R.string.systweaks_icons_active, state.hiddenIcons.sorted().joinToString(", "))
    list.addView(optionRow(this, getString(R.string.systweaks_icons), iconsDesc, selected = state.hiddenIcons.isNotEmpty()) {
        sheet.dismiss()
        showIconSlotSheet(state.hiddenIcons)
    })

    toggleRow(
        getString(R.string.systweaks_animoff), getString(R.string.systweaks_animoff_desc), state.animOff,
        SysTweaks.buildAnim(if (state.animOff) SysTweaks.ANIM_DEFAULT else SysTweaks.ANIM_OFF),
        "settings get global window_animation_scale",
        if (state.animOff) SysTweaks.ANIM_DEFAULT else SysTweaks.ANIM_OFF
    )

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

/** Set + read-back verify; failures show the copyable command-error sheet. */
private fun MainActivity.applySysToggle(
    setCmd: String, verifyCmd: String, expect: String, reopen: () -> Unit
) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(setCmd)
        val readBack = if (res.ok) {
            runAdbCommandWithResult(verifyCmd).trim()
        } else {
            ""
        }
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (res.ok && readBack == expect) {
                UiToast.success(this@applySysToggle, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                val detail = if (!res.ok) res else ShellResult(res.code, "$setCmd\nread-back: '$readBack' (expected '$expect')")
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), setCmd, detail) { reopen() }
                fetchSystemStatuses()
            }
        }
    }
}

/** Per-slot status-bar icon picker (`secure icon_blacklist`). */
private fun MainActivity.showIconSlotSheet(hidden: Set<String>) {
    if (!hasShizukuPermission()) return
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.systweaks_icons_title))
        .message(getString(R.string.systweaks_icons_msg))
    SysTweaks.ICON_SLOTS.forEach { slot ->
        val isHidden = slot in hidden
        list.addView(optionRow(
            this,
            slot,
            getString(if (isHidden) R.string.systweaks_slot_hidden else R.string.systweaks_slot_shown),
            selected = isHidden
        ) {
            sheet.dismiss()
            val next = if (isHidden) hidden - slot else hidden + slot
            val value = SysTweaks.buildBlacklist(next)
            val cmd = if (next.isEmpty()) "settings delete ${SysTweaks.ICONS_NS} ${SysTweaks.ICONS_KEY}"
            else SysTweaks.buildSet(SysTweaks.ICONS_NS, SysTweaks.ICONS_KEY, value)
            val expect = if (next.isEmpty()) "" else value
            lifecycleScope.launch(Dispatchers.IO) {
                val res = ShizukuRunner.exec(cmd)
                // `settings delete` reads back empty/null; a put reads back the list.
                val raw = runAdbCommandWithResult("settings get ${SysTweaks.ICONS_NS} ${SysTweaks.ICONS_KEY}").trim()
                val readBack = if (raw == "null") "" else raw
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val got = SysTweaks.parseBlacklist(readBack)
                    if (res.ok && got == next) {
                        UiToast.success(this@showIconSlotSheet, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                    } else {
                        showCommandErrorSheet(
                            getString(R.string.dlg_cmd_failed_title), cmd,
                            ShellResult(res.code, "$cmd\nread-back: '$readBack' (expected '$expect')")
                        ) { showIconSlotSheet(hidden) }
                    }
                    fetchSystemStatuses()
                }
            }
        })
    }
    if (hidden.isNotEmpty()) {
        list.addView(infoCard(this, getString(R.string.systweaks_icons_active, hidden.sorted().joinToString(", "))))
    }
    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

internal fun MainActivity.resetSysTweaks() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusSysTweaks)?.text = getString(R.string.status_checking)
    runAdbCommand(SysTweaks.buildReset(), getString(R.string.msg_systweaks_reset)) { fetchSystemStatuses() }
}

/** Status-line summary: Default when nothing is active, otherwise "N of 8 on". */
internal fun MainActivity.sysTweaksStatusText(): String {
    val s = runCatching { readSysTweakState() }.getOrNull() ?: return getString(R.string.status_unknown)
    val n = s.activeCount()
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.systweaks_status_fmt, n, SysTweaks.TOTAL)
}
