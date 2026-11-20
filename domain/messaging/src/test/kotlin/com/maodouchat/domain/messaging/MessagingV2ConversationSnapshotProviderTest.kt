package com.maodouchat.domain.messaging

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagingV2ConversationSnapshotProviderTest {

    private fun snapshot(conversationId: String) = MessagingV2ConversationSnapshot(
        conversationId = conversationId,
        ownerUserId = "u1",
        isGroup = false,
        memberRevision = 3L,
        participantUserIds = listOf("u1", "u2"),
        targets = emptySet()
    )

    @Test
    fun `fake provider returns the snapshot for the requested conversation`() = runTest {
        val provider = MessagingV2ConversationSnapshotProvider { _, conversationId ->
            snapshot(conversationId)
        }
        val got = provider.get("token", "c9")
        assertEquals("c9", got.conversationId)
        assertEquals("u1", got.ownerUserId)
        assertEquals(3L, got.memberRevision)
    }

    @Test
    fun `fake provider distinguishes conversations`() = runTest {
        val provider = MessagingV2ConversationSnapshotProvider { _, conversationId ->
            snapshot(conversationId)
        }
        assertEquals("c1", provider.get("t", "c1").conversationId)
        assertEquals("c2", provider.get("t", "c2").conversationId)
    }
}
