package com.muimex11.official

import android.content.Context
import android.location.LocationManager
import android.nfc.NfcManager
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Batch-B network & radio tweaks (internet-research audit).
 *
 * - Captive portal ...... `global captive_portal_mode` (0 = ignore, 1 = prompt,
 *   2 = avoid; AOSP-verified). Kills a real source of Wi-Fi join stalls.
 * - Airplane mode ....... `cmd connectivity airplane-mode enable|disable`
 *   (Airplane-Scheduler-verified modern route; read back via the setting).
 * - Data roaming ........ `global data_roaming`.
 * - NFC ................. `svc nfc enable|disable` (NfcCommand-verified; reflection
 *   fallback unnecessary — read back via NfcManager, no permission needed).
 * - Location master ..... `cmd location set-location-enabled true|false`
 *   (LocationShellCommand-verified; read back via LocationManager, no permission).
 * - Radio mode lock ..... `cmd phone set-allowed-network-types-for-users <mask>`
 *   (EXPERIMENTAL, NetToggle-verified syntax; probe-gated — the sheet first
 *   checks `cmd phone help` and refuses to offer options on ROMs without it).
 *
 * Every apply is verified with a read-back except the experimental RAT lock
 * (exec success only); failures surface the copyable error sheet.
 */
object NetRadio {
    const val CAP_NS = "global"
    const val CAP_KEY = "captive_portal_mode"
    const val CAP_OFF = "0"
    const val CAP_PROMPT = "1"
    const val CAP_AVOID = "2"

    const val AIR_NS = "global"
    const val AIR_KEY = "airplane_mode_on"

    const val ROAM_NS = "global"
    const val ROAM_KEY = "data_roaming"

    const val LOC_CMD = "cmd location set-location-enabled"

    const val RAT_PROBE = "cmd phone help"
    const val RAT_SUBSTR = "set-allowed-network-types"
    // Bitmask presets from NetToggle (decimal RAF masks).
    const val RAT_4G_ONLY = "1000000000000"
    const val RAT_5G_ONLY = "10000000000000000000"
    const val RAT_AUTO = "11011111111111111111"

    const val TOTAL = 6

    fun buildSet(ns: String, key: String, value: String): String =
        "settings put $ns $key $value"

    fun buildAirplane(on: Boolean): String =
        "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}"

    fun buildNfc(on: Boolean): String =
        "svc nfc ${if (on) "enable" else "disable"}"

    fun buildLocation(on: Boolean): String =
        "$LOC_CMD ${if (on) "true" else "false"}"

    fun buildRat(mask: String): String =
        "cmd phone set-allowed-network-types-for-users $mask"

    fun ratSupported(helpOut: String): Boolean =
        helpOut.contains(RAT_SUBSTR)

    fun buildReset(): String = listOf(
        buildSet(CAP_NS, CAP_KEY, CAP_PROMPT),
        buildAirplane(false),
        buildSet(ROAM_NS, ROAM_KEY, "0"),
        buildNfc(true),
        buildLocation(true),
        buildRat(RAT_AUTO)
    ).joinToString(" && ")
}

private data class NetRadioState(
    val captive: String?,
    val airplane: Boolean,
    val roaming: Boolean,
    val nfc: Boolean,
    val location: Boolean,
    val ratMask: String?
) {
    fun activeCount(): Int {
        var n = 0
        if (captive != null && captive != NetRadio.CAP_PROMPT) n++
        if (airplane) n++
        if (roaming) n++
        if (!nfc) n++
        if (!location) n++
        if (ratMask != null && ratMask != NetRadio.RAT_AUTO) n++
        return n
    }
}

private fun MainActivity.readNetRadioState(): NetRadioState {
    fun get(ns: String, key: String): String? {
        val t = runAdbCommandWithResult("settings get $ns $key").trim()
        return if (t.isEmpty() || t.equals("null", ignoreCase = true)) null else t
    }
    val nfcOn = try {
        (getSystemService(Context.NFC_SERVICE) as? NfcManager)?.defaultAdapter?.isEnabled != false
    } catch (_: Exception) {
        true
    }
    val locOn = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getSystemService(LocationManager::class.java)?.isLocationEnabled != false
        } else {
            @Suppress("DEPRECATION")
            android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (_: Exception) {
        true
    }
    val rat = runAdbCommandWithResult("cmd phone get-allowed-network-types-for-users").trim()
        .takeIf { it.isNotEmpty() && !it.startsWith("Error", ignoreCase = true) && it != "null" }
    return NetRadioState(
        captive = get(NetRadio.CAP_NS, NetRadio.CAP_KEY),
        airplane = get(NetRadio.AIR_NS, NetRadio.AIR_KEY) == "1",
        roaming = get(NetRadio.ROAM_NS, NetRadio.ROAM_KEY) == "1",
        nfc = nfcOn,
        location = locOn,
        ratMask = rat
    )
}

