package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmedDevicePolicyTest {

    @Test
    fun `legacy default skips pending ghost device 1`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(1, listOf(2, 5)))
        assertEquals(1, ConfirmedDevicePolicy.resolve(1, listOf(1, 2)))
        assertNull(ConfirmedDevicePolicy.resolve(1, emptyList()))
    }

    @Test
    fun `explicit device id is preserved`() {
        assertEquals(7, ConfirmedDevicePolicy.resolve(7, listOf(2, 5)))
        assertEquals(3, ConfirmedDevicePolicy.resolve(3, listOf(3)))
    }
}
