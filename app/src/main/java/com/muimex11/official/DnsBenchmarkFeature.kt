package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
import java.io.DataInputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.roundToInt

/** Pure DNS benchmark catalog, RFC 1035 packet codec, ranking, and persistence logic. */
object DnsBenchmarkLogic {
    enum class DnsTier { POPULAR, UNPOPULAR }
    enum class DnsProtocol { UDP_53, DOT_853 }
    enum class DnsFilter { ALL, POPULAR, UNPOPULAR }

    data class DnsProvider(
        val id: String,
        val name: String,
        val primaryIp: String,
        val secondaryIp: String?,
        val dotHost: String?,
        val tier: DnsTier,
        val region: String,
        val note: String
    )

    data class DnsBenchResult(
        val provider: DnsProvider,
        val protocol: DnsProtocol,
        val avgMs: Int?,
        val minMs: Int?,
        val maxMs: Int?,
        val okCount: Int,
        val totalCount: Int,
        val skippedReason: String? = null
    ) {
        val isReachable: Boolean get() = avgMs != null && okCount > 0
    }

    /** Curated catalog spanning mainstream global DNS and lesser-known / niche / regional DNS. */
    val catalog: List<DnsProvider> = listOf(
        // Popular / mainstream resolvers
        DnsProvider("cf", "Cloudflare", "1.1.1.1", "1.0.0.1", "one.one.one.one", DnsTier.POPULAR, "Global", "Fastest, no logs"),
        DnsProvider("cf_sec", "Cloudflare Security", "1.1.1.2", "1.0.0.2", "security.cloudflare-dns.com", DnsTier.POPULAR, "Global", "Blocks malware"),
        DnsProvider("cf_fam", "Cloudflare Family", "1.1.1.3", "1.0.0.3", "family.cloudflare-dns.com", DnsTier.POPULAR, "Global", "Malware + adult filter"),
        DnsProvider("google", "Google Public DNS", "8.8.8.8", "8.8.4.4", "dns.google", DnsTier.POPULAR, "Global", "Stable & reliable"),
        DnsProvider("quad9", "Quad9 (Security)", "9.9.9.9", "149.112.112.112", "dns.quad9.net", DnsTier.POPULAR, "CH / Global", "Threat blocking, Swiss privacy"),
        DnsProvider("quad9_unf", "Quad9 (Unfiltered)", "9.9.9.10", "149.112.112.10", "dns10.quad9.net", DnsTier.POPULAR, "CH / Global", "No filtering + ECS"),
        DnsProvider("adguard", "AdGuard DNS", "94.140.14.14", "94.140.15.15", "dns.adguard-dns.com", DnsTier.POPULAR, "CY / Global", "Blocks ads & trackers"),
        DnsProvider("adguard_unf", "AdGuard Unfiltered", "94.140.14.140", "94.140.14.141", "unfiltered.adguard-dns.com", DnsTier.POPULAR, "CY / Global", "Zero filtering, privacy"),
        DnsProvider("adguard_fam", "AdGuard Family", "94.140.14.15", "94.140.15.16", "family.adguard-dns.com", DnsTier.POPULAR, "CY / Global", "Ads + adult filter"),
        DnsProvider("opendns", "Cisco OpenDNS", "208.67.222.222", "208.67.220.220", "dns.opendns.com", DnsTier.POPULAR, "US / Global", "Phishing protection"),
        DnsProvider("opendns_fam", "OpenDNS FamilyShield", "208.67.222.123", "208.67.220.123", "familyshield.opendns.com", DnsTier.POPULAR, "US / Global", "Preconfigured family filter"),
        DnsProvider("nextdns", "NextDNS", "45.90.28.0", "45.90.30.0", "dns.nextdns.io", DnsTier.POPULAR, "Global", "Configurable firewall DNS"),
        DnsProvider("controld_unf", "Control D Unfiltered", "76.76.2.0", "76.76.10.0", "p0.freedns.controld.com", DnsTier.POPULAR, "CA / Global", "Anycast, unfiltered"),
        DnsProvider("controld_ads", "Control D (Ads + Malware)", "76.76.2.2", "76.76.10.2", "p2.freedns.controld.com", DnsTier.POPULAR, "CA / Global", "Blocks ads & malware"),
        DnsProvider("cleanbrowsing_sec", "CleanBrowsing Security", "185.228.168.9", "185.228.169.9", "security-filter-dns.cleanbrowsing.org", DnsTier.POPULAR, "US / Global", "Phishing & malware filter"),
        DnsProvider("cleanbrowsing_fam", "CleanBrowsing Family", "185.228.168.168", "185.228.169.168", "family-filter-dns.cleanbrowsing.org", DnsTier.POPULAR, "US / Global", "Strict family filter"),
        DnsProvider("mullvad", "Mullvad DNS (Unfiltered)", "194.242.2.2", null, "dns.mullvad.net", DnsTier.POPULAR, "SE / Global", "No logs, DNSSEC"),
        DnsProvider("mullvad_ads", "Mullvad DNS (Adblock)", "194.242.2.4", null, "adblock.dns.mullvad.net", DnsTier.POPULAR, "SE / Global", "Ads, trackers & malware"),
        DnsProvider("comodo", "Comodo Secure DNS", "8.26.56.26", "8.20.247.20", null, DnsTier.POPULAR, "US", "Malware defense (UDP)"),
        DnsProvider("verisign", "Verisign Public DNS", "64.6.64.6", "64.6.65.6", null, DnsTier.POPULAR, "US", "DNSSEC stability (UDP)"),

        // Unpopular / niche / independent / community / regional resolvers
        DnsProvider("gcore", "Gcore DNS", "95.85.95.85", "2.56.220.2", null, DnsTier.UNPOPULAR, "LU / Global", "CDN edge anycast (UDP)"),
        DnsProvider("dnswatch", "DNS.Watch", "84.200.69.80", "84.200.70.40", null, DnsTier.UNPOPULAR, "DE", "Neutral uncensored, no logs"),
        DnsProvider("uncensored", "UncensoredDNS", "91.239.100.100", "89.233.43.71", "anycast.uncensoreddns.org", DnsTier.UNPOPULAR, "DK", "Independent, uncensored"),
        DnsProvider("dns0", "DNS0.eu ZERO", "193.110.81.0", "185.253.5.0", "dns0.eu", DnsTier.UNPOPULAR, "EU", "European sovereign security"),
        DnsProvider("dnssb", "DNS.SB", "185.222.222.222", "45.11.45.11", "dot.sb", DnsTier.UNPOPULAR, "DE / Global", "xTom privacy, no logs"),
        DnsProvider("switch_ch", "Switch DNS", "130.59.31.248", "130.59.31.251", "dns.switch.ch", DnsTier.UNPOPULAR, "CH", "Swiss academic network"),
        DnsProvider("digitalcourage", "Digitalcourage e.V.", "5.9.164.112", null, "dns3.digitalcourage.de", DnsTier.UNPOPULAR, "DE", "German civil rights NGO"),
        DnsProvider("dismail", "DISMAIL DNS", "159.69.114.157", null, "fdns1.dismail.de", DnsTier.UNPOPULAR, "DE", "Ad & tracker block activist"),
        DnsProvider("ffmuc", "FFMUC (Freifunk Munich)", "5.1.66.255", null, "dot.ffmuc.net", DnsTier.UNPOPULAR, "DE", "Community mesh resolver"),
        DnsProvider("fdn", "FDN (French Data Net)", "80.67.169.12", "80.67.169.40", "ns0.fdn.fr", DnsTier.UNPOPULAR, "FR", "Non-profit ISP, uncensored"),
        DnsProvider("njalla", "Njalla DNS", "95.215.19.53", null, "dns.njal.la", DnsTier.UNPOPULAR, "SE / NL", "Privacy-hardened, no logs"),
        DnsProvider("libredns", "LibreDNS", "116.202.176.26", null, "dot.libredns.gr", DnsTier.UNPOPULAR, "DE / GR", "Uncensored Libreops"),
        DnsProvider("dnsforge", "DNS Forge", "176.9.93.198", "176.9.1.117", "dnsforge.de", DnsTier.UNPOPULAR, "DE", "German ad-block resolver"),
        DnsProvider("cznic", "CZ.NIC ODVR", "193.17.47.1", "185.43.135.1", "odvr.nic.cz", DnsTier.UNPOPULAR, "CZ", "Czech registry DNSSEC"),
        DnsProvider("digiges", "Digitale Gesellschaft", "185.95.218.42", "185.95.218.43", "dns.digitale-gesellschaft.ch", DnsTier.UNPOPULAR, "CH", "Swiss digital rights NGO"),
        DnsProvider("wikimedia", "Wikimedia DNS", "185.71.138.138", null, "wikimedia-dns.org", DnsTier.UNPOPULAR, "US / NL", "Wikimedia no-logs resolver"),
        DnsProvider("restena", "Restena DNS", "158.64.1.29", null, "kaitain.restena.lu", DnsTier.UNPOPULAR, "LU", "Luxembourg research net"),
        DnsProvider("appliedpriv", "Applied Privacy", "146.255.56.98", null, "dot1.applied-privacy.net", DnsTier.UNPOPULAR, "AT", "Austrian privacy foundation"),
        DnsProvider("cira", "CIRA Canadian Shield", "149.112.121.20", "149.112.122.20", "protected.canadianshield.cira.ca", DnsTier.UNPOPULAR, "CA", "Canadian malware shield"),
        DnsProvider("iij", "IIJ Public DNS", "103.2.57.5", "103.2.57.6", "public.dns.iij.jp", DnsTier.UNPOPULAR, "JP", "Japanese backbone ISP"),
        DnsProvider("quad101", "Quad101 (TWNIC)", "101.101.101.101", "101.102.103.104", "dns.twnic.tw", DnsTier.UNPOPULAR, "TW", "Taiwan NIC anycast"),
        DnsProvider("dnspod", "DNSPod Public DNS+", "119.29.29.29", null, "dot.pub", DnsTier.UNPOPULAR, "APAC", "Tencent APAC anycast"),
        DnsProvider("alidns", "AliDNS", "223.5.5.5", "223.6.6.6", "dns.alidns.com", DnsTier.UNPOPULAR, "APAC", "Alibaba APAC anycast"),
        DnsProvider("he", "Hurricane Electric", "74.82.42.42", null, null, DnsTier.UNPOPULAR, "US", "Backbone transit resolver"),
        DnsProvider("level3", "Level3 / Lumen", "4.2.2.1", "4.2.2.2", null, DnsTier.UNPOPULAR, "US", "Classic backbone resolver"),
        DnsProvider("dyn", "Oracle Dyn DNS", "216.146.35.35", "216.146.36.36", null, DnsTier.UNPOPULAR, "US", "Legacy enterprise resolver"),
        DnsProvider("neustar", "Neustar UltraDNS", "156.154.70.2", "156.154.71.2", null, DnsTier.UNPOPULAR, "US", "Enterprise threat block"),
        DnsProvider("safedns", "SafeDNS", "195.46.39.39", "195.46.39.40", null, DnsTier.UNPOPULAR, "US / EU", "Content filter resolver"),
        DnsProvider("alternate", "Alternate DNS", "76.76.19.19", "76.223.122.150", null, DnsTier.UNPOPULAR, "US", "Ad-blocking (UDP)"),
        DnsProvider("freenom", "Freenom World", "80.80.80.80", "80.80.81.81", null, DnsTier.UNPOPULAR, "NL", "Lightweight anonymous (UDP)"),
        DnsProvider("yandex", "Yandex DNS Basic", "77.88.8.8", "77.88.8.1", "common.dot.dns.yandex.net", DnsTier.UNPOPULAR, "RU", "Eastern Europe regional")
    )

