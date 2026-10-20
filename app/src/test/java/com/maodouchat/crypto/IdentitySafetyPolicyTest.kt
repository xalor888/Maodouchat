package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitySafetyPolicyTest {

    private fun device(
        id: Int,
        trust: IdentityTrustState,
        code: String? = "01234 05678 90123",
        current: Boolean = false
    ) = DeviceSafetyState(
        deviceId = id,
        identityKey = "key-$id",
        identityFingerprint = "fp-$id",
        trustState = trust,
        safetyCode = code,
        isCurrent = current
    )

    @Test
    fun `empty is unknown`() {
        assertEquals(
            IdentityTrustState.UNKNOWN,
            IdentitySafetyPolicy.aggregateTrust(emptyList())
        )
        assertNull(IdentitySafetyPolicy.primaryDevice(emptyList()))
        assertFalse(IdentitySafetyPolicy.canVerifyAny(emptyList()))
    }

    @Test
    fun `changed wins over verified`() {
        val states = listOf(
            device(1, IdentityTrustState.VERIFIED),
            device(2, IdentityTrustState.CHANGED)
        )
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.isStickyWarning(IdentitySafetyPolicy.aggregateTrust(states)))
        assertEquals(WarningKind.CHANGED, IdentitySafetyPolicy.warningKind(IdentityTrustState.CHANGED))
    }

    @Test
    fun `all verified aggregates verified`() {
        val states = listOf(
            device(1, IdentityTrustState.VERIFIED),
            device(2, IdentityTrustState.VERIFIED)
        )
        assertEquals(
            IdentityTrustState.VERIFIED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertFalse(IdentitySafetyPolicy.canVerifyAny(states))
        assertEquals(WarningKind.NONE, IdentitySafetyPolicy.warningKind(IdentityTrustState.VERIFIED))
    }

    @Test
    fun `mixed trusted and verified is trusted`() {
        val states = listOf(
            device(1, IdentityTrustState.VERIFIED),
            device(2, IdentityTrustState.TRUSTED)
        )
        assertEquals(
            IdentityTrustState.TRUSTED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.canVerifyAny(states))
    }

    @Test
    fun `primary prefers device one`() {
        val states = listOf(
            device(3, IdentityTrustState.TRUSTED),
            device(1, IdentityTrustState.VERIFIED, current = true)
        )
        assertEquals(1, IdentitySafetyPolicy.primaryDevice(states)?.deviceId)
    }

    @Test
    fun `missing safety code cannot verify`() {
        val states = listOf(device(1, IdentityTrustState.TRUSTED, code = null))
        assertFalse(IdentitySafetyPolicy.canVerifyAny(states))
    }

    @Test
    fun `unknown poisons trusted aggregation`() {
        val states = listOf(
            device(1, IdentityTrustState.TRUSTED),
            device(2, IdentityTrustState.UNKNOWN)
        )
        assertEquals(
            IdentityTrustState.UNKNOWN,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertEquals(
            WarningKind.UNKNOWN,
            IdentitySafetyPolicy.warningKind(IdentityTrustState.UNKNOWN)
        )
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.UNKNOWN))
    }

    @Test
    fun `changed beats unknown in worst wins`() {
        val states = listOf(
            device(1, IdentityTrustState.UNKNOWN),
            device(2, IdentityTrustState.CHANGED)
        )
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.CHANGED))
    }

    @Test
    fun `primary falls back to first listed device`() {
        val states = listOf(
            device(2, IdentityTrustState.TRUSTED),
            device(3, IdentityTrustState.VERIFIED)
        )
        assertEquals(2, IdentitySafetyPolicy.primaryDevice(states)?.deviceId)
    }

    @Test
    fun `blank safety code cannot verify`() {
        assertFalse(
            IdentitySafetyPolicy.canVerifyAny(
                listOf(device(1, IdentityTrustState.TRUSTED, code = ""))
            )
        )
        assertFalse(
            IdentitySafetyPolicy.canVerifyAny(
                listOf(device(1, IdentityTrustState.TRUSTED, code = "   "))
            )
        )
        assertTrue(
            IdentitySafetyPolicy.canVerifyAny(
                listOf(device(1, IdentityTrustState.TRUSTED))
            )
        )
    }

    @Test
    fun `verified needs no warning`() {
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.VERIFIED))
        assertFalse(IdentitySafetyPolicy.isStickyWarning(IdentityTrustState.TRUSTED))
        assertEquals(
            WarningKind.TRUSTED,
            IdentitySafetyPolicy.warningKind(IdentityTrustState.TRUSTED)
        )
    }

    @Test
    fun `all trusted aggregates trusted`() {
        val states = listOf(
            device(1, IdentityTrustState.TRUSTED),
            device(2, IdentityTrustState.TRUSTED)
        )
        assertEquals(
            IdentityTrustState.TRUSTED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.canVerifyAny(states))
        assertEquals(
            WarningKind.TRUSTED,
            IdentitySafetyPolicy.warningKind(IdentitySafetyPolicy.aggregateTrust(states))
        )
    }

    @Test
    fun `single changed aggregates changed`() {
        val states = listOf(device(1, IdentityTrustState.CHANGED))
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.isStickyWarning(IdentitySafetyPolicy.aggregateTrust(states)))
    }

    @Test
    fun `verified device with safety code is not verifiable`() {
        assertFalse(
            IdentitySafetyPolicy.canVerifyAny(
                listOf(device(1, IdentityTrustState.VERIFIED))
            )
        )
    }

    @Test
    fun `primary ignores isCurrent flag`() {
        val states = listOf(
            device(2, IdentityTrustState.TRUSTED, current = true),
            device(1, IdentityTrustState.TRUSTED)
        )
        assertEquals(1, IdentitySafetyPolicy.primaryDevice(states)?.deviceId)
    }

    @Test
    fun `changed still wins over trusted and unknown together`() {
        val states = listOf(
            device(1, IdentityTrustState.TRUSTED),
            device(2, IdentityTrustState.UNKNOWN),
            device(3, IdentityTrustState.CHANGED)
        )
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertEquals(
            WarningKind.CHANGED,
            IdentitySafetyPolicy.warningKind(IdentitySafetyPolicy.aggregateTrust(states))
        )
    }
}
