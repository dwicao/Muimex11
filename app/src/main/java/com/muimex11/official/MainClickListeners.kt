package com.muimex11.official

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView

internal fun MainActivity.setupClickListeners() {
    val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)

    setupTweakCardListeners(prefs)
    setupToolsCardListeners(prefs)
    setupOverlayResetListeners()
    setupTweakResetListeners(prefs)
    setupBottomActionListeners(prefs)
}

private fun MainActivity.en(res: Int): String = AppTranslator.english(this, res)

private fun MainActivity.runTweakOnce(
    statusId: Int,
    doneMarker: () -> String,
    alreadyToastRes: Int,
    command: String,
    successMessageRes: Int,
    onSuccess: () -> Unit = { fetchSystemStatuses() }
) {
    // Status text may be translated; compare against its English source.
    val status = AppTranslator.toEnglish(findViewById<TextView>(statusId).text.toString())
    if (status.contains(doneMarker())) {
        UiToast.info(this, getString(alreadyToastRes), Toast.LENGTH_SHORT).show()
        return
    }
    runAdbCommand(command, getString(successMessageRes)) { onSuccess() }
}

private fun MainActivity.bindCommandReset(buttonId: Int, command: String, messageRes: Int) {
    findViewById<android.view.View>(buttonId).setOnClickListener {
        runAdbCommand(command, getString(messageRes)) { fetchSystemStatuses() }
    }
}

private fun MainActivity.bindStatusReset(buttonId: Int, statusId: Int) {
    findViewById<android.view.View>(buttonId).setOnClickListener {
        findViewById<TextView>(statusId).text = getString(R.string.status_ready)
        UiToast.success(this, getString(R.string.toast_status_reset), Toast.LENGTH_SHORT).show()
    }
}


private fun MainActivity.setupTweakCardListeners(prefs: SharedPreferences) {
    findViewById<CardView>(R.id.cardApkInstall).setOnClickListener { showApkInstallSheet() }
    findViewById<CardView>(R.id.cardApkBackup).setOnClickListener { showApkBackupSheet() }
    findViewById<CardView>(R.id.cardFixedPerformance).setOnClickListener {
        if (prefs.getBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false)) {
            UiToast.info(this, getString(R.string.toast_already_aggr), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        showFixedPerformanceSheet(prefs)
    }

    findViewById<CardView>(R.id.cardOptimizeSystem).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusOptimize, { en(R.string.w_recently) }, R.string.toast_already_optimized,
            "cmd activity kill-all", R.string.msg_sys_optimized
        ) {
            findViewById<TextView>(R.id.tvStatusOptimize).text = getString(R.string.status_recently)
        }
    }

    findViewById<CardView>(R.id.cardImproveNetwork).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusNetwork, { en(R.string.w_optimized) }, R.string.toast_already_optimized,
            "settings put global tether_dun_required 0", R.string.msg_net_optimized
        )
    }

    findViewById<CardView>(R.id.cardRadioInfo).setOnClickListener { showRadioInfoSheet() }
    findViewById<CardView>(R.id.cardNetBlock).setOnClickListener { showNetBlockSheet() }

    findViewById<android.view.View>(R.id.btnResetNetBlock).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetNetBlock()
    }

    findViewById<CardView>(R.id.cardImproveTouch).setOnClickListener { improveTouchLatency() }

    findViewById<CardView>(R.id.cardFastAnimations).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusAnimations, { "0.5x" }, R.string.toast_already_sped,
            "settings put global window_animation_scale 0.5 && settings put global transition_animation_scale 0.5 && settings put global animator_duration_scale 0.5",
            R.string.msg_anim_fast
        )
    }

    findViewById<CardView>(R.id.cardDisableBlurs).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusBlurs, { en(R.string.w_disabled) }, R.string.toast_already_disabled,
            "settings put global disable_window_blurs 1", R.string.msg_blurs_off
        )
    }

    findViewById<CardView>(R.id.cardGamingDnd).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusDnd, { en(R.string.w_blocked) }, R.string.toast_already_blocked,
            "settings put global heads_up_notifications_enabled 0", R.string.msg_dnd_on
        )
    }

    findViewById<CardView>(R.id.cardDisableDevOptions).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusDevOptions, { en(R.string.w_disabled) }, R.string.toast_already_disabled,
            "settings put global development_settings_enabled 0", R.string.msg_devopt_off
        )
    }

    findViewById<CardView>(R.id.cardFixGyro).setOnClickListener { fixGyroDelay() }

    findViewById<CardView>(R.id.cardAdjustRefreshRate).setOnClickListener { showRefreshRateDialog() }

    findViewById<CardView>(R.id.cardGraphicsApi).setOnClickListener { showGraphicsApiSheet(prefs) }

    findViewById<CardView>(R.id.cardSysTweaks).setOnClickListener { showSysTweaksSheet() }

    findViewById<CardView>(R.id.cardOrientation).setOnClickListener { showOrientationSheet(prefs) }

    findViewById<android.view.View>(R.id.btnResetOrientation).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetOrientationMode(prefs)
    }
    findViewById<android.view.View>(R.id.btnResetGraphics).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetGraphicsRenderer(prefs)
    }
    findViewById<android.view.View>(R.id.btnResetSysTweaks).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetSysTweaks()
    }
    findViewById<CardView>(R.id.cardGameMode).setOnClickListener {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            UiToast.error(this, getString(R.string.toast_needs_a12), Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        showGameModeSelectorDialog()
    }

    findViewById<CardView>(R.id.cardGameSpace).setOnClickListener {
        toggleGameSpace()
    }

    findViewById<CardView>(R.id.cardSamsungBoost).setOnClickListener {
        showSamsungBoostSheet()
    }

    findViewById<android.view.View>(R.id.btnResetSamsungBoost).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        restoreAllSamsung()
    }
}

