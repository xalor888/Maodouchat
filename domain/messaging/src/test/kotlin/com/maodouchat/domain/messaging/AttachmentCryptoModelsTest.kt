package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttachmentCryptoModelsTest {

    @Test
    fun `exception message is the failure name`() {
        val e = AttachmentCryptoException(AttachmentCryptoFailure.TOO_LARGE)
        assertEquals("TOO_LARGE", e.message)
        assertEquals(AttachmentCryptoFailure.TOO_LARGE, e.failure)
        assertNull(e.cause)
    }

    @Test
    fun `exception carries its cause`() {
        val cause = IllegalStateException("disk")
        val e = AttachmentCryptoException(AttachmentCryptoFailure.READ_FAILED, cause)
        assertEquals(cause, e.cause)
    }

    @Test
    fun `failure enum covers all six outcomes`() {
        assertEquals(6, AttachmentCryptoFailure.values().size)
    }
}
