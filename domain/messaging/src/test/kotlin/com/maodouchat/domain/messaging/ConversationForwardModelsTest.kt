package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationForwardModelsTest {

    @Test
    fun forwardBatchResultDefaults() {
        val result = ForwardBatchResult(forwardedCount = 2, failedCount = 1)
        assertEquals(2, result.forwardedCount)
        assertEquals(1, result.failedCount)
        assertEquals(null, result.firstError)
        assertFalse(result.attachmentFailed)
    }

    @Test
    fun forwardExceptionsCarryType() {
        val sessionError = ConversationForwardSessionException("forward_session_missing")
        assertTrue(sessionError is IllegalStateException)
        assertEquals("forward_session_missing", sessionError.message)
        val unsupported = ConversationForwardUnsupportedException(MessageType.VOICE)
        assertTrue(unsupported is IllegalArgumentException)
        assertTrue(unsupported.message!!.contains("forward_type_unsupported"))
        assertEquals(MessageType.VOICE, unsupported.messageType)
    }
}
