package com.muimex11.official

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

/**
 * Automation entry point for Tasker / MacroDroid: send a broadcast with
 * action [ACTION_RUN] plus `token` and `action` extras.
 *
 *   action=renderer value=vulkan|opengl   → switch the GPU renderer
 *   action=game_boost                    → kill-all background apps
 *
 * The token (shown in Shell Execute → Automation) must match the stored one;
 * without it the broadcast is silently ignored. This keeps an exported
 * receiver from becoming an open shell for any installed app.
 */
class TaskerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RUN = "com.muimex11.official.TASKER_RUN"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ACTION = "action"
        const val EXTRA_VALUE = "value"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val prefs = context.getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        val stored = prefs.getString(Prefs.KEY_AUTOMATION_TOKEN, null)
        if (!AutomationAuth.matches(stored, intent.getStringExtra(EXTRA_TOKEN))) return

        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    when (TaskerCmd.parseAction(intent.getStringExtra(EXTRA_ACTION))) {
                        TaskerCmd.ACTION_RENDERER -> {
                            val target = TaskerCmd.parseRenderer(intent.getStringExtra(EXTRA_VALUE))
                                ?: return@runBlocking
                            GraphicsApi.applyRenderer(target, prefs)
                        }
                        TaskerCmd.ACTION_BOOST -> {
                            if (ShizukuRunner.isReady()) {
                                ShizukuRunner.exec("cmd activity kill-all")
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                // Automation must never crash the receiver.
            } finally {
                try { pending.finish() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }
}
