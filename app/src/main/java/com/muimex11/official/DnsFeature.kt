package com.muimex11.official

import android.content.SharedPreferences
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal fun MainActivity.showDnsDialog() {
    data class Dns(val name: String, val desc: String, val host: String)
    val providers = listOf(
        Dns("Control D", getString(R.string.dns_blocks_ads), "p2.freedns.controld.com"),
        Dns("Cloudflare", getString(R.string.dns_fastest), "one.one.one.one"),
        Dns("Quad9", getString(R.string.dns_malicious), "dns.quad9.net"),
        Dns("NextDNS", getString(R.string.dns_personalized), "dns.nextdns.io"),
        Dns("Google", getString(R.string.dns_stable), "dns.google"),
        Dns("AdGuard", getString(R.string.dns_blocks_ads), "dns.adguard-dns.com"),
        Dns("CleanBrowsing", getString(R.string.dns_family_safe), "family-filter-dns.cleanbrowsing.org")
    )
    val prefs = getSharedPreferences(Prefs.MAIN, MODE_PRIVATE)
    val current = findViewById<TextView>(R.id.tvStatusDns).text.toString()

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_lock)
        .title(getString(R.string.dlg_private_dns))
        .message(getString(R.string.dlg_private_dns_msg))

    providers.forEach { p ->
        list.addView(optionRow(this, p.name, "${p.desc}  ·  ${p.host}", selected = current.contains(p.host)) {
            sheet.dismiss()
            saveLastDns(prefs, p.host)
            val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier ${p.host}"
            runAdbCommand(cmd, getString(R.string.toast_dns_set, p.name)) { fetchSystemStatuses() }
        })
    }
    list.addView(optionRow(this, getString(R.string.dns_custom), getString(R.string.dns_custom_desc), selected = false) {
        sheet.dismiss()
        showCustomDnsDialog(prefs)
    })
    list.addView(optionRow(this, getString(R.string.w_default), getString(R.string.dns_off_desc), selected = current.contains(getString(R.string.w_default))) {
        sheet.dismiss()
        val cmd = "settings put global private_dns_mode default && settings delete global private_dns_specifier"
        runAdbCommand(cmd, getString(R.string.msg_dns_default)) { fetchSystemStatuses() }
    })

    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TEXT).show()
}

private fun MainActivity.showCustomDnsDialog(prefs: SharedPreferences) {
    val (layout, edit) = styledField(
        this,
        getString(R.string.dns_custom_hint),
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    )
    edit.setText(prefs.getString(Prefs.KEY_LAST_DNS, "") ?: "")
    val sheet = Sheet(this)
        .icon(R.drawable.ic_lock)
        .title(getString(R.string.dns_custom_title))
        .message(getString(R.string.dns_custom_msg))
    sheet.content(layout)
    sheet.button(getString(R.string.btn_cancel), SheetStyle.TONAL) { }
    sheet.button(getString(R.string.btn_apply), SheetStyle.PRIMARY, dismiss = false) {
        val host = edit.text?.toString()?.trim().orEmpty()
        if (host.isEmpty() || !isValidDnsHost(host)) {
            UiToast.error(this@showCustomDnsDialog, getString(R.string.dns_custom_invalid), Toast.LENGTH_SHORT).show()
            return@button
        }
        saveLastDns(prefs, host)
        sheet.dismiss()
        val cmd = "settings put global private_dns_mode hostname && settings put global private_dns_specifier $host"
        runAdbCommand(cmd, getString(R.string.toast_dns_set, host)) { fetchSystemStatuses() }
    }
    sheet.show()
}

private fun saveLastDns(prefs: SharedPreferences, host: String) {
    prefs.edit().putString(Prefs.KEY_LAST_DNS, host).apply()
}

private fun isValidDnsHost(host: String): Boolean {
    if (host.length !in 1..253) return false
    if (host.contains(' ') || host.contains('\'') || host.contains('"') || host.contains(';') || host.contains('&')) return false
    val single = Regex("^[A-Za-z0-9]$")
    if (single.matches(host)) return true
    return Regex("^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$").matches(host)
}
