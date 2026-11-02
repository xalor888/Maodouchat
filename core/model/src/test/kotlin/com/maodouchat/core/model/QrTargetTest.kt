package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class QrTargetTest {

    @Test
    fun `each subtype keeps its fields`() {
        assertEquals("u1", QrTarget.User("u1").userId)
        assertEquals("c1", QrTarget.Chat("c1").chatId)
        assertEquals("tok", QrTarget.ChatInvite("tok").token)
        val safety = QrTarget.Safety("ou", 1, "pu", 2, safetyCode = "1234")
        assertEquals("ou", safety.ownerUserId)
        assertEquals(2, safety.peerDeviceId)
        assertEquals("1234", safety.safetyCode)
        assertEquals(null, safety.ownerIdentityFingerprint)
    }
}
