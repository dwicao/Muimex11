package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure network latency, jitter, channel/band, and target parsing helpers. */
object NetPingLogic {

    data class PingTarget(
        val id: String,
        val region: String,
        val host: String,
        val port: Int = 443,
        val category: String
    )

    data class PingResult(
        val target: PingTarget,
        val avgMs: Int?,
        val jitterMs: Int?,
        val minMs: Int?,
        val maxMs: Int?,
        val okCount: Int,
        val totalCount: Int
    ) {
        val isReachable: Boolean get() = avgMs != null && okCount > 0
        val lossPercent: Int
            get() = if (totalCount <= 0) 0 else (((totalCount - okCount) * 100.0) / totalCount).roundToInt()
    }

    val targets: List<PingTarget> = listOf(
        PingTarget("id_cgk", "Indonesia (Jakarta)", "aws.amazon.com", 443, "SEA Edge"),
        PingTarget("sg", "Singapore (SEA)", "dynamodb.ap-southeast-1.amazonaws.com", 443, "SEA Gaming Hub"),
        PingTarget("hk", "Hong Kong", "dynamodb.ap-east-1.amazonaws.com", 443, "East Asia"),
        PingTarget("tw", "Taiwan", "dns.twnic.tw", 443, "East Asia"),
        PingTarget("jp", "Japan (Tokyo)", "dynamodb.ap-northeast-1.amazonaws.com", 443, "EA Gaming Hub"),
        PingTarget("kr", "South Korea (Seoul)", "dynamodb.ap-northeast-2.amazonaws.com", 443, "EA Gaming Hub"),
        PingTarget("in", "India (Mumbai)", "dynamodb.ap-south-1.amazonaws.com", 443, "South Asia"),
        PingTarget("au", "Australia (Sydney)", "dynamodb.ap-southeast-2.amazonaws.com", 443, "Oceania"),
        PingTarget("eu_de", "Europe (Frankfurt)", "dynamodb.eu-central-1.amazonaws.com", 443, "EU Core"),
        PingTarget("eu_uk", "Europe (London)", "dynamodb.eu-west-2.amazonaws.com", 443, "EU West"),
        PingTarget("us_w", "US West (Oregon)", "dynamodb.us-west-2.amazonaws.com", 443, "NA West"),
        PingTarget("us_e", "US East (Virginia)", "dynamodb.us-east-1.amazonaws.com", 443, "NA East"),
        PingTarget("sa_br", "South America (Sao Paulo)", "dynamodb.sa-east-1.amazonaws.com", 443, "LATAM"),
        PingTarget("cf_any", "Cloudflare Anycast", "1.1.1.1", 443, "Closest Edge"),
        PingTarget("gg_any", "Google Anycast", "8.8.8.8", 443, "Closest Edge")
    )

    fun summarize(samplesMs: List<Int>, totalProbes: Int): Triple<Int?, Int?, Int?> {
        if (samplesMs.isEmpty()) return Triple(null, null, null)
        val avg = samplesMs.average().roundToInt().coerceAtLeast(1)
        val jitter = if (samplesMs.size < 2) 0 else {
            val diffs = (1 until samplesMs.size).map { abs(samplesMs[it] - samplesMs[it - 1]) }
            diffs.average().roundToInt()
        }
        return Triple(avg, jitter, samplesMs.min().coerceAtLeast(1))
    }

    fun parseCustomTarget(raw: String): PingTarget? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == ';' || it == '&' }) return null
        val parts = trimmed.split(":")
        if (parts.size > 2) return null
        val host = parts[0].trim()
        val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 443
        if (port !in 1..65535) return null
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$").matches(host)) return null
        return PingTarget("custom", trimmed, host, port, "Custom Host")
    }

    /** Maps Wi-Fi frequency (MHz) to human-readable band & channel. */
    fun freqToBandChannel(freqMhz: Int): String? = when {
        freqMhz in 2412..2472 -> "2.4 GHz · Ch ${(freqMhz - 2407) / 5}"
        freqMhz == 2484 -> "2.4 GHz · Ch 14"
        freqMhz in 5160..5885 -> "5 GHz · Ch ${(freqMhz - 5000) / 5}"
        freqMhz in 5925..7115 -> "6 GHz · Ch ${(freqMhz - 5950) / 5}"
        else -> null
    }

    fun sortResults(results: List<PingResult>): List<PingResult> =
        results.sortedWith(
            compareBy<PingResult>(
                { !it.isReachable },
                { it.avgMs ?: Int.MAX_VALUE },
                { it.jitterMs ?: Int.MAX_VALUE },
                { it.target.region }
            )
        )
}

