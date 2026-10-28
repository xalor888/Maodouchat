package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDuplicatePolicyTest {

    @Test
    fun sameIdUnchangedIsRedundant() {
        val existing = base("srv-1")
        assertTrue(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy()))
        assertFalse(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(status = MessageStatus.READ)
            )
        )
    }

    @Test
    fun differentIdSameDeliveryIsDuplicate() {
        val local = base("local-tmp")
        val remote = local.copy(id = "srv-1")
        assertTrue(MessageDuplicatePolicy.isSameDelivery(local, remote))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(timestamp = 99L)))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(chatId = "c2")))
        assertEquals("local-tmp", MessageDuplicatePolicy.pickCanonical(local, remote).id)
        assertEquals("hello", MessageDuplicatePolicy.pickCanonical(local, remote.copy(content = "hello")).content)
    }

    @Test
    fun differentIdAloneBreaksRedundancy() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(id = "srv-2")))
    }

    @Test
    fun redundancyComparesStoredFieldsNotMeta() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(starred = true)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(editedAt = 5L)))
        assertFalse(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(reactions = listOf(MessageReaction("bob", "heart", 1L)))
            )
        )
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(sealedSender = true)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(expiresAt = 99L)))
        assertTrue(
            MessageDuplicatePolicy.isRedundantWrite(
                existing,
                existing.copy(meta = existing.meta.copy(replyToId = "m0"))
            )
        )
    }

    @Test
    fun senderOrTypeDiffBreaksSameDelivery() {
        val local = base("local-tmp")
        val remote = local.copy(id = "srv-1")
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(senderId = "u2")))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, remote.copy(type = MessageType.IMAGE)))
    }

    @Test
    fun sameDeliveryComparesParsedContent() {
        val local = base("local-tmp")
        val withMetaSuffix = local.copy(id = "srv-1", content = "hello<meta>{}")
        assertTrue(MessageDuplicatePolicy.isSameDelivery(local, withMetaSuffix))
        assertFalse(MessageDuplicatePolicy.isSameDelivery(local, local.copy(id = "srv-1", content = "bye")))
    }

    @Test
    fun pickCanonicalKeepsIdButTakesIncomingRest() {
        val local = base("local-tmp")
        val incoming = local.copy(id = "srv-1", status = MessageStatus.READ, chatId = "c2")
        val canonical = MessageDuplicatePolicy.pickCanonical(local, incoming)
        assertEquals("local-tmp", canonical.id)
        assertEquals(MessageStatus.READ, canonical.status)
        assertEquals("c2", canonical.chatId)
        assertEquals("hello", canonical.content)
    }

    @Test
    fun chatIdOrSenderIdDiffBreaksRedundancy() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(chatId = "c2")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(senderId = "u2")))
    }

    @Test
    fun typeOrTimestampDiffBreaksRedundancy() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(type = MessageType.IMAGE)))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(timestamp = 43L)))
    }

    @Test
    fun contentDiffBreaksRedundancyDespiteSameId() {
        val existing = base("srv-1")
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(content = "bye")))
        assertFalse(MessageDuplicatePolicy.isRedundantWrite(existing, existing.copy(content = "hello<meta>{}")))
    }

    @Test
    fun sameDeliveryIgnoresStatusIdAndStarred() {
        val local = base("local-tmp")
        val remote = local.copy(id = "srv-1", status = MessageStatus.READ, starred = true)
        assertTrue(MessageDuplicatePolicy.isSameDelivery(local, remote))
        assertTrue(
            MessageDuplicatePolicy.isSameDelivery(
                local,
                local.copy(id = "srv-1", reactions = listOf(MessageReaction("bob", "heart", 1L)))
            )
        )
    }

    @Test
    fun pickCanonicalTakesIncomingSideFields() {
        val local = base("local-tmp")
        val incoming = local.copy(
            id = "srv-1",
            reactions = listOf(MessageReaction("bob", "heart", 1L)),
            sealedSender = true,
            expiresAt = 99L
        )
        val canonical = MessageDuplicatePolicy.pickCanonical(local, incoming)
        assertEquals("local-tmp", canonical.id)
        assertEquals(incoming.reactions, canonical.reactions)
        assertTrue(canonical.sealedSender)
        assertEquals(99L, canonical.expiresAt)
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "hello",
        type = MessageType.TEXT,
        timestamp = 42L,
        status = MessageStatus.SENT
    )
}
