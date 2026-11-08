package com.muimex11.official

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Single source of truth for tweak card grouping (filter chips + grid mode). */
private val performanceCardIds = listOf(R.id.cardFixedPerformance, R.id.cardOptimizeSystem, R.id.cardSamsungBoost, R.id.cardThermalThrottling, R.id.cardBatterySaver, R.id.cardMemTweaks, R.id.cardBatteryHealth, R.id.cardBgRestrict, R.id.cardFstrim, R.id.cardRamBooster, R.id.cardClearCache, R.id.cardFreezeApps)
private val displayCardIds = listOf(R.id.cardAdjustRefreshRate, R.id.cardFastAnimations, R.id.cardDisableBlurs, R.id.cardChangeResolution, R.id.cardGraphicsApi, R.id.cardSysTweaks, R.id.cardComfortTweaks, R.id.cardDisplayExtras, R.id.cardOrientation)
private val networkCardIds = listOf(R.id.cardImproveNetwork, R.id.cardCustomDns, R.id.cardRadioInfo, R.id.cardNetBlock, R.id.cardNetRadio)
private val gamingCardIds = listOf(R.id.cardImproveTouch, R.id.cardGamingDnd, R.id.cardCrosshair, R.id.cardRealtimeMonitor, R.id.cardAutoClick, R.id.cardGameMode, R.id.cardGameSpace, R.id.cardFixGyro, R.id.cardGameProfiles, R.id.cardGameTuning, R.id.cardAudioLatency)
private val toolsCardIds = listOf(R.id.cardShellExecute, R.id.cardApkInstall, R.id.cardApkBackup, R.id.cardReboot, R.id.cardShizukuRecovery, R.id.cardAppOps, R.id.cardAppLocale, R.id.cardSensorsToggle, R.id.cardBenchmark, R.id.cardDisableDevOptions, R.id.cardDebloat)

internal fun tweakCardIds(): List<Int> =
    performanceCardIds + displayCardIds + networkCardIds + gamingCardIds + toolsCardIds

/** Card title for search matching. */
private val tweakTitles: Map<Int, Int> = mapOf(
    R.id.cardFixedPerformance to R.string.title_fixed_perf,
    R.id.cardOptimizeSystem to R.string.title_optimize,
    R.id.cardSamsungBoost to R.string.title_samsung_boost,
    R.id.cardAdjustRefreshRate to R.string.title_refresh,
    R.id.cardImproveNetwork to R.string.title_network,
    R.id.cardRadioInfo to R.string.title_radio,
    R.id.cardNetBlock to R.string.title_netblock,
    R.id.cardImproveTouch to R.string.title_touch,
    R.id.cardFixGyro to R.string.title_gyro,
    R.id.cardFastAnimations to R.string.title_anim,
    R.id.cardDisableBlurs to R.string.title_blurs,
    R.id.cardGamingDnd to R.string.title_focus,
    R.id.cardDisableDevOptions to R.string.title_devopts,
    R.id.cardApkInstall to R.string.title_apk_install,
    R.id.cardApkBackup to R.string.title_apkbackup,
    R.id.cardChangeResolution to R.string.title_resolution,
    R.id.cardGraphicsApi to R.string.title_graphics,
    R.id.cardSysTweaks to R.string.title_systweaks,
    R.id.cardComfortTweaks to R.string.title_comfort,
    R.id.cardDisplayExtras to R.string.title_dx,
    R.id.cardNetRadio to R.string.title_netradio,
    R.id.cardMemTweaks to R.string.title_mem,
    R.id.cardGameTuning to R.string.title_gametuning,
    R.id.cardOrientation to R.string.title_orientation,
    R.id.cardCustomDns to R.string.title_dns,
    R.id.cardCrosshair to R.string.title_crosshair,
    R.id.cardRealtimeMonitor to R.string.title_monitor,
    R.id.cardAutoClick to R.string.title_autoclick,
    R.id.cardGameMode to R.string.title_gamemode,
    R.id.cardGameSpace to R.string.title_gamespace,
    R.id.cardThermalThrottling to R.string.title_thermal,
    R.id.cardBatterySaver to R.string.title_battery,
    R.id.cardBatteryHealth to R.string.title_batthealth,
    R.id.cardFstrim to R.string.title_fstrim,
    R.id.cardRamBooster to R.string.title_ram,
    R.id.cardClearCache to R.string.title_cache,
    R.id.cardFreezeApps to R.string.title_freeze,
    R.id.cardAppOps to R.string.title_appops,
    R.id.cardAppLocale to R.string.title_applocale,
    R.id.cardGameProfiles to R.string.title_gameprofiles,
    R.id.cardDebloat to R.string.title_debloat,
    R.id.cardAudioLatency to R.string.title_audio,
    R.id.cardSensorsToggle to R.string.title_sensors,
    R.id.cardBgRestrict to R.string.title_bgrestrict,
    R.id.cardBenchmark to R.string.title_bench,
    R.id.cardShellExecute to R.string.title_shell,
    R.id.cardReboot to R.string.title_reboot,
    R.id.cardShizukuRecovery to R.string.title_srec
)

