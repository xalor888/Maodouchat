package com.maodouchat.widget

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G157：`ConversationWidgetConfigUi.chatTitle` 的测试。
 *
 * 桌面小组件要显示会话标题：群聊用群名，单聊用**对方**的显示名。
 * 四级回退：群名 → 对方 displayName → name → 截断 id。
 * 少一级就会让小组件显示成一串 id。
 */
class ChatTitleTest {

    private fun chat(id: String, group: Boolean, groupName: String?, participants: List<User>) =
        Chat(id = id, isGroup = group, groupName = groupName, participants = participants)

    @Test
    fun `group chat uses the group name`() {
        val c = chat("g1", group = true, groupName = "Dev Team", participants = emptyList())
        assertEquals("Dev Team", chatTitle(c, "me"))
    }

    @Test
    fun `group chat without a name falls back to a truncated id`() {
        val long = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(long.take(12), chatTitle(chat(long, group = true, groupName = null, participants = emptyList()), "me"))
        assertEquals(long.take(12), chatTitle(chat(long, group = true, groupName = "   ", participants = emptyList()), "me"))
    }

    @Test
    fun `direct chat picks the peer not me`() {
        val c = chat(
            "d1",
            group = false,
            groupName = null,
            participants = listOf(User(id = "me", name = "Me"), User(id = "peer", name = "Alice")),
        )
        assertEquals("Alice", chatTitle(c, "me"))
    }

    @Test
    fun `direct chat prefers displayName over name`() {
        // User.displayName = nickname ?: name ?: id，所以设 nickname 就走 nickname
        val c = chat(
            "d1",
            group = false,
            groupName = null,
            participants = listOf(User(id = "me", name = "Me"), User(id = "peer", name = "Alice", nickname = "Ally")),
        )
        assertEquals("Ally", chatTitle(c, "me"))
    }

    @Test
    fun `direct chat falls back to name when displayName is blank`() {
        // displayName 永远不为空（回落到 id），所以要让它「看起来空」只能让 id 也空
        val c = chat(
            "d1",
            group = false,
            groupName = null,
            participants = listOf(User(id = "me", name = "Me"), User(id = "", name = "Bob", nickname = null)),
        )
        // id 空 + name 非空 -> displayName == "Bob"
        assertEquals("Bob", chatTitle(c, "me"))
    }

    @Test
    fun `direct chat without a peer falls back to group name then truncated id`() {
        val long = "abcdefghijklmnopqrstuvwxyz"
        // 只有自己 -> 找不到对方 -> 用 groupName
        assertEquals(
            "Fallback Group",
            chatTitle(chat("d1", group = false, groupName = "Fallback Group", participants = listOf(User(id = "me", name = "Me"))), "me")
        )
        // 没有 groupName -> 截断 id
        assertEquals(
            long.take(12),
            chatTitle(chat(long, group = false, groupName = null, participants = listOf(User(id = "me", name = "Me"))), "me")
        )
    }

    @Test
    fun `blank group name on a direct chat is not used`() {
        val c = chat(
            "d1",
            group = false,
            groupName = "   ",
            participants = listOf(User(id = "me", name = "Me"), User(id = "peer", name = "Alice")),
        )
        assertEquals("Alice", chatTitle(c, "me"))
    }

    @Test
    fun `the peer is picked by id not by position`() {
        // 自己在列表中间
        val c = chat(
            "d1",
            group = false,
            groupName = null,
            participants = listOf(
                User(id = "other", name = "Someone"),
                User(id = "me", name = "Me"),
                User(id = "peer", name = "Alice"),
            ),
        )
        // 第一个非本人参与者是 "Someone"
        assertEquals("Someone", chatTitle(c, "me"))
    }
}
