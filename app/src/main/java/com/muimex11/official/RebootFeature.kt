package com.muimex11.official

import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-tap reboot menu for the Tools category: system restart, recovery,
 * bootloader and a SystemUI restart (applies theme/renderer-level changes
 * without a full reboot).
 */
internal fun MainActivity.showRebootSheet() {
    if (!hasShizukuPermission()) return
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val sheet = Sheet(this)
        .icon(R.drawable.ic_refresh)
        .title(getString(R.string.dlg_reboot_title))
        .message(getString(R.string.dlg_reboot_msg))
    list.addView(optionRow(
        this,
        getString(R.string.reboot_system),
        getString(R.string.reboot_system_desc)
    ) {
        sheet.dismiss()
        confirmReboot(RebootCmd.SYSTEM)
    })
    list.addView(optionRow(
        this,
        getString(R.string.reboot_recovery),
        getString(R.string.reboot_recovery_desc)
    ) {
        sheet.dismiss()
        confirmReboot(RebootCmd.RECOVERY)
    })
    list.addView(optionRow(
        this,
        getString(R.string.reboot_bootloader),
        getString(R.string.reboot_bootloader_desc)
    ) {
        sheet.dismiss()
        confirmReboot(RebootCmd.BOOTLOADER)
    })
    list.addView(optionRow(
        this,
        getString(R.string.reboot_systemui),
        getString(R.string.reboot_systemui_desc)
    ) {
        sheet.dismiss()
        runRebootAction(RebootCmd.SYSTEM_UI)
    })
    sheet.content(list).button(getString(R.string.btn_cancel), SheetStyle.TONAL).show()
}

private fun MainActivity.confirmReboot(mode: Int) {
    val cmd = RebootCmd.build(mode) ?: return
    val label = when (mode) {
        RebootCmd.RECOVERY -> getString(R.string.reboot_recovery)
        RebootCmd.BOOTLOADER -> getString(R.string.reboot_bootloader)
        else -> getString(R.string.reboot_system)
    }
    Sheet(this)
        .icon(R.drawable.ic_warning)
        .title(getString(R.string.dlg_reboot_confirm_title, label))
        .message(getString(R.string.dlg_reboot_confirm_msg))
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.btn_reboot_now), SheetStyle.DANGER) {
            runRebootAction(mode)
        }
        .show()
}

private fun MainActivity.runRebootAction(mode: Int) {
    val cmd = RebootCmd.build(mode) ?: return
    if (!hasShizukuPermission()) return
    if (mode == RebootCmd.SYSTEM_UI) {
        runAdbCommand(cmd, getString(R.string.msg_systemui_restarted)) { fetchSystemStatuses() }
        return
    }
    lifecycleScope.launch(Dispatchers.IO) {
        ShizukuRunner.exec(cmd)
        withContext(Dispatchers.Main) {
            UiToast.info(this@runRebootAction, getString(R.string.msg_rebooting), Toast.LENGTH_LONG).show()
        }
    }
}
