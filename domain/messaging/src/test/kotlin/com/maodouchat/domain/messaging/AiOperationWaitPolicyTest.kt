package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiOperationWaitPolicyTest {

    @Test
    fun `sub-second waits show as one second`() {
        assertEquals(1L, AiOperationWaitPolicy.waitSeconds(0L))
        assertEquals(1L, AiOperationWaitPolicy.waitSeconds(999L))
    }

    @Test
    fun `exact seconds pass through`() {
        assertEquals(1L, AiOperationWaitPolicy.waitSeconds(1_000L))
        assertEquals(2L, AiOperationWaitPolicy.waitSeconds(2_000L))
    }

    @Test
    fun `partial seconds truncate down`() {
        assertEquals(1L, AiOperationWaitPolicy.waitSeconds(1_500L))
        assertEquals(60L, AiOperationWaitPolicy.waitSeconds(60_000L))
    }
}
