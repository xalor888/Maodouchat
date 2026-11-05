package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingV2AdapterModelsTest {

    @Test
    fun `conversation snapshot carries targets and revision`() {
        val snapshot = MessagingV2ConversationSnapshot(
            conversationId = "c1",
            ownerUserId = "u1",
            isGroup = true,
            memberRevision = 7L,
            participantUserIds = listOf("u1", "u2"),
            targets = setOf(MessagingV2DeviceTarget("u2", 1)),
        )
        assertEquals(7L, snapshot.memberRevision)
        assertEquals(MessagingV2DeviceTarget("u2", 1), snapshot.targets.single())
        assertTrue(snapshot.isGroup)
    }
}
