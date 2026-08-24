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
}
