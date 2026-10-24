package com.maodouchat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun registerRequestRoundTrips() {
        val request = RegisterRequest(name = "Alex", email = "alex@example.com", password = "password123")
        assertEquals(request, json.decodeFromString<RegisterRequest>(json.encodeToString(request)))
    }

    @Test
    fun authResponseRefreshExpiryDecodes() {
        val decoded = json.decodeFromString<AuthResponse>(
            """{"token":"t","userId":"u1","name":"N","refreshToken":"rt","expiresAt":100,"refreshExpiresAt":200}"""
        )
        assertEquals("rt", decoded.refreshToken)
        assertEquals(100L, decoded.expiresAt)
        assertEquals(200L, decoded.refreshExpiresAt)
    }

    @Test
    fun authResponseIgnoresUnknownKeys() {
        val decoded = json.decodeFromString<AuthResponse>(
            """{"token":"t","userId":"u1","name":"N","futureField":1}"""
        )
        assertEquals("t", decoded.token)
        assertEquals("u1", decoded.userId)
    }

    @Test
    fun refreshTokenRequestRoundTripsWithDeviceId() {
        val request = RefreshTokenRequest(refreshToken = "rt_2", deviceId = "d7")
        assertEquals(request, json.decodeFromString<RefreshTokenRequest>(json.encodeToString(request)))
    }

    @Test
    fun userDtoDefaultsForFeedAuthor() {
        val decoded = json.decodeFromString<UserDto>("""{"id":"u9","name":"Zed"}""")
        assertEquals("", decoded.email)
        assertNull(decoded.avatar)
        assertFalse(decoded.isOnline)
        assertEquals(0L, decoded.lastSeen)
    }

    @Test
    fun sendCodeRequestPurposeDefaultsRegister() {
        val request = SendCodeRequest(email = "alex@example.com")
        assertEquals("register", request.purpose)
        assertEquals(request, json.decodeFromString<SendCodeRequest>(json.encodeToString(request)))
    }

    @Test
    fun registerWithCodeRequestRoundTrips() {
        val request = RegisterWithCodeRequest(
            name = "Alex", email = "alex@example.com", password = "password123", code = "654321"
        )
        assertEquals(request, json.decodeFromString<RegisterWithCodeRequest>(json.encodeToString(request)))
    }

    @Test
    fun deviceInfoDtoFallsBackToServerDefaultName() {
        val decoded = json.decodeFromString<DeviceInfoDto>(
            """{"userId":"u1","deviceId":7,"identityKey":"ik"}"""
        )
        assertEquals("我的设备", decoded.deviceName)
        assertEquals("CONFIRMED", decoded.status)
        assertNull(decoded.lastSeenAt)
        assertFalse(decoded.isCurrent)
    }

    @Test
    fun errorResponseOptionalFieldsDefaultNull() {
        val decoded = json.decodeFromString<ErrorResponse>("""{"error":"boom"}""")
        assertEquals("boom", decoded.error)
        assertNull(decoded.code)
        assertNull(decoded.retryAfterSeconds)
    }

    @Test
    fun setUsernameResponseDefaultsToNotOk() {
        val decoded = json.decodeFromString<SetUsernameResponse>("""{}""")
        assertFalse(decoded.ok)
        assertNull(decoded.username)
    }

    @Test
    fun resetPasswordRequestRoundTrips() {
        val request = ResetPasswordRequest(email = "alex@example.com", code = "654321", newPassword = "newpass123")
        assertEquals(request, json.decodeFromString<ResetPasswordRequest>(json.encodeToString(request)))
    }

    @Test
    fun groupInvitationDtoDefaultsStatusPending() {
        val decoded = json.decodeFromString<GroupInvitationDto>("""{"id":"g1","chatId":"c1","userId":"u1"}""")
        assertEquals("PENDING", decoded.status)
        assertEquals("GROUP", decoded.chatType)
        assertEquals(0, decoded.memberCount)
        assertNull(decoded.chatAvatar)
    }

    @Test
    fun chatFolderDtoDefaultsEmptyChatIds() {
        val decoded = json.decodeFromString<ChatFolderDto>("""{"id":"f1","name":"工作"}""")
        assertEquals(0, decoded.sortOrder)
        assertTrue(decoded.chatIds.isEmpty())
        assertEquals(0L, decoded.updatedAt)
    }

    @Test
    fun updatePrivacyRequestAllNullByDefault() {
        val request = UpdatePrivacyRequest()
        assertNull(request.showOnline)
        assertNull(request.searchable)
        assertNull(request.onlineVisibility)
    }

    @Test
    fun clientPrefsDtoDefaults() {
        val decoded = json.decodeFromString<ClientPrefsDto>("""{}""")
        assertEquals("system", decoded.themeMode)
        assertTrue(decoded.linkPreviewEnabled)
        assertFalse(decoded.writingStyleEnabled)
        assertEquals(5L, decoded.appLockTimeoutMinutes)
    }
}