    val probeDomains = listOf("example.com", "google.com", "wikipedia.org")

    fun filterProviders(tierFilter: DnsFilter, query: String = ""): List<DnsProvider> {
        val byTier = when (tierFilter) {
            DnsFilter.ALL -> catalog
            DnsFilter.POPULAR -> catalog.filter { it.tier == DnsTier.POPULAR }
            DnsFilter.UNPOPULAR -> catalog.filter { it.tier == DnsTier.UNPOPULAR }
        }
        val q = query.trim()
        if (q.isEmpty()) return byTier
        return byTier.filter { p ->
            p.name.contains(q, ignoreCase = true) ||
                p.primaryIp.contains(q, ignoreCase = true) ||
                (p.secondaryIp?.contains(q, ignoreCase = true) == true) ||
                (p.dotHost?.contains(q, ignoreCase = true) == true) ||
                p.region.contains(q, ignoreCase = true) ||
                p.note.contains(q, ignoreCase = true)
        }
    }

    /** Builds a minimal RFC 1035 standard query packet (RD=1, QDCOUNT=1). */
    fun buildDnsQuery(domain: String, txId: Int, qType: Int = 1): ByteArray {
        val labels = domain.trim('.').split('.').filter { it.isNotEmpty() }
        val qnameLen = labels.sumOf { 1 + it.length } + 1
        val out = ByteArray(12 + qnameLen + 4)
        out[0] = ((txId ushr 8) and 0xFF).toByte()
        out[1] = (txId and 0xFF).toByte()
        out[2] = 0x01 // RD = 1
        out[3] = 0x00
        out[4] = 0x00
        out[5] = 0x01 // QDCOUNT = 1
        var idx = 12
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out[idx++] = (bytes.size and 0x3F).toByte()
            bytes.copyInto(out, idx)
            idx += bytes.size
        }
        out[idx++] = 0x00 // root terminator
        out[idx++] = ((qType ushr 8) and 0xFF).toByte()
        out[idx++] = (qType and 0xFF).toByte()
        out[idx++] = 0x00
        out[idx] = 0x01 // QCLASS = IN
        return out
    }

    /**
     * Validates an RFC 1035 DNS reply and returns its RCODE (0 = NOERROR, 3 = NXDOMAIN),
     * or `null` if truncated, wrong transaction ID, or not a response.
     */
    fun parseDnsResponse(packet: ByteArray, length: Int, expectedTxId: Int): Int? {
        if (length < 12 || packet.size < 12) return null
        val rxId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        if (rxId != (expectedTxId and 0xFFFF)) return null
        val flagsHi = packet[2].toInt() and 0xFF
        val flagsLo = packet[3].toInt() and 0xFF
        if ((flagsHi and 0x80) == 0) return null // QR bit must be 1
        return flagsLo and 0x0F
    }

    /** Prefixes a DNS wire packet with 2-byte big-endian length per RFC 7858 (DNS-over-TLS). */
    fun frameDotQuery(dnsPacket: ByteArray): ByteArray {
        val len = dnsPacket.size
        val framed = ByteArray(len + 2)
        framed[0] = ((len ushr 8) and 0xFF).toByte()
        framed[1] = (len and 0xFF).toByte()
        dnsPacket.copyInto(framed, 2)
        return framed
    }

    data class LatencyStats(val avgMs: Int?, val minMs: Int?, val maxMs: Int?)

    fun summarizeSamples(samplesMs: List<Int>): LatencyStats {
        if (samplesMs.isEmpty()) return LatencyStats(null, null, null)
        val avg = samplesMs.average().roundToInt().coerceAtLeast(1)
        return LatencyStats(avg, samplesMs.min().coerceAtLeast(1), samplesMs.max().coerceAtLeast(1))
    }

    fun sortResults(
        results: List<DnsBenchResult>,
        tierFilter: DnsFilter = DnsFilter.ALL,
        query: String = ""
    ): List<DnsBenchResult> {
        val q = query.trim()
        return results
            .asSequence()
            .filter { r ->
                when (tierFilter) {
                    DnsFilter.ALL -> true
                    DnsFilter.POPULAR -> r.provider.tier == DnsTier.POPULAR
                    DnsFilter.UNPOPULAR -> r.provider.tier == DnsTier.UNPOPULAR
                }
            }
            .filter { r ->
                q.isEmpty() ||
                    r.provider.name.contains(q, ignoreCase = true) ||
                    r.provider.primaryIp.contains(q, ignoreCase = true) ||
                    (r.provider.dotHost?.contains(q, ignoreCase = true) == true) ||
                    r.provider.region.contains(q, ignoreCase = true) ||
                    r.provider.note.contains(q, ignoreCase = true)
            }
            .sortedWith(
                compareBy<DnsBenchResult>(
                    { !it.isReachable },
                    { it.avgMs ?: Int.MAX_VALUE },
                    { it.minMs ?: Int.MAX_VALUE },
                    { it.provider.name }
                )
            )
            .toList()
    }

    /** Delimited persistence format avoiding Android's stubbed org.json in JVM unit tests. */
    fun serializeResults(results: List<DnsBenchResult>): String =
        results.joinToString("\n") { r ->
            listOf(
                r.provider.id,
                r.protocol.name,
                r.avgMs?.toString() ?: "",
                r.minMs?.toString() ?: "",
                r.maxMs?.toString() ?: "",
                r.okCount.toString(),
                r.totalCount.toString(),
                (r.skippedReason ?: "").replace("|", "/").replace("\n", " ")
            ).joinToString("|")
        }

    fun deserializeResults(raw: String?): List<DnsBenchResult> {
        if (raw.isNullOrBlank()) return emptyList()
        val byId = catalog.associateBy { it.id }
        return raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split("|")
            if (parts.size < 7) return@mapNotNull null
            val provider = byId[parts[0]] ?: return@mapNotNull null
            val proto = DnsProtocol.entries.firstOrNull { it.name == parts[1] } ?: DnsProtocol.UDP_53
            DnsBenchResult(
                provider = provider,
                protocol = proto,
                avgMs = parts[2].toIntOrNull(),
                minMs = parts[3].toIntOrNull(),
                maxMs = parts[4].toIntOrNull(),
                okCount = parts[5].toIntOrNull() ?: 0,
                totalCount = parts[6].toIntOrNull() ?: 0,
                skippedReason = parts.getOrNull(7)?.takeIf { it.isNotEmpty() }
            )
        }.toList()
    }
}

