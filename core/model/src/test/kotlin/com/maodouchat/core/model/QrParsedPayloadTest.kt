package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrParsedPayloadTest {

    @Test
    fun `each subtype keeps its fields`() {
        assertEquals("u1", QrParsedPayload.User("u1").userId)
        assertEquals("c1", QrParsedPayload.Chat("c1").chatId)
        assertEquals("tok", QrParsedPayload.ChatInvite("tok").token)
        assertEquals("bad", QrParsedPayload.Invalid("bad").reason)
    }

    @Test
    fun `safety defaults optional fields to null`() {
        val safety = QrParsedPayload.Safety(
            version = 2,
            ownerUserId = "owner",
            ownerDeviceId = 1,
            peerUserId = "peer",
            peerDeviceId = 2
        )
        assertNull(safety.safetyCode)
        assertNull(safety.ownerIdentityFingerprint)
        assertNull(safety.peerIdentityFingerprint)
        assertEquals(safety, safety.copy(safetyCode = null))
    }
}
