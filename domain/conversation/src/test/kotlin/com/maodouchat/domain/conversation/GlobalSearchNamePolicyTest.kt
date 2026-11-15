package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSearchNamePolicyTest {

    private fun user(id: String, name: String) = User(id = id, name = name)

    @Test
    fun `null chat falls back to unknown label`() {
        assertEquals("未知", GlobalSearchNamePolicy.chatName(null, "未知", "群聊"))
    }

    @Test
    fun `group chat uses group name`() {
        val chat = Chat(id = "g1", isGroup = true, groupName = "跑团")
        assertEquals("跑团", GlobalSearchNamePolicy.chatName(chat, "未知", "群聊"))
    }

    @Test
    fun `group chat with blank name falls back to group label`() {
        val blank = Chat(id = "g1", isGroup = true, groupName = "  ")
        assertEquals("群聊", GlobalSearchNamePolicy.chatName(blank, "未知", "群聊"))
        val missing = Chat(id = "g1", isGroup = true, groupName = null)
        assertEquals("群聊", GlobalSearchNamePolicy.chatName(missing, "未知", "群聊"))
    }

    @Test
    fun `direct chat uses first participant display name`() {
        val chat = Chat(id = "d1", participants = listOf(user("u1", "Alice"), user("u2", "Bob")))
        assertEquals("Alice", GlobalSearchNamePolicy.chatName(chat, "未知", "群聊"))
    }

    @Test
    fun `direct chat without participants falls back to unknown label`() {
        val chat = Chat(id = "d1", participants = emptyList())
        assertEquals("未知", GlobalSearchNamePolicy.chatName(chat, "未知", "群聊"))
    }

    @Test
    fun `sender is self returns me label`() {
        assertEquals("我", GlobalSearchNamePolicy.senderName("u1", "u1", "我", listOf(user("u1", "Alice"))))
    }

    @Test
    fun `sender found by id returns display name`() {
        val participants = listOf(user("u1", "Alice"), user("u2", "Bob"))
        assertEquals("Bob", GlobalSearchNamePolicy.senderName("u2", "u1", "我", participants))
    }

    @Test
    fun `sender not found falls back to sender id`() {
        assertEquals("u9", GlobalSearchNamePolicy.senderName("u9", "u1", "我", listOf(user("u1", "Alice"))))
    }

    @Test
    fun `null current user id never matches sender`() {
        val participants = listOf(user("u1", "Alice"), user("u2", "Bob"))
        assertEquals("Bob", GlobalSearchNamePolicy.senderName("u2", null, "我", participants))
        assertEquals("u9", GlobalSearchNamePolicy.senderName("u9", null, "我", participants))
    }

    @Test
    fun `hit text folds newlines and trims`() {
        assertEquals("a b c", GlobalSearchNamePolicy.hitText("  a\nb\nc  "))
    }

    @Test
    fun `hit text of blank input is empty`() {
        assertEquals("", GlobalSearchNamePolicy.hitText("  \n  "))
    }

    @Test
    fun `hit text keeps inner spacing`() {
        assertEquals("a  b", GlobalSearchNamePolicy.hitText("a  b"))
    }
}
