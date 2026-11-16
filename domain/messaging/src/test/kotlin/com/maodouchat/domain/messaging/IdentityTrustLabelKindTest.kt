package com.maodouchat.domain.messaging

import com.maodouchat.core.model.IdentityTrustState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdentityTrustLabelKindTest {

    @Test
    fun `all four trust states map to their label kinds`() {
        assertEquals(IdentityTrustLabelKind.UNKNOWN, identityTrustLabelKind(IdentityTrustState.UNKNOWN))
        assertEquals(IdentityTrustLabelKind.TRUSTED, identityTrustLabelKind(IdentityTrustState.TRUSTED))
        assertEquals(IdentityTrustLabelKind.VERIFIED, identityTrustLabelKind(IdentityTrustState.VERIFIED))
        assertEquals(IdentityTrustLabelKind.CHANGED, identityTrustLabelKind(IdentityTrustState.CHANGED))
    }
}
