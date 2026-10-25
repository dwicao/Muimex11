package com.muimex11.official

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

/**
 * Lightweight locale helper (replaces on-device ML-Kit translation).
 *
 * Why: ML-Kit pulled a large native model, required downloads, and wrapped
 * every Resources call. Static values-xx/strings.xml (en + pt) is smaller,
 * offline, and instant. System locale is respected via per-app locales
 * (locales_config.xml).
 */
object AppTranslator {
    fun targetLanguage(): String? {
        val code = Locale.getDefault().language
        return if (code.isEmpty() || code == "en") null else code
    }

    fun languageDisplayName(): String {
        val loc = Locale.getDefault()
        return loc.getDisplayLanguage(loc).replaceFirstChar { it.uppercase(loc) }
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        // No-op: kept for call-site compatibility.
    }

    fun needsSetup(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    val isActive: Boolean get() = false

    fun startSetup(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") onPhase: (Int) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        onDone(false)
    }

    fun cancelSetup() {}

    fun wrap(base: Resources): Resources = base

    fun translateTree(@Suppress("UNUSED_PARAMETER") root: View) {
        // No-op: static resources already localized.
    }

    fun english(context: Context, id: Int): String = context.getString(id)

    fun toEnglish(text: String): String = text

    internal fun text(@Suppress("UNUSED_PARAMETER") id: Int): String? = null

    internal fun plural(@Suppress("UNUSED_PARAMETER") id: Int, @Suppress("UNUSED_PARAMETER") quantity: Int): String? = null

    fun applyLocale(base: Context): Context {
        // Respect system locale; per-app override could be added here.
        return base
    }
}

/** Kept for binary compat — now a plain pass-through. */
class TranslatingResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
    internal fun rawText(id: Int): CharSequence = super.getText(id)
    internal fun rawPlural(id: Int, quantity: Int): CharSequence = super.getQuantityText(id, quantity)
}

internal fun View.translateAllViews() {
    if (this is TextView) {
        // contentDescription already from XML; nothing to machine-translate.
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).translateAllViews()
    }
}

fun Context.withAppLocale(): Context {
    val cfg = Configuration(resources.configuration)
    return createConfigurationContext(cfg)
}
