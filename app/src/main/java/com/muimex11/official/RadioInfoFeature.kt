package com.muimex11.official

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-info launcher (bands / RAT / signal): fires the SECRET_CODE broadcast
 * for 4636, the same mechanism the dialer uses. Best-effort — some OEM skins
 * ignore it, in which case nothing opens and no setting is changed.
 */
internal fun MainActivity.showRadioInfoSheet() {
    if (!hasShizukuPermission()) return
    Sheet(this)
        .icon(R.drawable.ic_tune)
        .title(getString(R.string.dlg_radio_title))
        .message(getString(R.string.dlg_radio_msg))
        .button(getString(R.string.btn_cancel), SheetStyle.TONAL)
        .button(getString(R.string.btn_open_phone_info), SheetStyle.PRIMARY) {
            openRadioInfo()
        }
        .show()
}

private fun MainActivity.openRadioInfo() {
    lifecycleScope.launch(Dispatchers.IO) {
        ShizukuRunner.exec(
            "am broadcast -a android.provider.Telephony.SECRET_CODE " +
                "-d android_secret_code://4636"
        )
        withContext(Dispatchers.Main) {
            UiToast.info(this@openRadioInfo, getString(R.string.msg_radio_sent), Toast.LENGTH_LONG).show()
        }
    }
}
