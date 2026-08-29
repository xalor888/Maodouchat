package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2ContentPolicyTest {
    @Test
    fun `data cannot masquerade as receipt`() {
        val text = MessagingV2Content(type = "TEXT", body = "hello")

        assertTrue(MessagingV2ContentPolicy.accepts("DATA", text))
        assertFalse(MessagingV2ContentPolicy.accepts("RECEIPT", text))
    }

    @Test
    fun `receipt kind accepts only receipt actions`() {
        val read = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(
                action = MessagingV2EventAction.READ_RECEIPT,
                targetMessageId = "m1",
            ),
        )
        val edit = read.copy(event = read.event?.copy(action = MessagingV2EventAction.EDIT))

        assertTrue(MessagingV2ContentPolicy.accepts("RECEIPT", read))
        assertFalse(MessagingV2ContentPolicy.accepts("RECEIPT", edit))
        assertTrue(MessagingV2ContentPolicy.accepts("EVENT", edit))
    }

    @Test
    fun `unknown kinds and unsupported versions are rejected`() {
        assertFalse(MessagingV2ContentPolicy.accepts("SYNC", MessagingV2Content(type = "TEXT")))
        assertTrue(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(version = 2, type = "TEXT")))
        assertFalse(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(version = 3, type = "TEXT")))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "EVENT",
                MessagingV2Content(version = 2, type = "EVENT", event = MessagingV2Event("DELETE", "m1")),
            ),
        )
    }

    @Test
    fun `sender key request is a constrained protocol control payload`() {
        val request = MessagingV2Content(
            type = "SENDER_KEY_REQUEST",
            attributes = mapOf(
                "requestedSenderUserId" to "alice",
                "failedMessageId" to "message-1",
            ),
        )

        assertTrue(MessagingV2ContentPolicy.accepts("KEY_REQUEST", request))
        assertFalse(MessagingV2ContentPolicy.accepts("DATA", request))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                request.copy(attributes = request.attributes - "requestedSenderUserId"),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                request.copy(body = "not-empty"),
            ),
        )
    }

    @Test
    fun `service kind accepts only constrained bot mutations`() {
        val edit = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(
                action = MessagingV2EventAction.EDIT,
                targetMessageId = "m1",
                content = "updated",
                editedAt = 12L,
            ),
        )
        val reaction = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(
                action = MessagingV2EventAction.REACTION_SET,
                targetMessageId = "m1",
                reactionEmoji = "ok",
            ),
        )

        assertTrue(MessagingV2ContentPolicy.accepts("SERVICE", edit))
        assertTrue(MessagingV2ContentPolicy.accepts("SERVICE", reaction))
        assertFalse(MessagingV2ContentPolicy.accepts("SERVICE", edit.copy(event = edit.event?.copy(editedAt = null))))
        assertFalse(MessagingV2ContentPolicy.accepts("SERVICE", reaction.copy(event = reaction.event?.copy(reactionEmoji = null))))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "SERVICE",
                edit.copy(event = edit.event?.copy(action = MessagingV2EventAction.READ_RECEIPT)),
            ),
        )
    }

    @Test
    fun `service transport trusts only bot or system device zero`() {
        assertTrue(MessagingV2ServiceEnvelopePolicy.accepts("bot_helper", 0, "SERVICE_PLAINTEXT"))
        assertTrue(MessagingV2ServiceEnvelopePolicy.accepts("system", 0, "SERVICE_PLAINTEXT"))
        assertFalse(MessagingV2ServiceEnvelopePolicy.accepts("alice", 0, "SERVICE_PLAINTEXT"))
        assertFalse(MessagingV2ServiceEnvelopePolicy.accepts("system", 1, "SERVICE_PLAINTEXT"))
        assertFalse(MessagingV2ServiceEnvelopePolicy.accepts("system", 0, "PREKEY"))
    }
}
