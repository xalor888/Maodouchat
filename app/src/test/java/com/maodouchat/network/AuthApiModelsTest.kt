package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authResponseDecodesRequiresTotpWithoutTokens() {
        val decoded = json.decodeFromString<AuthResponse>(
            """{"requiresTotp":true,"totpEnabled":true,"token":"","userId":"","name":""}"""
        )
        assertTrue(decoded.requiresTotp)
        assertTrue(decoded.totpEnabled)
        assertEquals("", decoded.token)
        assertEquals("", decoded.refreshToken)
        assertEquals(0L, decoded.expiresAt)
    }

    @Test
    fun authResponseFillsDefaultsWhenOptionalKeysOmitted() {
        val decoded = json.decodeFromString<AuthResponse>("""{"token":"abc","userId":"u1","name":"Alex"}""")
        assertEquals("abc", decoded.token)
        assertEquals("u1", decoded.userId)
        assertEquals("Alex", decoded.name)
        assertEquals("", decoded.refreshToken)
        assertFalse(decoded.requiresTotp)
        assertFalse(decoded.totpEnabled)
    }

    @Test
    fun loginRequestTotpCodeDefaultsEmptyAndRoundTrips() {
        val request = LoginRequest(email = "alex@example.com", password = "password123")
        assertEquals("", request.totpCode)
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<LoginRequest>(encoded)
        assertEquals(request, decoded)
        val withCode = json.decodeFromString<LoginRequest>(
            """{"email":"alex@example.com","password":"password123","totpCode":"123456"}"""
        )
        assertEquals("123456", withCode.totpCode)
    }

    @Test
    fun refreshTokenRequestDeviceIdDefaultsEmpty() {
        val decoded = json.decodeFromString<RefreshTokenRequest>("""{"refreshToken":"rt_1"}""")
        assertEquals("rt_1", decoded.refreshToken)
        assertEquals("", decoded.deviceId)
    }
}
