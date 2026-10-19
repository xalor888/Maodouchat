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

    @Test
    fun `data kind pins version and type shape`() {
        assertTrue(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(version = 1, type = "TEXT")))
        assertFalse(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(version = 0, type = "TEXT")))
        assertFalse(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(version = 3, type = "TEXT")))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "DATA",
                MessagingV2Content(type = "TEXT", event = MessagingV2Event("EDIT", "m1")),
            ),
        )
        assertFalse(MessagingV2ContentPolicy.accepts("DATA", MessagingV2Content(type = "SK_DIST")))
    }

    @Test
    fun `event kind pins domain action set and version`() {
        assertTrue(
            MessagingV2ContentPolicy.accepts(
                "EVENT",
                MessagingV2Content(type = "EVENT", event = MessagingV2Event("REVOKE", "m1")),
            ),
        )
        assertTrue(
            MessagingV2ContentPolicy.accepts(
                "EVENT",
                MessagingV2Content(type = "EVENT", event = MessagingV2Event("REACTION_SNAPSHOT", "m1")),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "EVENT",
                MessagingV2Content(version = 2, type = "EVENT", event = MessagingV2Event("EDIT", "m1")),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "EVENT",
                MessagingV2Content(type = "EVENT", event = MessagingV2Event("READ_RECEIPT", "m1")),
            ),
        )
        assertFalse(MessagingV2ContentPolicy.accepts("EVENT", MessagingV2Content(type = "EVENT")))
    }

    @Test
    fun `receipt kind accepts delivery receipt and pins version`() {
        assertTrue(
            MessagingV2ContentPolicy.accepts(
                "RECEIPT",
                MessagingV2Content(type = "EVENT", event = MessagingV2Event("DELIVERY_RECEIPT", "m1")),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "RECEIPT",
                MessagingV2Content(version = 2, type = "EVENT", event = MessagingV2Event("READ_RECEIPT", "m1")),
            ),
        )
        assertFalse(MessagingV2ContentPolicy.accepts("RECEIPT", MessagingV2Content(type = "TEXT")))
    }

    @Test
    fun `key request pins attribute lengths and payload emptiness`() {
        val base = MessagingV2Content(
            type = "SENDER_KEY_REQUEST",
            attributes = mapOf(
                "requestedSenderUserId" to "alice",
                "failedMessageId" to "message-1",
            ),
        )

        assertTrue(MessagingV2ContentPolicy.accepts("KEY_REQUEST", base))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                base.copy(attributes = base.attributes + ("requestedSenderUserId" to "a".repeat(51))),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                base.copy(attributes = base.attributes + ("failedMessageId" to "m".repeat(101))),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "KEY_REQUEST",
                base.copy(attachmentIds = listOf("att-1")),
            ),
        )
    }

    @Test
    fun `service kind accepts constrained plain content and delete mutation`() {
        assertTrue(MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "TEXT", body = "hello")))
        assertFalse(MessagingV2ContentPolicy.accepts("SERVICE", MessagingV2Content(type = "SK_DIST")))

        val delete = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(action = "DELETE", targetMessageId = "m1"),
        )
        assertTrue(MessagingV2ContentPolicy.accepts("SERVICE", delete))
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "SERVICE",
                delete.copy(event = delete.event?.copy(content = "x")),
            ),
        )
        assertFalse(
            MessagingV2ContentPolicy.accepts(
                "SERVICE",
                delete.copy(event = delete.event?.copy(targetMessageId = "")),
            ),
        )
    }
}
