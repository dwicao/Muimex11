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

/** `window_animation_scale` value that the "fast animations" tweak writes. */
internal const val ANIM_FAST_VALUE = "0.5"

private class SystemStatuses(
    val fixedPerf: String,
    val thermal: String,
    val gameMode: String,
    val refresh: String,
    val resolution: String,
    val network: String,
    val touch: String,
    val dns: String,
    val dnsBenchmark: String,
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
    val battHealth: String,
    val dexHz: String,
    val mirror: String,
    val inputTester: String,
    val netPing: String,
    val artCompiler: String,
    val wakelock: String,
    val dexDesktop: String,
    val privacyAudit: String,
    val perfProfiler: String,
    /**
     * Cards whose read-back proved they are applied. Rendered into
     * [StatusRegistry] so "already applied" decisions never look at the
     * (localized, editable) status text.
     */
    val appliedIds: Set<Int> = emptySet()
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
    val orientation: String,
    val mirror: String,
    val mirrorApplied: Boolean = false
)

private data class NetStatus(
    val network: String,
    val touch: String,
    val dns: String,
    val dnd: String,
    val netBlock: String,
    val networkApplied: Boolean = false,
    val dndApplied: Boolean = false
)

private data class SysStatus(
    val anim: String,
    val blurs: String,
    val devOpts: String,
    val srec: String,
    val battHealth: String,
    val animApplied: Boolean = false,
    val blursApplied: Boolean = false,
    val devOptsApplied: Boolean = false
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
    val dnsBenchStatus = dnsBenchmarkStatusText(prefs)
    val inputTesterStatus = inputTesterStatusText(prefs)
    val netPingStatus = netPingStatusText(prefs)
    val artCompilerStatus = artCompilerStatusText(prefs)
    val wakelockStatus = wakelockStatusText(prefs)
    val privacyAuditStatus = privacyAuditStatusText(prefs)
    val perfProfilerStatus = perfProfilerStatusText(prefs)
    val rebootStatus = getString(R.string.status_ready)
    val appLocaleStatus = getString(R.string.status_ready)
    val radioStatus = getString(R.string.status_ready)

    // Graphics API: reconcile the post-reboot fallback before reading live.
    GraphicsApi.reconcileRebootReset(prefs)

    // Shell sections run in parallel; each is independently time-boxed so one
    // hung command (e.g. Shizuku dying mid-fetch) cannot stall the whole page.
    val displayJob = async {
        sectionOrNull {
            val d = ShizukuRunner.runTagged(
                listOf(
                    "min_refresh" to "settings get system min_refresh_rate",
                    "peak_refresh" to "settings get system peak_refresh_rate",
                    "wm_size" to "wm size",
                    "wm_density" to "wm density"
                )
            )
            val minRefresh = ShellBatcher.value(d, "min_refresh")
            val maxRefresh = ShellBatcher.value(d, "peak_refresh")
            val refreshStatus = if (minRefresh.isNotEmpty() && minRefresh != "null" && maxRefresh.isNotEmpty() && maxRefresh != "null") {
                getString(R.string.status_value, "$minRefresh - $maxRefresh Hz")
            } else {
                getString(R.string.status_default)
            }

            val wmSize = ShellBatcher.value(d, "wm_size")
            val wmDensity = ShellBatcher.value(d, "wm_density")
            val resStatus = if (wmSize.contains("Override size:")) {
                getString(R.string.status_value, wmSize.substringAfter("Override size:").trim())
            } else if (wmSize.contains("Physical size:")) {
                getString(R.string.status_value, wmSize.substringAfter("Physical size:").trim())
            } else {
                getString(R.string.status_default)
            }

            val mirrorState = MirrorLogic.parseWmOutput(wmSize, wmDensity)
            val mirrorText = mirrorStatusText(mirrorState)

            val liveRenderer = GraphicsApi.detectRenderer()
                .takeIf { it == GraphicsApi.VULKAN || it == GraphicsApi.OPENGL }
            DisplayStatus(
                refreshStatus, resStatus,
                graphicsStatusText(prefs, liveRenderer),
                orientationStatusText(prefs),
                mirrorText,
                mirrorState.isOverrideApplied
            )
        }
    }

    val dexHzJob = async {
        sectionOrNull {
            if (DexHzBridge.currentService() != null) DexHzBridge.refresh(this@readSystemStatuses)
            dexHzStatusText() to isDexHzActive()
        }
    }

    val dexDesktopJob = async {
        sectionOrNull { dexDesktopStatusPair() }
    }

    val netJob = async {
        sectionOrNull {
            val n = ShizukuRunner.runTagged(
                listOf(
                    "network" to "settings get global tether_dun_required",
                    "pointer_speed" to "settings get system pointer_speed",
                    "long_press" to "settings get secure long_press_timeout",
                    "dns_mode" to "settings get global private_dns_mode",
                    "dns_spec" to "settings get global private_dns_specifier",
                    "heads_up" to "settings get global heads_up_notifications_enabled"
                )
            )
            val networkOpt = ShellBatcher.value(n, "network")
            val touchPtr = ShellBatcher.value(n, "pointer_speed")
            val touchLp = ShellBatcher.value(n, "long_press")
            val dnsMode = ShellBatcher.value(n, "dns_mode")
            val dnsSpecifier = ShellBatcher.value(n, "dns_spec")
            val headsUpOpt = ShellBatcher.value(n, "heads_up")
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
                netBlock = netBlockStatusText(),
                networkApplied = networkOpt == "0",
                dndApplied = headsUpOpt == "0"
            )
        }
    }

    val sysJob = async {
        sectionOrNull {
            val sysOut = ShizukuRunner.runTagged(
                listOf(
                    "anim" to "settings get global window_animation_scale",
                    "blurs" to "settings get global disable_window_blurs",
                    "devopt" to "settings get global development_settings_enabled"
                )
            )
            val animScale = ShellBatcher.value(sysOut, "anim")
            val blursOpt = ShellBatcher.value(sysOut, "blurs")
            val devOpt = ShellBatcher.value(sysOut, "devopt")
            SysStatus(
                anim = when (animScale) {
                    "0.5" -> getString(R.string.status_anim_fast)
                    "0.0" -> getString(R.string.status_anim_off)
                    else -> getString(R.string.status_default)
                },
                blurs = if (blursOpt == "1") getString(R.string.status_blurs_off) else getString(R.string.status_default),
                devOpts = if (devOpt == "1") getString(R.string.status_devopt_on) else getString(R.string.status_devopt_off),
                srec = shizukuRecoveryStatusText(),
                battHealth = battHealthStatusText(),
                animApplied = animScale == ANIM_FAST_VALUE,
                blursApplied = blursOpt == "1",
                devOptsApplied = devOpt == "0"
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

    val display = displayJob.await() ?: DisplayStatus(fbUnknown, fbUnknown, fbUnknown, fbUnknown, fbUnknown)
    val dexHzPair = dexHzJob.await() ?: (fbUnknown to false)
    val dexDesktopPair = dexDesktopJob.await() ?: (fbUnknown to false)
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
        dnsBenchmark = dnsBenchStatus,
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
        battHealth = sys.battHealth,
        dexHz = dexHzPair.first,
        mirror = display.mirror,
        inputTester = inputTesterStatus,
        netPing = netPingStatus,
        artCompiler = artCompilerStatus,
        wakelock = wakelockStatus,
        dexDesktop = dexDesktopPair.first,
        privacyAudit = privacyAuditStatus,
        perfProfiler = perfProfilerStatus,
        appliedIds = buildSet {
            if (net.networkApplied) add(R.id.tvStatusNetwork)
            if (net.dndApplied) add(R.id.tvStatusDnd)
            if (sys.animApplied) add(R.id.tvStatusAnimations)
            if (sys.blursApplied) add(R.id.tvStatusBlurs)
            if (sys.devOptsApplied) add(R.id.tvStatusDevOptions)
            if (display.mirrorApplied) add(R.id.tvStatusMirrorDisplay)
            if (dexHzPair.second) add(R.id.tvStatusDexHz)
            if (dexDesktopPair.second) add(R.id.tvStatusDexDesktop)
        }
    )
}

private fun MainActivity.renderSystemStatuses(s: SystemStatuses) {
    // Registry first, text second: the status TextWatcher in MainUiSetup reads
    // the typed state to colour the row, and the "already applied" guards in
    // MainClickListeners read it to decide behaviour.
    fun push(id: Int, text: String) {
        StatusRegistry.set(id, if (id in s.appliedIds) StatusKind.APPLIED else StatusKind.READY)
        findViewById<TextView>(id).text = text
    }

    push(R.id.tvStatusFixedPerf, s.fixedPerf)
    push(R.id.tvStatusThermal, s.thermal)
    push(R.id.tvStatusGameMode, s.gameMode)
    push(R.id.tvStatusRefresh, s.refresh)
    push(R.id.tvStatusResolution, s.resolution)
    push(R.id.tvStatusNetwork, s.network)
    push(R.id.tvStatusTouch, s.touch)
    push(R.id.tvStatusDns, s.dns)
    push(R.id.tvStatusDnsBenchmark, s.dnsBenchmark)
    push(R.id.tvStatusAnimations, s.animations)
    push(R.id.tvStatusBlurs, s.blurs)
    push(R.id.tvStatusDnd, s.dnd)
    push(R.id.tvStatusDevOptions, s.devOptions)
    push(R.id.tvStatusSysTweaks, s.sysTweaks)
    push(R.id.tvStatusComfort, s.comfort)
    push(R.id.tvStatusDx, s.dx)
    push(R.id.tvStatusNetRadio, s.netRadio)
    push(R.id.tvStatusMem, s.mem)
    push(R.id.tvStatusGameTuning, s.gameTuning)
    push(R.id.tvStatusBatterySaver, s.battery)
    push(R.id.tvStatusAppOps, s.appOps)
    push(R.id.tvStatusGameProfiles, s.gameProfiles)
    push(R.id.tvStatusDebloat, s.debloat)
    push(R.id.tvStatusAudio, s.audio)
    push(R.id.tvStatusSensors, s.sensors)
    push(R.id.tvStatusBgRestrict, s.bgRestrict)
    push(R.id.tvStatusBenchmark, s.benchmark)
    push(R.id.tvStatusGraphics, s.graphics)
    push(R.id.tvStatusOrientation, s.orientation)
    push(R.id.tvStatusApkBackup, s.apkBackup)
    push(R.id.tvStatusReboot, s.reboot)
    push(R.id.tvStatusAppLocale, s.appLocale)
    push(R.id.tvStatusRadio, s.radio)
    push(R.id.tvStatusShizukuRecovery, s.shizukuRecovery)
    push(R.id.tvStatusNetBlock, s.netBlock)
    push(R.id.tvStatusBattHealth, s.battHealth)
    push(R.id.tvStatusDexHz, s.dexHz)
    push(R.id.tvStatusMirrorDisplay, s.mirror)
    push(R.id.tvStatusInputTester, s.inputTester)
    push(R.id.tvStatusNetPing, s.netPing)
    push(R.id.tvStatusArtCompiler, s.artCompiler)
    push(R.id.tvStatusWakelock, s.wakelock)
    push(R.id.tvStatusDexDesktop, s.dexDesktop)
    push(R.id.tvStatusPrivacyAudit, s.privacyAudit)
    push(R.id.tvStatusPerfProfiler, s.perfProfiler)
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
