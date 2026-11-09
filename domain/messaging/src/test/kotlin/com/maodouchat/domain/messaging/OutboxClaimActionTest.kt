package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxClaimActionTest {

    @Test
    fun `preparing claims are prepared`() {
        assertEquals(
            OutboxClaimAction.Prepare,
            decideOutboxClaimAction("PREPARING"),
        )
    }

    @Test
    fun `sending claims are sent`() {
        assertEquals(
            OutboxClaimAction.Send,
            decideOutboxClaimAction("SENDING"),
        )
    }

    @Test
    fun `queued claims are invalid`() {
        assertEquals(
            OutboxClaimAction.Invalid("QUEUED"),
            decideOutboxClaimAction("QUEUED"),
        )
    }

    @Test
    fun `an unknown state is invalid and keeps the state for the error`() {
        assertEquals(
            OutboxClaimAction.Invalid("RETRY_SEND"),
            decideOutboxClaimAction("RETRY_SEND"),
        )
    }
}
