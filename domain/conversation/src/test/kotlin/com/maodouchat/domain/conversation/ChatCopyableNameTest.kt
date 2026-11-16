package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatCopyableNameTest {

    @Test
    fun `channel returns group name`() {
        assertEquals("公告频道", Chat("c1", chatType = "CHANNEL", groupName = "公告频道").copyableName())
    }

    @Test
    fun `channel without group name returns empty`() {
        assertEquals("", Chat("c1", chatType = "CHANNEL", groupName = "  ").copyableName())
        assertEquals("", Chat("c1", chatType = "CHANNEL").copyableName())
    }

    @Test
    fun `group returns group name`() {
        assertEquals("小组", Chat("g1", isGroup = true, groupName = "小组").copyableName())
    }

    @Test
    fun `direct returns first participant display name`() {
        val chat = Chat("d1", participants = listOf(User("u1", "Alice")))
        assertEquals("Alice", chat.copyableName())
    }

    @Test
    fun `direct without participants returns empty`() {
        assertEquals("", Chat("d1").copyableName())
    }
}
