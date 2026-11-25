package com.muimex11.official

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure DeX Hz state & compatibility logic — unit-testable, no Android deps. */
object DexHzLogic {
    enum class CompatLevel { FULL, PARTIAL, LIMITED, UNAVAILABLE }

    data class ModeChoice(val width: Int, val height: Int, val hz: Int) {
        fun key(): String = "$width:$height:$hz"
        fun label(): String = "$width × $height @ $hz Hz"
    }

    data class ModeSuggestion(
        val width: Int,
        val height: Int,
        val hz: Int,
        val label: String,
        val reason: String
    )

    data class ModeSwitchState(
        val label: String,
        val shortLabel: String,
        val confirm: Boolean,
        val replug: Boolean,
        val rescue: Boolean,
        val secondsLeft: Int
    )

    data class MonitorInfo(
        val id: Long,
        val name: String,
        val width: Int,
        val height: Int,
        val linkHz: Float,
        val fastestHz: Float,
        val fasterListed: Boolean,
        val limitedHz: Float?,
        val lostHz: Float?,
        val phonePaced: Boolean,
        val modesSummary: String,
        val modeKey: String,
        val keptMode: String?,
        val startMode: String?,
        val modeChoices: List<ModeChoice>,
        val suggest: ModeSuggestion?,
        val lowerResHint: String?
    )

    data class Caps(
        val highRes: Boolean,
        val monitorTiming: Boolean,
        val monitorTimingVerified: Boolean,
        val phoneRates: Boolean,
        val heat: Boolean,
        val codes: String,
        val root: Boolean,
        val monitorModes: Boolean,
        val modeMethod: String,
        val displayConfig: Boolean
    ) {
        fun level(): CompatLevel = when {
            monitorTiming -> CompatLevel.FULL
            phoneRates -> CompatLevel.PARTIAL
            highRes -> CompatLevel.LIMITED
            else -> CompatLevel.UNAVAILABLE
        }
    }

    data class DexHzState(
        val running: Boolean,
        val reconnecting: Boolean,
        val waiting: Boolean,
        val targetHz: Float,
        val appHz: Float,
        val coolingHz: Float?,
        val notice: String?,
        val rolledBack: String?,
        val modeResult: String?,
        val device: String,
        val uid: Int,
        val caps: Caps?,
        val stops: List<Int>,
        val monitor: MonitorInfo?,
        val modeSwitch: ModeSwitchState?
    )

    fun smooth(linkHz: Float, hz: Float): Boolean {
        if (hz <= 0f || linkHz <= 0f) return true
        val ratio = linkHz / hz
        val whole = ratio.roundToInt().toLong()
        return whole >= 1L && abs(ratio - whole) < 0.02f
    }

    fun formatTarget(targetHz: Float): String =
        if (targetHz <= 0f || targetHz >= 1000f) "Fastest" else "${targetHz.roundToInt()} Hz"

