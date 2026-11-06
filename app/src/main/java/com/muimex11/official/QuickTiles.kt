package com.muimex11.official

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tiles for the most-used toggles. Each tile runs its shell
 * command over Shizuku; without a backend it unlocks the screen and opens
 * Muimex11 instead of failing silently.
 */
private abstract class MuimexTile : TileService() {
    protected val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    protected fun openApp() {
        try {
            unlockAndCollapse()
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        } catch (_: Exception) {}
    }

    protected fun toast(msg: String, long: Boolean = false) {
        try {
            Toast.makeText(
                applicationContext, msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {}
    }
}

private fun Tile.setActive(active: Boolean, label: String) {
    this.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    this.label = label
    updateTile()
}

class DnsTileService : MuimexTile() {
    override fun onStartListening() {
        super.onStartListening()
        tileScope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        if (!ShizukuRunner.isReady()) {
            openApp()
            return
        }
        tileScope.launch {
            val mode = ShizukuRunner.query("settings get global private_dns_mode").trim()
            if (mode == "hostname") {
                ShizukuRunner.exec(
                    "settings put global private_dns_mode default && " +
                        "settings delete global private_dns_specifier"
                )
            } else {
                ShizukuRunner.exec(
                    "settings put global private_dns_mode hostname && " +
                        "settings put global private_dns_specifier one.one.one.one"
                )
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val t = qsTile ?: return
        val ready = ShizukuRunner.isReady()
        val on = ready && ShizukuRunner.query("settings get global private_dns_mode")
            .trim() == "hostname"
        withContext(Dispatchers.Main) {
            t.setActive(on && ready, getString(R.string.tile_dns))
        }
    }
}

class DndTileService : MuimexTile() {
    override fun onStartListening() {
        super.onStartListening()
        tileScope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        if (!ShizukuRunner.isReady()) {
            openApp()
            return
        }
        tileScope.launch {
            val cur = ShizukuRunner.query("settings get global heads_up_notifications_enabled").trim()
            ShizukuRunner.exec(
                "settings put global heads_up_notifications_enabled " + if (cur == "0") "1" else "0"
            )
            refresh()
        }
    }

    private suspend fun refresh() {
        val t = qsTile ?: return
        val ready = ShizukuRunner.isReady()
        val blocked = ready && ShizukuRunner.query("settings get global heads_up_notifications_enabled")
            .trim() == "0"
        withContext(Dispatchers.Main) {
            t.setActive(blocked && ready, getString(R.string.tile_dnd))
        }
    }
}

class VulkanTileService : MuimexTile() {
    override fun onStartListening() {
        super.onStartListening()
        tileScope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        if (!ShizukuRunner.isReady()) {
            openApp()
            return
        }
        tileScope.launch {
            val cur = GraphicsApi.detectRenderer()
            val target = if (cur == GraphicsApi.VULKAN) GraphicsApi.OPENGL else GraphicsApi.VULKAN
            val prefs = getSharedPreferences(Prefs.MAIN, MODE_PRIVATE)
            val res = GraphicsApi.applyRenderer(target, prefs)
            withContext(Dispatchers.Main) {
                if (!res.verified) toast(getString(R.string.msg_graphics_fail, res.message), long = true)
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val t = qsTile ?: return
        val ready = ShizukuRunner.isReady()
        val vulkan = ready && GraphicsApi.detectRenderer() == GraphicsApi.VULKAN
        withContext(Dispatchers.Main) {
            t.setActive(vulkan && ready, getString(R.string.tile_vulkan))
        }
    }
}