/** Real socket DNS latency runner (UDP :53 DNS wire queries + RFC 7858 DoT :853). */
internal object DnsBenchmarkRunner {
    private const val TIMEOUT_MS = 1_500
    private const val DOT_CONNECT_TIMEOUT_MS = 2_000
    private val txSeq = AtomicInteger(1000)

    private fun nextTxId(): Int = (txSeq.getAndIncrement() and 0xFFFF).let { if (it == 0) 1 else it }

    fun probeProvider(
        provider: DnsBenchmarkLogic.DnsProvider,
        protocol: DnsBenchmarkLogic.DnsProtocol
    ): DnsBenchmarkLogic.DnsBenchResult {
        return when (protocol) {
            DnsBenchmarkLogic.DnsProtocol.UDP_53 -> probeUdp53(provider)
            DnsBenchmarkLogic.DnsProtocol.DOT_853 -> probeDot853(provider)
        }
    }

    private fun probeUdp53(provider: DnsBenchmarkLogic.DnsProvider): DnsBenchmarkLogic.DnsBenchResult {
        val samples = ArrayList<Int>(DnsBenchmarkLogic.probeDomains.size)
        val recvBuf = ByteArray(1024)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val addr = InetAddress.getByName(provider.primaryIp)
                for (domain in DnsBenchmarkLogic.probeDomains) {
                    val txId = nextTxId()
                    val query = DnsBenchmarkLogic.buildDnsQuery(domain, txId)
                    val packet = DatagramPacket(query, query.size, addr, 53)
                    val resp = DatagramPacket(recvBuf, recvBuf.size)
                    val t0 = System.nanoTime()
                    try {
                        socket.send(packet)
                        socket.receive(resp)
                        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                        val rcode = DnsBenchmarkLogic.parseDnsResponse(resp.data, resp.length, txId)
                        if (rcode == 0 || rcode == 3) {
                            samples.add(elapsedMs)
                        }
                    } catch (_: Exception) {
                        // Timed out or unreachable for this probe attempt.
                    }
                }
            }
        } catch (_: Exception) {
            // Unreachable IP / socket creation issue.
        }
        val stats = DnsBenchmarkLogic.summarizeSamples(samples)
        return DnsBenchmarkLogic.DnsBenchResult(
            provider = provider,
            protocol = DnsBenchmarkLogic.DnsProtocol.UDP_53,
            avgMs = stats.avgMs,
            minMs = stats.minMs,
            maxMs = stats.maxMs,
            okCount = samples.size,
            totalCount = DnsBenchmarkLogic.probeDomains.size
        )
    }

    private fun probeDot853(provider: DnsBenchmarkLogic.DnsProvider): DnsBenchmarkLogic.DnsBenchResult {
        val host = provider.dotHost
        if (host.isNullOrBlank()) {
            return DnsBenchmarkLogic.DnsBenchResult(
                provider = provider,
                protocol = DnsBenchmarkLogic.DnsProtocol.DOT_853,
                avgMs = null,
                minMs = null,
                maxMs = null,
                okCount = 0,
                totalCount = DnsBenchmarkLogic.probeDomains.size,
                skippedReason = "UDP only"
            )
        }
        val samples = ArrayList<Int>(DnsBenchmarkLogic.probeDomains.size)
        var rawSocket: SSLSocket? = null
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket() as SSLSocket
            rawSocket = ssl
            ssl.soTimeout = DOT_CONNECT_TIMEOUT_MS
            val params: SSLParameters = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = params
            ssl.connect(InetSocketAddress(provider.primaryIp, 853), DOT_CONNECT_TIMEOUT_MS)
            ssl.startHandshake()

            val out = ssl.outputStream
            val input = DataInputStream(ssl.inputStream)
            for (domain in DnsBenchmarkLogic.probeDomains) {
                val txId = nextTxId()
                val framed = DnsBenchmarkLogic.frameDotQuery(DnsBenchmarkLogic.buildDnsQuery(domain, txId))
                val t0 = System.nanoTime()
                out.write(framed)
                out.flush()
                val respLen = input.readUnsignedShort()
                if (respLen in 12..4096) {
                    val buf = ByteArray(respLen)
                    input.readFully(buf)
                    val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                    val rcode = DnsBenchmarkLogic.parseDnsResponse(buf, buf.size, txId)
                    if (rcode == 0 || rcode == 3) {
                        samples.add(elapsedMs)
                    }
                }
            }
        } catch (_: EOFException) {
            // Peer closed connection after partial responses.
        } catch (_: Exception) {
            // Handshake / connect timeout / network blocked.
        } finally {
            try {
                rawSocket?.close()
            } catch (_: Exception) {
            }
        }
        val stats = DnsBenchmarkLogic.summarizeSamples(samples)
        return DnsBenchmarkLogic.DnsBenchResult(
            provider = provider,
            protocol = DnsBenchmarkLogic.DnsProtocol.DOT_853,
            avgMs = stats.avgMs,
            minMs = stats.minMs,
            maxMs = stats.maxMs,
            okCount = samples.size,
            totalCount = DnsBenchmarkLogic.probeDomains.size
        )
    }

    suspend fun runAll(
        providers: List<DnsBenchmarkLogic.DnsProvider>,
        protocol: DnsBenchmarkLogic.DnsProtocol,
        onProviderDone: (DnsBenchmarkLogic.DnsBenchResult) -> Unit
    ) = coroutineScope {
        val gate = Semaphore(8)
        providers.map { p ->
            launch(Dispatchers.IO) {
                val res = gate.withPermit {
                    if (!isActive) throw CancellationException()
                    probeProvider(p, protocol)
                }
                withContext(Dispatchers.Main) {
                    onProviderDone(res)
                }
            }
        }.joinAll()
    }
}

