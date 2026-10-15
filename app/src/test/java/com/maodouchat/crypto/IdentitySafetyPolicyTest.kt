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
}
