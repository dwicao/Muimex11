package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

internal fun MainActivity.dexHzStatusText(): String {
    val st = DexHzBridge.state.value
    if (st != null) {
        if (!st.running) return getString(R.string.status_default)
        val mon = st.monitor
        return when {
            st.reconnecting -> getString(R.string.dexhz_status_reconnecting)
            st.appHz > 0f && mon != null ->
                getString(R.string.dexhz_status_active_mon, st.appHz.roundToInt(), mon.linkHz.roundToInt())
            st.appHz > 0f -> getString(R.string.dexhz_status_active, st.appHz.roundToInt())
            else -> getString(R.string.status_enabled)
        }
    }
    return if (DexHzBridge.getWantOn(this)) getString(R.string.status_enabled) else getString(R.string.status_default)
}

internal fun MainActivity.isDexHzActive(): Boolean {
    val st = DexHzBridge.state.value
    return st?.running ?: DexHzBridge.getWantOn(this)
}

internal fun MainActivity.showDexHzSheet() {
    if (!hasShizukuPermission()) return
    val act = this
    val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    val statusBanner = infoCard(act, getString(R.string.dexhz_connecting))
    content.addView(statusBanner)

    val masterSwitch = MaterialSwitch(act).apply {
        text = getString(R.string.dexhz_switch_title)
        isChecked = DexHzBridge.state.value?.running ?: DexHzBridge.getWantOn(act)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }
    content.addView(masterSwitch)

    // Mode switch confirmation banner (Keep / Undo)
    val confirmBox = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }
    val confirmText = infoCard(act, "", 0xFFE09B00.toInt())
    confirmBox.addView(confirmText)
    val confirmButtons = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
    val btnUndoMode = MaterialButton(act).apply {
        text = getString(R.string.btn_revert)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        }
    }
    val btnKeepMode = MaterialButton(act).apply {
        text = getString(R.string.btn_keep)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        }
    }
    confirmButtons.addView(btnUndoMode)
    confirmButtons.addView(btnKeepMode)
    confirmBox.addView(confirmButtons)
    content.addView(confirmBox)

    // Target Hz header + presets container
    val targetLabel = TextView(act).apply {
        text = getString(R.string.dexhz_target_title, getString(R.string.dexhz_fastest))
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurface(act))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }
    content.addView(targetLabel)

    val stopsContainer = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }
    content.addView(stopsContainer)

    val smoothHint = TextView(act).apply {
        textSize = 12f
        setTextColor(Ui.onSurfaceVariant(act))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }
    content.addView(smoothHint)

    // Suggestion row (one-tap faster mode)
    val suggestContainer = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    content.addView(suggestContainer)

    // Monitor Mode & Reconnect rows
    val actionsContainer = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    content.addView(actionsContainer)

    val autoReconnectSwitch = MaterialSwitch(act).apply {
        text = getString(R.string.dexhz_fix_slow)
        isChecked = DexHzBridge.getAutoReconnect(act)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }
    val coolDownSwitch = MaterialSwitch(act).apply {
        text = getString(R.string.dexhz_cool_down)
        isChecked = DexHzBridge.getCoolDown(act)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }
    val restoreSwitch = MaterialSwitch(act).apply {
        text = getString(R.string.dexhz_restore_auto)
        isChecked = DexHzBridge.getRestoreOnLaunch(act)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }
    content.addView(autoReconnectSwitch)
    content.addView(coolDownSwitch)
    content.addView(restoreSwitch)

    var latestState: DexHzLogic.DexHzState? = DexHzBridge.state.value
    var updatingUi = false
    var pollJob: Job? = null

    val sheet = Sheet(act)
        .icon(R.drawable.ic_refresh_rate)
        .title(getString(R.string.title_dexhz))
        .message(getString(R.string.dexhz_sheet_msg))
        .content(content)
        .button(getString(R.string.dexhz_btn_diagnostics), SheetStyle.TONAL, dismiss = false) {
            showDexHzDiagnosticsSheet(latestState)
        }
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .onDismiss { pollJob?.cancel() }
        .show()

    fun buildStopChips(stops: List<Int>, currentTarget: Float, enabled: Boolean) {
        stopsContainer.removeAllViews()
        val displayStops = if (stops.isNotEmpty()) stops else listOf(24, 30, 48, 60, 80, 120, 240)
        val labels = displayStops.map { "${it}Hz" } + getString(R.string.dexhz_fastest)
        labels.chunked(4).forEachIndexed { rowIdx, chunk ->
            val row = presetRow(act, chunk) { idx ->
                val globalIdx = rowIdx * 4 + idx
                val chosenHz = if (globalIdx < displayStops.size) displayStops[globalIdx].toFloat() else 0f
                DexHzBridge.setTargetHz(act, chosenHz)
                lifecycleScope.launch {
                    DexHzBridge.withService(act) { svc ->
                        DexHzBridge.pushSettings(act, svc)
                        null
                    }
                    withContext(Dispatchers.Main) {
                        val label = if (chosenHz <= 0f) getString(R.string.dexhz_fastest) else "${chosenHz.roundToInt()} Hz"
                        UiToast.success(act, getString(R.string.dexhz_target_set, label), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            for (i in 0 until row.childCount) {
                val btn = row.getChildAt(i) as? MaterialButton ?: continue
                btn.isEnabled = enabled
                val globalIdx = rowIdx * 4 + i
                val isSelected = if (globalIdx < displayStops.size) {
                    currentTarget > 0f && kotlin.math.abs(currentTarget - displayStops[globalIdx]) < 1f
                } else {
                    currentTarget <= 0f || currentTarget >= 1000f
                }
                if (isSelected) {
                    btn.strokeWidth = dp(2)
                    btn.strokeColor = android.content.res.ColorStateList.valueOf(Ui.accent(act))
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (rowIdx > 0) dp(6) else 0 }
            stopsContainer.addView(row)
        }
    }

    fun renderState(st: DexHzLogic.DexHzState?) {
        latestState = st
        updatingUi = true
        try {
            if (st == null) {
                statusBanner.text = getString(R.string.dexhz_connecting)
                masterSwitch.isEnabled = false
                buildStopChips(emptyList(), DexHzBridge.getTargetHz(act), false)
                return
            }
            masterSwitch.isEnabled = true
            masterSwitch.isChecked = st.running
            if (st.rolledBack != null) DexHzBridge.setWantOn(act, false)

            val mon = st.monitor
            val compat = st.caps?.level() ?: DexHzLogic.CompatLevel.UNAVAILABLE
            val compatLabel = when (compat) {
                DexHzLogic.CompatLevel.FULL -> getString(R.string.dexhz_compat_full)
                DexHzLogic.CompatLevel.PARTIAL -> getString(R.string.dexhz_compat_partial)
                DexHzLogic.CompatLevel.LIMITED -> getString(R.string.dexhz_compat_limited)
                DexHzLogic.CompatLevel.UNAVAILABLE -> getString(R.string.dexhz_compat_unavail)
            }

            val summary = buildString {
                append(compatLabel)
                if (mon != null) {
                    append("\n")
                    append(getString(R.string.dexhz_mon_line, mon.name, mon.width, mon.height, mon.linkHz.roundToInt()))
                    if (mon.fasterListed) {
                        append(" · ").append(getString(R.string.dexhz_mon_fastest_hint, mon.fastestHz.roundToInt()))
                    }
                } else {
                    append("\n").append(getString(R.string.dexhz_no_monitor))
                }
                if (st.running) {
                    append("\n")
                    when {
                        st.reconnecting -> append(getString(R.string.dexhz_state_reconnecting))
                        st.waiting -> append(getString(R.string.dexhz_state_waiting))
                        st.appHz > 0f -> append(getString(R.string.dexhz_state_running, st.appHz.roundToInt()))
                        else -> append(getString(R.string.status_enabled))
                    }
                    st.coolingHz?.let { append(" · ").append(getString(R.string.dexhz_state_cooling, it.roundToInt())) }
                }
                val extraMsg = st.rolledBack ?: st.notice ?: st.modeResult
                if (!extraMsg.isNullOrBlank()) {
                    append("\n").append(extraMsg)
                }
            }
            statusBanner.text = summary

            val savedTarget = DexHzBridge.getTargetHz(act)
            val targetName = if (savedTarget <= 0f || savedTarget >= 1000f) getString(R.string.dexhz_fastest)
            else "${savedTarget.roundToInt()} Hz"
            targetLabel.text = getString(R.string.dexhz_target_title, targetName)
            buildStopChips(st.stops, savedTarget, st.running)

            if (mon != null && st.running && savedTarget > 0f && savedTarget < mon.linkHz - 1f && !DexHzLogic.smooth(mon.linkHz, savedTarget)) {
                smoothHint.text = getString(R.string.dexhz_uneven_hint, savedTarget.roundToInt(), mon.linkHz.roundToInt())
                smoothHint.visibility = View.VISIBLE
            } else if (mon?.lowerResHint != null && mon.linkHz <= 61f) {
                smoothHint.text = getString(R.string.dexhz_lower_res_hint, mon.lowerResHint)
                smoothHint.visibility = View.VISIBLE
            } else {
                smoothHint.visibility = View.GONE
            }

            // Mode switch confirm banner
            val sw = st.modeSwitch
            if (sw != null && (sw.confirm || sw.replug)) {
                confirmBox.visibility = View.VISIBLE
                confirmText.text = if (sw.confirm) {
                    getString(R.string.dexhz_confirm_mode_msg, sw.shortLabel, sw.secondsLeft)
                } else {
                    getString(R.string.dexhz_replug_mode_msg, sw.shortLabel, sw.secondsLeft)
                }
                btnKeepMode.visibility = if (sw.confirm) View.VISIBLE else View.GONE
            } else {
                confirmBox.visibility = View.GONE
            }

            // Suggestion
            suggestContainer.removeAllViews()
            val sug = mon?.suggest
            if (sug != null && st.running && sw == null) {
                suggestContainer.visibility = View.VISIBLE
                suggestContainer.addView(optionRow(
                    act,
                    getString(R.string.dexhz_suggest_title, sug.label),
                    if (sug.reason == "slow") getString(R.string.dexhz_suggest_slow) else getString(R.string.dexhz_suggest_fast),
                    icon = Ui.drawable(act, R.drawable.ic_boost)
                ) {
                    lifecycleScope.launch {
                        val msg = DexHzBridge.withService(act) { svc ->
                            svc.switchMonitorMode(mon.id, sug.width, sug.height, sug.hz)
                        }
                        withContext(Dispatchers.Main) {
                            if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            } else {
                suggestContainer.visibility = View.GONE
            }

            // Action rows (Monitor Mode, Forget Start Mode, Reconnect)
            actionsContainer.removeAllViews()
            if (mon != null && st.running) {
                if (st.caps?.monitorModes == true && mon.modeChoices.isNotEmpty()) {
                    actionsContainer.addView(optionRow(
                        act,
                        getString(R.string.dexhz_monitor_mode_title),
                        mon.startMode?.let { getString(R.string.dexhz_start_mode_fmt, it) }
                            ?: getString(R.string.dexhz_monitor_mode_desc),
                        icon = Ui.drawable(act, R.drawable.ic_tune)
                    ) {
                        showDexHzModePickerSheet(mon)
                    })
                }
                if (mon.startMode != null) {
                    actionsContainer.addView(optionRow(
                        act,
                        getString(R.string.dexhz_use_default_mode),
                        getString(R.string.dexhz_use_default_desc),
                        icon = Ui.drawable(act, R.drawable.ic_reset)
                    ) {
                        lifecycleScope.launch {
                            val msg = DexHzBridge.withService(act) { svc -> svc.forgetMonitorStartMode() }
                            withContext(Dispatchers.Main) {
                                if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
                actionsContainer.addView(optionRow(
                    act,
                    getString(R.string.dexhz_reconnect_title),
                    getString(R.string.dexhz_reconnect_desc),
                    icon = Ui.drawable(act, R.drawable.ic_refresh)
                ) {
                    lifecycleScope.launch {
                        val msg = DexHzBridge.withService(act) { svc -> svc.reconnectMonitor() }
                        withContext(Dispatchers.Main) {
                            if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        } finally {
            updatingUi = false
        }
    }

    masterSwitch.setOnCheckedChangeListener { _, checked ->
        if (updatingUi) return@setOnCheckedChangeListener
        lifecycleScope.launch {
            val msg = DexHzBridge.withService(act) { svc ->
                if (checked) DexHzBridge.startWithSavedPrefs(act, svc)
                else DexHzBridge.stopAndForget(act, svc)
            }
            val fresh = withContext(Dispatchers.IO) { DexHzBridge.refresh(act) }
            withContext(Dispatchers.Main) {
                renderState(fresh)
                fetchSystemStatuses()
                if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    btnKeepMode.setOnClickListener {
        lifecycleScope.launch {
            val msg = DexHzBridge.withService(act) { svc -> svc.confirmMonitorMode(true) }
            val fresh = withContext(Dispatchers.IO) { DexHzBridge.refresh(act) }
            withContext(Dispatchers.Main) {
                renderState(fresh)
                if (!msg.isNullOrBlank()) UiToast.success(act, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    btnUndoMode.setOnClickListener {
        lifecycleScope.launch {
            val msg = DexHzBridge.withService(act) { svc -> svc.confirmMonitorMode(false) }
            val fresh = withContext(Dispatchers.IO) { DexHzBridge.refresh(act) }
            withContext(Dispatchers.Main) {
                renderState(fresh)
                fetchSystemStatuses()
                if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    autoReconnectSwitch.setOnCheckedChangeListener { _, checked ->
        DexHzBridge.setAutoReconnect(act, checked)
        lifecycleScope.launch {
            DexHzBridge.withService(act) { svc -> DexHzBridge.pushSettings(act, svc); null }
        }
    }

    coolDownSwitch.setOnCheckedChangeListener { _, checked ->
        DexHzBridge.setCoolDown(act, checked)
        lifecycleScope.launch {
            DexHzBridge.withService(act) { svc -> DexHzBridge.pushSettings(act, svc); null }
        }
    }

    restoreSwitch.setOnCheckedChangeListener { _, checked ->
        DexHzBridge.setRestoreOnLaunch(act, checked)
    }

    // Initial connect + live 1s polling while sheet is open
    renderState(latestState)
    pollJob = lifecycleScope.launch {
        DexHzBridge.withService(act) { svc ->
            DexHzBridge.pushSettings(act, svc)
            null
        }
        while (isActive && sheet.isShowing()) {
            val fresh = withContext(Dispatchers.IO) { DexHzBridge.refresh(act) }
            renderState(fresh)
            delay(1_000L)
        }
    }
}

private fun MainActivity.showDexHzModePickerSheet(mon: DexHzLogic.MonitorInfo) {
    val act = this
    val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(act)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dexhz_monitor_mode_title))
        .message(getString(R.string.dexhz_monitor_mode_msg))

    mon.modeChoices.forEach { choice ->
        val selected = mon.modeKey == choice.key()
        list.addView(optionRow(act, choice.label(), null, selected = selected) {
            sheet.dismiss()
            lifecycleScope.launch {
                val msg = DexHzBridge.withService(act) { svc ->
                    svc.switchMonitorMode(mon.id, choice.width, choice.height, choice.hz)
                }
                withContext(Dispatchers.Main) {
                    if (!msg.isNullOrBlank()) UiToast.info(act, msg, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    sheet.content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .show()
}

private fun MainActivity.showDexHzDiagnosticsSheet(state: DexHzLogic.DexHzState?) {
    val act = this
    lifecycleScope.launch(Dispatchers.IO) {
        val diagJson = try {
            DexHzBridge.currentService()?.getDiagnostics(false)
        } catch (_: Exception) { null }
        val reportText = buildString {
            appendLine("=== DeX Hz Unlock Diagnostics ===")
            if (state == null) {
                appendLine("Service: Not connected")
            } else {
                appendLine("Device: ${state.device}")
                appendLine("UID: ${state.uid} (${if (state.uid == 0) "root" else "ADB shell"})")
                state.caps?.let { c ->
                    appendLine("Compatibility: ${c.level()}")
                    appendLine("High-res switch: ${c.highRes}")
                    appendLine("Monitor timing: ${c.monitorTiming} (verified=${c.monitorTimingVerified}, codes=${c.codes})")
                    appendLine("Phone panel rates: ${c.phoneRates}")
                    appendLine("Mode method: ${c.modeMethod} (IDisplayConfig=${c.displayConfig})")
                }
                state.monitor?.let { m ->
                    appendLine("Monitor: ${m.name} ${m.width}x${m.height} @ ${m.linkHz.roundToInt()} Hz (fastest ${m.fastestHz.roundToInt()} Hz)")
                    if (m.modesSummary.isNotBlank()) appendLine("Fastest per size: ${m.modesSummary}")
                } ?: appendLine("Monitor: none")
                appendLine("Stops: ${state.stops}")
            }
            if (!diagJson.isNullOrBlank()) {
                runCatching {
                    val obj = JSONObject(diagJson)
                    val events = obj.optJSONArray("events")
                    if (events != null && events.length() > 0) {
                        appendLine("\n--- Session Events ---")
                        for (i in 0 until events.length()) {
                            appendLine(events.optString(i))
                        }
                    }
                    if (obj.has("crash")) {
                        appendLine("\n--- Saved Service Crash ---")
                        appendLine(obj.optString("crash"))
                    }
                }
            }
        }.trim()

        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            val tv = TextView(act).apply {
                text = reportText
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Ui.onSurfaceVariant(act))
                setTextIsSelectable(true)
            }
            Sheet(act)
                .icon(R.drawable.ic_terminal)
                .title(getString(R.string.dexhz_btn_diagnostics))
                .content(tv)
                .button(getString(R.string.shell_btn_copy), SheetStyle.PRIMARY, dismiss = false) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dexhz-report", reportText))
                    UiToast.success(act, getString(R.string.shell_copied), Toast.LENGTH_SHORT).show()
                }
                .button(getString(R.string.btn_close), SheetStyle.TONAL)
                .show()
        }
    }
}

internal fun MainActivity.resetDexHz(onDone: (() -> Unit)? = null) {
    DexHzBridge.setWantOn(this, false)
    if (!ShizukuRunner.isReady()) {
        fetchSystemStatuses()
        onDone?.invoke()
        return
    }
    lifecycleScope.launch {
        val msg = DexHzBridge.withService(this@resetDexHz) { svc ->
            DexHzBridge.stopAndForget(this@resetDexHz, svc)
        }
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            UiToast.success(
                this@resetDexHz,
                msg ?: getString(R.string.msg_dexhz_reset),
                Toast.LENGTH_SHORT
            ).show()
            fetchSystemStatuses()
            onDone?.invoke()
        }
    }
}