internal fun MainActivity.dnsBenchmarkStatusText(prefs: SharedPreferences): String {
    val cached = DnsBenchmarkLogic.deserializeResults(prefs.getString(Prefs.KEY_DNS_BENCH_RESULTS, null))
    val fastest = DnsBenchmarkLogic.sortResults(cached).firstOrNull { it.isReachable }
        ?: return getString(R.string.status_ready)
    return getString(R.string.dnsbench_status_fastest, fastest.provider.name, fastest.avgMs ?: 0)
}

internal fun MainActivity.resetDnsBenchmark(prefs: SharedPreferences) {
    prefs.edit().remove(Prefs.KEY_DNS_BENCH_RESULTS).apply()
    findViewById<TextView>(R.id.tvStatusDnsBenchmark)?.text = getString(R.string.status_ready)
    UiToast.info(this, getString(R.string.dnsbench_reset_done), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showDnsBenchmarkSheet() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
    var activeProto = DnsBenchmarkLogic.DnsProtocol.entries.firstOrNull {
        it.name == prefs.getString(Prefs.KEY_DNS_BENCH_PROTO, null)
    } ?: DnsBenchmarkLogic.DnsProtocol.UDP_53
    var activeFilter = DnsBenchmarkLogic.DnsFilter.ALL
    var searchQuery = ""

    val cached = DnsBenchmarkLogic.deserializeResults(prefs.getString(Prefs.KEY_DNS_BENCH_RESULTS, null))
    val liveResults = linkedMapOf<String, DnsBenchmarkLogic.DnsBenchResult>().apply {
        cached.filter { it.protocol == activeProto }.forEach { put(it.provider.id, it) }
    }
    var benchJob: Job? = null

    val popularCount = DnsBenchmarkLogic.catalog.count { it.tier == DnsBenchmarkLogic.DnsTier.POPULAR }
    val unpopularCount = DnsBenchmarkLogic.catalog.count { it.tier == DnsBenchmarkLogic.DnsTier.UNPOPULAR }

    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    // Summary banner card
    val summaryView = infoCard(
        this,
        getString(R.string.dnsbench_catalog_summary, DnsBenchmarkLogic.catalog.size, popularCount, unpopularCount),
        Ui.accent(this)
    )
    body.addView(summaryView)

    // Protocol selector (UDP :53 vs DoT :853)
    val protoLabel = TextView(this).apply {
        text = getString(R.string.dnsbench_proto_label)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurfaceVariant(this@showDnsBenchmarkSheet))
        setPadding(0, dp(14), 0, dp(4))
    }
    body.addView(protoLabel)

    val protoGroup = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    val chipUdp = Chip(this).apply {
        id = View.generateViewId()
        text = getString(R.string.dnsbench_proto_udp)
        isCheckable = true
        isChecked = activeProto == DnsBenchmarkLogic.DnsProtocol.UDP_53
    }
    val chipDot = Chip(this).apply {
        id = View.generateViewId()
        text = getString(R.string.dnsbench_proto_dot)
        isCheckable = true
        isChecked = activeProto == DnsBenchmarkLogic.DnsProtocol.DOT_853
    }
    protoGroup.addView(chipUdp)
    protoGroup.addView(chipDot)
    body.addView(protoGroup)

    // Tier filter chips: All / Popular / Unpopular
    val filterLabel = TextView(this).apply {
        text = getString(R.string.dnsbench_filter_label)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.onSurfaceVariant(this@showDnsBenchmarkSheet))
        setPadding(0, dp(10), 0, dp(4))
    }
    body.addView(filterLabel)

    val filterGroup = ChipGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
    }
    val chipAll = Chip(this).apply {
        id = View.generateViewId()
        text = getString(R.string.dnsbench_filter_all, DnsBenchmarkLogic.catalog.size)
        isCheckable = true
        isChecked = true
    }
    val chipPop = Chip(this).apply {
        id = View.generateViewId()
        text = getString(R.string.dnsbench_filter_popular, popularCount)
        isCheckable = true
    }
    val chipUnpop = Chip(this).apply {
        id = View.generateViewId()
        text = getString(R.string.dnsbench_filter_unpopular, unpopularCount)
        isCheckable = true
    }
    filterGroup.addView(chipAll)
    filterGroup.addView(chipPop)
    filterGroup.addView(chipUnpop)
    body.addView(filterGroup)

    // Search input
    val (searchLayout, searchInput) = styledField(
        this,
        getString(R.string.dnsbench_search_hint),
        InputType.TYPE_CLASS_TEXT,
        topMarginDp = 10
    )
    body.addView(searchLayout)

    // Progress bar + live status label
    val progressBar = LinearProgressIndicator(this).apply {
        max = 100
        progress = 0
        trackThickness = dp(6)
        trackCornerRadius = dp(3)
        setIndicatorColor(Ui.accent(this@showDnsBenchmarkSheet))
        trackColor = ColorUtils.setAlphaComponent(Ui.accent(this@showDnsBenchmarkSheet), 0x26)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }
    body.addView(progressBar)

    val statusText = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.onSurfaceVariant(this@showDnsBenchmarkSheet))
        setPadding(0, dp(10), 0, dp(4))
    }
    body.addView(statusText)

    val applyFastestBtn = MaterialButton(this).apply {
        isAllCaps = false
        cornerRadius = dp(14)
        textSize = 14f
        backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.parseColor("#2E9E5B"), 0x24))
        setTextColor(Color.parseColor("#2E9E5B"))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(8) }
    }
    body.addView(applyFastestBtn)

    val resultsContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }
    body.addView(resultsContainer)

    val sheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.title_dns_bench))
        .message(getString(R.string.dnsbench_sheet_msg))
        .content(body)
        .onDismiss { benchJob?.cancel() }

    fun latencyColor(avgMs: Int?): Int = when {
        avgMs == null -> Color.parseColor("#8A8F98")
        avgMs < 40 -> Color.parseColor("#2E9E5B")
        avgMs <= 95 -> Color.parseColor("#E09B00")
        else -> Color.parseColor("#E53935")
    }

    fun applyDotHostname(host: String, label: String) {
        if (!hasShizukuPermission()) return
        prefs.edit().putString(Prefs.KEY_LAST_DNS, host).apply()
        val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier $host"
        runAdbCommand(cmd, getString(R.string.toast_dns_set, label)) {
            fetchSystemStatuses()
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        UiToast.info(this, getString(R.string.dnsbench_copied, value), Toast.LENGTH_SHORT).show()
    }

    fun renderList() {
        resultsContainer.removeAllViews()
        val hasMeasured = liveResults.isNotEmpty()
        val sortedMeasured = DnsBenchmarkLogic.sortResults(liveResults.values.toList(), activeFilter, searchQuery)
        val fastestDot = DnsBenchmarkLogic.sortResults(liveResults.values.toList())
            .firstOrNull { it.isReachable && !it.provider.dotHost.isNullOrBlank() }

        if (fastestDot != null) {
            applyFastestBtn.visibility = View.VISIBLE
            applyFastestBtn.text = getString(
                R.string.dnsbench_apply_fastest,
                fastestDot.provider.name,
                fastestDot.avgMs ?: 0
            )
            applyFastestBtn.setOnClickListener {
                fastestDot.provider.dotHost?.let { host ->
                    applyDotHostname(host, fastestDot.provider.name)
                }
            }
        } else {
            applyFastestBtn.visibility = View.GONE
        }

        if (!hasMeasured) {
            val catalogView = DnsBenchmarkLogic.filterProviders(activeFilter, searchQuery)
            statusText.text = if (benchJob?.isActive == true) {
                getString(R.string.dnsbench_running_init)
            } else {
                getString(R.string.dnsbench_idle_list, catalogView.size)
            }
            if (catalogView.isEmpty()) {
                resultsContainer.addView(infoCard(this, getString(R.string.dnsbench_no_match)))
                return
            }
            catalogView.forEach { p ->
                val tierTag = getString(
                    if (p.tier == DnsBenchmarkLogic.DnsTier.POPULAR) R.string.dnsbench_tag_popular
                    else R.string.dnsbench_tag_unpopular
                )
                val dotText = p.dotHost ?: getString(R.string.dnsbench_udp_only_short)
                val sub = "$tierTag · ${p.region} · ${p.primaryIp} · $dotText\n${p.note}"
                resultsContainer.addView(optionRow(this, p.name, sub) {
                    showDnsDetailDialog(p, null, activeProto, ::applyDotHostname, ::copyToClipboard)
                })
            }
            return
        }

        // Include any not-yet-benchmarked providers in this filter after measured ones during live run
        val measuredIds = sortedMeasured.map { it.provider.id }.toSet()
        val pendingProviders = if (benchJob?.isActive == true) {
            DnsBenchmarkLogic.filterProviders(activeFilter, searchQuery).filter { it.id !in measuredIds }
        } else {
            emptyList()
        }

        val overallFastest = DnsBenchmarkLogic.sortResults(liveResults.values.toList()).firstOrNull { it.isReachable }
        if (benchJob?.isActive != true) {
            statusText.text = if (overallFastest != null) {
                getString(
                    R.string.dnsbench_summary_done,
                    liveResults.values.count { it.isReachable },
                    liveResults.size,
                    overallFastest.provider.name,
                    overallFastest.avgMs ?: 0
                )
            } else {
                getString(R.string.dnsbench_none_reachable)
            }
        }

        if (sortedMeasured.isEmpty() && pendingProviders.isEmpty()) {
            resultsContainer.addView(infoCard(this, getString(R.string.dnsbench_no_match)))
            return
        }

        sortedMeasured.forEachIndexed { idx, res ->
            val p = res.provider
            val tierTag = getString(
                if (p.tier == DnsBenchmarkLogic.DnsTier.POPULAR) R.string.dnsbench_tag_popular
                else R.string.dnsbench_tag_unpopular
            )
            val rankPrefix = if (res.isReachable) "#${idx + 1} " else "× "
            val metric = when {
                res.skippedReason != null -> res.skippedReason
                res.avgMs != null -> "${res.avgMs} ms (min ${res.minMs} · max ${res.maxMs} · ${res.okCount}/${res.totalCount})"
                else -> getString(R.string.dnsbench_timeout)
            }
            val dotText = p.dotHost ?: getString(R.string.dnsbench_udp_only_short)
            val sub = "$metric · $tierTag · ${p.region}\n${p.primaryIp} · $dotText · ${p.note}"
            val row = buildDnsBenchRow(
                title = "$rankPrefix${p.name}",
                subtitle = sub,
                badgeText = res.avgMs?.let { "${it} ms" } ?: (res.skippedReason ?: getString(R.string.dnsbench_fail_short)),
                badgeColor = latencyColor(res.avgMs)
            ) {
                showDnsDetailDialog(p, res, activeProto, ::applyDotHostname, ::copyToClipboard)
            }
            resultsContainer.addView(row)
        }

        pendingProviders.forEach { p ->
            val row = buildDnsBenchRow(
                title = p.name,
                subtitle = "${p.primaryIp} · ${p.region} · ${p.note}",
                badgeText = "...",
                badgeColor = Color.parseColor("#8A8F98")
            ) {
                showDnsDetailDialog(p, null, activeProto, ::applyDotHostname, ::copyToClipboard)
            }
            resultsContainer.addView(row)
        }
    }

    sheet.button(getString(R.string.btn_close), SheetStyle.TONAL) {
        benchJob?.cancel()
    }
    sheet.button(getString(R.string.dnsbench_btn_run), SheetStyle.PRIMARY, dismiss = false) { btn ->
        if (benchJob?.isActive == true) {
            benchJob?.cancel()
            benchJob = null
            btn.text = getString(R.string.dnsbench_btn_run)
            progressBar.visibility = View.GONE
            renderList()
            return@button
        }

        val targets = DnsBenchmarkLogic.filterProviders(activeFilter, "")
        liveResults.clear()
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        btn.text = getString(R.string.dnsbench_btn_stop)
        renderList()

        benchJob = lifecycleScope.launch {
            var done = 0
            try {
                DnsBenchmarkRunner.runAll(targets, activeProto) { result ->
                    liveResults[result.provider.id] = result
                    done++
                    val pct = (done * 100) / targets.size.coerceAtLeast(1)
                    progressBar.setProgressCompat(pct, true)
                    val fastestSoFar = DnsBenchmarkLogic.sortResults(liveResults.values.toList())
                        .firstOrNull { it.isReachable }
                    statusText.text = if (fastestSoFar != null) {
                        getString(
                            R.string.dnsbench_progress_fastest,
                            done,
                            targets.size,
                            fastestSoFar.provider.name,
                            fastestSoFar.avgMs ?: 0
                        )
                    } else {
                        getString(R.string.dnsbench_progress, done, targets.size)
                    }
                    renderList()
                }
                prefs.edit()
                    .putString(Prefs.KEY_DNS_BENCH_RESULTS, DnsBenchmarkLogic.serializeResults(liveResults.values.toList()))
                    .putString(Prefs.KEY_DNS_BENCH_PROTO, activeProto.name)
                    .apply()
                findViewById<TextView>(R.id.tvStatusDnsBenchmark)?.text = dnsBenchmarkStatusText(prefs)
            } catch (_: CancellationException) {
                if (liveResults.isNotEmpty()) {
                    prefs.edit()
                        .putString(Prefs.KEY_DNS_BENCH_RESULTS, DnsBenchmarkLogic.serializeResults(liveResults.values.toList()))
                        .apply()
                }
            } finally {
                benchJob = null
                btn.text = getString(R.string.dnsbench_btn_run)
                progressBar.visibility = View.GONE
                renderList()
            }
        }
    }

    protoGroup.setOnCheckedStateChangeListener { _, checkedIds ->
        val next = if (checkedIds.firstOrNull() == chipDot.id) {
            DnsBenchmarkLogic.DnsProtocol.DOT_853
        } else {
            DnsBenchmarkLogic.DnsProtocol.UDP_53
        }
        if (next != activeProto) {
            benchJob?.cancel()
            benchJob = null
            activeProto = next
            prefs.edit().putString(Prefs.KEY_DNS_BENCH_PROTO, activeProto.name).apply()
            liveResults.clear()
            val saved = DnsBenchmarkLogic.deserializeResults(prefs.getString(Prefs.KEY_DNS_BENCH_RESULTS, null))
            saved.filter { it.protocol == activeProto }.forEach { liveResults[it.provider.id] = it }
            sheet.buttonViews.lastOrNull()?.text = getString(R.string.dnsbench_btn_run)
            progressBar.visibility = View.GONE
            renderList()
        }
    }

    filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
        activeFilter = when (checkedIds.firstOrNull()) {
            chipPop.id -> DnsBenchmarkLogic.DnsFilter.POPULAR
            chipUnpop.id -> DnsBenchmarkLogic.DnsFilter.UNPOPULAR
            else -> DnsBenchmarkLogic.DnsFilter.ALL
        }
        renderList()
    }

    searchInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            searchQuery = s?.toString() ?: ""
            renderList()
        }
    })

    renderList()
    sheet.show()
}

