package com.muimex11.official

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Verifies that this APK is the official Muimex11 build.
 *
 * The expected certificate hash is pinned in [AppConfig] and committed to
 * this repository — it is never fetched from the network, so this check is
 * meaningful even on a hostile connection. The check is advisory: if the
 * signature doesn't match (for example a rebuilt or developer-signed APK),
 * a warning is shown but the app keeps working.
 */
internal fun MainActivity.verifyAppSignature() {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val currentSignature = getCurrentAppSignature()
            if (currentSignature.isNotEmpty() &&
                !currentSignature.equals(AppConfig.EXPECTED_SIGNATURE_SHA256, ignoreCase = true)
            ) {
                withContext(Dispatchers.Main) {
                    UiToast.warning(this@verifyAppSignature, getString(R.string.toast_unofficial), Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            logQuietly("verifyAppSignature", e)
            // Never block the app on a failed verification.
        }
    }
}

internal fun MainActivity.getCurrentAppSignature(): String {
    val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        packageInfo.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        packageInfo.signatures
    }

    if (signatures.isNullOrEmpty()) return ""
    val md = MessageDigest.getInstance("SHA-256")
    md.update(signatures[0].toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
}
