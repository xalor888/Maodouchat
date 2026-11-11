package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.SessionSnapshot
import com.maodouchat.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatOutgoingHydrationControllerTest {

    private class FakeCipher {
        val occupies = mutableListOf<Triple<String, String?, Boolean>>()
        val controller = ChatSessionCipherController(
            initialChatId = "",
            getChatById = { null },
            acquireOccupancy = { com.maodouchat.core.model.Lease(1) },
            occupyOccupancy = { _, chatId, peerId, updatePeer ->
                occupies += Triple(chatId, peerId, updatePeer)
            },
            releaseOccupancy = {},
            currentUserId = { "owner1" },
        )
    }

    private class Harness(
        liveUserId: String? = "owner1",
        liveToken: String? = "tok",
        generation: Long = 7L,
        purge: Boolean = false,
    ) {
        val uiState = MutableStateFlow(ChatDetailUiState(contact = User("prev", "Prev")))
        val cipher = FakeCipher()
        val activeChatIds = mutableListOf<String>()
        var usersById: Map<String, User> = emptyMap()
        val userLookups = mutableListOf<String>()
        var session = SessionSnapshot(token = liveToken, userId = liveUserId)
        var sessionGeneration = generation
        var purgeInProgress = purge

        val controller = ChatOutgoingHydrationController(
            uiState = uiState,
            cipherController = cipher.controller,
            setActiveChatId = { activeChatIds += it },
            currentUserId = { "owner1" },
            getUserById = { id ->
                userLookups += id
                usersById[id]
            },
            session = { session },
            sessionGeneration = { sessionGeneration },
            purgeInProgress = { purgeInProgress },
            text = { message, _ -> "[${message.name}]" },
            quantityText = { message, quantity, _ -> "[${message.name}:$quantity]" },
        )
    }

    @Test
    fun `group hydration builds group contact and occupies cipher`() = runTest {
        val h = Harness()
        val chat = Chat(
            id = "g1",
            isGroup = true,
            groupName = "G",
            groupAvatar = "av",
            participants = listOf(User("a", "A"), User("b", "B"), User("c", "C")),
        )
        val out = h.controller.hydrateOutgoingChat(chat, "owner1", null)
        assertEquals("g1", out.chatId)
        assertEquals(User("g1", "G", avatar = "av", status = "[MEMBERS_COUNT:3]"), h.uiState.value.contact)
        assertEquals(true, h.uiState.value.chatIsGroup)
        assertEquals(false, h.uiState.value.isSecretChat)
        assertEquals(0, h.uiState.value.disappearingMessageSeconds)
        assertEquals(listOf("g1"), h.activeChatIds)
        assertEquals(listOf(Triple<String, String?, Boolean>("g1", null, true)), h.cipher.occupies)
    }

    @Test
    fun `direct hydration resolves peer contact from participants`() = runTest {
        val h = Harness()
        val peer = User("peer", "Peer")
        val chat = Chat(
            id = "c1",
            participants = listOf(User("owner1", "Owner"), peer),
            disappearingMessageSeconds = 60,
        )
        val out = h.controller.hydrateOutgoingChat(chat, "owner1", "peer")
        assertEquals("c1", out.chatId)
        assertEquals(peer, h.uiState.value.contact)
        assertEquals(60, h.uiState.value.disappearingMessageSeconds)
        assertEquals(listOf(Triple<String, String?, Boolean>("c1", "peer", true)), h.cipher.occupies)
    }

    @Test
    fun `session changed cancels hydration`() = runTest {
        val h = Harness(liveUserId = "someone-else")
        val chat = Chat(id = "c1", participants = listOf(User("peer", "Peer")))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            h.controller.hydrateOutgoingChat(chat, "owner1", "peer")
        }
        assertEquals(null, h.uiState.value.chat)
        assertTrue(h.cipher.occupies.isEmpty())
    }

    @Test
    fun `direct chat without peer throws recipient not ready`() = runTest {
        val h = Harness()
        val chat = Chat(id = "c1", participants = listOf(User("owner1", "Owner")))
        val err = assertFailsWith<IllegalStateException> {
            h.controller.hydrateOutgoingChat(chat, "owner1", null)
        }
        assertEquals("[RECIPIENT_NOT_READY]", err.message)
    }

    @Test
    fun `withLocalNickname merges repo nickname`() = runTest {
        val h = Harness()
        h.usersById = mapOf("u1" to User("u1", "Plain", nickname = "Nick"))
        val merged = h.controller.withLocalNickname(User("u1", "Plain"))
        assertEquals("Nick", merged.nickname)
        assertEquals(listOf("u1"), h.userLookups)
    }

    @Test
    fun `withLocalNickname keeps existing nickname without repo call`() = runTest {
        val h = Harness()
        val kept = h.controller.withLocalNickname(User("u1", "Plain", nickname = "Already"))
        assertEquals("Already", kept.nickname)
        assertTrue(h.userLookups.isEmpty())
    }
}
