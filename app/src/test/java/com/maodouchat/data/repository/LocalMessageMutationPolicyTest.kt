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
