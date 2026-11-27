package com.muimex11.official

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure keep-alive algorithm adapted directly from `pancing.sh` (Penjaga Koneksi v16.1).
 * Keeps cellular/Wi-Fi data paths warm with 1-byte HTTP Range `0-0` probes, adaptive
 * interval adjustment based on latency, endpoint failover, and exponential backoff.
 */
object ConnectionKeeperLogic {
    const val USER_AGENT = "Speedtest/4.8.0"
    const val RANGE_HEADER = "bytes=0-0"
    const val CONNECT_TIMEOUT_MS = 4_000
    const val READ_TIMEOUT_MS = 6_000

    val URLS = listOf(
        "https://c.speedtest.net/speedtest-config.php",
        "https://c.speedtest.net/speedtest-servers-static.php"
    )

    enum class IntervalPreset(val minSec: Int, val maxSec: Int) {
        ADAPTIVE_DEFAULT(25, 30),
        GAMING_FAST(10, 15),
        SAVER(45, 60)
    }

    enum class LatencyClass { FAST, NORMAL, SLOW }

    data class ProbeOutcome(
        val url: String,
        val ok: Boolean,
        val httpCode: Int,
        val bytesDownloaded: Long,
        val totalMs: Long,
        val connectMs: Long,
        val ttfbMs: Long,
        val errorLabel: String? = null
    )

    data class KeeperState(
        val running: Boolean = false,
        val activeUrlIdx: Int = 0,
        val totalPings: Int = 0,
        val failCount: Int = 0,
        val backoffCount: Int = 0,
        val totalBytes: Long = 0L,
        val startEpochSec: Long = 0L,
        val lastProbe: ProbeOutcome? = null,
        val nextDelaySec: Int = 0,
        val logs: List<String> = emptyList()
    )

    fun isValidHttpCode(code: Int): Boolean = code in 200..206

    fun classifyLatency(totalMs: Long): LatencyClass = when {
        totalMs > 2_000L -> LatencyClass.SLOW
        totalMs < 300L -> LatencyClass.FAST
        else -> LatencyClass.NORMAL
    }

    /** Exact `pancing.sh` adaptive interval calculation parameterized by [preset]. */
    fun computeNextIntervalSec(
        preset: IntervalPreset,
        totalMs: Long,
        failCount: Int,
        backoffCount: Int,
        rawRandom: Int = Random.nextInt(preset.minSec, preset.maxSec + 1),
        jitterOffset: Int = Random.nextInt(-3, 4)
    ): Int {
        var interval = rawRandom.coerceIn(preset.minSec, preset.maxSec)
        interval = (interval + jitterOffset).coerceIn(preset.minSec, preset.maxSec)

        when (classifyLatency(totalMs)) {
            LatencyClass.SLOW -> interval = (interval + 5).coerceAtMost(preset.maxSec + 5)
            LatencyClass.FAST -> interval = (interval - 3).coerceAtLeast((preset.minSec - 2).coerceAtLeast(5))
            LatencyClass.NORMAL -> Unit
        }
        if (failCount > 0 || backoffCount < 3) {
            interval += 2
        }
        return interval
    }

    /** Exponential backoff (5s -> 10s max, resetting every 20 consecutive failures). */
    fun computeFailureBackoffSec(failCount: Int): Int {
        val effective = failCount.coerceAtLeast(1)
        val raw = (5.0 * 2.0.pow(min(effective - 1, 4))).toInt()
        return raw.coerceAtMost(10)
    }

    fun shouldResetFailureCycle(failCount: Int): Boolean = failCount >= 20

    fun formatDurationHms(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val rem = s % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, rem)
    }

    fun formatBytesLabel(bytes: Long): String =
        if (bytes < 1024L) "${bytes}B"
        else String.format(Locale.US, "%.1fKB", bytes / 1024.0)
}

/**
 * Background Foreground Service executing `pancing.sh` keep-alive loop natively over HTTPS
 * with optional PARTIAL_WAKE_LOCK and live notification updates.
 */
class ConnectionKeeperService : Service() {

