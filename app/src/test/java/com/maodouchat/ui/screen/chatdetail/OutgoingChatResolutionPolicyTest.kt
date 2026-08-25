package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingChatResolutionPolicyTest {
    @Test
    fun `known chat id prefers active then constructor before loaded metadata`() {
        assertEquals(
            "active",
            OutgoingChatResolutionPolicy.knownChatId("active", "constructor", "loaded")
        )
        assertEquals(
            "constructor",
            OutgoingChatResolutionPolicy.knownChatId("", "constructor", "loaded")
        )
        assertEquals(
            "loaded",
            OutgoingChatResolutionPolicy.knownChatId("", "", "loaded")
        )
    }

    @Test
    fun `direct peer is recovered from cached participants before active contact`() {
        val chat = Chat(
            id = "chat",
            participants = listOf(User("self", "Self"), User("cached-peer", "Peer"))
        )

        assertEquals(
            "cached-peer",
            OutgoingChatResolutionPolicy.directPeerId(chat, "self", "painted-peer")
        )
    }

    @Test
    fun `direct peer falls back to painted contact when cache is incomplete`() {
        val chat = Chat(id = "chat", participants = listOf(User("self", "Self")))

        assertEquals(
            "painted-peer",
            OutgoingChatResolutionPolicy.directPeerId(chat, "self", "painted-peer")
        )
    }

    @Test
    fun `group never exposes a direct encryption peer`() {
        val group = Chat(
            id = "group",
            isGroup = true,
            participants = listOf(User("peer", "Peer"))
        )

        assertNull(OutgoingChatResolutionPolicy.directPeerId(group, "self", "painted-peer"))
    }
}
