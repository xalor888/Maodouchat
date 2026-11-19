package com.maodouchat.domain.contacts

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class MyQrCodeLoadModelsTest {

    @Test
    fun `qr load decision rejects with reason and allows with fields`() {
        assertEquals(2, RejectReason.entries.size)
        val reject = MyQrCodeDecision.Reject(RejectReason.DISABLED)
        assertEquals(RejectReason.DISABLED, reject.reason)
        val allow = MyQrCodeDecision.Allow("u1", "阿毛", null)
        assertEquals("u1", allow.targetUserId)
        assertTrue(allow.avatar == null)
    }
}
