package com.maodouchat.security.totp

import com.maodouchat.network.api.AuthApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TotpCoordinatorTest {

    private lateinit var fakeAuthApi: FakeAuthApi
    private lateinit var coordinator: TotpCoordinator

    @Before
    fun setUp() {
        fakeAuthApi = FakeAuthApi()
        coordinator = TotpCoordinator(fakeAuthApi)
    }

    @Test
    fun loadStatus_transitionsToEnabledWhenTrue() = runBlocking {
        fakeAuthApi.statusResult = Result.success(true)
        val res = coordinator.loadStatus("token_123")
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow())
        assertTrue(coordinator.state.value is TotpState.Enabled)
    }

    @Test
    fun loadStatus_transitionsToDisabledWhenFalse() = runBlocking {
        fakeAuthApi.statusResult = Result.success(false)
        val res = coordinator.loadStatus("token_123")
        assertTrue(res.isSuccess)
        assertFalse(res.getOrThrow())
        assertTrue(coordinator.state.value is TotpState.Disabled)
    }

    @Test
    fun startSetup_parsesSecretAndTransitionsToSetupReady() = runBlocking {
        fakeAuthApi.setupResult = Result.success("""{"secret":"JBSWY3DPEHPK3PXP","otpauthUrl":"otpauth://totp/Maodou:user?secret=JBSWY3DPEHPK3PXP","backupCodes":["123456","654321"]}""")
        val res = coordinator.startSetup("token_123")
        assertTrue(res.isSuccess)
        val ready = coordinator.state.value as TotpState.SetupReady
        assertEquals("JBSWY3DPEHPK3PXP", ready.secret)
        assertEquals("otpauth://totp/Maodou:user?secret=JBSWY3DPEHPK3PXP", ready.otpauthUrl)
        assertEquals(listOf("123456", "654321"), ready.recoveryCodes)
    }

    @Test
    fun confirmSetup_success_transitionsToEnabledWithCodes() = runBlocking {
        fakeAuthApi.setupResult = Result.success("""{"secret":"JBSWY3DPEHPK3PXP"}""")
        coordinator.startSetup("token_123")

        fakeAuthApi.confirmResult = Result.success(listOf("rc1", "rc2"))
        val res = coordinator.confirmSetup("token_123", "123456")
        assertTrue(res.isSuccess)
        val enabled = coordinator.state.value as TotpState.Enabled
        assertEquals(listOf("rc1", "rc2"), enabled.recoveryCodes)
    }

    @Test
    fun confirmSetup_failure_preservesSetupReadyWithError() = runBlocking {
        fakeAuthApi.setupResult = Result.success("""{"secret":"JBSWY3DPEHPK3PXP"}""")
        coordinator.startSetup("token_123")

        fakeAuthApi.confirmResult = Result.failure(IllegalArgumentException("Bad code"))
        val res = coordinator.confirmSetup("token_123", "000000")
        assertTrue(res.isFailure)
        val ready = coordinator.state.value as TotpState.SetupReady
        assertEquals("Bad code", ready.error)
        assertFalse(ready.isConfirming)
    }

    @Test
    fun disable_transitionsToDisabled() = runBlocking {
        coordinator.loadStatus("token_123") // Initially disabled
        fakeAuthApi.statusResult = Result.success(true)
        coordinator.loadStatus("token_123") // now enabled

        fakeAuthApi.disableResult = Result.success("ok")
        val res = coordinator.disable("token_123", "123456")
        assertTrue(res.isSuccess)
        assertTrue(coordinator.state.value is TotpState.Disabled)
    }

    private class FakeAuthApi : AuthApi {
        var statusResult: Result<Boolean> = Result.success(false)
        var setupResult: Result<String> = Result.success("{}")
        var confirmResult: Result<List<String>> = Result.success(emptyList())
        var disableResult: Result<String> = Result.success("ok")
        var regenerateResult: Result<List<String>> = Result.success(emptyList())

        override suspend fun getTotpStatus(token: String): Result<String> =
            statusResult.map { """{"enabled":$it}""" }

        override suspend fun totpStatus(token: String): Result<Boolean> = statusResult

        override suspend fun setupTotp(token: String): Result<String> = setupResult

        override suspend fun confirmTotp(token: String, code: String): Result<List<String>> = confirmResult

        override suspend fun disableTotp(token: String, code: String): Result<String> = disableResult

        override suspend fun regenerateTotpCodes(token: String, code: String): Result<List<String>> = regenerateResult

        override suspend fun login(email: String, password: String, totpCode: String): Result<com.maodouchat.network.AuthResponse> = Result.failure(NotImplementedError())
        override suspend fun register(name: String, email: String, password: String): Result<com.maodouchat.network.AuthResponse> = Result.failure(NotImplementedError())
        override suspend fun logout(refreshToken: String, accessToken: String?, deviceId: String): Result<Unit> = Result.success(Unit)
        override suspend fun logoutAll(token: String): Result<Unit> = Result.success(Unit)
        override suspend fun sendVerificationCode(email: String, purpose: String): Result<Unit> = Result.success(Unit)
        override suspend fun registerWithCode(name: String, email: String, password: String, code: String): Result<com.maodouchat.network.AuthResponse> = Result.failure(NotImplementedError())
        override suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> = Result.success(Unit)
        override suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAccount(token: String, password: String): Result<com.maodouchat.network.DeleteAccountResponse> = Result.failure(NotImplementedError())
    }
}
