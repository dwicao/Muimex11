package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-app gaming profiles + auto-boost + settings backup/restore.
 * Stored as JSON in a dedicated prefs file so it can be exported/imported.
 */
data class GameProfile(
    val pkg: String,
    val fixedPerf: Boolean = false,
    val thermalBypass: Boolean = false,
    val gameModePerf: Boolean = true,
    val refresh120: Boolean = false,
    /** 0 = unset (follow system). */
    val refreshMin: Int = 0,
    val refreshMax: Int = 0,
    /** 0f = unset (follow system). One of 0.75f / 0.5f when set. */
    val resScale: Float = 0f,
    /** Auto-enable Gaming Focus (heads-up off) while this game is foreground. */
    val dndFocus: Boolean = false,
    /** OrientationMode.AUTO = follow system, else force while foreground. */
    val orientation: Int = 0,
    /** Keep the screen awake while this game is foreground. */
    val keepAwake: Boolean = false,
    /** Disable window blurs while this game is foreground. */
    val blurOff: Boolean = false,
    /** Force-stop the game when it leaves the foreground. */
    val killOnExit: Boolean = false
)

object GameProfileStore {
    private const val FILE = "MuimexGameProfiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_AUTO_BOOST = "auto_boost"
    private const val KEY_SCHEMA = "schema_v"
    private const val SCHEMA = 6
    private const val MAX_PROFILES = 200

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String): GameProfile? = all(ctx)[pkg]

    fun all(ctx: Context): Map<String, GameProfile> {
        val raw = prefs(ctx).getString(KEY_PROFILES, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, GameProfile>()
            o.keys().forEach { k ->
                val v = o.optJSONObject(k) ?: return@forEach
                out[k] = GameProfile(
                    pkg = k,
                    fixedPerf = v.optBoolean("fixedPerf", false),
                    thermalBypass = v.optBoolean("thermalBypass", false),
                    gameModePerf = v.optBoolean("gameModePerf", true),
                    refresh120 = v.optBoolean("refresh120", false),
                    refreshMin = v.optInt("refreshMin", 0),
                    refreshMax = v.optInt("refreshMax", 0),
                    resScale = v.optDouble("resScale", 0.0).toFloat(),
                    dndFocus = v.optBoolean("dndFocus", false),
                    orientation = v.optInt("orientation", 0)
                        .coerceIn(OrientationMode.AUTO, OrientationMode.REVERSE_LANDSCAPE),
                    keepAwake = v.optBoolean("keepAwake", false),
                    blurOff = v.optBoolean("blurOff", false),
                    killOnExit = v.optBoolean("killOnExit", false)
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(ctx: Context, profile: GameProfile) {
        // Validate before persist — corrupt refresh/res values brick display until reset.
        val clean = profile.copy(
            pkg = ShellSanitizer.cleanPkg(profile.pkg) ?: return,
            refreshMin = profile.refreshMin.coerceIn(0, 240),
            refreshMax = profile.refreshMax.coerceIn(0, 240),
            resScale = when (profile.resScale) { 0f, 0.75f, 0.5f -> profile.resScale; else -> 0f },
        )
        val map = all(ctx).toMutableMap()
        if (map.size >= MAX_PROFILES && !map.containsKey(clean.pkg)) {
            // Evict oldest (LinkedHashMap insertion order) to bound prefs size.
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
        map[clean.pkg] = clean.copy(
            orientation = if (OrientationMode.isValid(clean.orientation)) clean.orientation
            else OrientationMode.AUTO
        )
        persist(ctx, map)
    }

    fun remove(ctx: Context, pkg: String) {
        val map = all(ctx).toMutableMap()
        map.remove(pkg)
        persist(ctx, map)
    }

    private fun persist(ctx: Context, map: Map<String, GameProfile>) {
        val o = JSONObject()
        map.forEach { (k, v) ->
            if (ShellSanitizer.cleanPkg(k) == null) return@forEach
            o.put(k, JSONObject().apply {
                put("fixedPerf", v.fixedPerf)
                put("thermalBypass", v.thermalBypass)
                put("gameModePerf", v.gameModePerf)
                put("refresh120", v.refresh120)
                put("refreshMin", v.refreshMin.coerceIn(0, 240))
                put("refreshMax", v.refreshMax.coerceIn(0, 240))
                put("resScale", v.resScale.toDouble())
                put("dndFocus", v.dndFocus)
                put("orientation", v.orientation)
                put("keepAwake", v.keepAwake)
                put("blurOff", v.blurOff)
                put("killOnExit", v.killOnExit)
            })
        }
        prefs(ctx).edit().putString(KEY_PROFILES, o.toString())
            .putInt(KEY_SCHEMA, SCHEMA).apply()
    }

    fun isAutoBoost(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_BOOST, false)

    fun setAutoBoost(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BOOST, enabled).apply()
    }

    /** Export for backup (Settings -> Backup). */
    fun exportJson(ctx: Context): String {
        val o = JSONObject()
        o.put("profiles", JSONObject(prefs(ctx).getString(KEY_PROFILES, "{}") ?: "{}"))
        o.put("autoBoost", isAutoBoost(ctx))
        // Include main toggles snapshot
        val main = ctx.applicationContext.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        o.put("gameSpaceEnabled", main.getBoolean(Prefs.KEY_GAMESPACE_ENABLED, false))
        return o.toString(2)
    }

    /** Import from backup JSON. Returns number of profiles imported, -1 on failure. */
    fun importJson(ctx: Context, json: String): Int {
        return try {
            val o = JSONObject(json)
            val profilesObj = o.optJSONObject("profiles") ?: return -1
            // Schema check + pkg sanitization prevents bricking display via crafted backup.
            var count = 0
            profilesObj.keys().forEach { k ->
                if (ShellSanitizer.cleanPkg(k) == null) return@forEach
            }
            val profilesRaw = profilesObj.toString()
            prefs(ctx).edit()
                .putString(KEY_PROFILES, profilesRaw)
                .putBoolean(KEY_AUTO_BOOST, o.optBoolean("autoBoost", false))
                .putInt(KEY_SCHEMA, SCHEMA)
                .apply()
            count = profilesObj.length()
            count
        } catch (_: Exception) {
            -1
        }
    }

    /** Cooldown map so auto-boost re-applies at most once per 30s per pkg. */
    private val lastApplyAt = mutableMapOf<String, Long>()

    fun shouldAutoApply(pkg: String, nowMs: Long): Boolean {
        val safe = ShellSanitizer.cleanPkg(pkg) ?: return false
        val last = lastApplyAt[safe] ?: 0L
        if (nowMs - last < 30_000L) return false
        lastApplyAt[safe] = nowMs
        return true
    }

    fun clearCooldownForTest() { lastApplyAt.clear() }
}
