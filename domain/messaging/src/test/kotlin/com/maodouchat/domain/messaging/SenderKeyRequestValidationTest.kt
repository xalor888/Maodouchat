package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.SenderKeyRequestValidation.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenderKeyRequestValidationTest {

    @Test
    fun `blank sender is missing first`() {
        assertEquals(
            Problem.SENDER_MISSING,
            SenderKeyRequestValidation.validate("   ", 0L, ""),
        )
    }

    @Test
    fun `zero revision is missing`() {
        assertEquals(
            Problem.REVISION_MISSING,
            SenderKeyRequestValidation.validate("user-1", 0L, "msg-1"),
        )
    }

    @Test
    fun `negative revision is missing`() {
        assertEquals(
            Problem.REVISION_MISSING,
            SenderKeyRequestValidation.validate("user-1", -3L, "msg-1"),
        )
    }

    @Test
    fun `blank failed message is missing`() {
        assertEquals(
            Problem.MESSAGE_MISSING,
            SenderKeyRequestValidation.validate("user-1", 7L, "  "),
        )
    }

    @Test
    fun `complete request is valid`() {
        assertNull(
            SenderKeyRequestValidation.validate("user-1", 7L, "msg-1"),
        )
    }
}