internal fun MainActivity.cardTitle(id: Int): String? {
    val res = tweakTitles[id] ?: return null
    return try {
        getString(res)
    } catch (_: Exception) {
        null
    }
}

/** Tint each category with its own accent so the wall of cards scans faster. */
internal fun MainActivity.syncTweakCardColors() {
    val groups = listOf(
        performanceCardIds to "#2E9E5B",
        displayCardIds to "#2563EB",
        networkCardIds to "#E0A800",
        gamingCardIds to "#7C3AED",
        toolsCardIds to "#8A8F98"
    )
    groups.forEach { (ids, hex) ->
        val color = try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            return@forEach
        }
        ids.forEach { id ->
            try {
                (findViewById<View>(id) as? MaterialCardView)?.strokeColor = color
            } catch (_: Exception) {}
        }
    }
}

internal fun MainActivity.fontify(view: View) {
    ResourcesCompat.getFont(this, R.font.lexendregular)?.let { applyAppFont(view, it) }
}

internal fun MainActivity.xl(v: String): String {
    val id = when (v) {
        "Cross" -> R.string.xl_cross
        "Dot" -> R.string.xl_dot
        "Circle" -> R.string.xl_circle
        "Cross with Circle" -> R.string.xl_cross_circle
        "Square" -> R.string.xl_square
        "Target" -> R.string.xl_target
        "Gap Cross" -> R.string.xl_gap_cross
        "Diamond" -> R.string.xl_diamond
        "Triangle" -> R.string.xl_triangle
        "White" -> R.string.xl_white
        "Black" -> R.string.xl_black
        "Red" -> R.string.xl_red
        "Green" -> R.string.xl_green
        "Blue" -> R.string.xl_blue
        "Yellow" -> R.string.xl_yellow
        "Cyan" -> R.string.xl_cyan
        "Magenta" -> R.string.xl_magenta
        "Tiny" -> R.string.xl_tiny
        "Small" -> R.string.xl_small
        "Medium" -> R.string.xl_medium
        "Large" -> R.string.xl_large
        "Extra Large" -> R.string.xl_xlarge
        else -> return v
    }
    return getString(id)
}

