package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitySafetyPolicyTest {

    private fun device(
        id: Int,
        trust: SignalProtocol.IdentityTrustState,
        code: String? = "01234 05678 90123",
        current: Boolean = false
    ) = SignalProtocol.DeviceSafetyState(
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
            SignalProtocol.IdentityTrustState.UNKNOWN,
            IdentitySafetyPolicy.aggregateTrust(emptyList())
        )
        assertNull(IdentitySafetyPolicy.primaryDevice(emptyList()))
        assertFalse(IdentitySafetyPolicy.canVerifyAny(emptyList()))
    }

    @Test
    fun `changed wins over verified`() {
        val states = listOf(
            device(1, SignalProtocol.IdentityTrustState.VERIFIED),
            device(2, SignalProtocol.IdentityTrustState.CHANGED)
        )
        assertEquals(
            SignalProtocol.IdentityTrustState.CHANGED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.isStickyWarning(IdentitySafetyPolicy.aggregateTrust(states)))
        assertEquals(IdentitySafetyPolicy.WarningKind.CHANGED, IdentitySafetyPolicy.warningKind(SignalProtocol.IdentityTrustState.CHANGED))
    }

    @Test
    fun `all verified aggregates verified`() {
        val states = listOf(
            device(1, SignalProtocol.IdentityTrustState.VERIFIED),
            device(2, SignalProtocol.IdentityTrustState.VERIFIED)
        )
        assertEquals(
            SignalProtocol.IdentityTrustState.VERIFIED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertFalse(IdentitySafetyPolicy.canVerifyAny(states))
        assertEquals(IdentitySafetyPolicy.WarningKind.NONE, IdentitySafetyPolicy.warningKind(SignalProtocol.IdentityTrustState.VERIFIED))
    }

    @Test
    fun `mixed trusted and verified is trusted`() {
        val states = listOf(
            device(1, SignalProtocol.IdentityTrustState.VERIFIED),
            device(2, SignalProtocol.IdentityTrustState.TRUSTED)
        )
        assertEquals(
            SignalProtocol.IdentityTrustState.TRUSTED,
            IdentitySafetyPolicy.aggregateTrust(states)
        )
        assertTrue(IdentitySafetyPolicy.canVerifyAny(states))
    }

    @Test
    fun `primary prefers device one`() {
        val states = listOf(
            device(3, SignalProtocol.IdentityTrustState.TRUSTED),
            device(1, SignalProtocol.IdentityTrustState.VERIFIED, current = true)
        )
        assertEquals(1, IdentitySafetyPolicy.primaryDevice(states)?.deviceId)
    }

    @Test
    fun `missing safety code cannot verify`() {
        val states = listOf(device(1, SignalProtocol.IdentityTrustState.TRUSTED, code = null))
        assertFalse(IdentitySafetyPolicy.canVerifyAny(states))
    }
}
