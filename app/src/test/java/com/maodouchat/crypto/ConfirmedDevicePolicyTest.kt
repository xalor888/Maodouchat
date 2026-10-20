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

    @Test
    fun `custom default not confirmed falls back to smallest confirmed`() {
        assertEquals(4, ConfirmedDevicePolicy.resolve(2, listOf(9, 4), defaultDeviceId = 2))
        assertNull(ConfirmedDevicePolicy.resolve(2, emptyList(), defaultDeviceId = 2))
    }

    @Test
    fun `explicit negative and huge ids resolve to null`() {
        assertNull(ConfirmedDevicePolicy.resolve(-5, listOf(1, 2)))
        assertNull(ConfirmedDevicePolicy.resolve(1000, listOf(1)))
        assertNull(ConfirmedDevicePolicy.resolve(-1, emptyList()))
    }

    @Test
    fun `upper range boundary 255 is a valid device id`() {
        assertEquals(255, ConfirmedDevicePolicy.resolve(255, emptyList()))
        assertEquals(255, ConfirmedDevicePolicy.resolve(1, listOf(255)))
        assertEquals(254, ConfirmedDevicePolicy.resolve(1, listOf(0, 256, 254)))
    }

    @Test
    fun `requested equal to custom default collapses duplicates`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(2, listOf(9, 2, 9, 4), defaultDeviceId = 2))
    }

    @Test
    fun `custom default changes what counts as explicit`() {
        assertEquals(1, ConfirmedDevicePolicy.resolve(1, listOf(2), defaultDeviceId = 2))
        assertNull(ConfirmedDevicePolicy.resolve(300, listOf(1, 2), defaultDeviceId = 2))
    }

    @Test
    fun `nonLegacyDefaultDeviceIdSwitchesDefaultPath`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(2, listOf(9, 2), defaultDeviceId = 5))
        assertEquals(9, ConfirmedDevicePolicy.resolve(5, listOf(9, 12), defaultDeviceId = 5))
        assertEquals(3, ConfirmedDevicePolicy.resolve(3, listOf(9), defaultDeviceId = 5))
    }

    @Test
    fun `boundary 256 is dropped from confirmed but valid as custom default`() {
        assertEquals(1, ConfirmedDevicePolicy.resolve(1, listOf(1, 256, 0)))
        assertNull(ConfirmedDevicePolicy.resolve(256, listOf(3)))
        assertEquals(3, ConfirmedDevicePolicy.resolve(256, listOf(3), defaultDeviceId = 256))
    }

    @Test
    fun `invalid custom default id still behaves as the default slot`() {
        assertEquals(3, ConfirmedDevicePolicy.resolve(0, listOf(3), defaultDeviceId = 0))
        assertNull(ConfirmedDevicePolicy.resolve(0, emptyList(), defaultDeviceId = 0))
    }

    @Test
    fun `explicit out-of-range request never falls back to confirmed`() {
        assertNull(ConfirmedDevicePolicy.resolve(999, listOf(2, 4)))
        assertNull(ConfirmedDevicePolicy.resolve(-9, listOf(1)))
    }

    @Test
    fun `duplicate default among confirmed resolves to itself`() {
        assertEquals(1, ConfirmedDevicePolicy.resolve(1, listOf(1, 1, 1)))
        assertEquals(4, ConfirmedDevicePolicy.resolve(4, listOf(4, 4), defaultDeviceId = 4))
    }

    @Test
    fun `default slot with all confirmed invalid resolves to null`() {
        assertNull(ConfirmedDevicePolicy.resolve(1, listOf(0, 256, -1)))
    }

    @Test
    fun `explicit in-range request ignores the confirmed list`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(2, listOf(1)))
        assertEquals(200, ConfirmedDevicePolicy.resolve(200, listOf(3, 4)))
    }

    @Test
    fun `confirmed ids sort before picking the smallest`() {
        assertEquals(150, ConfirmedDevicePolicy.resolve(1, listOf(255, 200, 150)))
    }

    @Test
    fun `custom default inside confirmed returns itself`() {
        assertEquals(4, ConfirmedDevicePolicy.resolve(4, listOf(2, 4, 9), defaultDeviceId = 4))
    }

    @Test
    fun `negative custom default still behaves as the default slot`() {
        assertEquals(2, ConfirmedDevicePolicy.resolve(-3, listOf(2, 9), defaultDeviceId = -3))
        assertNull(ConfirmedDevicePolicy.resolve(-3, emptyList(), defaultDeviceId = -3))
    }
}
