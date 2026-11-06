package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingV2ContentModelsTest {

    @Test
    fun contentDefaults() {
        val content = MessagingV2Content(type = "TEXT")
        assertEquals(1, content.version)
        assertEquals("TEXT", content.type)
        assertEquals("", content.body)
        assertNull(content.replyToMessageId)
        assertTrue(content.attachmentIds.isEmpty())
        assertTrue(content.mentionedUserIds.isEmpty())
        assertEquals(0, content.expiresInSeconds)
        assertTrue(content.attributes.isEmpty())
        assertNull(content.metadata)
        assertNull(content.event)
    }

    @Test
    fun decodedPayloadDerivesFromMetadata() {
        val meta = MessageMeta(replyToId = "m1", mentions = listOf("u2"))
        val payload = DecodedContentPayload(type = MessageType.TEXT, body = "hi", metadata = meta)
        assertEquals("m1", payload.replyToMessageId)
        assertEquals(listOf("u2"), payload.mentionedUserIds)
        assertEquals("hi", payload.body)
    }

    @Test
    fun eventDefaults() {
        val event = MessagingV2Event(action = "EDIT", targetMessageId = "m1")
        assertEquals("EDIT", event.action)
        assertEquals("m1", event.targetMessageId)
        assertNull(event.content)
        assertNull(event.editedAt)
        assertNull(event.reactionEmoji)
        assertTrue(event.reactions.isEmpty())
    }

    @Test
    fun contentPolicyAcceptsDataVersionsOneAndTwo() {
        assertTrue(MessagingV2ContentPolicy.accepts(
            "DATA", MessagingV2Content(version = 1, type = "TEXT")))
        assertTrue(MessagingV2ContentPolicy.accepts(
            "DATA", MessagingV2Content(version = 2, type = "TEXT")))
        assertFalse(MessagingV2ContentPolicy.accepts(
            "DATA", MessagingV2Content(version = 3, type = "TEXT")))
        assertFalse(MessagingV2ContentPolicy.accepts(
            "DATA", MessagingV2Content(type = "EVENT")))
        assertFalse(MessagingV2ContentPolicy.accepts(
            "DATA", MessagingV2Content(type = "SK_DIST")))
    }

    @Test
    fun contentPolicyRejectsEventWithoutDomainAction() {
        assertFalse(MessagingV2ContentPolicy.accepts(
            "EVENT", MessagingV2Content(type = "EVENT")))
        val edit = MessagingV2Content(
            type = "EVENT",
            event = MessagingV2Event(action = "EDIT", targetMessageId = "m1"),
        )
        assertTrue(MessagingV2ContentPolicy.accepts("EVENT", edit))
    }

    @Test
    fun arrivalPolicyCountsVisibleIncomingOnce() {
        val decision = MessagingV2ArrivalPolicy.evaluate(
            isNew = true,
            ownerUserId = "me",
            senderUserId = "alice",
            conversationId = "c1",
            messageType = MessageType.TEXT,
            envelopeKind = "DATA",
            isConversationOccupied = false,
            appInForeground = false,
            activeChatId = "c2",
            openChatDetailId = null,
        )
        assertEquals(1, decision.unreadDelta)
        assertTrue(decision.shouldAttemptNotification)
        assertTrue(decision.shouldSendDeliveryReceipt)
    }

    @Test
    fun arrivalPolicyIgnoresOwnAndSkDist() {
        val own = MessagingV2ArrivalPolicy.evaluate(
            isNew = true,
            ownerUserId = "me",
            senderUserId = "me",
            conversationId = "c1",
            messageType = MessageType.TEXT,
            envelopeKind = "DATA",
            isConversationOccupied = false,
            appInForeground = false,
            activeChatId = "c2",
            openChatDetailId = null,
        )
        assertEquals(0, own.unreadDelta)
        assertFalse(own.shouldAttemptNotification)
        val keyDist = MessagingV2ArrivalPolicy.evaluate(
            isNew = true,
            ownerUserId = "me",
            senderUserId = "alice",
            conversationId = "c1",
            messageType = MessageType.SK_DIST,
            envelopeKind = "DATA",
            isConversationOccupied = false,
            appInForeground = false,
            activeChatId = "c2",
            openChatDetailId = null,
        )
        assertEquals(0, keyDist.unreadDelta)
    }
}
