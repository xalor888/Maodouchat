package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeviceRevocationResultTest {

    @Test
    fun remoteRevokedCarriesDeviceId() {
        assertEquals(102, DeviceRevocationResult.RemoteDeviceRevoked(102).deviceId)
    }

    @Test
    fun remoteRevokedEqualityIsByDeviceId() {
        assertEquals(
            DeviceRevocationResult.RemoteDeviceRevoked(101),
            DeviceRevocationResult.RemoteDeviceRevoked(101),
        )
        assertNotEquals(
            DeviceRevocationResult.RemoteDeviceRevoked(101),
            DeviceRevocationResult.RemoteDeviceRevoked(102),
        )
    }

    @Test
    fun currentRevokedIsDistinctFromRemote() {
        assertNotEquals(
            DeviceRevocationResult.CurrentDeviceRevoked,
            DeviceRevocationResult.RemoteDeviceRevoked(101),
        )
    }
}