internal fun MainActivity.setupFilterChips() {
    val chipGroup = findViewById<ChipGroup>(R.id.chipGroupCategories)

    val customTypeface = ResourcesCompat.getFont(this, R.font.lexendregular)
    val chipIds = listOf(R.id.chipAll, R.id.chipPerformance, R.id.chipDisplay, R.id.chipNetwork, R.id.chipGaming, R.id.chipTools)
    chipIds.forEach { id ->
        findViewById<Chip>(id)?.typeface = customTypeface
    }

    val allCards = tweakCardIds()
    var checkedId = chipGroup.checkedChipId
    if (checkedId == View.NO_ID) checkedId = R.id.chipAll
    var query = ""

    fun groupIds(): List<Int> = when (checkedId) {
        R.id.chipPerformance -> performanceCardIds
        R.id.chipDisplay -> displayCardIds
        R.id.chipNetwork -> networkCardIds
        R.id.chipGaming -> gamingCardIds
        R.id.chipTools -> toolsCardIds
        else -> allCards
    }

    fun refresh() {
        val group = groupIds().toSet()
        val q = query.trim()
        var shown = 0
        allCards.forEach { id ->
            val matchesGroup = id in group
            val matchesQuery = q.isEmpty() ||
                cardTitle(id)?.contains(q, ignoreCase = true) == true
            val visible = matchesGroup && matchesQuery
            if (visible) shown++
            findViewById<View>(id)?.visibility = if (visible) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.tvNoMatch)?.visibility =
            if (shown == 0) View.VISIBLE else View.GONE
    }

    chipGroup.setOnCheckedChangeListener { _, id ->
        checkedId = id
        refresh()
    }

    findViewById<EditText>(R.id.etSearchTweaks)?.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            query = s?.toString() ?: ""
            refresh()
        }
    })

    refresh()
}

internal enum class TweakState { ACTIVE, NEUTRAL, WARN }