    fun parseState(jsonStr: String?): DexHzState? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val j = JsonParser(jsonStr).parseRoot() ?: return null
            val capsObj = j.optObj("caps")
            val caps = capsObj?.let {
                Caps(
                    highRes = it.optBool("highRes"),
                    monitorTiming = it.optBool("monitorTiming"),
                    monitorTimingVerified = it.optBool("monitorTimingVerified"),
                    phoneRates = it.optBool("phoneRates"),
                    heat = it.optBool("heat"),
                    codes = it.optStr("codes", "none"),
                    root = it.optBool("root"),
                    monitorModes = it.optBool("monitorModes"),
                    modeMethod = it.optStr("modeMethod", "none"),
                    displayConfig = it.optBool("displayConfig")
                )
            }
            val stopsArr = j.optArr("stops")
            val stops = buildList {
                if (stopsArr != null) {
                    for (item in stopsArr) {
                        val v = (item as? Number)?.toInt() ?: -1
                        if (v > 0) add(v)
                    }
                }
            }
            val monObj = j.optObj("monitor")
            val monitor = monObj?.let { m ->
                val choicesArr = m.optArr("modeChoices")
                val choices = buildList {
                    if (choicesArr != null) {
                        for (item in choicesArr) {
                            val c = asStrMap(item) ?: continue
                            add(ModeChoice(c.optInt("width"), c.optInt("height"), c.optInt("hz")))
                        }
                    }
                }
                val sugObj = m.optObj("suggest")
                val sug = sugObj?.let {
                    ModeSuggestion(
                        width = it.optInt("width"),
                        height = it.optInt("height"),
                        hz = it.optInt("hz"),
                        label = it.optStr("label"),
                        reason = it.optStr("reason")
                    )
                }
                val lowerObj = m.optObj("lowerRes")
                val lowerHint = lowerObj?.let {
                    "${it.optInt("width")}×${it.optInt("height")} @ ${it.optDouble("hz").roundToInt()} Hz"
                }
                MonitorInfo(
                    id = m.optLong("id", -1L),
                    name = m.optStr("name", "External display"),
                    width = m.optInt("width"),
                    height = m.optInt("height"),
                    linkHz = m.optDouble("linkHz", 0.0).toFloat(),
                    fastestHz = m.optDouble("fastestHz", 0.0).toFloat(),
                    fasterListed = m.optBool("fasterListed"),
                    limitedHz = m.optNullableDouble("limitedHz")?.toFloat(),
                    lostHz = m.optNullableDouble("lostHz")?.toFloat(),
                    phonePaced = m.optBool("phonePaced"),
                    modesSummary = m.optStr("modes", ""),
                    modeKey = m.optStr("modeKey", ""),
                    keptMode = m.optNullableStr("keptMode"),
                    startMode = m.optNullableStr("startMode"),
                    modeChoices = choices,
                    suggest = sug,
                    lowerResHint = lowerHint
                )
            }
            val swObj = j.optObj("modeSwitch")
            val sw = swObj?.let {
                ModeSwitchState(
                    label = it.optStr("label"),
                    shortLabel = it.optStr("short"),
                    confirm = it.optBool("confirm"),
                    replug = it.optBool("replug"),
                    rescue = it.optBool("rescue"),
                    secondsLeft = it.optInt("secondsLeft", 0)
                )
            }
            DexHzState(
                running = j.optBool("running"),
                reconnecting = j.optBool("reconnecting"),
                waiting = j.optBool("waiting"),
                targetHz = j.optDouble("targetHz", 1000.0).toFloat(),
                appHz = j.optDouble("appHz", 0.0).toFloat(),
                coolingHz = j.optNullableDouble("coolingHz")?.toFloat(),
                notice = j.optNullableStr("notice"),
                rolledBack = j.optNullableStr("rolledBack"),
                modeResult = j.optNullableStr("modeResult"),
                device = j.optStr("device", ""),
                uid = j.optInt("uid", -1),
                caps = caps,
                stops = stops,
                monitor = monitor,
                modeSwitch = sw
            )
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asStrMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    private fun Map<String, Any?>.optObj(key: String): Map<String, Any?>? = asStrMap(this[key])

    private fun Map<String, Any?>.optArr(key: String): List<Any?>? = this[key] as? List<Any?>

    private fun Map<String, Any?>.optBool(key: String, fallback: Boolean = false): Boolean =
        (this[key] as? Boolean) ?: fallback

    private fun Map<String, Any?>.optInt(key: String, fallback: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: fallback

    private fun Map<String, Any?>.optLong(key: String, fallback: Long = -1L): Long =
        (this[key] as? Number)?.toLong() ?: fallback

    private fun Map<String, Any?>.optDouble(key: String, fallback: Double = 0.0): Double =
        (this[key] as? Number)?.toDouble() ?: fallback

    private fun Map<String, Any?>.optNullableDouble(key: String): Double? =
        (this[key] as? Number)?.toDouble()

    private fun Map<String, Any?>.optStr(key: String, fallback: String = ""): String =
        (this[key] as? String) ?: fallback

    private fun Map<String, Any?>.optNullableStr(key: String): String? =
        this[key] as? String

    /** Lightweight RFC 8259 JSON parser so unit tests run cleanly without Android's stubbed org.json. */
    private class JsonParser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Map<String, Any?>? {
            skipWs()
            if (pos >= src.length || src[pos] != '{') return null
            val root = readObject()
            skipWs()
            return if (pos == src.length) root else null
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                map[key] = readValue()
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                expect('}')
                break
            }
            return map
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWs()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(readValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                expect(']')
                break
            }
            return list
        }

        private fun readValue(): Any? {
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
            return when (src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Bad unicode escape")
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < src.length && src[pos] in "0123456789+-.eE") pos++
            if (pos == start) throw IllegalArgumentException("Unexpected character at $pos")
            val token = src.substring(start, pos)
            return token.toLongOrNull() ?: token.toDouble()
        }

        private fun expectLiteral(literal: String, value: Any?): Any? {
            if (!src.regionMatches(pos, literal, 0, literal.length)) {
                throw IllegalArgumentException("Expected $literal at $pos")
            }
            pos += literal.length
            return value
        }

        private fun expect(ch: Char) {
            if (pos >= src.length || src[pos] != ch) {
                throw IllegalArgumentException("Expected '$ch' at $pos")
            }
            pos++
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}

/**
 * Manages binding to [DexHzService] over Shizuku UserService and stores user preferences
 * for Samsung DeX high refresh rate unlocking.
 */
object DexHzBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DexHzLogic.DexHzState?>(null)
    val state: StateFlow<DexHzLogic.DexHzState?> = _state

