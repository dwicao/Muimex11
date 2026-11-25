package com.muimex11.official

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
abstract class MuimexTile : TileService() {    protected val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    /**
     * Open Muimex11 from a tile tap.
     *
     * Two platform traps made this a silent no-op before v4.34:
     *  - startActivityAndCollapse(Intent) throws UnsupportedOperationException
     *    on Android 14+ (targetSdk 34+), so the tap died inside a swallowed
     *    catch block and nothing happened at all.
     *  - On a locked device starting an activity directly fails, so it must
     *    be routed through unlockAndRun().
     */
    protected fun openApp() {
        val intent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Settings.ACTION_SETTINGS)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (isLocked) {
                // Prompt for unlock first, then launch (collapses the shade).
                unlockAndRun(Runnable { launchCollapsed(intent) })
            } else {
                launchCollapsed(intent)
            }
        } catch (e: Exception) {
            logQuietly("QuickTiles", e)
            toast(getString(R.string.tile_open_fail), long = true)
        }
    }

    // The Intent overload is the only option below API 34; the PendingIntent
    // path above is used there. Silenced narrowly instead of ignoring the check.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchCollapsed(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            logQuietly("QuickTiles", e)
            toast(getString(R.string.tile_open_fail), long = true)
        }
    }

    protected fun toast(msg: String, long: Boolean = false) {
        try {
            Toast.makeText(
                applicationContext, msg,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) { logQuietly("QuickTiles", e) }
    }
}

private fun Tile.setActive(active: Boolean, label: String) {
    this.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    this.label = label
    // updateTile() is only valid while the tile is listening; the service can
    // be unbound mid-flight (tap -> shade collapses), so never let it crash.
    try {
        updateTile()
    } catch (e: Exception) {
        logQuietly("QuickTiles", e)
    }
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
            val turningOff = mode == "hostname"
            val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            val saved = prefs.getString(Prefs.KEY_LAST_DNS, "one.one.one.one")?.trim().orEmpty()
            val host = if (saved.isNotEmpty() && saved.matches(Regex("^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$"))) saved else "one.one.one.one"
            val res = if (turningOff) {
                ShizukuRunner.exec(
                    "settings put global private_dns_mode default && " +
                        "settings delete global private_dns_specifier"
                )
            } else {
                ShizukuRunner.exec(
                    "settings put global private_dns_mode hostname && " +
                        "settings put global private_dns_specifier $host"
                )
            }
            withContext(Dispatchers.Main) {
                if (res.ok) {
                    if (turningOff) {
                        toast(getString(R.string.tile_dns_off))
                    } else {
                        toast(getString(R.string.tile_dns_on, host))
                    }
                } else {
                    toast(getString(R.string.tile_dns_fail), long = true)
                }
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
            val blocked = cur == "0"
            val res = ShizukuRunner.exec(
                "settings put global heads_up_notifications_enabled " + if (blocked) "1" else "0"
            )
            withContext(Dispatchers.Main) {
                if (res.ok) {
                    toast(getString(if (blocked) R.string.tile_dnd_off else R.string.tile_dnd_on))
                } else {
                    toast(getString(R.string.tile_dnd_fail), long = true)
                }
            }
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
            val prefs = getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
            val res = GraphicsApi.applyRenderer(target, prefs)
            withContext(Dispatchers.Main) {
                if (res.verified) {
                    toast(getString(R.string.tile_vulkan_applied, target), long = true)
                } else {
                    toast(getString(R.string.msg_graphics_fail, res.message), long = true)
                }
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

class DexHzTileService : MuimexTile() {
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
            val current = DexHzBridge.state.value ?: DexHzBridge.refresh(this@DexHzTileService)
            val turnOn = !(current?.running ?: false)
            val msg = DexHzBridge.withService(this@DexHzTileService) { svc ->
                if (turnOn) DexHzBridge.startWithSavedPrefs(this@DexHzTileService, svc)
                else DexHzBridge.stopAndForget(this@DexHzTileService, svc)
            }
            withContext(Dispatchers.Main) {
                if (!msg.isNullOrBlank()) toast(msg)
                else toast(getString(R.string.tile_dexhz_fail), long = true)
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val t = qsTile ?: return
        val ready = ShizukuRunner.isReady()
        val st = if (ready) DexHzBridge.state.value ?: DexHzBridge.refresh(this) else null
        val active = ready && (st?.running ?: false)
        val label = if (active && (st?.appHz ?: 0f) > 0f) {
            getString(R.string.tile_dexhz_rate, kotlin.math.round(st!!.appHz).toInt())
        } else {
            getString(R.string.tile_dexhz)
        }
        withContext(Dispatchers.Main) {
            t.setActive(active, label)
        }
    }
}

class MirrorTileService : MuimexTile() {
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
            val state = MirrorDisplayManager.refreshStateSync(this@MirrorTileService)
            if (state.isOverrideApplied) {
                val ok = ShizukuRunner.execOk(MirrorLogic.buildResetCmd())
                MirrorDisplayManager.refreshStateSync(this@MirrorTileService)
                withContext(Dispatchers.Main) {
                    toast(getString(if (ok) R.string.mirror_restored_toast else R.string.tile_mirror_fail), long = !ok)
                }
            } else {
                val dpi = MirrorDisplayManager.getPreferredDpi(this@MirrorTileService)
                val ok = ShizukuRunner.execOk(MirrorLogic.buildApplyCmd(1080, 1920, dpi))
                MirrorDisplayManager.refreshStateSync(this@MirrorTileService)
                withContext(Dispatchers.Main) {
                    toast(getString(if (ok) R.string.mirror_applied_port else R.string.tile_mirror_fail), long = !ok)
                }
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val t = qsTile ?: return
        val ready = ShizukuRunner.isReady()
        val st = if (ready) MirrorDisplayManager.refreshStateSync(this) else null
        val active = ready && (st?.isOverrideApplied ?: false)
        withContext(Dispatchers.Main) {
            t.setActive(active, getString(R.string.tile_mirror))
        }
    }
}
