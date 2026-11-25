package com.muimex11.official

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Pure 16:9 screen mirroring logic — unit-testable, no Android deps. */
object MirrorLogic {
    const val DEFAULT_DPI = 240
    const val MIN_DPI = 100
    const val MAX_DPI = 400

    data class DisplayState(
        val physicalWidth: Int = 1080,
        val physicalHeight: Int = 2400,
        val overrideWidth: Int? = null,
        val overrideHeight: Int? = null,
        val physicalDensity: Int = 420,
        val overrideDensity: Int? = null,
        val is16x9Active: Boolean = false
    ) {
        val currentWidth: Int get() = overrideWidth ?: physicalWidth
        val currentHeight: Int get() = overrideHeight ?: physicalHeight
        val currentDensity: Int get() = overrideDensity ?: physicalDensity
        val isOverrideApplied: Boolean
            get() = overrideWidth != null || overrideHeight != null || overrideDensity != null
    }

    fun is16x9Ratio(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = height.toDouble() / width.toDouble()
        val port = abs(ratio - (16.0 / 9.0)) < 0.05
        val land = abs((1.0 / ratio) - (16.0 / 9.0)) < 0.05
        return port || land
    }

    fun clampDpi(dpi: Int): Int = dpi.coerceIn(MIN_DPI, MAX_DPI)

    fun isValidDimensions(width: Int, height: Int): Boolean =
        width in 320..8192 && height in 320..8192

    fun parseWmOutput(sizeOutput: String, densityOutput: String): DisplayState {
        var pWidth = 1080
        var pHeight = 2400
        var oWidth: Int? = null
        var oHeight: Int? = null

        sizeOutput.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Physical size:", ignoreCase = true)) {
                val parts = trimmed.substringAfter(":").trim().split("x")
                if (parts.size == 2) {
                    pWidth = parts[0].toIntOrNull() ?: pWidth
                    pHeight = parts[1].toIntOrNull() ?: pHeight
                }
            } else if (trimmed.startsWith("Override size:", ignoreCase = true)) {
                val parts = trimmed.substringAfter(":").trim().split("x")
                if (parts.size == 2) {
                    oWidth = parts[0].toIntOrNull()
                    oHeight = parts[1].toIntOrNull()
                }
            }
        }

        var pDensity = 420
        var oDensity: Int? = null

        densityOutput.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Physical density:", ignoreCase = true)) {
                pDensity = trimmed.substringAfter(":").trim().toIntOrNull() ?: pDensity
            } else if (trimmed.startsWith("Override density:", ignoreCase = true)) {
                oDensity = trimmed.substringAfter(":").trim().toIntOrNull()
            }
        }

        val is16x9 = if (oWidth != null && oHeight != null) is16x9Ratio(oWidth!!, oHeight!!) else false
        return DisplayState(
            physicalWidth = pWidth,
            physicalHeight = pHeight,
            overrideWidth = oWidth,
            overrideHeight = oHeight,
            physicalDensity = pDensity,
            overrideDensity = oDensity,
            is16x9Active = is16x9
        )
    }

    fun buildApplyCmd(width: Int, height: Int, density: Int): String =
        "wm size ${width}x${height} && wm density ${clampDpi(density)}"

    fun buildDensityCmd(density: Int): String =
        "wm density ${clampDpi(density)}"

    fun buildResetCmd(): String =
        "wm size reset && wm density reset"
}

data class ExternalDisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int
)

object MirrorDisplayManager {
    private const val CHANNEL_ID = "muimex_mirror"
    private const val NOTIFICATION_ID = 4302
    const val ACTION_RESTORE_DEFAULT = "com.muimex11.official.ACTION_MIRROR_RESTORE"
    const val ACTION_APPLY_16_9 = "com.muimex11.official.ACTION_MIRROR_APPLY_16_9"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(MirrorLogic.DisplayState())
    val state: StateFlow<MirrorLogic.DisplayState> = _state

    @Volatile private var listenerRegistered = false
    @Volatile private var hadExternal = false
    @Volatile private var notificationShowing = false

