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

    @Test
    fun changePasswordRequestRoundTrips() {
        val request = ChangePasswordRequest(oldPassword = "old123", newPassword = "new456")
        assertEquals(request, json.decodeFromString<ChangePasswordRequest>(json.encodeToString(request)))
    }

    @Test
    fun sendCodeRequestResetPurposeRoundTrips() {
        val request = SendCodeRequest(email = "alex@example.com", purpose = "reset")
        assertEquals(request, json.decodeFromString<SendCodeRequest>(json.encodeToString(request)))
        val decoded = json.decodeFromString<SendCodeRequest>(
            """{"email":"alex@example.com","purpose":"reset"}"""
        )
        assertEquals("reset", decoded.purpose)
    }

    @Test
    fun setUsernameRequestRoundTrips() {
        val request = SetUsernameRequest(username = "alex99")
        assertEquals(request, json.decodeFromString<SetUsernameRequest>(json.encodeToString(request)))
    }

    @Test
    fun updateDeviceNameRequestRoundTrips() {
        val request = UpdateDeviceNameRequest(deviceName = "Pixel 9")
        assertEquals(request, json.decodeFromString<UpdateDeviceNameRequest>(json.encodeToString(request)))
    }

    @Test
    fun confirmDeviceRequestRoundTrips() {
        val request = ConfirmDeviceRequest(approverDeviceId = 7, signature = "sig_bytes")
        assertEquals(request, json.decodeFromString<ConfirmDeviceRequest>(json.encodeToString(request)))
    }

    @Test
    fun authResponseEmptyObjectDefaultsAll() {
        val decoded = json.decodeFromString<AuthResponse>("""{}""")
        assertEquals("", decoded.token)
        assertEquals("", decoded.userId)
        assertEquals("", decoded.name)
        assertEquals("", decoded.refreshToken)
        assertEquals(0L, decoded.expiresAt)
        assertEquals(0L, decoded.refreshExpiresAt)
        assertFalse(decoded.requiresTotp)
        assertFalse(decoded.totpEnabled)
    }

    @Test
    fun authResponseNegativeExpiryDecodes() {
        val decoded = json.decodeFromString<AuthResponse>(
            """{"token":"t","userId":"u1","name":"N","expiresAt":-1,"refreshExpiresAt":-5}"""
        )
        assertEquals(-1L, decoded.expiresAt)
        assertEquals(-5L, decoded.refreshExpiresAt)
    }

    @Test
    fun userDtoStatusModeratorAndUsernameDefaults() {
        val decoded = json.decodeFromString<UserDto>("""{"id":"u1","name":"A"}""")
        assertEquals("", decoded.status)
        assertFalse(decoded.isModerator)
        assertNull(decoded.username)
    }

    @Test
    fun errorResponseWithCodeAndRetryAfterDecodes() {
        val decoded = json.decodeFromString<ErrorResponse>(
            """{"error":"slow","code":"RATE_LIMITED","retryAfterSeconds":30}"""
        )
        assertEquals("slow", decoded.error)
        assertEquals("RATE_LIMITED", decoded.code)
        assertEquals(30L, decoded.retryAfterSeconds)
    }

    @Test
    fun setUsernameResponseOkTrueDecodes() {
        val decoded = json.decodeFromString<SetUsernameResponse>("""{"ok":true,"username":"alex99"}""")
        assertTrue(decoded.ok)
        assertEquals("alex99", decoded.username)
    }

    @Test
    fun userDtoOnlineWithLastSeenDecodes() {
        val decoded = json.decodeFromString<UserDto>("""{"id":"u1","name":"A","isOnline":true,"lastSeen":123}""")
        assertTrue(decoded.isOnline)
        assertEquals(123L, decoded.lastSeen)
        assertEquals("", decoded.email)
        assertNull(decoded.username)
    }

    @Test
    fun deviceInfoDtoCurrentWithTimestampsDecodes() {
        val decoded = json.decodeFromString<DeviceInfoDto>(
            """{"userId":"u1","deviceId":7,"identityKey":"ik","isCurrent":true,"lastSeenAt":999,"confirmedAt":100,"confirmedByDeviceId":3}"""
        )
        assertTrue(decoded.isCurrent)
        assertEquals(999L, decoded.lastSeenAt)
        assertEquals(100L, decoded.confirmedAt)
        assertEquals(3, decoded.confirmedByDeviceId)
        assertEquals("我的设备", decoded.deviceName)
        assertEquals("CONFIRMED", decoded.status)
    }

    @Test
    fun groupInvitationDtoFullDecode() {
        val decoded = json.decodeFromString<GroupInvitationDto>(
            """{"id":"g1","chatId":"c1","userId":"u1","inviterId":"u2","inviterName":"Bob","chatName":"群","chatAvatar":"http://x/a.png","chatType":"PRIVATE","memberCount":5,"status":"ACCEPTED","createdAt":10,"updatedAt":20}"""
        )
        assertEquals("u2", decoded.inviterId)
        assertEquals("Bob", decoded.inviterName)
        assertEquals("群", decoded.chatName)
        assertEquals("http://x/a.png", decoded.chatAvatar)
        assertEquals("PRIVATE", decoded.chatType)
        assertEquals(5, decoded.memberCount)
        assertEquals("ACCEPTED", decoded.status)
        assertEquals(10L, decoded.createdAt)
        assertEquals(20L, decoded.updatedAt)
    }

    @Test
    fun chatFolderDtoFullDecode() {
        val decoded = json.decodeFromString<ChatFolderDto>(
            """{"id":"f2","name":"家人","sortOrder":3,"chatIds":["c1","c2"],"updatedAt":555}"""
        )
        assertEquals("f2", decoded.id)
        assertEquals("家人", decoded.name)
        assertEquals(3, decoded.sortOrder)
        assertEquals(listOf("c1", "c2"), decoded.chatIds)
        assertEquals(555L, decoded.updatedAt)
    }

    @Test
    fun clientPrefsDtoNonDefaultsDecode() {
        val decoded = json.decodeFromString<ClientPrefsDto>(
            """{"themeMode":"dark","themeStyle":"custom","accentColor":"blue","linkPreviewEnabled":false,"writingStyleEnabled":true,"appLockTimeoutMinutes":10}"""
        )
        assertEquals("dark", decoded.themeMode)
        assertEquals("custom", decoded.themeStyle)
        assertEquals("blue", decoded.accentColor)
        assertFalse(decoded.linkPreviewEnabled)
        assertTrue(decoded.writingStyleEnabled)
        assertEquals(10L, decoded.appLockTimeoutMinutes)
    }

    @Test
    fun userPrivacyDtoDefaults() {
        val decoded = json.decodeFromString<UserPrivacyDto>("""{}""")
        assertTrue(decoded.showOnline)
        assertTrue(decoded.showStatus)
        assertTrue(decoded.searchable)
        assertEquals("PUBLIC", decoded.defaultPostVisibility)
        assertEquals("everyone", decoded.onlineVisibility)
    }

    @Test
    fun updatePrivacyRequestPartialRoundTrips() {
        val request = UpdatePrivacyRequest(showOnline = false, onlineVisibility = "contacts")
        assertEquals(request, json.decodeFromString<UpdatePrivacyRequest>(json.encodeToString(request)))
        assertNull(request.showStatus)
        assertNull(request.searchable)
        assertNull(request.defaultPostVisibility)
    }

    @Test
    fun clientPrefsUpdateRequestAllNullRoundTrips() {
        val request = ClientPrefsUpdateRequest()
        assertEquals(request, json.decodeFromString<ClientPrefsUpdateRequest>(json.encodeToString(request)))
        assertNull(request.themeMode)
        assertNull(request.linkPreviewEnabled)
        assertNull(request.appLockTimeoutMinutes)
    }

    @Test
    fun deleteAccountRequestAndResponseDecode() {
        val request = DeleteAccountRequest(password = "pw123")
        assertEquals(request, json.decodeFromString<DeleteAccountRequest>(json.encodeToString(request)))
        val response = json.decodeFromString<DeleteAccountResponse>("""{"deletedAt":123}""")
        assertEquals("ok", response.status)
        assertEquals(123L, response.deletedAt)
    }

    @Test
    fun updateProfileRequestPartialRoundTrips() {
        val request = UpdateProfileRequest(name = "New")
        assertEquals(request, json.decodeFromString<UpdateProfileRequest>(json.encodeToString(request)))
        assertNull(request.status)
        val decoded = json.decodeFromString<UpdateProfileRequest>("""{"status":"busy"}""")
        assertNull(decoded.name)
        assertEquals("busy", decoded.status)
    }

    @Test
    fun userPrivacyDtoNonDefaultsDecode() {
        val decoded = json.decodeFromString<UserPrivacyDto>(
            """{"showOnline":false,"showStatus":false,"searchable":false,"defaultPostVisibility":"CONTACTS","onlineVisibility":"contacts"}"""
        )
        assertFalse(decoded.showOnline)
        assertFalse(decoded.showStatus)
        assertFalse(decoded.searchable)
        assertEquals("CONTACTS", decoded.defaultPostVisibility)
        assertEquals("contacts", decoded.onlineVisibility)
    }

    @Test
    fun updatePrivacyRequestAllFieldsRoundTrips() {
        val request = UpdatePrivacyRequest(
            showOnline = false, showStatus = true, searchable = false,
            defaultPostVisibility = "CONTACTS", onlineVisibility = "nobody"
        )
        assertEquals(request, json.decodeFromString<UpdatePrivacyRequest>(json.encodeToString(request)))
    }

    @Test
    fun clientPrefsUpdateRequestPartialRoundTrips() {
        val request = ClientPrefsUpdateRequest(
            themeMode = "dark", writingStyleEnabled = true, sensitiveGateEnabled = false
        )
        val decoded = json.decodeFromString<ClientPrefsUpdateRequest>(json.encodeToString(request))
        assertEquals(request, decoded)
        assertNull(decoded.themeStyle)
        assertNull(decoded.appLockTimeoutMinutes)
    }

    @Test
    fun updateProfileRequestBothFieldsRoundTrips() {
        val request = UpdateProfileRequest(name = "New", status = "busy")
        assertEquals(request, json.decodeFromString<UpdateProfileRequest>(json.encodeToString(request)))
    }

    @Test
    fun deleteAccountResponseNonDefaultStatusDecodes() {
        val decoded = json.decodeFromString<DeleteAccountResponse>("""{"status":"pending","deletedAt":5}""")
        assertEquals("pending", decoded.status)
        assertEquals(5L, decoded.deletedAt)
        val max = DeleteAccountResponse(status = "ok", deletedAt = Long.MAX_VALUE)
        assertEquals(max, json.decodeFromString<DeleteAccountResponse>(json.encodeToString(max)))
    }

}
