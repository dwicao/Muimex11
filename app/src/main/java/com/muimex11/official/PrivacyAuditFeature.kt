package com.muimex11.official

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure parser and AppOps hardening helpers for Privacy & Sensitive Permission Audit. */
object PrivacyAuditLogic {
    val AUDIT_OPS = listOf(
        "READ_CLIPBOARD",
        "ACCESS_FINE_LOCATION",
        "CAMERA",
        "RECORD_AUDIO",
        "SYSTEM_ALERT_WINDOW",
        "RUN_ANY_IN_BACKGROUND"
    )

    data class PrivacyFinding(
        val pkg: String,
        val op: String,
        val mode: String
    )

    /**
     * Parses `dumpsys appops` output to locate allowed (`allow`, `foreground`, `allow_fg`)
     * entries across audited sensitive AppOps for non-system user packages.
     */
    fun parseDumpsysAppOps(raw: String, userPackages: Set<String>? = null): List<PrivacyFinding> {
        val findings = ArrayList<PrivacyFinding>()
        var currentPkg: String? = null
        val pkgHeader = Regex("""^\s*Package\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)\s*:""")
        val opParen = Regex("""^\s*([A-Z0-9_]+)\s*\(([^)]+)\)\s*:""")
        val opColon = Regex("""^\s*([A-Z0-9_]+):\s*mode=([A-Za-z_]+)""")
        val seen = HashSet<String>()

        for (line in raw.lineSequence()) {
            val pm = pkgHeader.find(line)
            if (pm != null) {
                currentPkg = pm.groupValues[1]
                continue
            }
            val pkg = currentPkg ?: continue
            if (pkg == "com.muimex11.official" || pkg == "moe.shizuku.privileged.api") continue
            if (userPackages != null && pkg !in userPackages) continue

            val om = opParen.find(line) ?: opColon.find(line) ?: continue
            val op = om.groupValues[1]
            if (op !in AUDIT_OPS) continue
            val mode = om.groupValues[2].trim().lowercase()
            if (mode == "allow" || mode == "foreground" || mode == "allow_fg") {
                val key = "$pkg|$op"
                if (seen.add(key)) {
                    findings.add(PrivacyFinding(pkg, op, mode))
                }
            }
        }
        return findings
    }

    fun buildRevokeCommand(pkg: String, op: String): String? {
        val safePkg = ShellSanitizer.cleanPkg(pkg) ?: return null
        if (op !in AUDIT_OPS) return null
        return "cmd appops set $safePkg $op ignore"
    }

    fun buildResetCommand(pkg: String, op: String): String? {
        val safePkg = ShellSanitizer.cleanPkg(pkg) ?: return null
        if (op !in AUDIT_OPS) return null
        return "cmd appops set $safePkg $op default"
    }

    fun buildBulkResetCommand(rules: Set<String>): String? {
        val cmds = rules.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 2) return@mapNotNull null
            buildResetCommand(parts[0], parts[1])
        }
        return if (cmds.isEmpty()) null else cmds.joinToString(" && ")
    }
}

internal fun MainActivity.privacyAuditStatusText(prefs: SharedPreferences): String {
    val rules = prefs.getStringSet(Prefs.KEY_PRIVACY_RULES, emptySet()) ?: emptySet()
    return if (rules.isEmpty()) getString(R.string.status_ready)
    else getString(R.string.privacy_status_fmt, rules.size)
}

internal fun MainActivity.resetPrivacyAudit(prefs: SharedPreferences) {
    if (!hasShizukuPermission()) return
    val rules = prefs.getStringSet(Prefs.KEY_PRIVACY_RULES, emptySet()) ?: emptySet()
    val cmd = PrivacyAuditLogic.buildBulkResetCommand(rules)
    if (cmd == null) {
        prefs.edit().remove(Prefs.KEY_PRIVACY_RULES).apply()
        findViewById<TextView>(R.id.tvStatusPrivacyAudit)?.text = getString(R.string.status_ready)
        UiToast.info(this, getString(R.string.privacy_reset_done), Toast.LENGTH_SHORT).show()
        return
    }
    runAdbCommand(cmd, getString(R.string.privacy_reset_done)) {
        prefs.edit().remove(Prefs.KEY_PRIVACY_RULES).apply()
        fetchSystemStatuses()
    }
}

