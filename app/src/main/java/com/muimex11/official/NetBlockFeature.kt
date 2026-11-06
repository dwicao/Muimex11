package com.muimex11.official

import android.content.Context
import android.content.pm.PackageManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app background-data block over `cmd netpolicy` (same mechanism as the
 * stock Settings toggle, enforced on metered networks without needing Data
 * Saver). Every mutation is verified with a read-back; unknown ROM output
 * fails safely instead of lying.
 */
internal fun MainActivity.showNetBlockSheet() {
    if (!hasShizukuPermission()) return
    val scanning = ProgressSheet(this)
        .icon(R.drawable.ic_dns)
        .title(getString(R.string.dlg_loading_apps))
        .message(getString(R.string.txt_please_wait))
    scanning.show(indeterminate = true)
    lifecycleScope.launch(Dispatchers.IO) {
        val blocked = queryBlockedUids()
        val saverOn = NetPolicy.isSaverOn(runAdbCommandWithResult(NetPolicy.CMD_GET_SAVER))
        val apps = try {
            scanInstalledApps().filter { !it.frozen }.mapNotNull { a ->
                val uid = try {
                    packageManager.getApplicationInfo(a.pkg, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                a to uid
            }
        } catch (e: Exception) {
            emptyList()
        }
        withContext(Dispatchers.Main) {
            scanning.dismiss()
            if (apps.isEmpty()) {
                UiToast.info(this@showNetBlockSheet, getString(R.string.toast_no_apps), Toast.LENGTH_LONG).show()
            } else {
                presentNetBlockList(apps, blocked.toMutableSet(), saverOn)
            }
        }
    }
}

private fun MainActivity.queryBlockedUids(): Set<Int> =
    NetPolicy.parseList(runAdbCommandWithResult(NetPolicy.CMD_LIST))

private fun MainActivity.presentNetBlockList(
    apps: List<Pair<AppEntry, Int>>,
    blocked: MutableSet<Int>,
    saverOn: Boolean
) {
    var query = ""
    var saver = saverOn
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)

    fun current(): List<Pair<AppEntry, Int>> {
        val base = if (query.isBlank()) apps
        else apps.filter { it.first.label.contains(query, true) || it.first.pkg.contains(query, true) }
        return base
    }

    fun render() {
        list.removeAllViews()
        list.addView(optionRow(
            this,
            getString(R.string.netblock_saver),
            getString(if (saver) R.string.netblock_saver_on else R.string.netblock_saver_off),
            selected = saver
        ) {
            applyDataSaver(!saver) { ok ->
                if (ok) {
                    saver = !saver
                    render()
                    fetchSystemStatuses()
                }
            }
        })
        list.addView(infoCard(this, getString(R.string.netblock_hint)))
        val rows = current()
        val shown = rows.take(80)
        if (shown.isEmpty()) {
            list.addView(infoCard(this, getString(R.string.txt_no_match)))
        }
        shown.forEach { (a, uid) ->
            val isBlocked = uid in blocked
            list.addView(optionRow(
                this, a.label,
                a.pkg + if (isBlocked) getString(R.string.netblock_blocked_suffix) else "",
                selected = isBlocked
            ) {
                // Single tap toggles; multi-select is unnecessary for a toggle.
                applyNetBlock(a.pkg, uid, !isBlocked) { ok, nowBlocked ->
                    if (ok) {
                        if (nowBlocked) blocked.add(uid) else blocked.remove(uid)
                        render()
                        fetchSystemStatuses()
                    }
                }
            })
        }
        if (rows.size > shown.size) {
            list.addView(TextView(this).apply {
                text = getString(R.string.showing_n, shown.size, rows.size)
                textSize = 12f
                setTextColor(Ui.onSurfaceVariant(this@presentNetBlockList))
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        fontify(list)
    }

    val (searchLayout, searchEdit) = styledField(
        this, getString(R.string.hint_search_apps),
        android.text.InputType.TYPE_CLASS_TEXT, topMarginDp = 10
    )
    searchEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            query = s?.toString()?.trim() ?: ""
            render()
        }
    })

    sheet.icon(R.drawable.ic_dns)
        .title(getString(R.string.dlg_netblock_title))
        .message(getString(R.string.dlg_netblock_msg))
        .content(searchLayout)
        .content(list)
        .button(getString(R.string.btn_close), SheetStyle.PRIMARY)
        .show()
    render()
}

private fun MainActivity.applyNetBlock(pkg: String, uid: Int, block: Boolean, done: (Boolean, Boolean) -> Unit) {
    val cmd = (if (block) NetPolicy.buildAdd(uid) else NetPolicy.buildRemove(uid)) ?: run {
        done(false, !block); return
    }
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(cmd)
        val nowBlocked = queryBlockedUids().contains(uid)
        val ok = res.ok && nowBlocked == block
        withContext(Dispatchers.Main) {
            if (ok) {
                UiToast.success(
                    this@applyNetBlock,
                    getString(if (block) R.string.msg_netblock_on else R.string.msg_netblock_off, pkg),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showCommandErrorSheet(getString(R.string.dlg_cmd_failed_title), cmd, res)
            }
            done(ok, nowBlocked)
        }
    }
}

private fun MainActivity.applyDataSaver(on: Boolean, done: (Boolean) -> Unit) {
    lifecycleScope.launch(Dispatchers.IO) {
        val res = ShizukuRunner.exec(NetPolicy.buildSetSaver(on))
        val verified = NetPolicy.isSaverOn(runAdbCommandWithResult(NetPolicy.CMD_GET_SAVER)) == on
        withContext(Dispatchers.Main) {
            if (!(res.ok && verified)) {
                showCommandErrorSheet(
                    getString(R.string.dlg_cmd_failed_title),
                    NetPolicy.buildSetSaver(on), res
                )
            }
            done(res.ok && verified)
        }
    }
}

internal fun MainActivity.netBlockStatusText(): String {
    if (!ShizukuRunner.isReady()) {
        val n = netBlockCountCached()
        return if (n <= 0) getString(R.string.status_default)
        else getString(R.string.status_netblock_fmt, n)
    }
    val n = queryBlockedUids().size
    getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE).edit()
        .putInt(Prefs.KEY_NETBLOCK_COUNT, n).apply()
    return if (n == 0) getString(R.string.status_default)
    else getString(R.string.status_netblock_fmt, n)
}

private fun MainActivity.netBlockCountCached(): Int =
    getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
        .getInt(Prefs.KEY_NETBLOCK_COUNT, 0)

internal fun MainActivity.resetNetBlock(onDone: (() -> Unit)? = null) {
    if (!hasShizukuPermission()) {
        onDone?.invoke()
        return
    }
    lifecycleScope.launch(Dispatchers.IO) {
        val blocked = queryBlockedUids().toList()
        var okCount = 0
        blocked.forEach { uid ->
            val cmd = NetPolicy.buildRemove(uid) ?: return@forEach
            if (ShizukuRunner.execOk(cmd)) okCount++
        }
        withContext(Dispatchers.Main) {
            getSharedPreferences(Prefs.MAIN, Context.MODE_PRIVATE)
                .edit().putInt(Prefs.KEY_NETBLOCK_COUNT, 0).apply()
            if (blocked.isNotEmpty()) {
                UiToast.success(
                    this@resetNetBlock,
                    getString(R.string.msg_netblock_reset, okCount), Toast.LENGTH_SHORT
                ).show()
            }
            fetchSystemStatuses()
            onDone?.invoke()
        }
    }
}
