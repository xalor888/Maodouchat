package com.maodouchat.server.update

import java.security.MessageDigest

/**
 * GitHub Release → 服务端 APK 发布：token、versionCode、ZIP/APK 魔数。
 * 不走 GitHub 直链；客户端只认本机 HTTPS apkUrl。
 */
object AppUpdatePublishPolicy {
    const val MAX_APK_BYTES = 80L * 1024 * 1024
    const val MIN_APK_BYTES = 64L
    private const val MIN_TOKEN_CHARS = 16

    fun tokenConfigured(token: String): Boolean =
        token.trim().length >= MIN_TOKEN_CHARS

    fun tokensMatch(expected: String, provided: String): Boolean {
        if (!tokenConfigured(expected) || provided.isBlank()) return false
        val a = expected.trim().toByteArray(Charsets.UTF_8)
        val b = provided.trim().toByteArray(Charsets.UTF_8)
        if (a.size != b.size) return false
        return MessageDigest.isEqual(a, b)
    }

    fun parseVersionCode(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        if (value <= 0) return null
        return value
    }

    fun parseVersionName(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > 32) return null
        if (!value.matches(Regex("^[A-Za-z0-9._+-]+$"))) return null
        return value
    }

    fun sanitizeNotes(raw: String?): String =
        (raw ?: "").trim().take(2_000)

    fun isZipMagic(header: ByteArray): Boolean {
        if (header.size < 4) return false
        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (
                (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                    (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
                    (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
                )
    }

    fun publicApkUrl(baseUrl: String): String =
        "${baseUrl.trimEnd('/')}/api/public/app-update/latest.apk"

    fun bearerToken(authorization: String?): String? {
        val value = authorization?.trim().orEmpty()
        if (!value.startsWith("Bearer ", ignoreCase = true)) return null
        return value.substring(7).trim().takeIf { it.isNotEmpty() }
    }
}
