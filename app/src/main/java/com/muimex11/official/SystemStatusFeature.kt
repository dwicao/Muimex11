package com.muimex11.official

import android.content.Context
import android.content.SharedPreferences
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private class SystemStatuses(
    val fixedPerf: String,
    val thermal: String,
    val gameMode: String,
    val refresh: String,
    val resolution: String,
    val network: String,
    val touch: String,
    val dns: String,
    val animations: String,
    val blurs: String,
    val dnd: String,
    val devOptions: String,
    val sysTweaks: String,
    val comfort: String,
    val dx: String,
    val netRadio: String,
    val mem: String,
    val gameTuning: String,
    val battery: String,
    val appOps: String,
    val gameProfiles: String,
    val debloat: String,
    val audio: String,
    val sensors: String,
    val bgRestrict: String,
    val benchmark: String,
    val graphics: String,
    val orientation: String,
    val apkBackup: String,
    val reboot: String,
    val appLocale: String,
    val radio: String,
    val shizukuRecovery: String,
    val netBlock: String,
    val battHealth: String
)

internal fun MainActivity.fetchSystemStatuses() {
    if (!hasShizukuPermission()) return

    lifecycleScope.launch(Dispatchers.IO) {
        val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        val statuses = readSystemStatuses(prefs)

        withContext(Dispatchers.Main) {
            renderSystemStatuses(statuses)
            renderOverlayStatuses(prefs)
        }
    }
}

private data class DisplayStatus(
    val refresh: String,
    val resolution: String,
    val graphics: String,
    val orientation: String
)

private data class NetStatus(
    val network: String,
    val touch: String,
    val dns: String,
    val dnd: String,
    val netBlock: String
)

private data class SysStatus(
    val anim: String,
    val blurs: String,
    val devOpts: String,
    val srec: String,
    val battHealth: String
)

/**
 * One status section: time-boxed AND exception-proof. A single bad section
 * (dead backend, missing string, odd ROM output) degrades to Unknown
 * instead of killing the whole status page.
 */
private suspend fun <T> sectionOrNull(block: suspend () -> T): T? = try {
    withTimeoutOrNull(8_000L) { block() }
} catch (_: Exception) {
    null
}

