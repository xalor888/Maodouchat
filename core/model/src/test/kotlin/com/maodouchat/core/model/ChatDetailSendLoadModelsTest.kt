package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailSendLoadModelsTest {

    @Test
    fun chatLoadInputDefaultsToNoCacheFallback() {
        val input = ChatLoadInput(
            chat = Chat(id = "c1"),
            previousRevision = null,
            currentUserId = "u1",
        )
        assertFalse(input.fromCache)
        assertEquals(Chat(id = "c1"), input.chat)
        assertEquals("u1", input.currentUserId)
    }

    @Test
    fun chatLoadInputCopiesWithNewRevision() {
        val input = ChatLoadInput(
            chat = Chat(id = "c1"),
            previousRevision = 7L,
            currentUserId = "u1",
            fromCache = true,
        )
        val copied = input.copy(previousRevision = 8L)
        assertEquals(8L, copied.previousRevision)
        assertTrue(copied.fromCache)
    }

    @Test
    fun sendDecisionAllowCarriesFields() {
        val decision = SendDecision.Allow(
            text = "hi",
            meta = MessageMeta(),
            messageType = MessageType.TEXT,
            clearDraft = true,
            consumeSilent = false,
        )
        assertEquals("hi", decision.text)
        assertTrue(decision.clearDraft)
        assertFalse(decision.consumeSilent)
    }

    @Test
    fun sendDecisionRejectCarriesReason() {
        val decision = SendDecision.Reject(SendRejectReason.BLANK)
        assertEquals(SendRejectReason.BLANK, decision.reason)
    }
}
