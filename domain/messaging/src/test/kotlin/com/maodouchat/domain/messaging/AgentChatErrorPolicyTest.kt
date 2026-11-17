package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentChatErrorPolicyTest {

    @Test
    fun `contactNotFound matches the lookup error`() {
        assertEquals("Error: contact not found", AgentChatErrorPolicy.contactNotFound())
    }

    @Test
    fun `notificationNotFound matches the lookup error`() {
        assertEquals(
            "Error: notification not found",
            AgentChatErrorPolicy.notificationNotFound(),
        )
    }

    @Test
    fun `provideUpdateFields lists the four updatable keys`() {
        assertEquals(
            "Error: provide pinned, muted, archived, or markedUnread",
            AgentChatErrorPolicy.provideUpdateFields(),
        )
    }
}