    companion object {
        const val ACTION_STOP = "com.muimex11.official.ACTION_KEEPER_STOP"

        private val _state = MutableStateFlow(ConnectionKeeperLogic.KeeperState())
        val state: StateFlow<ConnectionKeeperLogic.KeeperState> = _state

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionKeeperService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ConnectionKeeperService::class.java))
        }

        fun executeSingleProbe(urlStr: String): ConnectionKeeperLogic.ProbeOutcome {
            val tStart = System.nanoTime()
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = ConnectionKeeperLogic.CONNECT_TIMEOUT_MS
                    readTimeout = ConnectionKeeperLogic.READ_TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", ConnectionKeeperLogic.USER_AGENT)
                    setRequestProperty("Range", ConnectionKeeperLogic.RANGE_HEADER)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                }
                conn.connect()
                val tConnect = System.nanoTime()
                val code = conn.responseCode
                val tTtfb = System.nanoTime()
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                var bytesRead = 0L
                stream?.use { ins ->
                    val buf = ByteArray(1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        bytesRead += n
                    }
                }
                val tEnd = System.nanoTime()
                val connectMs = ((tConnect - tStart) / 1_000_000L).coerceAtLeast(1L)
                val ttfbMs = ((tTtfb - tStart) / 1_000_000L).coerceAtLeast(1L)
                val totalMs = ((tEnd - tStart) / 1_000_000L).coerceAtLeast(1L)

                if (ConnectionKeeperLogic.isValidHttpCode(code)) {
                    ConnectionKeeperLogic.ProbeOutcome(
                        url = urlStr,
                        ok = true,
                        httpCode = code,
                        bytesDownloaded = bytesRead,
                        totalMs = totalMs,
                        connectMs = connectMs,
                        ttfbMs = ttfbMs
                    )
                } else {
                    ConnectionKeeperLogic.ProbeOutcome(
                        url = urlStr,
                        ok = false,
                        httpCode = code,
                        bytesDownloaded = bytesRead,
                        totalMs = totalMs,
                        connectMs = connectMs,
                        ttfbMs = ttfbMs,
                        errorLabel = "Bad HTTP ($code)"
                    )
                }
            } catch (_: UnknownHostException) {
                ConnectionKeeperLogic.ProbeOutcome(urlStr, false, 0, 0L, 0L, 0L, 0L, "DNS Resolution Failed")
            } catch (_: SocketTimeoutException) {
                ConnectionKeeperLogic.ProbeOutcome(urlStr, false, 0, 0L, 0L, 0L, 0L, "Timeout (4s/6s)")
            } catch (_: SSLException) {
                ConnectionKeeperLogic.ProbeOutcome(urlStr, false, 0, 0L, 0L, 0L, 0L, "SSL/TLS Handshake Failed")
            } catch (e: Exception) {
                val msg = e.javaClass.simpleName.ifBlank { "Network Error" }
                ConnectionKeeperLogic.ProbeOutcome(urlStr, false, 0, 0L, 0L, 0L, 0L, msg)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceNotif.ensureChannel(this)
        val initialNotif = buildStatusNotification(getString(R.string.keeper_notif_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ServiceNotif.KEEPER_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ServiceNotif.KEEPER_ID, initialNotif)
        }

        isRunning = true
        val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Prefs.KEY_KEEPER_RUNNING, true).apply()
        syncWakeLock(prefs.getBoolean(Prefs.KEY_KEEPER_WAKELOCK, true))

        if (workerJob?.isActive != true) {
            workerJob = scope.launch { runKeeperLoop(prefs) }
        }
        return START_STICKY
    }

    private fun syncWakeLock(enable: Boolean) {
        if (enable) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muimex11::ConnectionKeeper")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        } else {
            if (wakeLock?.isHeld == true) {
                try {
                    wakeLock?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun runKeeperLoop(prefs: SharedPreferences) {
        val startEpochSec = System.currentTimeMillis() / 1000L
        var urlIdx = 0
        var failCount = 0
        var backoffCount = 0
        var totalPings = 0
        var totalBytes = 0L
        val logRing = ArrayDeque<String>(45)

        fun pushLog(entry: String) {
            val stamp = timeFmt.format(Date())
            logRing.addFirst("[$stamp] $entry")
            while (logRing.size > 40) logRing.removeLast()
        }

        pushLog(getString(R.string.keeper_log_started))
        _state.value = ConnectionKeeperLogic.KeeperState(
            running = true,
            startEpochSec = startEpochSec,
            logs = logRing.toList()
        )

        while (coroutineContext.isActive && isRunning) {
            syncWakeLock(prefs.getBoolean(Prefs.KEY_KEEPER_WAKELOCK, true))
            val preset = ConnectionKeeperLogic.IntervalPreset.entries.firstOrNull {
                it.name == prefs.getString(Prefs.KEY_KEEPER_PRESET, null)
            } ?: ConnectionKeeperLogic.IntervalPreset.ADAPTIVE_DEFAULT

            val targetUrl = ConnectionKeeperLogic.URLS[urlIdx]
            val outcome = executeSingleProbe(targetUrl)
            totalPings++

            val uptimeStr = ConnectionKeeperLogic.formatDurationHms(
                (System.currentTimeMillis() / 1000L) - startEpochSec
            )

            if (outcome.ok) {
                failCount = 0
                backoffCount = (backoffCount + 1).coerceAtMost(3)
                totalBytes += outcome.bytesDownloaded
                val intervalSec = ConnectionKeeperLogic.computeNextIntervalSec(
                    preset = preset,
                    totalMs = outcome.totalMs,
                    failCount = failCount,
                    backoffCount = backoffCount
                )
                val sizeStr = ConnectionKeeperLogic.formatBytesLabel(outcome.bytesDownloaded)
                val msg = "✅ $sizeStr | total ${String.format(Locale.US, "%.2fs", outcome.totalMs / 1000.0)} | " +
                    "conn ${String.format(Locale.US, "%.2fs", outcome.connectMs / 1000.0)} | " +
                    "ttfb ${String.format(Locale.US, "%.2fs", outcome.ttfbMs / 1000.0)} | " +
                    "HTTP ${outcome.httpCode} | #$totalPings | up $uptimeStr"
                pushLog(msg)

                _state.value = ConnectionKeeperLogic.KeeperState(
                    running = true,
                    activeUrlIdx = urlIdx,
                    totalPings = totalPings,
                    failCount = failCount,
                    backoffCount = backoffCount,
                    totalBytes = totalBytes,
                    startEpochSec = startEpochSec,
                    lastProbe = outcome,
                    nextDelaySec = intervalSec,
                    logs = logRing.toList()
                )
                updateNotification("✅ $sizeStr · TTFB ${outcome.ttfbMs} ms · #$totalPings · up $uptimeStr")
                delay(intervalSec * 1000L)
            } else {
                failCount++
                backoffCount = 0
                urlIdx = (urlIdx + 1) % ConnectionKeeperLogic.URLS.size
                val retrySec = ConnectionKeeperLogic.computeFailureBackoffSec(failCount)
                val errText = outcome.errorLabel ?: "Error"
                pushLog("❌ #$totalPings: $errText | Switch -> ${ConnectionKeeperLogic.URLS[urlIdx].substringAfterLast('/')} | Retry ${retrySec}s")

                _state.value = ConnectionKeeperLogic.KeeperState(
                    running = true,
                    activeUrlIdx = urlIdx,
                    totalPings = totalPings,
                    failCount = failCount,
                    backoffCount = backoffCount,
                    totalBytes = totalBytes,
                    startEpochSec = startEpochSec,
                    lastProbe = outcome,
                    nextDelaySec = retrySec,
                    logs = logRing.toList()
                )
                updateNotification("❌ $errText · Retry ${retrySec}s (#$failCount)")
                delay(retrySec * 1000L)

                if (ConnectionKeeperLogic.shouldResetFailureCycle(failCount)) {
                    pushLog("⚠️ Reset failure counter after 20 retries, cooling 30s")
                    failCount = 0
                    delay(30_000L)
                }
            }
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ServiceNotif.KEEPER_ID, buildStatusNotification(contentText))
    }

    private fun buildStatusNotification(contentText: String): Notification {
        ServiceNotif.ensureChannel(this)
        val openIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, ConnectionKeeperService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ServiceNotif.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.title_conn_keeper))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_dns)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.keeper_btn_stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        workerJob?.cancel()
        scope.cancel()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (_: Exception) {
            }
        }
        getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_KEEPER_RUNNING, false).apply()
        _state.value = _state.value.copy(running = false)
        super.onDestroy()
    }
}

