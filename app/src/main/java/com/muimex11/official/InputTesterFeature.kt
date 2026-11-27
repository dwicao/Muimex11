package com.muimex11.official

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Pure math and signal-rate helpers for touch Hz, mouse polling Hz, and gamepad deadzones. */
object InputTesterLogic {

    /**
     * Computes polling/sampling rate in Hz from ordered event intervals in nanoseconds.
     * Drops anomalous zero or huge (>250 ms) idle gaps between separate touches/movements.
     */
    fun hzFromTimestampsNs(timestampsNs: List<Long>): Int {
        if (timestampsNs.size < 2) return 0
        val deltasNs = ArrayList<Long>(timestampsNs.size - 1)
        for (i in 1 until timestampsNs.size) {
            val d = timestampsNs[i] - timestampsNs[i - 1]
            if (d in 100_000L..250_000_000L) {
                deltasNs.add(d)
            }
        }
        if (deltasNs.isEmpty()) return 0
        val avgNs = deltasNs.average()
        if (avgNs <= 0.0) return 0
        return (1_000_000_000.0 / avgNs).roundToInt().coerceIn(1, 2000)
    }

    fun stickMagnitude(x: Float, y: Float, deadzone: Float = 0.06f): Float {
        val m = hypot(x.toDouble(), y.toDouble()).toFloat()
        return if (m < deadzone) 0f else m.coerceAtMost(1.5f)
    }

    fun formatControllers(devices: List<Pair<String, Int>>): List<String> =
        devices.map { (name, sources) ->
            val tags = buildList {
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) add("Gamepad")
                if ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) add("Joystick")
                if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) add("Mouse")
            }
            if (tags.isEmpty()) name else "$name (${tags.joinToString(", ")})"
        }
}

internal fun MainActivity.inputTesterStatusText(prefs: SharedPreferences): String {
    val peak = prefs.getInt(Prefs.KEY_INPUT_PEAK_HZ, 0)
    return if (peak > 0) getString(R.string.input_status_peak, peak) else getString(R.string.status_ready)
}

internal fun MainActivity.resetInputTester(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_INPUT_PEAK_HZ).apply()
    findViewById<TextView>(R.id.tvStatusInputTester)?.text = getString(R.string.status_ready)
    UiToast.info(this, getString(R.string.input_reset_done), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showInputTesterSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val savedPeak = prefs.getInt(Prefs.KEY_INPUT_PEAK_HZ, 0)

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val statsCard = infoCard(this, getString(R.string.input_waiting_touch), Ui.accent(this))
    body.addView(statsCard)

    val externalDevicesCard = infoCard(this, "")
    externalDevicesCard.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }
    body.addView(externalDevicesCard)

    val controllerStatusView = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.onSurfaceVariant(this@showInputTesterSheet))
        setPadding(dp(4), dp(8), dp(4), dp(4))
        text = getString(R.string.input_gamepad_idle)
    }
    body.addView(controllerStatusView)

    fun refreshConnectedDevices() {
        val list = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
            val src = dev.sources
            val isExt = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            if (isExt) dev.name to src else null
        }
        val formatted = InputTesterLogic.formatControllers(list)
        externalDevicesCard.text = if (formatted.isEmpty()) {
            getString(R.string.input_no_ext_device)
        } else {
            getString(R.string.input_ext_devices_fmt, formatted.joinToString("\n• ", prefix = "• "))
        }
    }
    refreshConnectedDevices()

    val canvas = InputTouchCanvas(
        context = this,
        initialPeakHz = savedPeak,
        onSampleUpdate = { curHz, avgHz, peakHz, fingers, mouseHz ->
            if (peakHz > prefs.getInt(Prefs.KEY_INPUT_PEAK_HZ, 0)) {
                prefs.edit().putInt(Prefs.KEY_INPUT_PEAK_HZ, peakHz).apply()
                findViewById<TextView>(R.id.tvStatusInputTester)?.text = inputTesterStatusText(prefs)
            }
            val mouseStr = if (mouseHz > 0) getString(R.string.input_mouse_hz_fmt, mouseHz) else "--"
            statsCard.text = getString(
                R.string.input_live_stats_fmt,
                curHz, avgHz, peakHz, fingers, mouseStr
            )
        },
        onGamepadMotion = { lx, ly, rx, ry, l2, r2, btnLabel ->
            controllerStatusView.text = getString(
                R.string.input_gamepad_live_fmt,
                lx, ly, InputTesterLogic.stickMagnitude(lx, ly),
                rx, ry, l2, r2, btnLabel
            )
        }
    ).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230)
        ).apply { topMargin = dp(12) }
    }
    body.addView(canvas)

    val sheet = Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.title_input_tester))
        .message(getString(R.string.input_sheet_msg))
        .content(body)
        .button(getString(R.string.input_btn_refresh_devs), SheetStyle.TONAL, dismiss = false) {
            refreshConnectedDevices()
        }
        .button(getString(R.string.btn_clear), SheetStyle.PRIMARY, dismiss = false) {
            canvas.resetStats()
            statsCard.text = getString(R.string.input_waiting_touch)
            controllerStatusView.text = getString(R.string.input_gamepad_idle)
        }
    sheet.show()
    // Keep BottomSheet from hijacking vertical finger drags while testing touch rate inside the canvas.
    sheet.dialog.behavior.isDraggable = false
    canvas.requestFocus()
}

