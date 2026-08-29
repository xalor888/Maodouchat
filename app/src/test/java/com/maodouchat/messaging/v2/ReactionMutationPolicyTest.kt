package com.maodouchat.messaging.v2

import com.maodouchat.data.model.MessageReaction
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionMutationPolicyTest {
    @Test
    fun `one member cannot overwrite another member reaction`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        val updated = ReactionMutationPolicy.apply(existing, "alice", "laugh", 30L)

        assertEquals(
            listOf(
                MessageReaction("bob", "heart", 20L),
                MessageReaction("alice", "laugh", 30L),
            ),
            updated,
        )
    }

    @Test
    fun `empty emoji removes only the actor reaction`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        assertEquals(
            listOf(MessageReaction("bob", "heart", 20L)),
            ReactionMutationPolicy.apply(existing, "alice", null, 30L),
        )
    }

    @Test
    fun `legacy snapshot is restricted to envelope sender`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )
        val untrustedSnapshot = listOf(
            MessageReaction("alice", "laugh", 30L),
            MessageReaction("bob", "angry", 30L),
        )

        assertEquals(
            listOf(
                MessageReaction("bob", "heart", 20L),
                MessageReaction("alice", "laugh", 30L),
            ),
            ReactionMutationPolicy.applyLegacySnapshot(existing, "alice", untrustedSnapshot, 30L),
        )
    }
}