private suspend fun MainActivity.readSystemStatuses(prefs: SharedPreferences): SystemStatuses = coroutineScope {
    val fbUnknown = getString(R.string.status_unknown)

    // Prefs-only section: instant, no shell involved.
    val isFixedPerf = prefs.getBoolean(Prefs.KEY_FIXED_PERF, false)
    val fixedPerfStatus = if (isFixedPerf)
        getString(if (prefs.getBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false)) R.string.status_enabled_aggr else R.string.status_enabled)
    else getString(R.string.status_default)

    val thermalStatus = thermalStatusText(prefs)

    val lastGameMode = prefs.getString(Prefs.KEY_LAST_GAME_MODE, "")
    val gameModeStatus = if (lastGameMode.isNullOrEmpty()) getString(R.string.status_default) else getString(R.string.status_active, lastGameMode.orEmpty())

    val batteryStatus = batteryStatusText(this@readSystemStatuses)

    val opsCount = appOpsRulesCount(prefs)
    val appOpsStatus = if (opsCount == 0) getString(R.string.status_default)
    else getString(R.string.status_value, opsCount.toString())

    val gameProfilesStatus = gameProfilesStatusText(this@readSystemStatuses)

    val debloatN = debloatRemovedCount(prefs)
    val debloatStatus = if (debloatN == 0) getString(R.string.status_ready)
    else resources.getQuantityString(R.plurals.status_frozen_apps, debloatN, debloatN)

    val audioPreset = prefs.getString(Prefs.KEY_AUDIO_PRESET, AudioPresets.PRESET_OFF).orEmpty()
    val audioStatus = if (audioPreset == AudioPresets.PRESET_LOWLAT) getString(R.string.status_audio_lowlat)
    else getString(R.string.status_default)

    val sensorsStatus = sensorsStatusText(prefs)

    val bgN = bgRestrictCount(prefs)
    val bgStatus = if (bgN == 0) getString(R.string.status_ready)
    else getString(R.string.status_value, getString(R.string.bgrestrict_fmt, bgN))

    val benchLast = readBenchRuns(prefs).lastOrNull()
    val benchStatus = if (benchLast == null) getString(R.string.status_ready)
    else getString(
        R.string.status_bench_fmt,
        benchLast.label, benchLast.launchMs, benchLast.fps?.toString() ?: "--"
    )

    val apkBackupStatus = apkBackupStatusText()
    val rebootStatus = getString(R.string.status_ready)
    val appLocaleStatus = getString(R.string.status_ready)
    val radioStatus = getString(R.string.status_ready)

    // Graphics API: reconcile the post-reboot fallback before reading live.
    GraphicsApi.reconcileRebootReset(prefs)

    // Shell sections run in parallel; each is independently time-boxed so one
    // hung command (e.g. Shizuku dying mid-fetch) cannot stall the whole page.
    val displayJob = async {
        sectionOrNull {
            val minRefresh = runAdbCommandWithResult("settings get system min_refresh_rate")
            val maxRefresh = runAdbCommandWithResult("settings get system peak_refresh_rate")
            val refreshStatus = if (minRefresh.isNotEmpty() && minRefresh != "null" && maxRefresh.isNotEmpty() && maxRefresh != "null") {
                getString(R.string.status_value, "$minRefresh - $maxRefresh Hz")
            } else {
                getString(R.string.status_default)
            }

            val wmSize = runAdbCommandWithResult("wm size")
            val resStatus = if (wmSize.contains("Override size:")) {
                getString(R.string.status_value, wmSize.substringAfter("Override size:").trim())
            } else if (wmSize.contains("Physical size:")) {
                getString(R.string.status_value, wmSize.substringAfter("Physical size:").trim())
            } else {
                getString(R.string.status_default)
            }

            val liveRenderer = GraphicsApi.detectRenderer()
                .takeIf { it == GraphicsApi.VULKAN || it == GraphicsApi.OPENGL }
            DisplayStatus(
                refreshStatus, resStatus,
                graphicsStatusText(prefs, liveRenderer),
                orientationStatusText(prefs)
            )
        }
    }

    val netJob = async {
        sectionOrNull {
            val networkOpt = runAdbCommandWithResult("settings get global tether_dun_required")
            val touchPtr = runAdbCommandWithResult("settings get system pointer_speed")
            val touchLp = runAdbCommandWithResult("settings get secure long_press_timeout")
            val dnsMode = runAdbCommandWithResult("settings get global private_dns_mode")
            val dnsSpecifier = runAdbCommandWithResult("settings get global private_dns_specifier")
            val headsUpOpt = runAdbCommandWithResult("settings get global heads_up_notifications_enabled")
            NetStatus(
                network = if (networkOpt == "0") getString(R.string.status_optimized) else getString(R.string.status_default),
                touch = when (
                    TouchTweak.appliedCount(
                        TouchTweak.isPointerApplied(touchPtr),
                        TouchTweak.isLongPressApplied(touchLp)
                    )
                ) {
                    2 -> getString(R.string.status_improved)
                    1 -> getString(R.string.status_touch_partial)
                    else -> getString(R.string.status_default)
                },
                dns = if (dnsMode.trim() == "hostname" && dnsSpecifier.isNotEmpty() && dnsSpecifier != "null") {
                    getString(R.string.status_value, dnsSpecifier.trim())
                } else {
                    getString(R.string.status_default)
                },
                dnd = if (headsUpOpt == "0") getString(R.string.status_dnd_blocked) else getString(R.string.status_default),
                netBlock = netBlockStatusText()
            )
        }
    }

    val sysJob = async {
        sectionOrNull {
            val animScale = runAdbCommandWithResult("settings get global window_animation_scale")
            val blursOpt = runAdbCommandWithResult("settings get global disable_window_blurs")
            val devOpt = runAdbCommandWithResult("settings get global development_settings_enabled")
            SysStatus(
                anim = when (animScale) {
                    "0.5" -> getString(R.string.status_anim_fast)
                    "0.0" -> getString(R.string.status_anim_off)
                    else -> getString(R.string.status_default)
                },
                blurs = if (blursOpt == "1") getString(R.string.status_blurs_off) else getString(R.string.status_default),
                devOpts = if (devOpt == "1") getString(R.string.status_devopt_on) else getString(R.string.status_devopt_off),
                srec = shizukuRecoveryStatusText(),
                battHealth = battHealthStatusText()
            )
        }
    }

    val tweaksJob = async {
        sectionOrNull { sysTweaksStatusText() }
    }

    val comfortJob = async {
        sectionOrNull { comfortStatusText() }
    }

    val dxJob = async {
        sectionOrNull { dxStatusText() }
    }

    val netRadioJob = async {
        sectionOrNull { netRadioStatusText() }
    }

    val memJob = async {
        sectionOrNull { memStatusText(prefs) }
    }

    val display = displayJob.await() ?: DisplayStatus(fbUnknown, fbUnknown, fbUnknown, fbUnknown)
    val net = netJob.await() ?: NetStatus(fbUnknown, fbUnknown, fbUnknown, fbUnknown, fbUnknown)
    val sys = sysJob.await() ?: SysStatus(fbUnknown, fbUnknown, fbUnknown, fbUnknown, fbUnknown)
    val tweaks = tweaksJob.await() ?: fbUnknown
    val comfort = comfortJob.await() ?: fbUnknown
    val dx = dxJob.await() ?: fbUnknown
    val netRadio = netRadioJob.await() ?: fbUnknown
    val mem = memJob.await() ?: fbUnknown

    return@coroutineScope SystemStatuses(
        fixedPerf = fixedPerfStatus,
        thermal = thermalStatus,
        gameMode = gameModeStatus,
        refresh = display.refresh,
        resolution = display.resolution,
        network = net.network,
        touch = net.touch,
        dns = net.dns,
        animations = sys.anim,
        blurs = sys.blurs,
        dnd = net.dnd,
        devOptions = sys.devOpts,
        sysTweaks = tweaks,
        comfort = comfort,
        dx = dx,
        netRadio = netRadio,
        mem = mem,
        gameTuning = gameTuningStatusText(prefs),
        battery = batteryStatus,
        appOps = appOpsStatus,
        gameProfiles = gameProfilesStatus,
        debloat = debloatStatus,
        audio = audioStatus,
        sensors = sensorsStatus,
        bgRestrict = bgStatus,
        benchmark = benchStatus,
        graphics = display.graphics,
        orientation = display.orientation,
        apkBackup = apkBackupStatus,
        reboot = rebootStatus,
        appLocale = appLocaleStatus,
        radio = radioStatus,
        shizukuRecovery = sys.srec,
        netBlock = net.netBlock,
        battHealth = sys.battHealth
    )
}

