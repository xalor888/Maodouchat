package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentMessageErrorPolicyTest {

    @Test
    fun `messageNotFound matches the lookup error`() {
        assertEquals("Error: message not found", AgentMessageErrorPolicy.messageNotFound())
    }

    @Test
    fun `onlySenderCanRevoke matches the guard error`() {
        assertEquals(
            "Error: only the sender can revoke this message",
            AgentMessageErrorPolicy.onlySenderCanRevoke(),
        )
    }

    @Test
    fun `starSyncFailed keeps the server message`() {
        assertEquals("Error: timeout", AgentMessageErrorPolicy.starSyncFailed("timeout"))
    }

    @Test
    fun `starSyncFailed falls back when the message is null`() {
        assertEquals("Error: star sync failed", AgentMessageErrorPolicy.starSyncFailed(null))
    }
}
