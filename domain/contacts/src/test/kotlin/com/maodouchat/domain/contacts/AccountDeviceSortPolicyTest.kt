package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDeviceSortPolicyTest {

    private fun device(deviceId: Int, isCurrent: Boolean = false) =
        DeviceInfoDto(userId = "u1", deviceId = deviceId, deviceName = "d$deviceId",
            identityKey = "key-$deviceId", isCurrent = isCurrent)

    @Test
    fun `current device comes first even with a larger id`() {
        val devices = listOf(device(1), device(9, isCurrent = true), device(5))
        assertEquals(listOf(9, 1, 5),
            AccountDeviceSortPolicy.sort(devices).map { it.deviceId })
    }

    @Test
    fun `non-current devices sort by device id ascending`() {
        val devices = listOf(device(30), device(10), device(20))
        assertEquals(listOf(10, 20, 30),
            AccountDeviceSortPolicy.sort(devices).map { it.deviceId })
    }

    @Test
    fun `single current device stays first among currents by id`() {
        val devices = listOf(device(7, isCurrent = true), device(3, isCurrent = true))
        assertEquals(listOf(3, 7),
            AccountDeviceSortPolicy.sort(devices).map { it.deviceId })
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<DeviceInfoDto>(), AccountDeviceSortPolicy.sort(emptyList()))
    }
}
