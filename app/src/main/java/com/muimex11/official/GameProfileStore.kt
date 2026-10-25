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
    val resScale: Float = 0f
)

object GameProfileStore {
    private const val FILE = "MuimexGameProfiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_AUTO_BOOST = "auto_boost"

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
                    resScale = v.optDouble("resScale", 0.0).toFloat()
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(ctx: Context, profile: GameProfile) {
        val map = all(ctx).toMutableMap()
        map[profile.pkg] = profile
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
            o.put(k, JSONObject().apply {
                put("fixedPerf", v.fixedPerf)
                put("thermalBypass", v.thermalBypass)
                put("gameModePerf", v.gameModePerf)
                put("refresh120", v.refresh120)
                put("refreshMin", v.refreshMin)
                put("refreshMax", v.refreshMax)
                put("resScale", v.resScale.toDouble())
            })
        }
        prefs(ctx).edit().putString(KEY_PROFILES, o.toString()).apply()
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
            val profilesRaw = o.optJSONObject("profiles")?.toString() ?: "{}"
            prefs(ctx).edit()
                .putString(KEY_PROFILES, profilesRaw)
                .putBoolean(KEY_AUTO_BOOST, o.optBoolean("autoBoost", false))
                .apply()
            val count = o.optJSONObject("profiles")?.length() ?: 0
            count
        } catch (_: Exception) {
            -1
        }
    }
}