internal object NetPingRunner {
    private const val PROBES = 4
    private const val TIMEOUT_MS = 2_000

    fun probe(target: NetPingLogic.PingTarget): NetPingLogic.PingResult {
        val samples = ArrayList<Int>(PROBES)
        repeat(PROBES) {
            try {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(target.host, target.port), TIMEOUT_MS)
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                    samples.add(ms)
                }
            } catch (_: Exception) {
                // Connect timed out or refused.
            }
        }
        val (avg, jitter, minMs) = NetPingLogic.summarize(samples, PROBES)
        return NetPingLogic.PingResult(
            target = target,
            avgMs = avg,
            jitterMs = jitter,
            minMs = minMs,
            maxMs = samples.maxOrNull(),
            okCount = samples.size,
            totalCount = PROBES
        )
    }

    suspend fun runAll(
        targets: List<NetPingLogic.PingTarget>,
        onDone: (NetPingLogic.PingResult) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(6)
        targets.map { t ->
            launch(Dispatchers.IO) {
                val res = gate.withPermit {
                    if (!isActive) throw CancellationException()
                    probe(t)
                }
                withContext(Dispatchers.Main) {
                    onDone(res)
                }
            }
        }.joinAll()
    }
}

internal fun MainActivity.netPingStatusText(prefs: SharedPreferences): String {
    val cached = prefs.getString(Prefs.KEY_NETPING_LAST, null)
    return if (cached.isNullOrBlank()) getString(R.string.status_ready) else cached
}