internal fun MainActivity.showPrivacyAuditSheet(prefs: SharedPreferences) {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_security)
        .title(getString(R.string.title_privacy_audit))
        .message(getString(R.string.privacy_sheet_msg))

    list.addView(optionRow(
        this,
        getString(R.string.privacy_clear_clip),
        getString(R.string.privacy_clear_clip_desc)
    ) {
        clearClipboardSafely()
    })

    val opsUi = listOf(
        "READ_CLIPBOARD" to R.string.privacy_op_clipboard to R.string.privacy_op_clipboard_desc,
        "ACCESS_FINE_LOCATION" to R.string.privacy_op_location to R.string.privacy_op_location_desc,
        "CAMERA" to R.string.privacy_op_camera to R.string.privacy_op_camera_desc,
        "RECORD_AUDIO" to R.string.privacy_op_mic to R.string.privacy_op_mic_desc,
        "SYSTEM_ALERT_WINDOW" to R.string.privacy_op_overlay to R.string.privacy_op_overlay_desc,
        "RUN_ANY_IN_BACKGROUND" to R.string.privacy_op_bg to R.string.privacy_op_bg_desc
    )

    opsUi.forEach { (pair, descRes) ->
        val (op, titleRes) = pair
        list.addView(optionRow(this, getString(titleRes), getString(descRes)) {
            if (!hasShizukuPermission()) return@optionRow
            sheet.dismiss()
            scanPrivacyOp(prefs, op, getString(titleRes))
        })
    }

    sheet.content(list).button(getString(R.string.btn_close), SheetStyle.TONAL).show()
}

private fun MainActivity.clearClipboardSafely() {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        cm.clearPrimaryClip()
    } else {
        cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
    UiToast.success(this, getString(R.string.privacy_clip_cleared), Toast.LENGTH_SHORT).show()
}

private fun MainActivity.scanPrivacyOp(
    prefs: SharedPreferences,
    op: String,
    opLabel: String
) {
    val progress = ProgressSheet(this)
        .icon(R.drawable.ic_security)
        .title(opLabel)
        .message(getString(R.string.privacy_scanning))
    progress.show(indeterminate = true)

    lifecycleScope.launch(Dispatchers.IO) {
        val installed = try {
            scanInstalledApps().associateBy { it.pkg }
        } catch (_: Exception) {
            emptyMap()
        }
        val dump = ShizukuRunner.query("dumpsys appops", timeoutMs = 15_000L)
        val findings = PrivacyAuditLogic.parseDumpsysAppOps(dump, installed.keys.takeIf { it.isNotEmpty() })
            .filter { it.op == op }
            .sortedBy { installed[it.pkg]?.label ?: it.pkg }

        withContext(Dispatchers.Main) {
            progress.dismiss()
            presentPrivacyFindings(prefs, op, opLabel, findings, installed)
        }
    }
}

private fun MainActivity.presentPrivacyFindings(
    prefs: SharedPreferences,
    op: String,
    opLabel: String,
    findings: List<PrivacyAuditLogic.PrivacyFinding>,
    installed: Map<String, AppEntry>
) {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_security)
        .title(opLabel)
        .message(getString(R.string.privacy_found_msg, findings.size))

    if (findings.isEmpty()) {
        body.addView(infoCard(this, getString(R.string.privacy_none_found)))
    } else {
        findings.take(60).forEach { f ->
            val label = installed[f.pkg]?.label ?: f.pkg
            val sub = "${f.pkg} · mode=${f.mode}"
            body.addView(optionRow(this, label, sub) {
                sheet.dismiss()
                val cmd = PrivacyAuditLogic.buildRevokeCommand(f.pkg, f.op) ?: return@optionRow
                runAdbCommand(cmd, getString(R.string.privacy_revoked_fmt, label, opLabel)) {
                    val cur = (prefs.getStringSet(Prefs.KEY_PRIVACY_RULES, emptySet()) ?: emptySet()).toMutableSet()
                    cur.add("${f.pkg}|${f.op}")
                    prefs.edit().putStringSet(Prefs.KEY_PRIVACY_RULES, cur).apply()
                    fetchSystemStatuses()
                }
            })
        }
        if (findings.size > 60) {
            body.addView(infoCard(this, getString(R.string.showing_n, 60, findings.size)))
        }
    }

    sheet.content(body).button(getString(R.string.btn_close), SheetStyle.TONAL)
    if (findings.isNotEmpty()) {
        sheet.button(getString(R.string.privacy_revoke_all), SheetStyle.DANGER) {
            val top = findings.take(40)
            val cmds = top.mapNotNull { PrivacyAuditLogic.buildRevokeCommand(it.pkg, it.op) }
            if (cmds.isEmpty()) return@button
            runAdbCommand(cmds.joinToString(" && "), getString(R.string.privacy_bulk_revoked, cmds.size)) {
                val cur = (prefs.getStringSet(Prefs.KEY_PRIVACY_RULES, emptySet()) ?: emptySet()).toMutableSet()
                top.forEach { cur.add("${it.pkg}|${it.op}") }
                prefs.edit().putStringSet(Prefs.KEY_PRIVACY_RULES, cur).apply()
                fetchSystemStatuses()
            }
        }
    }
    sheet.show()
}
