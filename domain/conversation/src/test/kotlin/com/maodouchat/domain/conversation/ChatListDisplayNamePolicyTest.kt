package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListDisplayNamePolicyTest {

    @Test
    fun `channel with group name shows it`() {
        val chat = Chat(id = "c1", chatType = "CHANNEL", groupName = "公告频道")
        assertEquals("公告频道", ChatListDisplayNamePolicy.resolve(chat, false, "广播频道", "群聊", "未知"))
    }

    @Test
    fun `channel without group name falls back to channel default`() {
        val chat = Chat(id = "c1", chatType = "CHANNEL")
        assertEquals("广播频道", ChatListDisplayNamePolicy.resolve(chat, false, "广播频道", "群聊", "未知"))
    }

    @Test
    fun `channel with blank group name falls back to channel default`() {
        val chat = Chat(id = "c1", chatType = "CHANNEL", groupName = "   ")
        assertEquals("广播频道", ChatListDisplayNamePolicy.resolve(chat, false, "广播频道", "群聊", "未知"))
    }

    @Test
    fun `group with group name shows it, without falls back to group default`() {
        val named = Chat(id = "c1", isGroup = true, groupName = "摸鱼群")
        assertEquals("摸鱼群", ChatListDisplayNamePolicy.resolve(named, false, "广播频道", "群聊", "未知"))
        val unnamed = Chat(id = "c2", isGroup = true)
        assertEquals("群聊", ChatListDisplayNamePolicy.resolve(unnamed, false, "广播频道", "群聊", "未知"))
    }

    @Test
    fun `direct shows other user display name, none falls back to unknown`() {
        val chat = Chat(id = "c1", participants = listOf(User(id = "u1", name = "小明")))
        assertEquals("小明", ChatListDisplayNamePolicy.resolve(chat, false, "广播频道", "群聊", "未知"))
        val empty = Chat(id = "c2")
        assertEquals("未知", ChatListDisplayNamePolicy.resolve(empty, false, "广播频道", "群聊", "未知"))
    }

    @Test
    fun `secret chat always mosaics to secret label`() {
        val chat = Chat(id = "c1", chatType = "SECRET", participants = listOf(User(id = "u1", name = "小明")))
        assertEquals("密聊", ChatListDisplayNamePolicy.resolve(chat, false, "广播频道", "群聊", "未知"))
        val flagged = Chat(id = "c2", participants = listOf(User(id = "u1", name = "小明")))
        assertEquals("密聊", ChatListDisplayNamePolicy.resolve(flagged, true, "广播频道", "群聊", "未知"))
    }
}
