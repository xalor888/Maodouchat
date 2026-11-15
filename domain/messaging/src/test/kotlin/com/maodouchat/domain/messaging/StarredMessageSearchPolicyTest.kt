package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StarredMessageSearchPolicyTest {

    private val me = "me"
    private val group = "群聊"
    private val member = "群成员"

    private fun user(id: String, name: String) = User(id = id, name = name)
    private fun chat(participants: List<User> = emptyList(), isGroup: Boolean = false, groupName: String? = null) =
        Chat(id = "c1", participants = participants, isGroup = isGroup, groupName = groupName)
    private fun message(id: String = "m1", chatId: String = "c1", senderId: String = "u2", content: String = "hello") =
        Message(id = id, chatId = chatId, senderId = senderId, content = content, timestamp = 1L)

    @Test
    fun `senderLabel returns me label for self`() {
        assertEquals(me, StarredMessageSearchPolicy.senderLabel(chat(), message(senderId = "u1"), "u1", me, member))
    }

    @Test
    fun `senderLabel returns participant display name for other`() {
        val c = chat(participants = listOf(user("u2", "Bob")))
        assertEquals("Bob", StarredMessageSearchPolicy.senderLabel(c, message(), "u1", me, member))
    }

    @Test
    fun `senderLabel falls back to member label for unknown sender`() {
        assertEquals(member, StarredMessageSearchPolicy.senderLabel(chat(), message(), "u1", me, member))
    }

    @Test
    fun `chatTitle returns group label for null chat`() {
        assertEquals(group, StarredMessageSearchPolicy.chatTitle(null, "u1", group, member))
    }

    @Test
    fun `chatTitle returns group name for group chat`() {
        assertEquals("Fans", StarredMessageSearchPolicy.chatTitle(chat(isGroup = true, groupName = "Fans"), "u1", group, member))
        assertEquals(group, StarredMessageSearchPolicy.chatTitle(chat(isGroup = true, groupName = "  "), "u1", group, member))
    }

    @Test
    fun `chatTitle returns other participant for direct chat`() {
        val c = chat(participants = listOf(user("u1", "Me"), user("u2", "Bob")))
        assertEquals("Bob", StarredMessageSearchPolicy.chatTitle(c, "u1", group, member))
        assertEquals(member, StarredMessageSearchPolicy.chatTitle(chat(), "u1", group, member))
    }

    @Test
    fun `searchPreview strips after last meta tag`() {
        assertEquals("hello", StarredMessageSearchPolicy.searchPreview("hello <meta>{}</meta>"))
        assertEquals("hello", StarredMessageSearchPolicy.searchPreview("  hello  "))
    }

    @Test
    fun `filter returns all on blank query`() {
        val messages = listOf(message("m1"), message("m2", content = "bye"))
        assertEquals(messages, StarredMessageSearchPolicy.filter(messages, emptyMap(), null, "u1", "  ", me, group, member))
    }

    @Test
    fun `filter matches sender name`() {
        val c = chat(participants = listOf(user("u2", "Bob")))
        // m2 属另一会话（无映射、回退为空），会话名与发信人名都不含 bob
        val messages = listOf(message("m1"), message("m2", chatId = "c2", senderId = "u9", content = "bye"))
        val found = StarredMessageSearchPolicy.filter(messages, mapOf("c1" to c), null, "u1", "bob", me, group, member)
        assertEquals(listOf(message("m1")), found)
    }

    @Test
    fun `filter matches preview but not meta payload`() {
        val messages = listOf(message("m1", content = "secret <meta>bob</meta>"))
        val found = StarredMessageSearchPolicy.filter(messages, emptyMap(), null, "u1", "bob", me, group, member)
        assertEquals(emptyList<Message>(), found)
        val hit = StarredMessageSearchPolicy.filter(messages, emptyMap(), null, "u1", "secret", me, group, member)
        assertEquals(messages, hit)
    }
}