private fun MainActivity.setupToolsCardListeners(prefs: SharedPreferences) {
    findViewById<CardView>(R.id.cardFstrim).setOnClickListener {
        runTweakOnce(
            R.id.tvStatusFstrim, { en(R.string.w_recently) }, R.string.toast_storage_already,
            "sm fstrim", R.string.msg_fstrim_done
        ) {
            findViewById<TextView>(R.id.tvStatusFstrim).text = getString(R.string.status_recently)
        }
    }

    findViewById<CardView>(R.id.cardFreezeApps).setOnClickListener { showFreezeAppsSheet() }
    findViewById<CardView>(R.id.cardShellExecute).setOnClickListener { showShellExecuteSheet() }
    findViewById<CardView>(R.id.cardReboot).setOnClickListener { showRebootSheet() }
    findViewById<CardView>(R.id.cardShizukuRecovery).setOnClickListener { showShizukuRecoverySheet() }
    findViewById<CardView>(R.id.cardRamBooster).setOnClickListener { showRamBoosterSheet() }
    findViewById<CardView>(R.id.cardClearCache).setOnClickListener { confirmClearCaches() }
    findViewById<CardView>(R.id.cardChangeResolution).setOnClickListener { showResolutionModeDialog() }
    findViewById<CardView>(R.id.cardCustomDns).setOnClickListener { showDnsDialog() }
    findViewById<CardView>(R.id.cardCrosshair).setOnClickListener { showCrosshairConfigDialog() }
    findViewById<CardView>(R.id.cardRealtimeMonitor).setOnClickListener { toggleRealtimeMonitor() }
    findViewById<CardView>(R.id.cardAutoClick).setOnClickListener { showAutoClickDialog() }

    findViewById<CardView>(R.id.cardSamsungBoost).setOnClickListener {
        showSamsungBoostSheet()
    }

    findViewById<CardView>(R.id.cardThermalThrottling).setOnClickListener {
        showThermalSheet(prefs)
    }

    findViewById<CardView>(R.id.cardBatterySaver).setOnClickListener {
        showBatterySaverSheet(prefs)
    }

    findViewById<android.view.View>(R.id.btnResetBatterySaver).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetBatterySaver(prefs)
    }

    findViewById<CardView>(R.id.cardBatteryHealth).setOnClickListener { showBatteryHealthSheet() }

    findViewById<android.view.View>(R.id.btnResetBattHealth).setOnClickListener {
        fetchSystemStatuses()
        UiToast.info(this, getString(R.string.toast_batthealth_refresh), Toast.LENGTH_SHORT).show()
    }

    findViewById<CardView>(R.id.cardAppOps).setOnClickListener { showAppOpsSheet(prefs) }
    findViewById<CardView>(R.id.cardAppLocale).setOnClickListener { showAppLocaleSheet() }

    findViewById<CardView>(R.id.cardGameProfiles).setOnClickListener { showGameProfilesSheet() }

    findViewById<CardView>(R.id.cardDebloat).setOnClickListener { showDebloatSheet(prefs) }

    findViewById<CardView>(R.id.cardAudioLatency).setOnClickListener { showAudioSheet(prefs) }

    findViewById<CardView>(R.id.cardSensorsToggle).setOnClickListener { showSensorsSheet(prefs) }

    findViewById<CardView>(R.id.cardBgRestrict).setOnClickListener { showBgRestrictSheet(prefs) }

    findViewById<CardView>(R.id.cardBenchmark).setOnClickListener { showBenchmarkSheet(prefs) }

    findViewById<android.view.View>(R.id.btnResetAppOps).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        val cmd = appOpsResetCmdForAll(prefs)
        if (cmd == null) {
            UiToast.info(this, getString(R.string.toast_no_apps), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        runAdbCommand(cmd, getString(R.string.appops_reset_done)) {
            prefs.edit().remove(Prefs.KEY_APPOPS_RULES).apply()
            fetchSystemStatuses()
        }
    }
    findViewById<android.view.View>(R.id.btnResetGameProfiles).setOnClickListener {
        GameProfileStore.setAutoBoost(this, false)
        findViewById<TextView>(R.id.tvStatusGameProfiles).text = gameProfilesStatusText(this)
        UiToast.success(this, getString(R.string.toast_status_reset), Toast.LENGTH_SHORT).show()
    }
    findViewById<android.view.View>(R.id.btnResetDebloat).setOnClickListener { restoreAllFrozen() }
    findViewById<android.view.View>(R.id.btnResetAudio).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        resetAudioPreset(prefs)
    }
    findViewById<android.view.View>(R.id.btnResetSensors).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        val cmd = sensorsResetCmd(prefs)
        if (cmd == null) {
            UiToast.info(this, getString(R.string.toast_no_apps), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        runAdbCommand(cmd, getString(R.string.sensors_reset_done)) {
            clearSensorsPrefs(prefs)
            findViewById<TextView>(R.id.tvStatusSensors).text = getString(R.string.status_default)
            fetchSystemStatuses()
        }
    }

    findViewById<android.view.View>(R.id.btnResetBgRestrict).setOnClickListener {
        if (!hasShizukuPermission()) return@setOnClickListener
        val cmd = bgRestrictResetCmdForAll(prefs)
        if (cmd == null) {
            UiToast.info(this, getString(R.string.toast_no_apps), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        runAdbCommand(cmd, getString(R.string.appops_reset_done)) {
            clearBgRestrictPrefs(prefs)
            findViewById<TextView>(R.id.tvStatusBgRestrict).text = getString(R.string.status_ready)
            fetchSystemStatuses()
        }
    }
    findViewById<android.view.View>(R.id.btnResetBenchmark).setOnClickListener {
        UiToast.info(this, getString(R.string.toast_bench_kept), Toast.LENGTH_SHORT).show()
    }
}

private fun MainActivity.setupOverlayResetListeners() {
    findViewById<android.view.View>(R.id.btnResetAutoClick).setOnClickListener {
        if (isAutoClickEnabled) stopAutoClickService()
    }
    findViewById<android.view.View>(R.id.btnResetCrosshair).setOnClickListener {
        if (isCrosshairEnabled) stopCrosshairService()
    }
    findViewById<android.view.View>(R.id.btnResetMonitor).setOnClickListener {
        if (isMonitorEnabled) toggleRealtimeMonitor()
    }
    findViewById<android.view.View>(R.id.btnResetGameSpace).setOnClickListener {
        if (isGameSpaceEnabled) toggleGameSpace()
    }
}

private fun MainActivity.setupTweakResetListeners(prefs: SharedPreferences) {
    findViewById<android.view.View>(R.id.btnResetFreeze).setOnClickListener {
        restoreAllFrozen()
        restoreAllUninstalled(silentEmpty = true)
    }

    findViewById<android.view.View>(R.id.btnResetFixedPerf).setOnClickListener {
        runAdbCommand(fixedPerfResetCmd(prefs), getString(R.string.fp_reset_done)) {
            prefs.edit().putBoolean(Prefs.KEY_FIXED_PERF, false).putBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false).apply()
            findViewById<TextView>(R.id.tvStatusFixedPerf).text = getString(R.string.status_default)
        }
    }
    findViewById<android.view.View>(R.id.btnResetThermal).setOnClickListener {
        resetThermal(prefs)
    }
    findViewById<android.view.View>(R.id.btnResetGameMode).setOnClickListener {
        val lastGame = prefs.getString(Prefs.KEY_LAST_GAME_MODE, "")
        val safe = ShellSanitizer.cleanPkg(lastGame)
        if (!safe.isNullOrEmpty()) {
            runAdbCommand("cmd game mode standard $safe", getString(R.string.msg_gm_reset)) {
                prefs.edit().putString(Prefs.KEY_LAST_GAME_MODE, "").apply()
                fetchSystemStatuses()
            }
        } else {
            UiToast.info(this, getString(R.string.toast_no_gm), Toast.LENGTH_SHORT).show()
        }
    }

    bindStatusReset(R.id.btnResetOptimize, R.id.tvStatusOptimize)
    bindStatusReset(R.id.btnResetRamBooster, R.id.tvStatusRamBooster)
    bindStatusReset(R.id.btnResetCache, R.id.tvStatusCache)
    bindStatusReset(R.id.btnResetFstrim, R.id.tvStatusFstrim)
    bindStatusReset(R.id.btnResetGyro, R.id.tvStatusGyro)
    bindStatusReset(R.id.btnResetShell, R.id.tvStatusShell)
    bindStatusReset(R.id.btnResetReboot, R.id.tvStatusReboot)
    bindStatusReset(R.id.btnResetAppLocale, R.id.tvStatusAppLocale)
    bindStatusReset(R.id.btnResetRadio, R.id.tvStatusRadio)
    bindStatusReset(R.id.btnResetShizukuRecovery, R.id.tvStatusShizukuRecovery)
    findViewById<android.view.View>(R.id.btnResetApkBackup).setOnClickListener {
        prefs.edit()
            .remove(Prefs.KEY_APKBACKUP_COUNT)
            .remove(Prefs.KEY_APKBACKUP_TIME)
            .apply()
        findViewById<TextView>(R.id.tvStatusApkBackup).text = getString(R.string.status_ready)
        UiToast.success(this, getString(R.string.toast_status_reset), Toast.LENGTH_SHORT).show()
    }

    bindCommandReset(
        R.id.btnResetRefresh,
        "settings delete system min_refresh_rate && settings delete system peak_refresh_rate",
        R.string.msg_refresh_reset
    )
    bindCommandReset(R.id.btnResetNetwork, "settings delete global tether_dun_required", R.string.msg_net_reset)
    bindCommandReset(
        R.id.btnResetTouch,
        TouchTweak.buildReset(),
        R.string.msg_touch_reset
    )
    bindCommandReset(R.id.btnResetResolution, "wm size reset && wm density reset", R.string.msg_res_reset)
    bindCommandReset(
        R.id.btnResetDns,
        "settings put global private_dns_mode default && settings delete global private_dns_specifier",
        R.string.msg_dns_reset
    )
    bindCommandReset(
        R.id.btnResetAnimations,
        "settings put global window_animation_scale 1 && settings put global transition_animation_scale 1 && settings put global animator_duration_scale 1",
        R.string.msg_anim_reset
    )
    bindCommandReset(R.id.btnResetBlurs, "settings put global disable_window_blurs 0", R.string.msg_blurs_reset)
    bindCommandReset(R.id.btnResetDnd, "settings put global heads_up_notifications_enabled 1", R.string.msg_dnd_reset)
    bindCommandReset(R.id.btnResetDevOptions, "settings put global development_settings_enabled 1", R.string.msg_devopt_on)
}


private fun MainActivity.setupBottomActionListeners(prefs: SharedPreferences) {
    findViewById<Button>(R.id.btnLaunchGame).setOnClickListener {
        showGameLauncherDialog()
    }

    findViewById<Button>(R.id.btnGameSpace).setOnClickListener {
        toggleGameSpace()
    }

    findViewById<Button>(R.id.btnResetAll).setOnClickListener {
        Sheet(this)
            .icon(R.drawable.ic_reset, Color.parseColor("#E53935"))
            .title(getString(R.string.dlg_reset_all_title))
            .message(getString(R.string.dlg_reset_all_msg))
            .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
            .button(getString(R.string.btn_reset_all), SheetStyle.DANGER) { resetAllFunctions(prefs) }
            .show()
    }
}

internal fun MainActivity.resetAllFunctions(prefs: android.content.SharedPreferences, onDone: (() -> Unit)? = null) {
    run {
        val lastGame = ShellSanitizer.cleanPkg(prefs.getString(Prefs.KEY_LAST_GAME_MODE, ""))
        val gameModeReset = if (lastGame.isNullOrEmpty()) "" else "cmd game mode standard $lastGame && "
        val aggressiveReset = if (prefs.getBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false))
            "{ ${fixedPerfAggressiveRestore(prefs)}; true; } && " else ""
        val batteryReset = batteryResetCmdForAll(prefs)
        val dozeReset = dozeResetCmdForAll(prefs)
        val dozePart = if (dozeReset != null) "$dozeReset && " else ""
        val appOpsReset = appOpsResetCmdForAll(prefs)
        val appOpsPart = if (appOpsReset != null) "$appOpsReset && " else ""
        val audioReset = audioResetCmdForAll(prefs)
        val sensorsReset = sensorsResetCmd(prefs)
        val sensorsPart = if (sensorsReset != null) "$sensorsReset && " else ""
        val bgReset = bgRestrictResetCmdForAll(prefs)
        val bgPart = if (bgReset != null) "$bgReset && " else ""

        val resetCmd = """
            cmd power set-fixed-performance-mode-enabled false
            cmd thermalservice reset
            $gameModeReset$aggressiveReset$batteryReset && $dozePart$appOpsPart$audioReset && $sensorsPart$bgPart
            settings delete system min_refresh_rate
            settings delete system peak_refresh_rate
            wm size reset
            wm density reset
            settings delete system pointer_speed
            settings put secure long_press_timeout 400
            settings put global private_dns_mode default
            settings delete global private_dns_specifier
            settings put global window_animation_scale 1
            settings put global transition_animation_scale 1
            settings put global animator_duration_scale 1
            settings put global disable_window_blurs 0
            settings put global heads_up_notifications_enabled 1
            settings put global development_settings_enabled 0
            setprop debug.hwui.renderer opengl
            settings put global enable_msaa 0
            settings put system show_touches 0
            settings delete secure doze_always_on
            settings put secure audio_safe_volume_state 0
            settings put global force_resizable_activities 0
            settings put global stay_on_while_plugged_in 0
            settings delete secure icon_blacklist
        """.trimIndent().replace("\n", " && ").replace("&&  &&", "&&")

        runAdbCommand(resetCmd, getString(R.string.msg_all_reset)) {
            prefs.edit()
                .putBoolean(Prefs.KEY_FIXED_PERF, false)
                .putBoolean(Prefs.KEY_FIXED_PERF_AGGRESSIVE, false)
                .putBoolean(Prefs.KEY_THERMAL_DISABLED, false)
                .putBoolean(Prefs.KEY_THERMAL_ADAPTIVE, false)
                .putBoolean(Prefs.KEY_THERMAL_HEATRESP, false)
                .putString(Prefs.KEY_LAST_GAME_MODE, "")
                .putString(Prefs.KEY_GRAPHICS_RENDERER, GraphicsApi.OPENGL)
                .putString(Prefs.KEY_GRAPHICS_DESIRED, GraphicsApi.OPENGL)
                .remove(Prefs.KEY_APPOPS_RULES)
                .apply()
            syncThermalService(0)
            clearBatteryPrefs(prefs)
            clearDozePrefs(prefs)
            clearAudioPrefs(prefs)
            clearSensorsPrefs(prefs)
            clearBgRestrictPrefs(prefs)
            resetNetBlock()


            if (isMonitorEnabled) {
                stopService(Intent(this@resetAllFunctions, RealtimeMonitorService::class.java))
                isMonitorEnabled = false
                prefs.edit().putBoolean(Prefs.KEY_MONITOR_ENABLED, false).apply()
                findViewById<TextView>(R.id.tvStatusMonitor)?.text = getString(R.string.status_disabled)
            }

            if (isCrosshairEnabled) stopCrosshairService()
            if (isAutoClickEnabled) stopAutoClickService()
            if (isGameSpaceEnabled) toggleGameSpace()

            findViewById<TextView>(R.id.tvStatusFixedPerf).text = getString(R.string.status_default)
            findViewById<TextView>(R.id.tvStatusOptimize).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusBatterySaver).text = getString(R.string.status_default)
            findViewById<TextView>(R.id.tvStatusAppOps).text = getString(R.string.status_default)
            findViewById<TextView>(R.id.tvStatusGameProfiles).text = gameProfilesStatusText(this)
            findViewById<TextView>(R.id.tvStatusDebloat).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusAudio).text = getString(R.string.status_default)
            findViewById<TextView>(R.id.tvStatusSensors).text = getString(R.string.status_default)
            findViewById<TextView>(R.id.tvStatusBgRestrict).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusBenchmark).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusGraphics).text = getString(R.string.status_graphics_opengl)

            findViewById<TextView>(R.id.tvStatusFstrim).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusRamBooster).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusCache).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusGyro).text = getString(R.string.status_ready)
            findViewById<TextView>(R.id.tvStatusShell)?.text = getString(R.string.status_ready)

            fetchSystemStatuses()
            onDone?.invoke()
        }
    }
}
