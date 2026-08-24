package com.maodouchat.update

/**
 * In-app update offers always come from the official JSON
 * `{versionCode, versionName, apkUrl, serverUrl, notes}` — never GitHub.
 * Third-party connected servers still check the official host.
 */
object AppUpdatePolicy {

    fun shouldOfferUpdate(
        currentVersionCode: Int,
        remoteVersionCode: Int,
        apkUrl: String,
    ): Boolean {
        if (currentVersionCode < 0 || remoteVersionCode <= 0) return false
        if (!isOfficialApkUrl(apkUrl)) return false
        return remoteVersionCode > currentVersionCode
    }

    fun isOfficialApkUrl(apkUrl: String): Boolean {
        if (apkUrl.isBlank()) return false
        return apkUrl.trim().lowercase().startsWith("https://")
    }
}
