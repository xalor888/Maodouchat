package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageReactionJsonPolicyTest {

    @Test
    fun `empty list encodes to empty json array`() {
        assertEquals("[]", MessageReactionJsonPolicy.encodeReactions(emptyList()))
    }

    @Test
    fun `single reaction round trips`() {
        val reaction = MessageReaction(userId = "u1", emoji = "👍", reactedAt = 1000L)
        val encoded = MessageReactionJsonPolicy.encodeReactions(listOf(reaction))
        assertEquals(listOf(reaction), MessageReactionJsonPolicy.decodeReactions(encoded))
    }

    @Test
    fun `decode valid json list`() {
        val out = MessageReactionJsonPolicy.decodeReactions(
            """[{"userId":"u1","emoji":"❤️","reactedAt":42}]"""
        )
        assertEquals(listOf(MessageReaction(userId = "u1", emoji = "❤️", reactedAt = 42L)), out)
    }

    @Test
    fun `decode invalid text falls back to empty list`() {
        assertEquals(emptyList(), MessageReactionJsonPolicy.decodeReactions("not-json"))
    }

    @Test
    fun `decode empty array gives empty list`() {
        assertEquals(emptyList(), MessageReactionJsonPolicy.decodeReactions("[]"))
    }
}