private fun MainActivity.renderSystemStatuses(s: SystemStatuses) {
    findViewById<TextView>(R.id.tvStatusFixedPerf).text = s.fixedPerf
    findViewById<TextView>(R.id.tvStatusThermal).text = s.thermal
    findViewById<TextView>(R.id.tvStatusGameMode).text = s.gameMode
    findViewById<TextView>(R.id.tvStatusRefresh).text = s.refresh
    findViewById<TextView>(R.id.tvStatusResolution).text = s.resolution
    findViewById<TextView>(R.id.tvStatusNetwork).text = s.network
    findViewById<TextView>(R.id.tvStatusTouch).text = s.touch
    findViewById<TextView>(R.id.tvStatusDns).text = s.dns
    findViewById<TextView>(R.id.tvStatusAnimations).text = s.animations
    findViewById<TextView>(R.id.tvStatusBlurs).text = s.blurs
    findViewById<TextView>(R.id.tvStatusDnd).text = s.dnd
    findViewById<TextView>(R.id.tvStatusDevOptions).text = s.devOptions
    findViewById<TextView>(R.id.tvStatusSysTweaks).text = s.sysTweaks
    findViewById<TextView>(R.id.tvStatusComfort).text = s.comfort
    findViewById<TextView>(R.id.tvStatusDx).text = s.dx
    findViewById<TextView>(R.id.tvStatusNetRadio).text = s.netRadio
    findViewById<TextView>(R.id.tvStatusMem).text = s.mem
    findViewById<TextView>(R.id.tvStatusGameTuning).text = s.gameTuning
    findViewById<TextView>(R.id.tvStatusBatterySaver).text = s.battery
    findViewById<TextView>(R.id.tvStatusAppOps).text = s.appOps
    findViewById<TextView>(R.id.tvStatusGameProfiles).text = s.gameProfiles
    findViewById<TextView>(R.id.tvStatusDebloat).text = s.debloat
    findViewById<TextView>(R.id.tvStatusAudio).text = s.audio
    findViewById<TextView>(R.id.tvStatusSensors).text = s.sensors
    findViewById<TextView>(R.id.tvStatusBgRestrict).text = s.bgRestrict
    findViewById<TextView>(R.id.tvStatusBenchmark).text = s.benchmark
    findViewById<TextView>(R.id.tvStatusGraphics).text = s.graphics
    findViewById<TextView>(R.id.tvStatusOrientation).text = s.orientation
    findViewById<TextView>(R.id.tvStatusApkBackup).text = s.apkBackup
    findViewById<TextView>(R.id.tvStatusReboot).text = s.reboot
    findViewById<TextView>(R.id.tvStatusAppLocale).text = s.appLocale
    findViewById<TextView>(R.id.tvStatusRadio).text = s.radio
    findViewById<TextView>(R.id.tvStatusShizukuRecovery).text = s.shizukuRecovery
    findViewById<TextView>(R.id.tvStatusNetBlock).text = s.netBlock
    findViewById<TextView>(R.id.tvStatusBattHealth).text = s.battHealth
}

