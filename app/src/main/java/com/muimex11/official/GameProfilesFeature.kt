package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.showGameProfilesSheet() {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.dlg_loading_apps_msg))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val apps = try {
            scanInstalledApps().filter { !it.frozen }
        } catch (e: Exception) {
            emptyList()
        }
        val auto = GameProfileStore.isAutoBoost(this@showGameProfilesSheet)
        val count = GameProfileStore.all(this@showGameProfilesSheet).size
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            presentGameProfiles(auto, count, apps)
        }
    }
}

private fun MainActivity.presentGameProfiles(auto: Boolean, count: Int, apps: List<AppEntry>) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (!ForegroundAppTracker.hasUsageAccess(this)) {
        list.addView(optionRow(
            this,
            getString(R.string.gprof_usage_title),
            getString(R.string.gprof_usage_desc),
        ) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {}
        })
    }
    list.addView(optionRow(
        this,
        getString(R.string.gprof_auto),
        getString(if (auto) R.string.gprof_auto_on_desc else R.string.gprof_auto_off_desc),
        selected = auto
    ) {
        GameProfileStore.setAutoBoost(this, !auto)
        UiToast.success(
            this,
            getString(if (!auto) R.string.gprof_auto_on else R.string.gprof_auto_off),
            Toast.LENGTH_SHORT
        ).show()
        refreshGameProfilesStatus()
    })
    apps.take(60).forEach { a ->
        val has = GameProfileStore.get(this, a.pkg) != null
        list.addView(optionRow(
            this, a.label, a.pkg + if (has) " ✓" else "",
            selected = has
        ) {
            showProfileEditor(a.pkg, a.label)
        })
    }
    if (apps.size > 60) {
        list.addView(infoCard(this, getString(R.string.showing_n, 60, apps.size)))
    }
    Sheet(this)
        .icon(R.drawable.ic_gamepad)
        .title(getString(R.string.gprof_title))
        .message(getString(R.string.gprof_message, count))
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
}

private fun MainActivity.showProfileEditor(pkg: String, label: String) {
    val cur = GameProfileStore.get(this, pkg) ?: GameProfile(pkg = pkg)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    var fixed = cur.fixedPerf
    var thermal = cur.thermalBypass
    var gm = cur.gameModePerf
    var hz120 = cur.refresh120
    var refresh = if (cur.refreshMax > 0) cur.refreshMax else 0
    var scale = cur.resScale
    var dnd = cur.dndFocus
    var orient = if (OrientationMode.isValid(cur.orientation)) cur.orientation else OrientationMode.AUTO
    var awake = cur.keepAwake
    var noBlur = cur.blurOff
    var killExit = cur.killOnExit

    val sheet = Sheet(this)
    fun rows() {
        list.removeAllViews()
        list.addView(optionRow(this, getString(R.string.gprof_gamemode), getString(R.string.gprof_gamemode_desc), selected = gm) {
            gm = !gm; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_fixed), getString(R.string.gprof_fixed_desc), selected = fixed) {
            fixed = !fixed; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_thermal), getString(R.string.gprof_thermal_desc), selected = thermal) {
            thermal = !thermal; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_dnd), getString(R.string.gprof_dnd_desc), selected = dnd) {
            dnd = !dnd; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_awake), getString(R.string.gprof_awake_desc), selected = awake) {
            awake = !awake; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_noblur), getString(R.string.gprof_noblur_desc), selected = noBlur) {
            noBlur = !noBlur; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_killexit), getString(R.string.gprof_killexit_desc), selected = killExit) {
            killExit = !killExit; rows()
        })
        list.addView(optionRow(this, getString(R.string.gprof_120), getString(R.string.gprof_120_desc), selected = hz120) {
            hz120 = !hz120; if (hz120) refresh = 0; rows()
        })
        if (!hz120) {
            listOf(0 to getString(R.string.gprof_refresh_default), 60 to "60 Hz", 90 to "90 Hz", 120 to "120 Hz").forEach { (v, name) ->
                list.addView(optionRow(this, getString(R.string.gprof_refresh_fmt, name),
                    if (v == 0) getString(R.string.gprof_follow_system) else "", selected = refresh == v) {
                    refresh = v; rows()
                })
            }
        }
        listOf(0f to "100%", 0.75f to "75%", 0.5f to "50%").forEach { (v, name) ->
            list.addView(optionRow(this, getString(R.string.gprof_res_fmt, name),
                if (v == 0f) getString(R.string.gprof_follow_system) else "", selected = scale == v) {
                scale = v; rows()
            })
        }
        listOf(
            OrientationMode.AUTO to getString(R.string.gprof_orient_follow),
            OrientationMode.PORTRAIT to getString(R.string.gprof_orient_portrait),
            OrientationMode.LANDSCAPE to getString(R.string.gprof_orient_landscape),
            OrientationMode.REVERSE_PORTRAIT to getString(R.string.gprof_orient_rportrait),
            OrientationMode.REVERSE_LANDSCAPE to getString(R.string.gprof_orient_rlandscape)
        ).forEach { (v, name) ->
            list.addView(optionRow(this, getString(R.string.gprof_orient_fmt, name),
                if (v == OrientationMode.AUTO) getString(R.string.gprof_follow_system) else "",
                selected = orient == v) {
                orient = v; rows()
            })
        }
    }
    rows()
    sheet.icon(R.drawable.ic_gamepad)
        .title(label)
        .message(pkg)
        .content(list)
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
    if (GameProfileStore.get(this, pkg) != null) {
        sheet.button(getString(R.string.btn_delete), SheetStyle.DANGER) {
            GameProfileStore.remove(this, pkg)
            refreshGameProfilesStatus()
        }
    }
    sheet.button(getString(R.string.btn_save), SheetStyle.PRIMARY) {
        GameProfileStore.save(
            this,
            GameProfile(pkg, fixed, thermal, gm, hz120, refresh, refresh, scale, dnd, orient, awake, noBlur, killExit)
        )
        UiToast.success(this, getString(R.string.profile_saved, label), Toast.LENGTH_SHORT).show()
        refreshGameProfilesStatus()
    }
    sheet.show()
}

internal fun MainActivity.refreshGameProfilesStatus() {
    val tv = findViewById<TextView?>(R.id.tvStatusGameProfiles) ?: return
    val n = GameProfileStore.all(this).size
    val auto = GameProfileStore.isAutoBoost(this)
    tv.text = getString(R.string.status_gprof_fmt, n, getString(if (auto) R.string.w_enabled else R.string.w_disabled))
}

internal fun gameProfilesStatusText(ctx: Context): String {
    val n = GameProfileStore.all(ctx).size
    val auto = GameProfileStore.isAutoBoost(ctx)
    return ctx.getString(
        R.string.status_gprof_fmt, n,
        ctx.getString(if (auto) R.string.w_enabled else R.string.w_disabled)
    )
}
