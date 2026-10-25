package com.muimex11.official

object AppConfig {
    const val MIN_DPI = 72
    const val MAX_DPI = 1000

    const val REPO_RAW_BASE = "https://raw.githubusercontent.com/dwicao/Muimex11/main"
    const val SIGNATURE_URL = "$REPO_RAW_BASE/signature.txt"
    const val VERSION_URL = "$REPO_RAW_BASE/version.json"
    const val TELEGRAM_URL = "https://t.me/muimex11"
    const val SHIZUKU_APK_URL =
        "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"

    const val EXPECTED_SIGNATURE_SHA256 = "9f5ce76b37bf4f9427f9bbcd5ddabf5326800195a6d5175d4d723e1ddabe4df3"

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}