private fun MainActivity.renderOverlayStatuses(prefs: SharedPreferences) {
    val isCrosshairOn = prefs.getBoolean(Prefs.KEY_CROSSHAIR_ENABLED, false)
    isCrosshairEnabled = isCrosshairOn
    if (isCrosshairOn) {
        val style = prefs.getString(Prefs.KEY_CROSSHAIR_STYLE, "Cross")
        val color = prefs.getString(Prefs.KEY_CROSSHAIR_COLOR, "Red")
        val size = prefs.getString(Prefs.KEY_CROSSHAIR_SIZE, "Medium")
        findViewById<TextView>(R.id.tvStatusCrosshair).text = getString(R.string.status_crosshair, xl(style.orEmpty()), xl(color.orEmpty()), xl(size.orEmpty()))
    } else {
        findViewById<TextView>(R.id.tvStatusCrosshair).text = getString(R.string.status_disabled)
    }

    val isMonitorOn = prefs.getBoolean(Prefs.KEY_MONITOR_ENABLED, false)
    isMonitorEnabled = isMonitorOn
    if (isMonitorOn) {
        findViewById<TextView>(R.id.tvStatusMonitor)?.text = getString(R.string.status_enabled)
    } else {
        findViewById<TextView>(R.id.tvStatusMonitor)?.text = getString(R.string.status_disabled)
    }

    val isAutoOn = prefs.getBoolean(AutoClickService.KEY_ENABLED, false)
    isAutoClickEnabled = isAutoOn
    findViewById<TextView>(R.id.tvStatusAutoClick)?.text = if (isAutoOn)
        getString(R.string.status_autoclick, AutoClickService.modeLabel(this, prefs.getInt(AutoClickService.KEY_MODE, 0)), prefs.getFloat(AutoClickService.KEY_INTERVAL, 5f).toString())
    else getString(R.string.status_disabled)
}
