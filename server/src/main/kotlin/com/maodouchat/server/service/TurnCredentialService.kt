package com.maodouchat.server.service

import com.maodouchat.server.model.IceConfigResponse
import com.maodouchat.server.model.IceServerResponse
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Creates coturn REST-auth credentials with a bounded lifetime. */
class TurnCredentialService(
    private val turnUrls: List<String>,
    private val sharedSecret: String,
    private val ttlSeconds: Long = 3600,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun issue(userId: String): IceConfigResponse {
        val expiresAtSeconds = nowSeconds() + ttlSeconds.coerceIn(300, 86_400)
        val turnEnabled = turnUrls.isNotEmpty() && sharedSecret.length >= 32
        val servers = mutableListOf(
            IceServerResponse(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
        )
        if (turnEnabled) {
            val username = "$expiresAtSeconds:$userId"
            servers += IceServerResponse(
                urls = turnUrls,
                username = username,
                credential = hmacSha1Base64(sharedSecret, username)
            )
        }
        return IceConfigResponse(
            iceServers = servers,
            expiresAt = expiresAtSeconds * 1000,
            turnEnabled = turnEnabled
        )
    }

    private fun hmacSha1Base64(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
}