internal fun MainActivity.resetNetPing(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_NETPING_LAST).apply()
    findViewById<TextView>(R.id.tvStatusNetPing)?.text = getString(R.string.status_ready)
    UiToast.info(this, getString(R.string.netping_reset_done), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showNetPingSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val linkCard = infoCard(this, readActiveNetworkInfo(), Ui.accent(this))
    body.addView(linkCard)

    val (customLayout, customEdit) = styledField(
        this,
        getString(R.string.netping_custom_hint),
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        topMarginDp = 12
    )
    body.addView(customLayout)

    val progressBar = LinearProgressIndicator(this).apply {
        max = 100
        progress = 0
        trackThickness = dp(6)
        trackCornerRadius = dp(3)
        setIndicatorColor(Ui.accent(this@showNetPingSheet))
        trackColor = ColorUtils.setAlphaComponent(Ui.accent(this@showNetPingSheet), 0x26)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }
    body.addView(progressBar)

    val statusText = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.onSurfaceVariant(this@showNetPingSheet))
        setPadding(0, dp(8), 0, dp(4))
        text = getString(R.string.netping_idle, NetPingLogic.targets.size)
    }
    body.addView(statusText)

    val resultsBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }
    body.addView(resultsBox)

    var pingJob: Job? = null
    val liveResults = linkedMapOf<String, NetPingLogic.PingResult>()

    fun renderRows() {
        resultsBox.removeAllViews()
        val list = if (liveResults.isEmpty()) {
            NetPingLogic.targets.forEach { t ->
                resultsBox.addView(
                    buildPingRow(t.region, "${t.host}:${t.port} · ${t.category}", "--", Color.parseColor("#8A8F98"))
                )
            }
            return
        } else {
            NetPingLogic.sortResults(liveResults.values.toList())
        }
        list.forEachIndexed { idx, r ->
            val rank = if (r.isReachable) "#${idx + 1} " else "× "
            val metric = if (r.avgMs != null) {
                getString(R.string.netping_row_fmt, r.avgMs, r.jitterMs ?: 0, r.minMs ?: 0, r.maxMs ?: 0, r.lossPercent)
            } else {
                getString(R.string.dnsbench_timeout)
            }
            val color = when {
                r.avgMs == null -> Color.parseColor("#E53935")
                r.avgMs < 50 -> Color.parseColor("#2E9E5B")
                r.avgMs <= 120 -> Color.parseColor("#E09B00")
                else -> Color.parseColor("#E53935")
            }
            val badge = r.avgMs?.let { "$it ms" } ?: getString(R.string.dnsbench_fail_short)
            resultsBox.addView(buildPingRow("$rank${r.target.region}", "$metric\n${r.target.host}:${r.target.port}", badge, color))
        }
    }
    renderRows()

    // Enrich Wi-Fi band/RSSI with Shizuku in background if permission exists
    if (ShizukuRunner.isReady()) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dump = ShizukuRunner.query("dumpsys wifi | grep -E 'RSSI:|Frequency:|mWifiInfo' | head -n 5")
            val freq = Regex("Frequency:\\s*(\\d+)").find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val rssi = Regex("RSSI:\\s*(-?\\d+)").find(dump)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (freq != null || rssi != null) {
                val extra = buildList {
                    freq?.let { NetPingLogic.freqToBandChannel(it) }?.let { add(it) }
                    rssi?.let { add("RSSI $it dBm") }
                }.joinToString(" · ")
                withContext(Dispatchers.Main) {
                    if (extra.isNotEmpty()) {
                        linkCard.text = "${readActiveNetworkInfo()}\n$extra"
                    }
                }
            }
        }
    }

    Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.title_net_ping))
        .message(getString(R.string.netping_sheet_msg))
        .content(body)
        .onDismiss { pingJob?.cancel() }
        .button(getString(R.string.btn_close), SheetStyle.TONAL) { pingJob?.cancel() }
        .button(getString(R.string.netping_btn_run), SheetStyle.PRIMARY, dismiss = false) { btn ->
            if (pingJob?.isActive == true) {
                pingJob?.cancel()
                pingJob = null
                btn.text = getString(R.string.netping_btn_run)
                progressBar.visibility = View.GONE
                return@button
            }
            val customRaw = customEdit.text?.toString().orEmpty().trim()
            val customTarget = if (customRaw.isNotEmpty()) {
                NetPingLogic.parseCustomTarget(customRaw) ?: run {
                    UiToast.error(this@showNetPingSheet, getString(R.string.netping_invalid_host), Toast.LENGTH_SHORT).show()
                    return@button
                }
            } else null

            val listToRun = buildList {
                if (customTarget != null) add(customTarget)
                addAll(NetPingLogic.targets)
            }
            liveResults.clear()
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            btn.text = getString(R.string.dnsbench_btn_stop)
            linkCard.text = readActiveNetworkInfo()

            pingJob = lifecycleScope.launch {
                var done = 0
                try {
                    NetPingRunner.runAll(listToRun) { r ->
                        liveResults[r.target.id] = r
                        done++
                        progressBar.setProgressCompat((done * 100) / listToRun.size.coerceAtLeast(1), true)
                        val best = NetPingLogic.sortResults(liveResults.values.toList()).firstOrNull { it.isReachable }
                        statusText.text = if (best != null) {
                            getString(R.string.netping_progress_best, done, listToRun.size, best.target.region, best.avgMs ?: 0, best.jitterMs ?: 0)
                        } else {
                            getString(R.string.netping_progress, done, listToRun.size)
                        }
                        renderRows()
                    }
                    val best = NetPingLogic.sortResults(liveResults.values.toList()).firstOrNull { it.isReachable }
                    if (best != null) {
                        val summary = getString(R.string.netping_status_best, best.target.region, best.avgMs ?: 0)
                        prefs.edit().putString(Prefs.KEY_NETPING_LAST, summary).apply()
                        findViewById<TextView>(R.id.tvStatusNetPing)?.text = summary
                    }
                } catch (_: CancellationException) {
                } finally {
                    pingJob = null
                    btn.text = getString(R.string.netping_btn_run)
                    progressBar.visibility = View.GONE
                }
            }
        }
        .show()
}

private fun MainActivity.readActiveNetworkInfo(): String {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return getString(R.string.netping_no_network)
    val net = cm.activeNetwork ?: return getString(R.string.netping_no_network)
    val caps = cm.getNetworkCapabilities(net) ?: return getString(R.string.netping_no_network)
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet / USB"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }
    val downMbps = caps.linkDownstreamBandwidthKbps / 1000
    val upMbps = caps.linkUpstreamBandwidthKbps / 1000
    return getString(R.string.netping_link_fmt, transport, downMbps, upMbps)
}

private fun MainActivity.buildPingRow(title: String, sub: String, badge: String, badgeColor: Int): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(12), dp(10))
        background = Ui.rounded(Ui.tonalFill(this@buildPingRow), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }

        val texts = LinearLayout(this@buildPingRow).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        texts.addView(TextView(this@buildPingRow).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.onSurface(this@buildPingRow))
        })
        texts.addView(TextView(this@buildPingRow).apply {
            text = sub
            textSize = 12f
            setTextColor(Ui.onSurfaceVariant(this@buildPingRow))
            setPadding(0, dp(2), 0, 0)
        })
        addView(texts)

        addView(TextView(this@buildPingRow).apply {
            text = badge
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(badgeColor)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = Ui.rounded(ColorUtils.setAlphaComponent(badgeColor, 0x1F), dp(10))
        })
    }
}
