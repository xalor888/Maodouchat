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

    @Test
    fun `legacy default picks the smallest confirmed device`() {
        assertEquals(3, ConfirmedDevicePolicy.resolve(1, listOf(9, 3, 5)))
    }

    @Test
    fun `confirmed ids outside the valid range are dropped`() {
        assertEquals(7, ConfirmedDevicePolicy.resolve(1, listOf(0, -4, 260, 7)))
        assertNull(ConfirmedDevicePolicy.resolve(1, listOf(0, 999)))
    }

    @Test
    fun `duplicate and unsorted discovery lists collapse`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(1, listOf(5, 2, 5, 2)))
    }

    @Test
    fun `custom default device id is honored`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(2, listOf(2, 4), defaultDeviceId = 2))
        assertEquals(4, ConfirmedDevicePolicy.resolve(2, listOf(4), defaultDeviceId = 2))
    }

    @Test
    fun `explicit request outside the valid range resolves to null`() {
        assertNull(ConfirmedDevicePolicy.resolve(0, listOf(3)))
        assertNull(ConfirmedDevicePolicy.resolve(256, listOf(3)))
        assertEquals(9, ConfirmedDevicePolicy.resolve(9, emptyList()))
    }
}
