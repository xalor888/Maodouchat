package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityTrustStateMachineTest {

    @Test
    fun `verification converges any state to VERIFIED`() {
        assertEquals(
            IdentityTrustState.VERIFIED,
            IdentityTrustStateMachine.onVerification(IdentityTrustState.UNKNOWN),
        )
        assertEquals(
            IdentityTrustState.VERIFIED,
            IdentityTrustStateMachine.onVerification(IdentityTrustState.CHANGED),
        )
        assertEquals(
            IdentityTrustState.VERIFIED,
            IdentityTrustStateMachine.onVerification(IdentityTrustState.VERIFIED),
        )
    }

    @Test
    fun `identity key change only degrades VERIFIED to CHANGED`() {
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentityTrustStateMachine.onIdentityKeyChange(IdentityTrustState.VERIFIED),
        )
        assertEquals(
            IdentityTrustState.UNKNOWN,
            IdentityTrustStateMachine.onIdentityKeyChange(IdentityTrustState.UNKNOWN),
        )
        assertEquals(
            IdentityTrustState.CHANGED,
            IdentityTrustStateMachine.onIdentityKeyChange(IdentityTrustState.CHANGED),
        )
    }

    @Test
    fun `reset returns UNKNOWN`() {
        assertEquals(IdentityTrustState.UNKNOWN, IdentityTrustStateMachine.onReset())
    }
}