    @Volatile private var service: IDexHzService? = null
    @Volatile private var bound = false
    @Volatile private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private val pendingCallbacks = CopyOnWriteArrayList<(IDexHzService?) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound) return
            val s = IDexHzService.Stub.asInterface(binder)
            service = s
            pendingCallbacks.forEach { it(s) }
            pendingCallbacks.clear()
            scope.launch { refresh() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            _state.value = null
            pendingCallbacks.forEach { it(null) }
            pendingCallbacks.clear()
        }
    }

    private fun args(context: Context): Shizuku.UserServiceArgs {
        userServiceArgs?.let { return it }
        val app = context.applicationContext
        val versionCode = try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) { 1 }
        return Shizuku.UserServiceArgs(ComponentName(app, DexHzService::class.java))
            .processNameSuffix("dexhz")
            .tag("muimex-dexhz")
            .version(versionCode)
            .daemon(true)
            .also { userServiceArgs = it }
    }

    fun isShizukuReady(): Boolean = ShizukuRunner.isReady()

    fun currentService(): IDexHzService? = service

    fun ensureConnected(context: Context, onReady: ((IDexHzService?) -> Unit)? = null) {
        val current = service
        if (current != null && current.asBinder().isBinderAlive) {
            onReady?.invoke(current)
            return
        }
        if (!isShizukuReady()) {
            onReady?.invoke(null)
            return
        }
        if (onReady != null) pendingCallbacks.add(onReady)
        if (bound) return
        bound = true
        try {
            Shizuku.bindUserService(args(context), connection)
        } catch (_: Exception) {
            bound = false
            service = null
            pendingCallbacks.forEach { it(null) }
            pendingCallbacks.clear()
        }
    }

    fun refresh(context: Context? = null): DexHzLogic.DexHzState? {
        val s = service
        if (s == null) {
            if (context != null && isShizukuReady()) ensureConnected(context)
            return null
        }
        return try {
            val parsed = DexHzLogic.parseState(s.state)
            _state.value = parsed
            parsed
        } catch (_: Exception) {
            service = null
            bound = false
            _state.value = null
            null
        }
    }

    suspend fun withService(context: Context, block: (IDexHzService) -> String?): String? =
        withContext(Dispatchers.IO) {
            val s = service?.takeIf { it.asBinder().isBinderAlive }
            if (s != null) {
                return@withContext try {
                    val msg = block(s)
                    refresh()
                    msg
                } catch (e: Exception) {
                    e.message ?: "Service error"
                }
            }
            if (!isShizukuReady()) return@withContext null
            val latch = java.util.concurrent.CountDownLatch(1)
            var resolved: IDexHzService? = null
            ensureConnected(context) { svc ->
                resolved = svc
                latch.countDown()
            }
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            val svc = resolved ?: return@withContext null
            try {
                val msg = block(svc)
                refresh()
                msg
            } catch (e: Exception) {
                e.message ?: "Service error"
            }
        }

    fun getTargetHz(context: Context): Float =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getFloat(Prefs.KEY_DEXHZ_TARGET, 0f)

    fun setTargetHz(context: Context, hz: Float) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putFloat(Prefs.KEY_DEXHZ_TARGET, hz).apply()
    }

    fun getAutoReconnect(context: Context): Boolean =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_DEXHZ_RECONNECT, false)

    fun setAutoReconnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_DEXHZ_RECONNECT, enabled).apply()
    }

    fun getCoolDown(context: Context): Boolean =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_DEXHZ_COOLDOWN, false)

    fun setCoolDown(context: Context, enabled: Boolean) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_DEXHZ_COOLDOWN, enabled).apply()
    }

    fun getRestoreOnLaunch(context: Context): Boolean =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_DEXHZ_RESTORE, true)

    fun setRestoreOnLaunch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_DEXHZ_RESTORE, enabled).apply()
    }

    fun getWantOn(context: Context): Boolean =
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_DEXHZ_WANT_ON, false)

    fun setWantOn(context: Context, wantOn: Boolean) {
        context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            .edit().putBoolean(Prefs.KEY_DEXHZ_WANT_ON, wantOn).apply()
    }

    fun pushSettings(context: Context, s: IDexHzService) {
        s.configure(getTargetHz(context), getAutoReconnect(context), getCoolDown(context))
    }

    fun startWithSavedPrefs(context: Context, s: IDexHzService): String {
        setWantOn(context, true)
        return s.startBoost(getTargetHz(context), getAutoReconnect(context), getCoolDown(context))
    }

    fun stopAndForget(context: Context, s: IDexHzService): String {
        setWantOn(context, false)
        return s.stopBoost()
    }

    fun restoreIfWanted(context: Context) {
        if (!getRestoreOnLaunch(context) || !getWantOn(context) || !isShizukuReady()) return
        scope.launch {
            withService(context) { svc ->
                val cur = DexHzLogic.parseState(svc.state)
                if (cur?.rolledBack != null) {
                    setWantOn(context, false)
                    return@withService null
                }
                if (cur?.running == false) {
                    svc.startBoost(getTargetHz(context), getAutoReconnect(context), getCoolDown(context))
                } else {
                    null
                }
            }
        }
    }
}
