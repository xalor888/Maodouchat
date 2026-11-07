package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedSmallModelsTest {

    @Test
    fun `friend request item derives identity fields from user`() {
        val item = FriendRequestItem(
            id = "r1",
            user = User(id = "u1", name = "毛豆", avatar = "a.png"),
            message = "hi",
            outgoing = false,
        )
        assertEquals("u1", item.userId)
        assertEquals("毛豆", item.userName)
        assertEquals("a.png", item.userAvatar)
        assertEquals("hi", item.note)
        assertTrue(item.isIncoming)
        assertEquals("r1", FriendRequestsSnapshot(incoming = listOf(item)).incoming.first().id)
    }

    @Test
    fun `friend requests snapshot defaults to empty lists`() {
        val snapshot = FriendRequestsSnapshot()
        assertTrue(snapshot.incoming.isEmpty())
        assertTrue(snapshot.outgoing.isEmpty())
    }

    @Test
    fun `contacts load result defaults to no failure and session present`() {
        val result = ContactsLoadResult(users = listOf(User(id = "u1", name = "毛豆")))
        assertEquals(1, result.users.size)
        assertEquals(null, result.failure)
        assertFalse(result.sessionMissing)
        assertEquals("u1", ContactsSession(ownerUserId = "u1").ownerUserId)
    }

    @Test
    fun `nearby ui state defaults to policy radius and empty items`() {
        val state = NearbyUiState()
        assertTrue(state.items.isEmpty())
        assertEquals(NearbyPolicy.DEFAULT_RADIUS_KM, state.radiusKm, 0.0)
        assertFalse(state.isLoading)
        val person = NearbyPerson(User(id = "u2", name = "阿毛"), distanceMeters = 120, locationUpdatedAt = 7L)
        assertEquals(120, NearbyUiState(items = listOf(person)).items.first().distanceMeters)
    }

    @Test
    fun `chat list selection defaults to no selection mode`() {
        val selection = ChatListSelection()
        assertFalse(selection.selectionMode)
        assertTrue(selection.selectedChatIds.isEmpty())
        assertEquals(
            UnreadBatchTargets(),
            UnreadBatchTargets(ordinary = emptyList(), secret = emptyList()),
        )
    }

    @Test
    fun `settings toggle mutation holds optimistic chat and request`() {
        val chat = Chat(id = "c1")
        val request = UpdateChatSettingsRequest(notificationsMuted = true)
        val mutation = SettingsToggleMutation(optimistic = chat, request = request)
        assertEquals("c1", mutation.optimistic.id)
        assertEquals(true, mutation.request.notificationsMuted)
    }

    @Test
    fun `outgoing conversation models hold request and resolution`() {
        val request = OutgoingConversationRequest(
            ownerUserId = "u1",
            authToken = "t",
            activeConversationId = "c1",
            constructorConversationId = "c1",
            paintedConversation = null,
            activeContactId = "u2",
            createSecretConversation = false,
        )
        assertEquals("u2", request.activeContactId)
        val resolved = ResolvedOutgoingConversation(conversation = Chat(id = "c1"), peerUserId = "u2")
        assertEquals("c1", resolved.conversation.id)
        assertEquals("u2", resolved.peerUserId)
        val errors = OutgoingConversationErrors(
            notLoggedIn = { "login" },
            recipientNotReady = { "not-ready" },
            cannotSendToSelf = { "self" },
        )
        assertEquals("login", errors.notLoggedIn())
    }

    @Test
    fun `contacts ui state derives online count from contacts`() {
        val state = ContactsUiState(
            contacts = listOf(
                User(id = "u1", name = "甲", isOnline = true),
                User(id = "u2", name = "乙", isOnline = false),
            ),
        )
        assertEquals(1, state.onlineCount)
        assertTrue(ContactsUiState().contacts.isEmpty())
    }
}
