package com.maodouchat.server.service

import com.maodouchat.server.model.IceConfigResponse
import com.maodouchat.server.model.IceServerResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Creates coturn REST-auth credentials with a bounded lifetime.
 *
 * B09：可选 [callId] 绑定到 username（`expiry:userId:callId`），挂断后 [revokeForCall]
 * 使该会话不再签发 TURN（已发出凭据仍受 TTL 约束，故 TTL 宜短）。
 */
class TurnCredentialService(
    private val turnUrls: List<String>,
    private val sharedSecret: String,
    private val ttlSeconds: Long = 3600,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val revokedCallKeys = ConcurrentHashMap.newKeySet<String>()

    fun issue(userId: String, callId: String = ""): IceConfigResponse {
        val expiresAtSeconds = nowSeconds() + ttlSeconds.coerceIn(300, 86_400)
        val turnEnabled = turnUrls.isNotEmpty() && sharedSecret.length >= 32
        val safeUserId = sanitizeSegment(userId)
        val safeCallId = sanitizeSegment(callId)
        val servers = mutableListOf(
            IceServerResponse(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
        )
        val sessionRevoked = safeCallId.isNotBlank() && isRevoked(safeUserId, safeCallId)
        if (turnEnabled && !sessionRevoked) {
            val username = if (safeCallId.isBlank()) {
                "$expiresAtSeconds:$safeUserId"
            } else {
                "$expiresAtSeconds:$safeUserId:$safeCallId"
            }
            servers += IceServerResponse(
                urls = turnUrls,
                username = username,
                credential = hmacSha1Base64(sharedSecret, username)
            )
        }
        return IceConfigResponse(
            iceServers = servers,
            expiresAt = expiresAtSeconds * 1000,
            turnEnabled = turnEnabled && !sessionRevoked
        )
    }

    fun revokeForCall(userId: String, callId: String) {
        val safeUserId = sanitizeSegment(userId)
        val safeCallId = sanitizeSegment(callId)
        if (safeUserId.isBlank() || safeCallId.isBlank()) return
        revokedCallKeys.add(revokeKey(safeUserId, safeCallId))
    }

    fun isRevoked(userId: String, callId: String): Boolean {
        val safeUserId = sanitizeSegment(userId)
        val safeCallId = sanitizeSegment(callId)
        if (safeUserId.isBlank() || safeCallId.isBlank()) return false
        return revokedCallKeys.contains(revokeKey(safeUserId, safeCallId))
    }

    private fun revokeKey(userId: String, callId: String) = "$userId\u0000$callId"

    private fun sanitizeSegment(raw: String): String =
        raw.trim().replace(":", "_").replace("\r", "").replace("\n", "").take(64)

    private fun hmacSha1Base64(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
}
