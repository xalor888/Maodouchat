package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentitySecurityEventPolicyTest {

    @Test
    fun `mismatch detail names device and session`() {
        assertEquals(
            "deviceId=2 session=sess-a",
            IdentitySecurityEventPolicy.mismatchDetail(deviceId = 2, authSessionId = "sess-a"),
        )
        assertTrue(IdentitySecurityEventPolicy.shouldRevokeAuthSessionOnMismatch())
        assertEquals("IDENTITY_KEY_MISMATCH", IdentitySecurityEventPolicy.ACTION_MISMATCH)
    }
}
