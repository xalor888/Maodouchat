package com.maodouchat.server.service

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnCredentialServiceTest {
    @Test
    fun `issues user scoped short lived coturn credential`() {
        val secret = "0123456789abcdef0123456789abcdef"
        val response = TurnCredentialService(
            turnUrls = listOf("turn:turn.example.com:3478", "turns:turn.example.com:5349"),
            sharedSecret = secret,
            ttlSeconds = 600,
            nowSeconds = { 1_000 }
        ).issue("user-a")

        assertTrue(response.turnEnabled)
        assertEquals(1_600_000, response.expiresAt)
        val turn = response.iceServers.single { it.username.isNotBlank() }
        assertEquals("1600:user-a", turn.username)
        assertEquals(expectedCredential(secret, turn.username), turn.credential)
        assertEquals(2, turn.urls.size)
    }

    @Test
    fun `falls back to stun when turn is not configured`() {
        val response = TurnCredentialService(emptyList(), "", nowSeconds = { 1_000 }).issue("user-a")

        assertFalse(response.turnEnabled)
        assertTrue(response.iceServers.isNotEmpty())
        assertTrue(response.iceServers.all { it.username.isBlank() && it.credential.isBlank() })
    }

    @Test
    fun `sanitizes userId in coturn username to prevent colon and newline injection`() {
        val secret = "0123456789abcdef0123456789abcdef"
        val response = TurnCredentialService(
            turnUrls = listOf("turn:turn.example.com:3478"),
            sharedSecret = secret,
            ttlSeconds = 600,
            nowSeconds = { 1_000 }
        ).issue("user:admin\r\nmalicious")

        val turn = response.iceServers.single { it.username.isNotBlank() }
        assertEquals("1600:user_adminmalicious", turn.username)
    }

    private fun expectedCredential(secret: String, username: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray()))
    }
}
