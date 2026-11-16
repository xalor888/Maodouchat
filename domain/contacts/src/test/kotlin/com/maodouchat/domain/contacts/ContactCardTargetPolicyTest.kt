package com.maodouchat.domain.contacts

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactCardTargetPolicyTest {

    private fun user(id: String, name: String) = User(id = id, name = name)

    private fun chat(id: String, isGroup: Boolean, vararg members: User) =
        Chat(id = id, participants = members.toList(), isGroup = isGroup)

    @Test
    fun `group chats are filtered out`() {
        val chats = listOf(
            chat("g1", true, user("me", "Me"), user("u1", "Alice")),
            chat("c1", false, user("me", "Me"), user("u2", "Bob")),
        )
        assertEquals(listOf("c1"),
            ContactCardTargetPolicy.pickTargets(chats, "me").map { it.first.id })
    }

    @Test
    fun `chats without another participant are skipped`() {
        val chats = listOf(
            chat("c1", false, user("me", "Me")),
            chat("c2", false, user("me", "Me"), user("u2", "Bob")),
        )
        assertEquals(listOf("c2"),
            ContactCardTargetPolicy.pickTargets(chats, "me").map { it.first.id })
    }

    @Test
    fun `targets sort by other display name case-insensitively`() {
        val chats = listOf(
            chat("c1", false, user("me", "Me"), user("u1", "Zoe")),
            chat("c2", false, user("me", "Me"), user("u2", "alice")),
            chat("c3", false, user("me", "Me"), user("u3", "Bob")),
        )
        assertEquals(listOf("c2", "c3", "c1"),
            ContactCardTargetPolicy.pickTargets(chats, "me").map { it.first.id })
    }

    @Test
    fun `first non-self participant wins and pairing is kept`() {
        val chats = listOf(
            chat("c1", false, user("me", "Me"), user("u1", "Alice"), user("u2", "Bob")),
        )
        val picked = ContactCardTargetPolicy.pickTargets(chats, "me")
        assertEquals(listOf("u1"), picked.map { it.second.id })
    }
}