    fun getExternalDisplay(context: Context): ExternalDisplayInfo? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            for (display in dm.displays) {
                // Skip built-in default display (0) and Z Flip cover screen (1).
                if (display.displayId != Display.DEFAULT_DISPLAY && display.displayId != 1) {
                    val mode = display.mode
                    return ExternalDisplayInfo(
                        id = display.displayId,
                        name = display.name ?: context.getString(R.string.mirror_ext_default_name),
                        width = mode.physicalWidth,
                        height = mode.physicalHeight
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        if (listenerRegistered) return
        try {
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            hadExternal = getExternalDisplay(app) != null
            dm.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    if (getExternalDisplay(app) != null) hadExternal = true
                }

                override fun onDisplayChanged(displayId: Int) {}

                override fun onDisplayRemoved(displayId: Int) {
                    val connectedNow = getExternalDisplay(app) != null
                    if (hadExternal && !connectedNow) {
                        hadExternal = false
                        if (isAutoRestoreEnabled(app) && _state.value.isOverrideApplied && ShizukuRunner.isReady()) {
                            resetToDefault(app) {
                                mainHandler.post {
                                    UiToast.info(app, app.getString(R.string.mirror_auto_restored), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }, mainHandler)
            listenerRegistered = true
        } catch (e: Exception) {
            logQuietly("MirrorDisplayManager", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.mirror_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.mirror_notif_channel_desc)
            }
        )
    }

    fun getPreferredDpi(context: Context): Int {
        val prefs = context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        return MirrorLogic.clampDpi(prefs.getInt(Prefs.KEY_MIRROR_DPI, MirrorLogic.DEFAULT_DPI))
    }

    fun setPreferredDpi(context: Context, dpi: Int) {
        val prefs = context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        prefs.edit().putInt(Prefs.KEY_MIRROR_DPI, MirrorLogic.clampDpi(dpi)).apply()
    }

    fun isAutoRestoreEnabled(context: Context): Boolean =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_MIRROR_AUTO_RESTORE, true)

    fun setAutoRestoreEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_MIRROR_AUTO_RESTORE, enabled).apply()
    }

    fun refreshStateSync(context: Context): MirrorLogic.DisplayState {
        val d = ShizukuRunner.runTagged(listOf("size" to "wm size", "density" to "wm density"))
        val newState = MirrorLogic.parseWmOutput(
            ShellBatcher.value(d, "size"),
            ShellBatcher.value(d, "density")
        )
        val changed = _state.value != newState
        _state.value = newState
        if (newState.isOverrideApplied) {
            if (changed || !notificationShowing) {
                showPersistentNotification(context.applicationContext, newState)
            }
        } else if (notificationShowing) {
            dismissNotification(context.applicationContext)
        }
        return newState
    }

    fun refreshState(context: Context, onComplete: ((MirrorLogic.DisplayState) -> Unit)? = null) {
        scope.launch {
            val st = if (ShizukuRunner.isReady()) refreshStateSync(context) else _state.value
            withContext(Dispatchers.Main) { onComplete?.invoke(st) }
        }
    }

    fun applyResolution(
        context: Context,
        width: Int,
        height: Int,
        density: Int = getPreferredDpi(context),
        onDone: ((Boolean, MirrorLogic.DisplayState) -> Unit)? = null
    ) {
        setPreferredDpi(context, density)
        scope.launch {
            val ok = ShizukuRunner.execOk(MirrorLogic.buildApplyCmd(width, height, density))
            val st = refreshStateSync(context)
            withContext(Dispatchers.Main) { onDone?.invoke(ok, st) }
        }
    }

    fun applyDensityOnly(
        context: Context,
        density: Int,
        onDone: ((Boolean, MirrorLogic.DisplayState) -> Unit)? = null
    ) {
        setPreferredDpi(context, density)
        scope.launch {
            val ok = ShizukuRunner.execOk(MirrorLogic.buildDensityCmd(density))
            val st = refreshStateSync(context)
            withContext(Dispatchers.Main) { onDone?.invoke(ok, st) }
        }
    }

    fun resetToDefault(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        scope.launch {
            val ok = ShizukuRunner.execOk(MirrorLogic.buildResetCmd())
            refreshStateSync(context)
            dismissNotification(context.applicationContext)
            withContext(Dispatchers.Main) { onDone?.invoke(ok) }
        }
    }

    private fun showPersistentNotification(context: Context, state: MirrorLogic.DisplayState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piContent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(context, MirrorRestoreReceiver::class.java).apply {
            action = ACTION_RESTORE_DEFAULT
        }
        val piRestore = PendingIntent.getBroadcast(
            context, 1, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val actionLabel = context.getString(R.string.btn_restore)
        val actionIcon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_reset)
        val action = Notification.Action.Builder(actionIcon, actionLabel, piRestore).build()

        val notif = builder
            .setSmallIcon(R.drawable.ic_resolution)
            .setContentTitle(context.getString(R.string.mirror_notif_title))
            .setContentText(context.getString(R.string.mirror_notif_body, state.currentWidth, state.currentHeight, state.currentDensity))
            .setOngoing(true)
            .setContentIntent(piContent)
            .addAction(action)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notif)
            notificationShowing = true
        } catch (e: Exception) {
            logQuietly("MirrorDisplayManager", e)
        }
    }

    fun dismissNotification(context: Context) {
        notificationShowing = false
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            logQuietly("MirrorDisplayManager", e)
        }
    }
}

class MirrorRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ShizukuRunner.isReady()) return
        when (intent.action) {
            MirrorDisplayManager.ACTION_RESTORE_DEFAULT -> {
                MirrorDisplayManager.resetToDefault(context) { ok ->
                    if (ok) UiToast.success(context, context.getString(R.string.mirror_restored_toast), Toast.LENGTH_SHORT).show()
                }
            }
            MirrorDisplayManager.ACTION_APPLY_16_9 -> {
                MirrorDisplayManager.applyResolution(context, 1080, 1920) { ok, _ ->
                    if (ok) UiToast.success(context, context.getString(R.string.mirror_applied_port), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

internal fun MainActivity.mirrorStatusText(state: MirrorLogic.DisplayState): String {
    return if (state.overrideWidth != null && state.overrideHeight != null) {
        if (state.is16x9Active) {
            val mode = if (state.overrideHeight > state.overrideWidth) getString(R.string.mirror_badge_port)
            else getString(R.string.mirror_badge_land)
            "$mode (${state.overrideWidth}×${state.overrideHeight})"
        } else {
            "${state.overrideWidth}×${state.overrideHeight}"
        }
    } else if (state.overrideDensity != null) {
        "${state.currentDensity} DPI"
    } else {
        getString(R.string.status_default)
    }
}

internal fun MainActivity.showMirrorDisplaySheet() {
    if (!hasShizukuPermission()) return
    MirrorDisplayManager.init(this)
    val act = this
    lifecycleScope.launch(Dispatchers.IO) {
        val state = MirrorDisplayManager.refreshStateSync(act)
        val ext = MirrorDisplayManager.getExternalDisplay(act)
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            renderMirrorSheet(state, ext)
        }
    }
}

private fun MainActivity.renderMirrorSheet(initialState: MirrorLogic.DisplayState, ext: ExternalDisplayInfo?) {
    val act = this
    var state = initialState
    var preferredDpi = MirrorDisplayManager.getPreferredDpi(act)

    val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    // External display status card
    val extMsg = if (ext != null) {
        getString(R.string.mirror_ext_connected, ext.name, ext.width, ext.height)
    } else {
        getString(R.string.mirror_ext_waiting)
    }
    val extCard = infoCard(act, extMsg, if (ext != null) 0xFF2E9E5B.toInt() else null)
    content.addView(extCard)

    // Current state card
    val stateCard = infoCard(act, "")
    stateCard.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }
    content.addView(stateCard)

    // Auto-restore switch
    val autoRestoreSwitch = MaterialSwitch(act).apply {
        text = getString(R.string.mirror_auto_restore)
        isChecked = MirrorDisplayManager.isAutoRestoreEnabled(act)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }
    autoRestoreSwitch.setOnCheckedChangeListener { _, checked ->
        MirrorDisplayManager.setAutoRestoreEnabled(act, checked)
    }
    content.addView(autoRestoreSwitch)

    // DPI controller header + slider
    val dpiHeader = TextView(act).apply {
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurface(act))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }
    content.addView(dpiHeader)

    val dpiSliderRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }
    val btnDpiMinus = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "-10"
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
    }
    val dpiSlider = Slider(act).apply {
        valueFrom = MirrorLogic.MIN_DPI.toFloat()
        valueTo = MirrorLogic.MAX_DPI.toFloat()
        stepSize = 10f
        value = preferredDpi.toFloat()
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
    }
    val btnDpiPlus = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = "+10"
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
    }
    dpiSliderRow.addView(btnDpiMinus)
    dpiSliderRow.addView(dpiSlider)
    dpiSliderRow.addView(btnDpiPlus)
    content.addView(dpiSliderRow)

    // DPI quick chips (120, 160, 200, 240★, 280, 320, 360, 400)
    val chipValues = listOf(120, 160, 200, 240, 280, 320, 360, 400)
    val chipLabels = chipValues.map { if (it == 240) "240★" else "$it" }
    val applyDpiBtn = MaterialButton(act).apply {
        isAllCaps = false
        cornerRadius = dp(14)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(8) }
    }

    fun syncDpiUi(dpi: Int) {
        preferredDpi = MirrorLogic.clampDpi(dpi)
        MirrorDisplayManager.setPreferredDpi(act, preferredDpi)
        val tag = if (preferredDpi == MirrorLogic.DEFAULT_DPI) " ★" else ""
        dpiHeader.text = getString(R.string.mirror_dpi_title, preferredDpi, tag)
        dpiSlider.value = (preferredDpi / 10 * 10).toFloat().coerceIn(dpiSlider.valueFrom, dpiSlider.valueTo)
        applyDpiBtn.text = getString(R.string.mirror_apply_dpi_btn, preferredDpi)
    }

    chipLabels.chunked(4).forEachIndexed { rowIdx, chunk ->
        val row = presetRow(act, chunk) { i ->
            syncDpiUi(chipValues[rowIdx * 4 + i])
        }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        content.addView(row)
    }
    content.addView(applyDpiBtn)

    dpiSlider.addOnChangeListener { _, value, fromUser ->
        if (fromUser) syncDpiUi(value.toInt())
    }
    btnDpiMinus.setOnClickListener { syncDpiUi(preferredDpi - 10) }
    btnDpiPlus.setOnClickListener { syncDpiUi(preferredDpi + 10) }

    // Presets list
    val presetsTitle = TextView(act).apply {
        text = getString(R.string.mirror_presets_title)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurface(act))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }
    content.addView(presetsTitle)

    val presetsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    content.addView(presetsBox)

    lateinit var sheet: Sheet

    fun refreshSheetUi(newState: MirrorLogic.DisplayState) {
        state = newState
        val badge = if (state.isOverrideApplied) getString(R.string.mirror_state_override) else getString(R.string.mirror_state_native)
        stateCard.text = getString(
            R.string.mirror_state_summary,
            badge,
            state.currentWidth, state.currentHeight,
            state.currentDensity,
            state.physicalWidth, state.physicalHeight
        )
        presetsBox.removeAllViews()
        presetsBox.addView(optionRow(
            act,
            getString(R.string.mirror_preset_port),
            getString(R.string.mirror_preset_port_desc, preferredDpi),
            icon = Ui.drawable(act, R.drawable.ic_resolution),
            selected = state.overrideWidth == 1080 && state.overrideHeight == 1920
        ) {
            sheet.dismiss()
            MirrorDisplayManager.applyResolution(act, 1080, 1920, preferredDpi) { ok, _ ->
                if (ok) UiToast.success(act, getString(R.string.mirror_applied_port), Toast.LENGTH_SHORT).show()
                else UiToast.error(act, getString(R.string.toast_res_fail), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            }
        })
        presetsBox.addView(optionRow(
            act,
            getString(R.string.mirror_preset_land),
            getString(R.string.mirror_preset_land_desc, preferredDpi),
            icon = Ui.drawable(act, R.drawable.ic_resolution),
            selected = state.overrideWidth == 1920 && state.overrideHeight == 1080
        ) {
            sheet.dismiss()
            MirrorDisplayManager.applyResolution(act, 1920, 1080, preferredDpi) { ok, _ ->
                if (ok) UiToast.success(act, getString(R.string.mirror_applied_land), Toast.LENGTH_SHORT).show()
                else UiToast.error(act, getString(R.string.toast_res_fail), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            }
        })
        presetsBox.addView(optionRow(
            act,
            getString(R.string.mirror_preset_qhd),
            getString(R.string.mirror_preset_qhd_desc, preferredDpi),
            icon = Ui.drawable(act, R.drawable.ic_resolution),
            selected = state.overrideWidth == 1440 && state.overrideHeight == 2560
        ) {
            sheet.dismiss()
            MirrorDisplayManager.applyResolution(act, 1440, 2560, preferredDpi) { ok, _ ->
                if (ok) UiToast.success(act, getString(R.string.mirror_applied_qhd), Toast.LENGTH_SHORT).show()
                else UiToast.error(act, getString(R.string.toast_res_fail), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            }
        })
        presetsBox.addView(optionRow(
            act,
            getString(R.string.mirror_preset_custom),
            getString(R.string.mirror_preset_custom_desc),
            icon = Ui.drawable(act, R.drawable.ic_tune)
        ) {
            sheet.dismiss()
            showCustomMirrorDialog(state, preferredDpi)
        })
    }

    applyDpiBtn.setOnClickListener {
        MirrorDisplayManager.applyDensityOnly(act, preferredDpi) { ok, updated ->
            if (ok) {
                refreshSheetUi(updated)
                UiToast.success(act, getString(R.string.mirror_dpi_applied, preferredDpi), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            } else {
                UiToast.error(act, getString(R.string.toast_res_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    syncDpiUi(preferredDpi)
    sheet = Sheet(act)
        .icon(R.drawable.ic_resolution)
        .title(getString(R.string.title_mirror))
        .message(getString(R.string.mirror_sheet_msg))
        .content(content)
        .button(getString(R.string.mirror_btn_restore), SheetStyle.DANGER) {
            resetMirrorDisplay()
        }
        .button(getString(R.string.btn_close), SheetStyle.TONAL)

    refreshSheetUi(state)
    sheet.show()
}

private fun MainActivity.showCustomMirrorDialog(state: MirrorLogic.DisplayState, initialDpi: Int) {
    val act = this
    val (wLayout, wInput) = styledField(act, getString(R.string.hint_width_now, state.currentWidth), InputType.TYPE_CLASS_NUMBER, topMarginDp = 0)
    val (hLayout, hInput) = styledField(act, getString(R.string.hint_height_now, state.currentHeight), InputType.TYPE_CLASS_NUMBER, topMarginDp = 0)
    wInput.setText(state.currentWidth.toString())
    hInput.setText(state.currentHeight.toString())

    val fields = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        wLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        hLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        }
        addView(wLayout)
        addView(hLayout)
    }

    val (dpiLayout, dpiInput) = styledField(act, getString(R.string.hint_dpi_now, initialDpi), InputType.TYPE_CLASS_NUMBER, topMarginDp = 10)
    dpiInput.setText(initialDpi.toString())

    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(fields)
        addView(dpiLayout)
    }

    val sheet = Sheet(act)
    sheet.icon(R.drawable.ic_tune)
        .title(getString(R.string.mirror_preset_custom))
        .content(box)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.btn_apply), SheetStyle.PRIMARY, dismiss = false) {
            val w = wInput.text.toString().trim().toIntOrNull()
            val h = hInput.text.toString().trim().toIntOrNull()
            val d = dpiInput.text.toString().trim().toIntOrNull() ?: initialDpi
            if (w == null || h == null || !MirrorLogic.isValidDimensions(w, h)) {
                UiToast.warning(act, getString(R.string.toast_res_small), Toast.LENGTH_SHORT).show()
                return@button
            }
            if (d !in MirrorLogic.MIN_DPI..MirrorLogic.MAX_DPI) {
                UiToast.warning(act, getString(R.string.toast_dpi_invalid, MirrorLogic.MIN_DPI, MirrorLogic.MAX_DPI), Toast.LENGTH_SHORT).show()
                return@button
            }
            sheet.dismiss()
            MirrorDisplayManager.applyResolution(act, w, h, d) { ok, _ ->
                if (ok) UiToast.success(act, getString(R.string.mirror_applied_custom, w, h, d), Toast.LENGTH_SHORT).show()
                else UiToast.error(act, getString(R.string.toast_res_fail), Toast.LENGTH_SHORT).show()
                fetchSystemStatuses()
            }
        }
        .show()
}

internal fun MainActivity.resetMirrorDisplay(onDone: (() -> Unit)? = null) {
    if (!hasShizukuPermission()) return
    MirrorDisplayManager.resetToDefault(this) { ok ->
        if (ok) UiToast.success(this, getString(R.string.mirror_restored_toast), Toast.LENGTH_SHORT).show()
        else UiToast.error(this, getString(R.string.toast_cmd_failed, 1), Toast.LENGTH_SHORT).show()
        fetchSystemStatuses()
        onDone?.invoke()
    }
}
