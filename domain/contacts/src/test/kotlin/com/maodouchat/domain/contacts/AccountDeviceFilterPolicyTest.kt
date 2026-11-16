package com.maodouchat.domain.contacts

import com.maodouchat.core.model.DeviceInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeviceFilterPolicyTest {

    private fun device(deviceId: Int, name: String, status: String = "CONFIRMED") =
        DeviceInfoDto(userId = "u1", deviceId = deviceId, deviceName = name, identityKey = "key-$deviceId", status = status)

    private val devices = listOf(
        device(11, "我的设备"),
        device(22, "Pixel 9", status = "PENDING"),
        device(33, "工作平板"),
    )

    @Test
    fun `blank query returns all`() {
        assertEquals(devices, AccountDeviceFilterPolicy.filter(devices, ""))
        assertEquals(devices, AccountDeviceFilterPolicy.filter(devices, "  "))
    }

    @Test
    fun `matches by device name case-insensitively`() {
        assertEquals(listOf(devices[1]), AccountDeviceFilterPolicy.filter(devices, "pixel"))
    }

    @Test
    fun `matches by device id`() {
        assertTrue(AccountDeviceFilterPolicy.matches(devices[0], "11"))
        assertFalse(AccountDeviceFilterPolicy.matches(devices[0], "22"))
    }

    @Test
    fun `matches by status`() {
        assertEquals(listOf(devices[1]), AccountDeviceFilterPolicy.filter(devices, "pending"))
    }

    @Test
    fun `matches chinese name and trims query`() {
        assertEquals(listOf(devices[2]), AccountDeviceFilterPolicy.filter(devices, "  平板 "))
    }

    @Test
    fun `no match returns empty`() {
        assertEquals(emptyList<DeviceInfoDto>(), AccountDeviceFilterPolicy.filter(devices, "zzz"))
    }
}