internal fun MainActivity.setupStatusStyling() {
    val statusIds = listOf(
        R.id.tvStatusFixedPerf, R.id.tvStatusOptimize, R.id.tvStatusRefresh, R.id.tvStatusNetwork,
        R.id.tvStatusTouch, R.id.tvStatusAnimations, R.id.tvStatusBlurs, R.id.tvStatusDnd,
        R.id.tvStatusDevOptions, R.id.tvStatusResolution, R.id.tvStatusDns, R.id.tvStatusCrosshair,
        R.id.tvStatusMonitor, R.id.tvStatusGameMode, R.id.tvStatusThermal, R.id.tvStatusFstrim,
        R.id.tvStatusRamBooster, R.id.tvStatusCache, R.id.tvStatusFreeze, R.id.tvStatusAutoClick,
        R.id.tvStatusGameSpace, R.id.tvStatusSamsungBoost, R.id.tvStatusGyro, R.id.tvStatusBatterySaver,
        R.id.tvStatusAppOps, R.id.tvStatusGameProfiles, R.id.tvStatusDebloat, R.id.tvStatusAudio,
        R.id.tvStatusSensors, R.id.tvStatusBgRestrict, R.id.tvStatusBenchmark,
        R.id.tvStatusGraphics, R.id.tvStatusOrientation, R.id.tvStatusApkBackup,
        R.id.tvStatusReboot, R.id.tvStatusAppLocale, R.id.tvStatusRadio,
        R.id.tvStatusShizukuRecovery, R.id.tvStatusNetBlock, R.id.tvStatusBattHealth,
        R.id.tvStatusSysTweaks, R.id.tvStatusComfort, R.id.tvStatusNetRadio,
        R.id.tvStatusMem, R.id.tvStatusGameTuning, R.id.tvStatusDx
    )
    val states = HashMap<Int, TweakState>()
    val green = Color.parseColor("#2E9E5B")
    val amber = Color.parseColor("#E09B00")

    fun stateOf(id: Int, text: String): TweakState {
        // Compare against the English source so this works in every language.
        fun en(res: Int) = AppTranslator.english(this, res)
        val t = AppTranslator.toEnglish(text).removePrefix("Status:").trim()
        return when {
            t.isEmpty() || t.startsWith(en(R.string.w_default)) || t.startsWith(en(R.string.w_ready)) ||
                t.startsWith(en(R.string.w_unknown)) || t.startsWith(en(R.string.w_checking)) -> TweakState.NEUTRAL
            id == R.id.tvStatusDevOptions -> if (t.startsWith(en(R.string.w_enabled))) TweakState.WARN else TweakState.ACTIVE
            id == R.id.tvStatusThermal -> TweakState.WARN
            id == R.id.tvStatusGraphics -> if (t.contains(en(R.string.w_vulkan))) TweakState.ACTIVE else TweakState.NEUTRAL
            id == R.id.tvStatusOrientation -> if (t.contains(en(R.string.orient_auto))) TweakState.NEUTRAL else TweakState.ACTIVE
            id == R.id.tvStatusTouch -> when {
                t.startsWith(en(R.string.w_improved)) -> TweakState.ACTIVE
                t.contains(en(R.string.w_partial)) -> TweakState.WARN
                else -> TweakState.NEUTRAL
            }
            id == R.id.tvStatusShizukuRecovery -> if (t.startsWith(en(R.string.w_ready))) TweakState.NEUTRAL else TweakState.WARN
            id == R.id.tvStatusBlurs -> if (t.startsWith(en(R.string.w_disabled))) TweakState.ACTIVE else TweakState.NEUTRAL
            t.startsWith(en(R.string.w_disabled)) -> TweakState.NEUTRAL
            else -> TweakState.ACTIVE
        }
    }

    fun refresh() {
        val active = states.values.count { it != TweakState.NEUTRAL }
        val warn = states.values.count { it == TweakState.WARN }
        findViewById<TextView>(R.id.tvActiveSummary)?.text =
            getString(R.string.summary_active, active, statusIds.size) +
                if (warn > 0) "  ·  ${warn} ${getString(R.string.summary_warn)}" else ""
    }

    fun apply(tv: TextView, id: Int) {
        val st = stateOf(id, tv.text.toString())
        states[id] = st
        val color = when (st) {
            TweakState.ACTIVE -> green
            TweakState.WARN -> amber
            TweakState.NEUTRAL -> Color.parseColor("#8A8F98")
        }
        tv.setTextColor(color)
        AppCompatResources.getDrawable(this, R.drawable.ic_status_dot)?.let { d ->
            val dot = DrawableCompat.wrap(d.mutate())
            DrawableCompat.setTint(dot, color)
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        }
        refresh()
    }

    statusIds.forEach { id ->
        val tv = findViewById<TextView>(id) ?: return@forEach
        tv.contentDescription = tv.text
        apply(tv, id)
        tv.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tv.contentDescription = tv.text
                apply(tv, id)
            }
        })
    }
}

internal fun MainActivity.setupBottomBarScrollBehavior() {
    val scrollView = findViewById<NestedScrollView>(R.id.mainScrollView)
    val bottomBar = findViewById<View>(R.id.floatingBottomBar)
    val header = findViewById<View>(R.id.headerControls)
    var isBottomBarVisible = true

    scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
        if (scrollY > oldScrollY && isBottomBarVisible) {
            isBottomBarVisible = false
            val margin = (bottomBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            bottomBar.animate()
                .translationY(bottomBar.height.toFloat() + margin)
                .setDuration(250)
                .start()
        } else if (scrollY < oldScrollY && !isBottomBarVisible) {
            isBottomBarVisible = true
            bottomBar.animate()
                .translationY(0f)
                .setDuration(250)
                .start()
        }

        // enterAlways-style: hide while scrolling down, slide back over the
        // content on any upward scroll.
        val h = header.height
        if (h > 0) {
            if (headerRevealed < 0) headerRevealed = h
            val dy = scrollY - oldScrollY
            if (dy != 0) {
                headerRevealed = (headerRevealed - dy).coerceIn(0, h)
                header.translationY = (scrollY - h + headerRevealed).toFloat()
            }
        }
    }
}

/** Pixels of the enterAlways search header currently revealed [0..H]. */
private var headerRevealed = -1
