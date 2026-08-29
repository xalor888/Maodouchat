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
        apkSha256: String = "",
    ): Boolean {
        if (currentVersionCode < 0 || remoteVersionCode <= 0) return false
        if (!isOfficialApkUrl(apkUrl) || !hasExpectedApkSha256(apkSha256)) return false
        return remoteVersionCode > currentVersionCode
    }

    fun isOfficialApkUrl(apkUrl: String): Boolean {
        if (apkUrl.isBlank()) return false
        val trimmed = apkUrl.trim()
        if (!trimmed.lowercase().startsWith("https://")) return false
        val host = hostOf(trimmed) ?: return false
        return host.equals("chat.mdou.me", ignoreCase = true) ||
            host.equals("mdou.me", ignoreCase = true) ||
            host.endsWith(".mdou.me", ignoreCase = true)
    }

    fun hasExpectedApkSha256(apkSha256: String): Boolean =
        SHA256_REGEX.matches(apkSha256.trim())

    fun matchesExpectedApkSha256(actualSha256: String, expectedSha256: String): Boolean =
        hasExpectedApkSha256(expectedSha256) &&
            actualSha256.equals(expectedSha256.trim(), ignoreCase = true)

    private val SHA256_REGEX = Regex("^[A-Fa-f0-9]{64}$")

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isBlank()) return null
        return withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':').trim()
            .takeIf { it.isNotBlank() }
    }
}
