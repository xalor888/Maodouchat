package com.maodouchat.security.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceRevocationCoordinatorTest {

    private lateinit var fakeHandler: FakeDeviceRevocationHandler
    private lateinit var coordinator: DeviceRevocationCoordinator

    @Before
    fun setUp() {
        fakeHandler = FakeDeviceRevocationHandler()
        coordinator = DeviceRevocationCoordinator(fakeHandler)
    }

    @Test
    fun revokeDevice_whenRemote_callsRemoveAndInvalidatesSignal() = runBlocking {
        fakeHandler.removeRemoteResult = Result.success(Unit)

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 102,
            currentDeviceId = 101
        )

        assertTrue(res.isSuccess)
        assertEquals(DeviceRevocationResult.RemoteDeviceRevoked(102), res.getOrThrow())
        assertEquals(102, fakeHandler.lastRemovedDeviceId)
        assertEquals(102, fakeHandler.lastInvalidatedSignalDeviceId)
        assertFalse(fakeHandler.currentDeviceRevoked)
    }

    @Test
    fun revokeDevice_whenRemoteFails_doesNotInvalidateSignal() = runBlocking {
        fakeHandler.removeRemoteResult = Result.failure(IllegalStateException("Server error"))

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 102,
            currentDeviceId = 101
        )

        assertTrue(res.isFailure)
        assertEquals(102, fakeHandler.lastRemovedDeviceId)
        assertEquals(null, fakeHandler.lastInvalidatedSignalDeviceId)
    }

    @Test
    fun revokeDevice_whenCurrent_callsRevokeCurrentDevice() = runBlocking {
        fakeHandler.revokeCurrentResult = Result.success(Unit)

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 101,
            currentDeviceId = 101,
            currentDeviceIdString = "device_101",
            refreshToken = "refresh_token_123"
        )

        assertTrue(res.isSuccess)
        assertEquals(DeviceRevocationResult.CurrentDeviceRevoked, res.getOrThrow())
        assertTrue(fakeHandler.currentDeviceRevoked)
        assertEquals("device_101", fakeHandler.lastRevokedDeviceIdString)
    }

    @Test
    fun revokeDevice_whenBlankToken_failsImmediately() = runBlocking {
        val res = coordinator.revokeDevice(
            token = "",
            targetDeviceId = 102,
            currentDeviceId = 101
        )
        assertTrue(res.isFailure)
    }

    @Test
    fun revokeDevice_whenCurrentHandlerFails_returnsFailure() = runBlocking {
        fakeHandler.revokeCurrentResult = Result.failure(IllegalStateException("logout failed"))

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 101,
            currentDeviceId = 101
        )

        assertTrue(res.isFailure)
        assertEquals("logout failed", res.exceptionOrNull()?.message)
        assertTrue(fakeHandler.currentDeviceRevoked)
    }

    @Test
    fun revokeDevice_whenCurrentDeviceUnknown_treatsTargetAsRemote() = runBlocking {
        fakeHandler.removeRemoteResult = Result.success(Unit)

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 101,
            currentDeviceId = null
        )

        assertTrue(res.isSuccess)
        assertEquals(DeviceRevocationResult.RemoteDeviceRevoked(101), res.getOrThrow())
        assertEquals(101, fakeHandler.lastRemovedDeviceId)
        assertEquals(101, fakeHandler.lastInvalidatedSignalDeviceId)
        assertFalse(fakeHandler.currentDeviceRevoked)
    }

    @Test
    fun revokeDevice_whenTokenIsWhitespace_failsBeforeAnyHandlerCall() = runBlocking {
        val res = coordinator.revokeDevice(
            token = "   ",
            targetDeviceId = 101,
            currentDeviceId = 101
        )

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        assertEquals(null, fakeHandler.lastRemovedDeviceId)
        assertFalse(fakeHandler.currentDeviceRevoked)
    }

    @Test
    fun revokeDevice_whenCurrent_derivesDeviceIdStringFromCurrentDeviceId() = runBlocking {
        fakeHandler.revokeCurrentResult = Result.success(Unit)

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 101,
            currentDeviceId = 101
        )

        assertTrue(res.isSuccess)
        // 未显式传 currentDeviceIdString 时走默认值 currentDeviceId.toString()。
        assertEquals("101", fakeHandler.lastRevokedDeviceIdString)
        assertEquals("", fakeHandler.lastRefreshToken)
    }

    @Test
    fun revokeDevice_whenRemoteFails_propagatesOriginalError() = runBlocking {
        val boom = IllegalStateException("Server error")
        fakeHandler.removeRemoteResult = Result.failure(boom)

        val res = coordinator.revokeDevice(
            token = "token_alice",
            targetDeviceId = 102,
            currentDeviceId = 101
        )

        assertTrue(res.isFailure)
        assertEquals(boom, res.exceptionOrNull())
        assertEquals(null, fakeHandler.lastInvalidatedSignalDeviceId)
    }

    private class FakeDeviceRevocationHandler : DeviceRevocationHandler {
        var removeRemoteResult: Result<Unit> = Result.success(Unit)
        var revokeCurrentResult: Result<Unit> = Result.success(Unit)

        var lastRemovedDeviceId: Int? = null
        var lastInvalidatedSignalDeviceId: Int? = null
        var currentDeviceRevoked: Boolean = false
        var lastRevokedDeviceIdString: String? = null
        var lastRefreshToken: String? = null

        override suspend fun removeRemoteDevice(token: String, deviceId: Int): Result<Unit> {
            lastRemovedDeviceId = deviceId
            return removeRemoteResult
        }

        override suspend fun invalidateSignalDeviceSession(deviceId: Int) {
            lastInvalidatedSignalDeviceId = deviceId
        }

        override suspend fun revokeCurrentDevice(
            token: String,
            refreshToken: String,
            deviceId: String
        ): Result<Unit> {
            currentDeviceRevoked = true
            lastRevokedDeviceIdString = deviceId
            lastRefreshToken = refreshToken
            return revokeCurrentResult
        }
    }
}