private fun MainActivity.applyNetRadio(
    setCmd: String, verify: () -> Boolean, reopen: () -> Unit
) {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(setCmd)
        val okNow = res.ok && runCatching { verify() }.getOrDefault(false)
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (okNow) {
                UiToast.success(this@applyNetRadio, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                val detail = if (res.ok) ShellResult(res.code, "$setCmd\nread-back mismatch (ROM kept its own value)")
                else res
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), setCmd, detail) { reopen() }
                fetchSystemStatuses()
            }
        }
    }
}

internal fun MainActivity.showNetRadioSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val state = readNetRadioState()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderNetRadioSheet(state)
        }
    }
}

private fun MainActivity.renderNetRadioSheet(s: NetRadioState) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.title_netradio))
        .message(getString(R.string.desc_netradio))

    fun setOf(ns: String, key: String, v: String) = NetRadio.buildSet(ns, key, v)

    // Captive portal (3-mode sub-sheet)
    list.addView(optionRow(
        this, getString(R.string.netradio_captive), captiveLabel(s.captive),
        selected = s.captive != null && s.captive != NetRadio.CAP_PROMPT
    ) {
        sheet.dismiss()
        showCaptiveSheet(s.captive)
    })

    // Airplane (tournament mode building block)
    list.addView(optionRow(
        this, getString(R.string.netradio_airplane), getString(R.string.netradio_airplane_desc),
        selected = s.airplane
    ) {
        sheet.dismiss()
        applyNetRadio(NetRadio.buildAirplane(!s.airplane), {
            runAdbCommandWithResult("settings get ${NetRadio.AIR_NS} ${NetRadio.AIR_KEY}").trim() ==
                if (s.airplane) "0" else "1"
        }) { showNetRadioSheet() }
    })

    // Data roaming
    list.addView(optionRow(
        this, getString(R.string.netradio_roaming), getString(R.string.netradio_roaming_desc),
        selected = s.roaming
    ) {
        sheet.dismiss()
        val want = if (s.roaming) "0" else "1"
        applyNetRadio(setOf(NetRadio.ROAM_NS, NetRadio.ROAM_KEY, want), {
            runAdbCommandWithResult("settings get ${NetRadio.ROAM_NS} ${NetRadio.ROAM_KEY}").trim() == want
        }) { showNetRadioSheet() }
    })

    // NFC via svc (read back through the framework, no permission needed)
    list.addView(optionRow(
        this, getString(R.string.netradio_nfc), getString(R.string.netradio_nfc_desc),
        selected = !s.nfc
    ) {
        sheet.dismiss()
        applyNetRadio(NetRadio.buildNfc(!s.nfc), {
            ((getSystemService(Context.NFC_SERVICE) as? NfcManager)?.defaultAdapter?.isEnabled
                ?: !s.nfc) == !s.nfc
        }) { showNetRadioSheet() }
    })

    // Location master switch
    list.addView(optionRow(
        this, getString(R.string.netradio_location), getString(R.string.netradio_location_desc),
        selected = !s.location
    ) {
        sheet.dismiss()
        applyNetRadio(NetRadio.buildLocation(!s.location), {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (getSystemService(LocationManager::class.java)?.isLocationEnabled
                    ?: !s.location) == !s.location
            } else true
        }) { showNetRadioSheet() }
    })

    // Radio mode lock (experimental, probe-gated)
    list.addView(optionRow(
        this, getString(R.string.netradio_rat), getString(R.string.netradio_rat_desc),
        selected = s.ratMask != null && s.ratMask != NetRadio.RAT_AUTO
    ) {
        sheet.dismiss()
        showRatSheet()
    })

    sheet.content(list)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.captiveLabel(raw: String?): String = getString(when (raw) {
    NetRadio.CAP_OFF -> R.string.netradio_captive_off
    NetRadio.CAP_AVOID -> R.string.netradio_captive_avoid
    else -> R.string.netradio_captive_prompt
})

