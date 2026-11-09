package com.maodouchat.domain.messaging

import com.maodouchat.domain.messaging.PlayReceiptTarget.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayReceiptTargetTest {

    @Test
    fun `target is the existing message`() {
        assertEquals(
            Decision.EXISTING,
            PlayReceiptTarget.decide("msg-1", "msg-1"),
        )
    }

    @Test
    fun `different target needs a lookup`() {
        assertEquals(
            Decision.LOOKUP,
            PlayReceiptTarget.decide("msg-1", "msg-2"),
        )
    }
}
