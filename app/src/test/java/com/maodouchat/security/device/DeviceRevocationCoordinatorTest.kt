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

    private class FakeDeviceRevocationHandler : DeviceRevocationHandler {
        var removeRemoteResult: Result<Unit> = Result.success(Unit)
        var revokeCurrentResult: Result<Unit> = Result.success(Unit)

        var lastRemovedDeviceId: Int? = null
        var lastInvalidatedSignalDeviceId: Int? = null
        var currentDeviceRevoked: Boolean = false
        var lastRevokedDeviceIdString: String? = null

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
            return revokeCurrentResult
        }
    }
}
