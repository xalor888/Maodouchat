package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalMessageMutationPolicyTest {
    @Test
    fun `edit cannot resurrect revoked message`() {
        assertNull(
            applyEditedMessageVersion(
                existing = message(type = MessageType.REVOKED, editedAt = 20L),
                candidate = message(content = "edited", editedAt = 30L),
            ),
        )
    }

    @Test
    fun `older edit cannot replace newer edit`() {
        assertNull(
            applyEditedMessageVersion(
                existing = message(content = "newer", editedAt = 20L),
                candidate = message(content = "older", editedAt = 10L),
            ),
        )
    }

    @Test
    fun `equal edit revision converges deterministically`() {
        val lower = message(content = "alpha", editedAt = 20L)
        val higher = message(content = "omega", editedAt = 20L)

        assertEquals(higher, applyEditedMessageVersion(lower, higher))
        assertNull(applyEditedMessageVersion(higher, lower))
    }

    @Test
    fun `revoke wins over edit and clears reactions`() {
        val existing = message(content = "body", editedAt = 30L).copy(
            reactions = listOf(com.maodouchat.data.model.MessageReaction("bob", "heart", 1L)),
        )
        val revoked = message(
            content = "revoked",
            type = MessageType.REVOKED,
            editedAt = 20L,
        )

        val applied = applyRevokedMessageVersion(existing, revoked)!!

        assertEquals(MessageType.REVOKED, applied.type)
        assertEquals("revoked", applied.content)
        assertEquals(30L, applied.editedAt)
        assertEquals(emptyList(), applied.reactions)
    }

    @Test
    fun `edit and revoke reject mismatched ids`() {
        assertNull(
            applyEditedMessageVersion(
                existing = message(editedAt = 20L),
                candidate = message(content = "edited", editedAt = 30L).copy(id = "m2"),
            ),
        )
        assertNull(
            applyRevokedMessageVersion(
                existing = message(editedAt = 20L),
                candidate = message(type = MessageType.REVOKED, editedAt = 30L).copy(id = "m2"),
            ),
        )
    }

    @Test
    fun `edit without a revision is rejected`() {
        assertNull(
            applyEditedMessageVersion(
                existing = message(editedAt = 20L),
                candidate = message(content = "edited", editedAt = null),
            ),
        )
    }

    @Test
    fun `edit applies against an unedited message`() {
        val applied = applyEditedMessageVersion(
            existing = message(content = "old", editedAt = null),
            candidate = message(content = "new", editedAt = 5L),
        )!!
        assertEquals("new", applied.content)
        assertEquals(5L, applied.editedAt)
    }

    @Test
    fun `equal revision and equal content keep the existing row`() {
        val existing = message(content = "same", editedAt = 20L).copy(
            meta = com.maodouchat.data.model.MessageMeta(replyToId = "r1"),
        )
        val candidate = message(content = "same", editedAt = 20L).copy(
            meta = com.maodouchat.data.model.MessageMeta(replyToId = "r2"),
        )
        val applied = applyEditedMessageVersion(existing, candidate)!!
        assertEquals(existing, applied)
        assertEquals("r1", applied.meta.replyToId)
    }

    @Test
    fun `revoke against a revoked row keeps the newer row`() {
        val existing = message(content = "gone", type = MessageType.REVOKED, editedAt = 30L)
        assertEquals(
            existing,
            applyRevokedMessageVersion(
                existing,
                message(content = "older", type = MessageType.REVOKED, editedAt = 20L),
            ),
        )
        assertEquals(
            existing,
            applyRevokedMessageVersion(
                existing,
                message(content = "aaa", type = MessageType.REVOKED, editedAt = 30L),
            ),
        )
    }

    @Test
    fun `newer edit applies and takes candidate meta`() {
        val existing = message(content = "old", editedAt = 10L).copy(
            meta = com.maodouchat.data.model.MessageMeta(replyToId = "r1"),
        )
        val candidate = message(content = "new", editedAt = 20L).copy(
            meta = com.maodouchat.data.model.MessageMeta(replyToId = "r2"),
        )
        val applied = applyEditedMessageVersion(existing, candidate)!!
        assertEquals("new", applied.content)
        assertEquals(20L, applied.editedAt)
        assertEquals("r2", applied.meta.replyToId)
    }

    @Test
    fun `equal revision orders by raw string comparison`() {
        assertEquals(
            "apple",
            applyEditedMessageVersion(
                message(content = "Zebra", editedAt = 20L),
                message(content = "apple", editedAt = 20L),
            )!!.content,
        )
        assertNull(
            applyEditedMessageVersion(
                message(content = "apple", editedAt = 20L),
                message(content = "Zebra", editedAt = 20L),
            ),
        )
    }

    @Test
    fun `edit with no revision on either side is rejected`() {
        assertNull(
            applyEditedMessageVersion(
                existing = message(editedAt = null),
                candidate = message(content = "edited", editedAt = null),
            ),
        )
    }

    @Test
    fun `revoke with no revision on either side keeps null revision`() {
        val applied = applyRevokedMessageVersion(
            existing = message(editedAt = null),
            candidate = message(content = "gone", type = MessageType.REVOKED, editedAt = null),
        )!!
        assertEquals(MessageType.REVOKED, applied.type)
        assertNull(applied.editedAt)
        assertEquals(emptyList(), applied.reactions)
    }

    @Test
    fun `revoke against revoked equal revision higher content applies`() {
        val applied = applyRevokedMessageVersion(
            existing = message(content = "aaa", type = MessageType.REVOKED, editedAt = 30L),
            candidate = message(content = "zzz", type = MessageType.REVOKED, editedAt = 30L),
        )!!
        assertEquals("zzz", applied.content)
        assertEquals(30L, applied.editedAt)
        assertEquals(MessageType.REVOKED, applied.type)
    }

    private fun message(
        content: String = "body",
        type: MessageType = MessageType.TEXT,
        editedAt: Long? = null,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "alice",
        content = content,
        type = type,
        timestamp = 1L,
        editedAt = editedAt,
    )
}
