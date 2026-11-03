package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArchiveSuggestionTest {

    @Test
    fun `fields are kept as given`() {
        val item = ArchiveSuggestion(chatId = "chat-1", score = 9, reason = "idle")
        assertEquals("chat-1", item.chatId)
        assertEquals(9, item.score)
        assertEquals("idle", item.reason)
    }

    @Test
    fun `data class equality and copy hold`() {
        val a = ArchiveSuggestion("chat-1", 9, "idle")
        val b = ArchiveSuggestion("chat-1", 9, "idle")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(10, a.copy(score = 10).score)
        assertNotEquals(a, a.copy(chatId = "chat-2"))
    }
}
