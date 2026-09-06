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
        val isOfficial = host.equals("chat.mdou.me", ignoreCase = true) ||
            host.equals("mdou.me", ignoreCase = true) ||
            host.endsWith(".mdou.me", ignoreCase = true)
        if (isOfficial) return true
        // Allow self-hosted runtime server hosting its own official APK
        val runtimeHost = hostOf(com.maodouchat.network.ApiConfig.BASE_URL)
        return runtimeHost != null && host.equals(runtimeHost, ignoreCase = true)
    }

    fun hasExpectedApkSha256(apkSha256: String): Boolean =
        SHA256_REGEX.matches(apkSha256.trim())

    fun matchesExpectedApkSha256(actualSha256: String, expectedSha256: String): Boolean =
        hasExpectedApkSha256(expectedSha256) &&
            actualSha256.trim().equals(expectedSha256.trim(), ignoreCase = true)

    /**
     * Clean and format update notes, recovering from HTTP header mojibake or URL-encoding.
     */
    fun formatNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        return try {
            if (trimmed.contains('%')) {
                java.net.URLDecoder.decode(trimmed, "UTF-8")
            } else if (trimmed.any { it.code in 0x80..0xFF && it.code !in 0x4E00..0x9FFF }) {
                val bytes = trimmed.toByteArray(Charsets.ISO_8859_1)
                val utf8 = String(bytes, Charsets.UTF_8)
                if (utf8.none { it == '\uFFFD' }) utf8 else trimmed
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private val SHA256_REGEX = Regex("^[A-Fa-f0-9]{64}$")

    fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isBlank()) return null
        return withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':').trim()
            .takeIf { it.isNotBlank() }
    }
}
