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
import org.json.JSONObject
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
            val j = JSONObject(jsonStr)
            val capsObj = j.optJSONObject("caps")
            val caps = capsObj?.let {
                Caps(
                    highRes = it.optBoolean("highRes"),
                    monitorTiming = it.optBoolean("monitorTiming"),
                    monitorTimingVerified = it.optBoolean("monitorTimingVerified"),
                    phoneRates = it.optBoolean("phoneRates"),
                    heat = it.optBoolean("heat"),
                    codes = it.optString("codes", "none"),
                    root = it.optBoolean("root"),
                    monitorModes = it.optBoolean("monitorModes"),
                    modeMethod = it.optString("modeMethod", "none"),
                    displayConfig = it.optBoolean("displayConfig")
                )
            }
            val stopsArr = j.optJSONArray("stops")
            val stops = buildList {
                if (stopsArr != null) {
                    for (i in 0 until stopsArr.length()) {
                        val v = stopsArr.optInt(i, -1)
                        if (v > 0) add(v)
                    }
                }
            }
            val monObj = j.optJSONObject("monitor")
            val monitor = monObj?.let { m ->
                val choicesArr = m.optJSONArray("modeChoices")
                val choices = buildList {
                    if (choicesArr != null) {
                        for (i in 0 until choicesArr.length()) {
                            val c = choicesArr.optJSONObject(i) ?: continue
                            add(ModeChoice(c.optInt("width"), c.optInt("height"), c.optInt("hz")))
                        }
                    }
                }
                val sugObj = m.optJSONObject("suggest")
                val sug = sugObj?.let {
                    ModeSuggestion(
                        width = it.optInt("width"),
                        height = it.optInt("height"),
                        hz = it.optInt("hz"),
                        label = it.optString("label"),
                        reason = it.optString("reason")
                    )
                }
                val lowerObj = m.optJSONObject("lowerRes")
                val lowerHint = lowerObj?.let {
                    "${it.optInt("width")}×${it.optInt("height")} @ ${it.optDouble("hz").roundToInt()} Hz"
                }
                MonitorInfo(
                    id = m.optLong("id", -1L),
                    name = m.optString("name", "External display"),
                    width = m.optInt("width"),
                    height = m.optInt("height"),
                    linkHz = m.optDouble("linkHz", 0.0).toFloat(),
                    fastestHz = m.optDouble("fastestHz", 0.0).toFloat(),
                    fasterListed = m.optBoolean("fasterListed"),
                    limitedHz = if (m.has("limitedHz")) m.optDouble("limitedHz").toFloat() else null,
                    lostHz = if (m.has("lostHz")) m.optDouble("lostHz").toFloat() else null,
                    phonePaced = m.optBoolean("phonePaced"),
                    modesSummary = m.optString("modes", ""),
                    modeKey = m.optString("modeKey", ""),
                    keptMode = if (m.has("keptMode")) m.optString("keptMode") else null,
                    startMode = if (m.has("startMode")) m.optString("startMode") else null,
                    modeChoices = choices,
                    suggest = sug,
                    lowerResHint = lowerHint
                )
            }
            val swObj = j.optJSONObject("modeSwitch")
            val sw = swObj?.let {
                ModeSwitchState(
                    label = it.optString("label"),
                    shortLabel = it.optString("short"),
                    confirm = it.optBoolean("confirm"),
                    replug = it.optBoolean("replug"),
                    rescue = it.optBoolean("rescue"),
                    secondsLeft = it.optInt("secondsLeft", 0)
                )
            }
            DexHzState(
                running = j.optBoolean("running"),
                reconnecting = j.optBoolean("reconnecting"),
                waiting = j.optBoolean("waiting"),
                targetHz = j.optDouble("targetHz", 1000.0).toFloat(),
                appHz = j.optDouble("appHz", 0.0).toFloat(),
                coolingHz = if (j.has("coolingHz")) j.optDouble("coolingHz").toFloat() else null,
                notice = if (j.has("notice")) j.optString("notice") else null,
                rolledBack = if (j.has("rolledBack")) j.optString("rolledBack") else null,
                modeResult = if (j.has("modeResult")) j.optString("modeResult") else null,
                device = j.optString("device", ""),
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
