package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class TypingSignalPolicyTest {

    @Test
    fun `outgoing typing only signals state transitions`() {
        assertEquals(TypingSignalAction.START, resolveTypingSignalAction(isAnnounced = false, hasInput = true))
        assertEquals(TypingSignalAction.NONE, resolveTypingSignalAction(isAnnounced = true, hasInput = true))
        assertEquals(TypingSignalAction.STOP, resolveTypingSignalAction(isAnnounced = true, hasInput = false))
        assertEquals(TypingSignalAction.NONE, resolveTypingSignalAction(isAnnounced = false, hasInput = false))
    }
}
