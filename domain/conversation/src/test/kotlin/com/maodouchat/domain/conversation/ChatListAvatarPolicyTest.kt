package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListAvatarPolicyTest {

    @Test
    fun `secret chat hides avatar and online dot follows the secret-list flag`() {
        val chat = Chat(id = "c1", chatType = "SECRET", groupAvatar = "g.png", participants = listOf(User(id = "u1", name = "小明", avatar = "a.png", isOnline = true)))
        assertEquals(null, ChatListAvatarPolicy.avatarUrl(chat, false))
        // 绿点只看 isSecret 入参（迁移前原代码亦然）：chatType 为 SECRET 但不在密聊列表时绿点仍显示。
        assertTrue(ChatListAvatarPolicy.showOnlineDot(chat, false))
        assertEquals(null, ChatListAvatarPolicy.avatarUrl(chat, true))
        assertFalse(ChatListAvatarPolicy.showOnlineDot(chat, true))
    }

    @Test
    fun `group uses group avatar and never shows online dot`() {
        val chat = Chat(id = "c1", isGroup = true, groupAvatar = "g.png", participants = listOf(User(id = "u1", name = "小明", avatar = "a.png", isOnline = true)))
        assertEquals("g.png", ChatListAvatarPolicy.avatarUrl(chat, false))
        assertFalse(ChatListAvatarPolicy.showOnlineDot(chat, false))
    }

    @Test
    fun `direct uses other user avatar and online state`() {
        val online = Chat(id = "c1", participants = listOf(User(id = "u1", name = "小明", avatar = "a.png", isOnline = true)))
        assertEquals("a.png", ChatListAvatarPolicy.avatarUrl(online, false))
        assertTrue(ChatListAvatarPolicy.showOnlineDot(online, false))
        val offline = Chat(id = "c2", participants = listOf(User(id = "u1", name = "小明", avatar = "a.png", isOnline = false)))
        assertFalse(ChatListAvatarPolicy.showOnlineDot(offline, false))
    }

    @Test
    fun `direct without participants has no avatar or dot`() {
        val chat = Chat(id = "c1")
        assertEquals(null, ChatListAvatarPolicy.avatarUrl(chat, false))
        assertFalse(ChatListAvatarPolicy.showOnlineDot(chat, false))
    }

    @Test
    fun `channel keeps direct-style avatar and online dot`() {
        val chat = Chat(id = "c1", chatType = "CHANNEL", groupAvatar = "g.png", participants = listOf(User(id = "u1", name = "小明", avatar = "a.png", isOnline = true)))
        assertEquals("a.png", ChatListAvatarPolicy.avatarUrl(chat, false))
        assertTrue(ChatListAvatarPolicy.showOnlineDot(chat, false))
    }
}
