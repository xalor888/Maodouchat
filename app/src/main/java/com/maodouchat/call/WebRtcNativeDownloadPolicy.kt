package com.maodouchat.call

/**
 * ABI + checksum helpers for [WebRtcNativeLibraryLoader].
 * Release APK is arm64-only; debug emulator is typically x86_64.
 */
object WebRtcNativeDownloadPolicy {
    val HOSTED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    fun requestAbi(supportedAbis: Array<String>): String {
        for (abi in supportedAbis) {
            if (abi in HOSTED_ABIS) return abi
        }
        return supportedAbis.firstOrNull { it.startsWith("arm64") }
            ?: "arm64-v8a"
    }

    fun isOriginTrusted(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        // Allow loopback in debug/emulator environments
        if (trimmed.startsWith("http://10.0.2.2:") || trimmed.startsWith("http://localhost:") || trimmed.startsWith("http://127.0.0.1:")) {
            return true
        }
        if (!trimmed.startsWith("https://")) return false
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').trim()
        val isOfficial = host.equals("chat.mdou.me", ignoreCase = true) ||
            host.equals("mdou.me", ignoreCase = true) ||
            host.endsWith(".mdou.me", ignoreCase = true)
        if (isOfficial) return true
        val runtimeHost = com.maodouchat.network.ApiConfig.BASE_URL.substringAfter("://", "").substringBefore('/').substringBefore(':').trim()
        return runtimeHost.isNotBlank() && host.equals(runtimeHost, ignoreCase = true)
    }

    /**
     * Prefer an explicit content hash header. Fall back to ETag, stripping
     * weak-validator prefix and quotes (Caddy/Ktor may wrap SHA-256).
     */
    fun parseChecksum(etag: String?, contentSha256: String?): String? {
        contentSha256?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            ?.let { return it }
        val raw = etag?.trim().orEmpty()
        if (raw.isBlank()) return null
        val stripped = raw
            .removePrefix("W/")
            .removePrefix("w/")
            .trim()
            .trim('"')
            .lowercase()
        return stripped.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
    }

    /**
     * Cryptographically verify downloaded native library against checksum.
     */
    fun verifyIntegrity(actualSha256: String, expectedChecksum: String?): Boolean {
        if (actualSha256.isBlank() || actualSha256.length != 64) return false
        val expected = expectedChecksum?.trim()?.lowercase() ?: return false
        return actualSha256.trim().lowercase() == expected
    }
}
