package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSuggestionTest {

    @Test
    fun `null chat resolves to null`() {
        assertNull(chatNameForSuggestion(null))
    }

    @Test
    fun `group name wins over participants`() {
        val chat = Chat(id = "c1", groupName = "Family", participants = listOf(User(id = "u1", name = "Alice")))
        assertEquals("Family", chatNameForSuggestion(chat))
    }

    @Test
    fun `first participant nickname wins when no group name`() {
        val chat = Chat(id = "c1", participants = listOf(User(id = "u1", name = "Alice", nickname = "Al")))
        assertEquals("Al", chatNameForSuggestion(chat))
    }

    @Test
    fun `first participant name is used when no nickname`() {
        val chat = Chat(id = "c1", participants = listOf(User(id = "u1", name = "Alice")))
        assertEquals("Alice", chatNameForSuggestion(chat))
    }

    @Test
    fun `no group name and no participants resolves to null`() {
        assertNull(chatNameForSuggestion(Chat(id = "c1")))
    }
}
