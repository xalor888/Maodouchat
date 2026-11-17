package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSendGuardModelsTest {

    @Test
    fun `send reject reasons are the five expected entries in order`() {
        assertEquals(
            listOf(
                SendRejectReason.ALREADY_SENDING,
                SendRejectReason.BLANK,
                SendRejectReason.NO_SESSION,
                SendRejectReason.BLOCKED,
                SendRejectReason.MENTION_EVERYONE_FORBIDDEN,
            ),
            SendRejectReason.entries.toList(),
        )
    }

    @Test
    fun `retry reject reasons are the five expected entries in order`() {
        assertEquals(
            listOf(
                RetryRejectReason.NOT_FOUND,
                RetryRejectReason.NOT_MY_MESSAGE,
                RetryRejectReason.NOT_FAILED,
                RetryRejectReason.NO_SESSION,
                RetryRejectReason.UNSUPPORTED_TYPE,
            ),
            RetryRejectReason.entries.toList(),
        )
    }

    @Test
    fun `valueOf resolves every entry name`() {
        for (reason in SendRejectReason.entries) {
            assertEquals(reason, SendRejectReason.valueOf(reason.name))
        }
        for (reason in RetryRejectReason.entries) {
            assertEquals(reason, RetryRejectReason.valueOf(reason.name))
        }
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
