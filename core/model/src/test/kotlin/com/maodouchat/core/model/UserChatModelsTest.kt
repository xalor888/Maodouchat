package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserChatModelsTest {

    @Test
    fun `displayName prefers nickname then name then id`() {
        assertEquals("阿毛", User(id = "u1", name = "毛豆", nickname = "阿毛").displayName)
        assertEquals("毛豆", User(id = "u1", name = "毛豆", nickname = "  ").displayName)
        assertEquals("u1", User(id = "u1", name = "   ").displayName)
    }

    @Test
    fun `chat defaults to direct with group type derived from isGroup`() {
        val chat = Chat(id = "c1")
        assertFalse(chat.isGroup)
        assertEquals("DIRECT", chat.chatType)
        assertFalse(chat.isChannel)
        assertFalse(chat.isSecret)
    }

    @Test
    fun `chatType channel and secret derive isChannel and isSecret`() {
        assertTrue(Chat(id = "c1", chatType = "CHANNEL").isChannel)
        assertTrue(Chat(id = "c1", chatType = "SECRET").isSecret)
        assertFalse(Chat(id = "c1", chatType = "SECRET").isChannel)
    }
}
