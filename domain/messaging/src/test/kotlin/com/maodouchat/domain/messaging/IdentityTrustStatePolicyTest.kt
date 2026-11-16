package com.maodouchat.domain.messaging

import com.maodouchat.core.model.IdentityTrustState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentityTrustStatePolicyTest {

    @Test
    fun `verified and changed map one to one`() {
        assertEquals(IdentityTrustState.VERIFIED, IdentityTrustStatePolicy.stateFor("VERIFIED"))
        assertEquals(IdentityTrustState.CHANGED, IdentityTrustStatePolicy.stateFor("CHANGED"))
    }

    @Test
    fun `anything else falls back to trusted`() {
        assertEquals(IdentityTrustState.TRUSTED, IdentityTrustStatePolicy.stateFor("TRUSTED"))
        assertEquals(IdentityTrustState.TRUSTED, IdentityTrustStatePolicy.stateFor(""))
        assertEquals(IdentityTrustState.TRUSTED, IdentityTrustStatePolicy.stateFor("UNKNOWN_VALUE"))
    }
}
