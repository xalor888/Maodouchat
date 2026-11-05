package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretChatDaoModelsTest {

    @Test
    fun `secret chat activity keeps chat and timestamp`() {
        val row = SecretChatActivity("c1", 1700000000000L)
        assertEquals("c1", row.chatId)
        assertEquals(1700000000000L, row.lastActivityAt)
        assertEquals(SecretChatActivity("c1", 1L), row.copy(lastActivityAt = 1L))
    }
}