private fun MainActivity.showCaptiveSheet(current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.netradio_captive))
        .message(getString(R.string.netradio_captive_msg))
    listOf(
        NetRadio.CAP_OFF to R.string.netradio_captive_off,
        NetRadio.CAP_PROMPT to R.string.netradio_captive_prompt,
        NetRadio.CAP_AVOID to R.string.netradio_captive_avoid
    ).forEach { (v, label) ->
        list.addView(optionRow(this, getString(label), null, selected = (current ?: NetRadio.CAP_PROMPT) == v) {
            sheet.dismiss()
            val cmd = NetRadio.buildSet(NetRadio.CAP_NS, NetRadio.CAP_KEY, v)
            lifecycleScope.launch(Dispatchers.IO) {
                val res = ShizukuRunner.exec(cmd)
                val back = runAdbCommandWithResult("settings get ${NetRadio.CAP_NS} ${NetRadio.CAP_KEY}").trim()
                withContext(Dispatchers.Main) {
                    if (res.ok && back == v) {
                        UiToast.success(this@showCaptiveSheet, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                    } else {
                        showCommandErrorSheet(
                            getString(R.string.dlg_cmd_failed_title), cmd,
                            if (res.ok) ShellResult(res.code, "$cmd\nread-back: '$back'") else res
                        ) { showNetRadioSheet() }
                    }
                    fetchSystemStatuses()
                }
            }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.showRatSheet() {
    if (!hasShizukuPermission()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val help = ShizukuRunner.exec(NetRadio.RAT_PROBE)
        val supported = help.ok && NetRadio.ratSupported(help.out)
        val current = if (supported) {
            runAdbCommandWithResult("cmd phone get-allowed-network-types-for-users").trim()
                .takeIf { it.isNotEmpty() && it != "null" }
        } else null
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderRatSheet(supported, current)
        }
    }
}

private fun MainActivity.renderRatSheet(supported: Boolean, current: String?) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.netradio_rat))
        .message(getString(R.string.netradio_rat_msg))
    if (!supported) {
        list.addView(infoCard(this, getString(R.string.netradio_rat_unsupported)))
        sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
        return
    }
    if (current != null) {
        list.addView(infoCard(this, getString(R.string.netradio_rat_current, current)))
    }
    listOf(
        NetRadio.RAT_AUTO to R.string.netradio_rat_auto,
        NetRadio.RAT_4G_ONLY to R.string.netradio_rat_4g,
        NetRadio.RAT_5G_ONLY to R.string.netradio_rat_5g
    ).forEach { (mask, label) ->
        list.addView(optionRow(this, getString(label), mask, selected = current == mask) {
            sheet.dismiss()
            // Experimental: `cmd phone` prints nothing reliable to verify
            // against, so exec success is the only signal (honestly labelled).
            lifecycleScope.launch(Dispatchers.IO) {
                val res = ShizukuRunner.exec(NetRadio.buildRat(mask))
                withContext(Dispatchers.Main) {
                    if (res.ok) {
                        UiToast.success(this@renderRatSheet, getString(R.string.msg_systweak_applied), Toast.LENGTH_SHORT).show()
                    } else {
                        showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), NetRadio.buildRat(mask), res) {
                            showRatSheet()
                        }
                    }
                    fetchSystemStatuses()
                }
            }
        })
    }
    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

internal fun MainActivity.resetNetRadio() {
    if (!hasShizukuPermission()) return
    findViewById<TextView>(R.id.tvStatusNetRadio)?.text = getString(R.string.status_checking)
    runAdbCommand(NetRadio.buildReset(), getString(R.string.msg_netradio_reset)) { fetchSystemStatuses() }
}

/** Status-line summary: Default when stock, otherwise "N of 6 changed". */
internal fun MainActivity.netRadioStatusText(): String {
    val s = runCatching { readNetRadioState() }.getOrNull() ?: return getString(R.string.status_unknown)
    val n = s.activeCount()
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.netradio_status_fmt, n, NetRadio.TOTAL)
}
