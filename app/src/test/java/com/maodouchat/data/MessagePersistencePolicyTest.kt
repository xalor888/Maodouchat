package com.maodouchat.data

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.mergeDeliveryStatusForPersistence
import com.maodouchat.data.repository.mergeLocalMediaMetaForPersistence
import com.maodouchat.data.repository.mergeMessageForPersistence
import com.maodouchat.util.ViewOncePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePersistencePolicyTest {

    @Test
    fun `equal revision can unstar and clear reactions`() {
        val existing = base("m1").copy(
            starred = true,
            reactions = listOf(MessageReaction("u1", "❤")),
            status = MessageStatus.READ
        )
        val incoming = existing.copy(starred = false, reactions = emptyList(), status = MessageStatus.SENT)

        val merged = mergeMessageForPersistence(existing, incoming)

        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
        assertEquals(MessageStatus.READ, merged.status)
    }

    @Test
    fun `newer revision can unstar and clear reactions`() {
        val existing = base("m1").copy(
            content = "old",
            editedAt = 10L,
            starred = true,
            reactions = listOf(MessageReaction("u1", "👍"))
        )
        val incoming = base("m1").copy(
            content = "new",
            editedAt = 20L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("new", merged.content)
        assertFalse(merged.starred)
        assertTrue(merged.reactions.isEmpty())
    }

    @Test
    fun `older revision cannot wipe newer star and reactions`() {
        val reactions = listOf(MessageReaction("u2", "🎉", reactedAt = 1L))
        val existing = base("m1").copy(
            content = "new",
            editedAt = 20L,
            starred = true,
            reactions = reactions
        )
        val incoming = base("m1").copy(
            content = "old",
            editedAt = 10L,
            starred = false,
            reactions = emptyList()
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("new", merged.content)
        assertTrue(merged.starred)
        assertEquals(reactions, merged.reactions)
    }

    @Test
    fun `readable plaintext is preferred over ciphertext envelope`() {
        val reactions = listOf(MessageReaction("u1", "❤", reactedAt = 1L))
        val existing = base("m1").copy(content = "hello readable")
        val incoming = base("m1").copy(
            content = """{"ciphertext":"abc","devices":[]}""",
            starred = true,
            reactions = reactions
        )

        val merged = mergeMessageForPersistence(existing, incoming)

        assertEquals("hello readable", merged.content)
        assertTrue(merged.starred)
        assertEquals(reactions, merged.reactions)
    }

    @Test
    fun `delivery status does not regress READ to SENT`() {
        assertEquals(
            MessageStatus.READ,
            mergeDeliveryStatusForPersistence(MessageStatus.READ, MessageStatus.SENT)
        )
    }

    @Test
    fun `view once opened state is encoded into persisted content`() {
        val message = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true))

        val opened = ViewOncePolicy.markOpened(message)

        assertEquals("text", opened.parsedContent())
        assertTrue(opened.parsedMeta().viewOnce)
        assertTrue(opened.parsedMeta().viewOnceOpened)
    }

    @Test
    fun `stale snapshot cannot reopen view once media`() {
        val opened = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true, viewOnceOpened = true))
        val stale = base("m1").copy(type = MessageType.IMAGE)
            .withEncodedMeta(MessageMeta(viewOnce = true, viewOnceOpened = false))

        val merged = mergeMessageForPersistence(opened, stale)

        assertTrue(merged.parsedMeta().viewOnce)
        assertTrue(merged.parsedMeta().viewOnceOpened)
    }

    @Test
    fun `local media flags do not overwrite newer body or metadata`() {
        val current = base("m1").copy(content = "new body")
            .withEncodedMeta(MessageMeta(replyToId = "new-reply", spoilerMedia = true))
        val staleLocal = base("m1").copy(content = "old body")
            .withEncodedMeta(
                MessageMeta(
                    replyToId = "old-reply",
                    spoilerMedia = true,
                    spoilerRevealed = true
                )
            )

        val merged = mergeLocalMediaMetaForPersistence(current, staleLocal)

        assertEquals("new body", merged.parsedContent())
        assertEquals("new-reply", merged.parsedMeta().replyToId)
        assertTrue(merged.parsedMeta().spoilerRevealed)
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "text",
        type = MessageType.TEXT,
        timestamp = 1L,
        status = MessageStatus.SENT
    )
}
