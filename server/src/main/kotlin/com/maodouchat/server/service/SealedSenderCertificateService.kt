package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight server-issued sender certificates for sealed-sender style delivery.
 *
 * Full Signal sealed-sender cert chain (ServerCertificate + SenderCertificate) needs libsignal
 * server-side; this provides an equivalent authorization token the server can verify without
 * embedding the real sender id in fan-out metadata when clients opt in.
 *
 * Token format (base64url):
 *   v1.{userId}.{deviceId}.{expiresAtMs}.{sigHex}
 * where sig = HMAC-SHA256(jwtSecret, "v1|userId|deviceId|expiresAtMs")
 */
object SealedSenderCertificateService {
    private const val VERSION = "v1"
    private const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_TTL_MS = 7L * 24L * 60L * 60L * 1000L

    data class IssuedCertificate(
        val certificate: String,
        val userId: String,
        val deviceId: Int,
        val expiresAt: Long
    )

    data class VerifiedCertificate(
        val userId: String,
        val deviceId: Int,
        val expiresAt: Long
    )

    fun issue(userId: String, deviceId: Int, ttlMs: Long = DEFAULT_TTL_MS): IssuedCertificate? {
        if (userId.isBlank()) return null
        if (!RuntimeConfigService.isSealedSenderEnabled()) return null
        val dev = deviceId.coerceIn(1, 100_000)
        val ttl = ttlMs.coerceIn(60_000L, MAX_TTL_MS)
        val expiresAt = System.currentTimeMillis() + ttl
        val sig = sign(userId, dev, expiresAt) ?: return null
        val cert = listOf(VERSION, userId, dev.toString(), expiresAt.toString(), sig).joinToString(".")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(cert.toByteArray(Charsets.UTF_8))
        return IssuedCertificate(encoded, userId, dev, expiresAt)
    }

    fun verify(certificate: String): VerifiedCertificate? {
        if (!RuntimeConfigService.isSealedSenderEnabled()) return null
        val raw = runCatching {
            String(Base64.getUrlDecoder().decode(certificate.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val parts = raw.split('.')
        if (parts.size != 5 || parts[0] != VERSION) return null
        val userId = parts[1]
        val deviceId = parts[2].toIntOrNull() ?: return null
        val expiresAt = parts[3].toLongOrNull() ?: return null
        val sig = parts[4]
        if (userId.isBlank() || deviceId < 1) return null
        if (expiresAt < System.currentTimeMillis()) return null
        val expected = sign(userId, deviceId, expiresAt) ?: return null
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), sig.toByteArray(Charsets.UTF_8))) {
            return null
        }
        return VerifiedCertificate(userId, deviceId, expiresAt)
    }

    private fun sign(userId: String, deviceId: Int, expiresAt: Long): String? {
        // 8.39：jwtSecret 为空时 fail-closed——此前回退到公开硬编码密钥，任何人可用
        // 该已知字符串为任意 userId/deviceId 签发合法证书，伪造 sealed-sender 认证
        val secret = ServerConfig.jwtSecret.ifBlank { return null }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val payload = "$VERSION|$userId|$deviceId|$expiresAt"
            mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
