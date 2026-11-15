package com.muimex11.official

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)

        // Explicit user choice wins; otherwise follow the system setting.
        val prefs = getSharedPreferences(Prefs.THEME, MODE_PRIVATE)
        val nightMode = if (prefs.contains(Prefs.KEY_DARK_MODE)) {
            if (prefs.getBoolean(Prefs.KEY_DARK_MODE, false)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
