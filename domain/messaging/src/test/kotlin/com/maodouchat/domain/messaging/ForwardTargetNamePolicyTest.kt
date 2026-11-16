package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals

class ForwardTargetNamePolicyTest {

    private fun user(id: String, name: String) = User(id = id, name = name)

    @Test
    fun `group chat with name returns it`() {
        val chat = Chat(id = "g", isGroup = true, groupName = "Dev Team")
        assertEquals(ForwardTargetName.GroupNamed("Dev Team"), ForwardTargetNamePolicy.resolve(chat, "me"))
    }

    @Test
    fun `group chat without name returns member count`() {
        val chat = Chat(id = "g", isGroup = true, groupName = null, participants = List(3) { user("u$it", "u$it") })
        assertEquals(ForwardTargetName.GroupCount(3), ForwardTargetNamePolicy.resolve(chat, "me"))
    }

    @Test
    fun `group chat with blank name keeps it verbatim`() {
        val chat = Chat(id = "g", isGroup = true, groupName = "")
        assertEquals(ForwardTargetName.GroupNamed(""), ForwardTargetNamePolicy.resolve(chat, "me"))
    }

    @Test
    fun `direct chat picks first peer`() {
        val chat = Chat(id = "d", isGroup = false, participants = listOf(user("me", "Me"), user("peer", "Alice")))
        assertEquals(ForwardTargetName.Private("Alice"), ForwardTargetNamePolicy.resolve(chat, "me"))
    }

    @Test
    fun `direct chat with only self falls back`() {
        val chat = Chat(id = "d", isGroup = false, participants = listOf(user("me", "Me")))
        assertEquals(ForwardTargetName.PrivateFallback, ForwardTargetNamePolicy.resolve(chat, "me"))
    }

    @Test
    fun `direct chat without participants falls back`() {
        val chat = Chat(id = "d", isGroup = false, participants = emptyList())
        assertEquals(ForwardTargetName.PrivateFallback, ForwardTargetNamePolicy.resolve(chat, "me"))
    }
}
