package com.maodouchat.domain.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchWindowKindTest {

    @Test
    fun `all four windows map to their kinds`() {
        assertEquals(ChatSearchWindowKind.ALL, chatSearchWindowKind(ChatSearchWindow.ALL))
        assertEquals(ChatSearchWindowKind.TODAY, chatSearchWindowKind(ChatSearchWindow.TODAY))
        assertEquals(ChatSearchWindowKind.SEVEN_DAYS, chatSearchWindowKind(ChatSearchWindow.SEVEN_DAYS))
        assertEquals(ChatSearchWindowKind.THIRTY_DAYS, chatSearchWindowKind(ChatSearchWindow.THIRTY_DAYS))
    }
}