internal fun MainActivity.connectionKeeperStatusPair(): Pair<String, Boolean> {
    val st = ConnectionKeeperService.state.value
    return if (ConnectionKeeperService.isRunning || st.running) {
        val pings = st.totalPings
        val ms = st.lastProbe?.ttfbMs
        val text = if (ms != null) {
            getString(R.string.keeper_status_active_ms, pings, ms)
        } else {
            getString(R.string.keeper_status_active, pings)
        }
        text to true
    } else {
        getString(R.string.status_default) to false
    }
}

internal fun MainActivity.resetConnectionKeeper() {
    ConnectionKeeperService.stop(this)
    findViewById<TextView>(R.id.tvStatusConnKeeper)?.text = getString(R.string.status_default)
    fetchSystemStatuses()
    UiToast.info(this, getString(R.string.keeper_stopped_toast), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showConnectionKeeperSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    var preset = ConnectionKeeperLogic.IntervalPreset.entries.firstOrNull {
        it.name == prefs.getString(Prefs.KEY_KEEPER_PRESET, null)
    } ?: ConnectionKeeperLogic.IntervalPreset.ADAPTIVE_DEFAULT
    var wakeLockEnabled = prefs.getBoolean(Prefs.KEY_KEEPER_WAKELOCK, true)

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val statusCard = infoCard(this, "", Ui.accent(this))
    body.addView(statusCard)

    val presetLabel = TextView(this).apply {
        text = getString(R.string.keeper_preset_label)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurfaceVariant(this@showConnectionKeeperSheet))
        setPadding(0, dp(12), 0, dp(4))
    }
    body.addView(presetLabel)

    val presetGroup = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    val chipDefault = Chip(this).apply {
        id = android.view.View.generateViewId()
        text = getString(R.string.keeper_preset_default)
        isCheckable = true
        isChecked = preset == ConnectionKeeperLogic.IntervalPreset.ADAPTIVE_DEFAULT
    }
    val chipFast = Chip(this).apply {
        id = android.view.View.generateViewId()
        text = getString(R.string.keeper_preset_fast)
        isCheckable = true
        isChecked = preset == ConnectionKeeperLogic.IntervalPreset.GAMING_FAST
    }
    val chipSaver = Chip(this).apply {
        id = android.view.View.generateViewId()
        text = getString(R.string.keeper_preset_saver)
        isCheckable = true
        isChecked = preset == ConnectionKeeperLogic.IntervalPreset.SAVER
    }
    presetGroup.addView(chipDefault)
    presetGroup.addView(chipFast)
    presetGroup.addView(chipSaver)
    body.addView(presetGroup)

    val wakeRowContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }
    body.addView(wakeRowContainer)

    fun rebuildWakeRow() {
        wakeRowContainer.removeAllViews()
        wakeRowContainer.addView(
            optionRow(
                this,
                getString(R.string.keeper_wakelock_title),
                getString(R.string.keeper_wakelock_desc),
                selected = wakeLockEnabled
            ) {
                wakeLockEnabled = !wakeLockEnabled
                prefs.edit().putBoolean(Prefs.KEY_KEEPER_WAKELOCK, wakeLockEnabled).apply()
                rebuildWakeRow()
            }
        )
    }
    rebuildWakeRow()

    val logsHeader = TextView(this).apply {
        text = getString(R.string.keeper_logs_title)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurfaceVariant(this@showConnectionKeeperSheet))
        setPadding(0, dp(12), 0, dp(6))
    }
    body.addView(logsHeader)

    val logsCard = infoCard(this, getString(R.string.keeper_logs_empty)).apply {
        setTextColor(Ui.onSurface(this@showConnectionKeeperSheet))
    }
    body.addView(logsCard)

    var uiCollector: Job? = null
    val sheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.title_conn_keeper))
        .message(getString(R.string.keeper_sheet_msg))
        .content(body)
        .onDismiss { uiCollector?.cancel() }

    fun renderSnapshot(st: ConnectionKeeperLogic.KeeperState) {
        val active = ConnectionKeeperService.isRunning || st.running
        val endpoint = ConnectionKeeperLogic.URLS[st.activeUrlIdx].substringAfterLast('/')
        val uptime = if (active && st.startEpochSec > 0) {
            ConnectionKeeperLogic.formatDurationHms((System.currentTimeMillis() / 1000L) - st.startEpochSec)
        } else "00:00:00"
        val lastLine = st.lastProbe?.let { p ->
            if (p.ok) {
                getString(R.string.keeper_last_ok_fmt, ConnectionKeeperLogic.formatBytesLabel(p.bytesDownloaded), p.ttfbMs, p.connectMs, p.httpCode)
            } else {
                p.errorLabel ?: getString(R.string.dnsbench_fail_short)
            }
        } ?: "--"

        statusCard.text = getString(
            R.string.keeper_stats_card_fmt,
            getString(if (active) R.string.status_enabled else R.string.status_disabled),
            uptime,
            st.totalPings,
            st.failCount,
            ConnectionKeeperLogic.formatBytesLabel(st.totalBytes),
            endpoint,
            lastLine
        )
        logsCard.text = if (st.logs.isEmpty()) getString(R.string.keeper_logs_empty) else st.logs.joinToString("\n")
        sheet.buttonViews.lastOrNull()?.let { primaryBtn ->
            primaryBtn.text = getString(if (active) R.string.keeper_btn_stop else R.string.keeper_btn_start)
        }
        findViewById<TextView>(R.id.tvStatusConnKeeper)?.text = connectionKeeperStatusPair().first
    }

    presetGroup.setOnCheckedStateChangeListener { _, checkedIds ->
        preset = when (checkedIds.firstOrNull()) {
            chipFast.id -> ConnectionKeeperLogic.IntervalPreset.GAMING_FAST
            chipSaver.id -> ConnectionKeeperLogic.IntervalPreset.SAVER
            else -> ConnectionKeeperLogic.IntervalPreset.ADAPTIVE_DEFAULT
        }
        prefs.edit().putString(Prefs.KEY_KEEPER_PRESET, preset.name).apply()
    }

    sheet.button(getString(R.string.keeper_btn_test_one), SheetStyle.TONAL, dismiss = false) {
        logsCard.text = getString(R.string.keeper_testing_one)
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                ConnectionKeeperService.executeSingleProbe(ConnectionKeeperLogic.URLS[0])
            }
            val msg = if (out.ok) {
                getString(
                    R.string.keeper_last_ok_fmt,
                    ConnectionKeeperLogic.formatBytesLabel(out.bytesDownloaded),
                    out.ttfbMs,
                    out.connectMs,
                    out.httpCode
                )
            } else {
                out.errorLabel ?: getString(R.string.dnsbench_fail_short)
            }
            UiToast.info(this@showConnectionKeeperSheet, msg, Toast.LENGTH_LONG).show()
            renderSnapshot(ConnectionKeeperService.state.value)
        }
    }

    sheet.button(getString(R.string.keeper_btn_start), SheetStyle.PRIMARY, dismiss = false) {
        if (ConnectionKeeperService.isRunning) {
            ConnectionKeeperService.stop(this@showConnectionKeeperSheet)
            UiToast.info(this@showConnectionKeeperSheet, getString(R.string.keeper_stopped_toast), Toast.LENGTH_SHORT).show()
        } else {
            ConnectionKeeperService.start(this@showConnectionKeeperSheet)
            UiToast.success(this@showConnectionKeeperSheet, getString(R.string.keeper_started_toast), Toast.LENGTH_SHORT).show()
        }
        fetchSystemStatuses()
    }

    sheet.show()
    renderSnapshot(ConnectionKeeperService.state.value)
    uiCollector = lifecycleScope.launch {
        ConnectionKeeperService.state.collect { st ->
            renderSnapshot(st)
        }
    }
}