private fun MainActivity.buildDnsBenchRow(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Int,
    onClick: () -> Unit
): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(12), dp(10))
        background = Ui.ripple(this@buildDnsBenchRow, Ui.tonalFill(this@buildDnsBenchRow), dp(14))
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }

        val texts = LinearLayout(this@buildDnsBenchRow).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        texts.addView(TextView(this@buildDnsBenchRow).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Ui.onSurface(this@buildDnsBenchRow))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this@buildDnsBenchRow).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Ui.onSurfaceVariant(this@buildDnsBenchRow))
            setPadding(0, dp(2), 0, 0)
        })
        addView(texts)

        addView(TextView(this@buildDnsBenchRow).apply {
            text = badgeText
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(badgeColor)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = Ui.rounded(ColorUtils.setAlphaComponent(badgeColor, 0x1F), dp(10))
        })

        setOnClickListener { onClick() }
    }
}

private fun MainActivity.showDnsDetailDialog(
    provider: DnsBenchmarkLogic.DnsProvider,
    result: DnsBenchmarkLogic.DnsBenchResult?,
    proto: DnsBenchmarkLogic.DnsProtocol,
    onApplyDot: (String, String) -> Unit,
    onCopy: (String, String) -> Unit
) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tierLabel = getString(
        if (provider.tier == DnsBenchmarkLogic.DnsTier.POPULAR) R.string.dnsbench_tag_popular
        else R.string.dnsbench_tag_unpopular
    )
    val lines = buildString {
        appendLine(getString(R.string.dnsbench_detail_tier, tierLabel, provider.region))
        appendLine(getString(R.string.dnsbench_detail_primary, provider.primaryIp))
        if (!provider.secondaryIp.isNullOrBlank()) {
            appendLine(getString(R.string.dnsbench_detail_secondary, provider.secondaryIp))
        }
        appendLine(
            getString(
                R.string.dnsbench_detail_dot,
                provider.dotHost ?: getString(R.string.dnsbench_udp_only_short)
            )
        )
        if (result != null) {
            val resLine = when {
                result.skippedReason != null -> result.skippedReason
                result.avgMs != null -> getString(
                    R.string.dnsbench_detail_latency,
                    result.avgMs,
                    result.minMs ?: 0,
                    result.maxMs ?: 0,
                    result.okCount,
                    result.totalCount
                )
                else -> getString(R.string.dnsbench_timeout)
            }
            append(resLine)
        }
    }.trimEnd()

    body.addView(infoCard(this, lines))

    val singleResultView = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.onSurfaceVariant(this@showDnsDetailDialog))
        setPadding(0, dp(10), 0, 0)
        visibility = View.GONE
    }
    body.addView(singleResultView)

    body.addView(optionRow(this, getString(R.string.dnsbench_copy_primary), provider.primaryIp) {
        onCopy(provider.name, provider.primaryIp)
    })
    if (!provider.secondaryIp.isNullOrBlank()) {
        body.addView(optionRow(this, getString(R.string.dnsbench_copy_secondary), provider.secondaryIp) {
            onCopy(provider.name, provider.secondaryIp)
        })
    }
    body.addView(optionRow(this, getString(R.string.dnsbench_retest_one), provider.primaryIp) {
        singleResultView.visibility = View.VISIBLE
        singleResultView.text = getString(R.string.dnsbench_testing_single)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { DnsBenchmarkRunner.probeProvider(provider, proto) }
            singleResultView.text = when {
                r.skippedReason != null -> r.skippedReason
                r.avgMs != null -> getString(
                    R.string.dnsbench_detail_latency,
                    r.avgMs,
                    r.minMs ?: 0,
                    r.maxMs ?: 0,
                    r.okCount,
                    r.totalCount
                )
                else -> getString(R.string.dnsbench_timeout)
            }
        }
    })

    val detailSheet = Sheet(this)
        .icon(R.drawable.ic_dns)
        .title(provider.name)
        .message(provider.note)
        .content(body)
        .button(getString(R.string.btn_close), SheetStyle.TONAL)

    if (!provider.dotHost.isNullOrBlank()) {
        detailSheet.button(getString(R.string.dnsbench_apply_dot), SheetStyle.PRIMARY) {
            onApplyDot(provider.dotHost, provider.name)
        }
    }
    detailSheet.show()
}