private class InputTouchCanvas(
    context: Context,
    initialPeakHz: Int,
    private val onSampleUpdate: (curHz: Int, avgHz: Int, peakHz: Int, fingers: Int, mouseHz: Int) -> Unit,
    private val onGamepadMotion: (lx: Float, ly: Float, rx: Float, ry: Float, l2: Float, r2: Float, btn: String) -> Unit
) : View(context) {

    private val accent = Ui.accent(context)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.tonalFill(context)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = ColorUtils.setAlphaComponent(accent, 0x80)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dp(3).toFloat()
        color = accent
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(accent, 0xCC)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.onSurfaceVariant(context)
        textSize = context.dp(13).toFloat()
        textAlign = Paint.Align.CENTER
    }

    private val touchTimestamps = ArrayDeque<Long>(160)
    private val mouseTimestamps = ArrayDeque<Long>(160)
    private val recentHzWindow = ArrayDeque<Int>(40)
    private val trailPoints = ArrayDeque<Pair<Float, Float>>(90)
    private val activePointers = mutableListOf<Pair<Float, Float>>()

    private var peakHz = initialPeakHz
    private var currentHz = 0
    private var avgHz = 0
    private var mouseHz = 0
    private var lastBtnLabel = "--"

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resetStats() {
        touchTimestamps.clear()
        mouseTimestamps.clear()
        recentHzWindow.clear()
        trailPoints.clear()
        activePointers.clear()
        currentHz = 0
        avgHz = 0
        peakHz = 0
        mouseHz = 0
        lastBtnLabel = "--"
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }

        val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        val tsNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.eventTimeNanos
        } else {
            event.eventTime * 1_000_000L
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(0, h)
                    val hy = event.getHistoricalY(0, h)
                    val hNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        event.getHistoricalEventTimeNanos(h)
                    } else {
                        event.getHistoricalEventTime(h) * 1_000_000L
                    }
                    pushTimestamp(isMouse, hNs)
                    pushTrail(hx, hy)
                }
                pushTimestamp(isMouse, tsNs)
                pushTrail(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                invalidate()
                return true
            }
        }

        activePointers.clear()
        for (i in 0 until event.pointerCount) {
            activePointers.add(event.getX(i) to event.getY(i))
        }

        val hz = InputTesterLogic.hzFromTimestampsNs(
            (if (isMouse) mouseTimestamps else touchTimestamps).toList()
        )
        if (hz > 0) {
            if (isMouse) {
                mouseHz = hz
            } else {
                currentHz = hz
                recentHzWindow.addLast(hz)
                if (recentHzWindow.size > 30) recentHzWindow.removeFirst()
                avgHz = recentHzWindow.average().roundToInt()
                if (hz > peakHz) peakHz = hz
            }
            onSampleUpdate(currentHz, avgHz, peakHz, event.pointerCount, mouseHz)
        }
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            val tsNs = event.eventTime * 1_000_000L
            pushTimestamp(isMouse = true, tsNs = tsNs)
            val hz = InputTesterLogic.hzFromTimestampsNs(mouseTimestamps.toList())
            if (hz > 0) {
                mouseHz = hz
                onSampleUpdate(currentHz, avgHz, peakHz, activePointers.size, mouseHz)
            }
            return true
        }
        if ((src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            val lx = event.getAxisValue(MotionEvent.AXIS_X)
            val ly = event.getAxisValue(MotionEvent.AXIS_Y)
            val rx = event.getAxisValue(MotionEvent.AXIS_Z)
            val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
            val l2 = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            val r2 = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
            onGamepadMotion(lx, ly, rx, ry, l2, r2, lastBtnLabel)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        ) {
            lastBtnLabel = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            onGamepadMotion(0f, 0f, 0f, 0f, 0f, 0f, lastBtnLabel)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun pushTimestamp(isMouse: Boolean, tsNs: Long) {
        val q = if (isMouse) mouseTimestamps else touchTimestamps
        if (q.isEmpty() || tsNs > q.last()) {
            q.addLast(tsNs)
            if (q.size > 120) q.removeFirst()
        }
    }

    private fun pushTrail(x: Float, y: Float) {
        trailPoints.addLast(x to y)
        if (trailPoints.size > 80) trailPoints.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = context.dp(16).toFloat()
        canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, r, r, bgPaint)
        canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, r, r, borderPaint)

        if (trailPoints.size >= 2) {
            var prev = trailPoints.first()
            for (pt in trailPoints.drop(1)) {
                val dx = pt.first - prev.first
                val dy = pt.second - prev.second
                if (dx * dx + dy * dy < 90_000f) {
                    canvas.drawLine(prev.first, prev.second, pt.first, pt.second, trailPaint)
                }
                prev = pt
            }
        }
        for ((px, py) in activePointers) {
            canvas.drawCircle(px, py, context.dp(14).toFloat(), pointPaint)
        }
        if (trailPoints.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.input_canvas_prompt),
                width / 2f,
                height / 2f,
                textPaint
            )
        }
    }
}
