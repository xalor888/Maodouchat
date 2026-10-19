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

    @Test
    fun `blank actor leaves every reaction untouched`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        assertEquals(
            existing,
            ReactionMutationPolicy.apply(existing, "  ", "laugh", 30L),
        )
    }

    @Test
    fun `whitespace-only emoji counts as removal`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        assertEquals(
            listOf(MessageReaction("bob", "heart", 20L)),
            ReactionMutationPolicy.apply(existing, "alice", "   ", 30L),
        )
    }

    @Test
    fun `emoji is trimmed before it is stored`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        assertEquals(
            listOf(
                MessageReaction("bob", "heart", 20L),
                MessageReaction("alice", "laugh", 30L),
            ),
            ReactionMutationPolicy.apply(existing, "alice", "  laugh\t", 30L),
        )
    }

    @Test
    fun `reapplying the same emoji replaces the old entry instead of duplicating`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )

        val once = ReactionMutationPolicy.apply(existing, "alice", "laugh", 30L)
        val twice = ReactionMutationPolicy.apply(once, "alice", "laugh", 40L)

        assertEquals(
            listOf(
                MessageReaction("bob", "heart", 20L),
                MessageReaction("alice", "laugh", 40L),
            ),
            twice,
        )
    }

    @Test
    fun `legacy snapshot uses the actor latest entry when the snapshot repeats them`() {
        val existing = listOf(
            MessageReaction("alice", "like", 10L),
            MessageReaction("bob", "heart", 20L),
        )
        val snapshot = listOf(
            MessageReaction("alice", "laugh", 20L),
            MessageReaction("bob", "angry", 30L),
            MessageReaction("alice", "wow", 40L),
        )

        assertEquals(
            listOf(
                MessageReaction("bob", "heart", 20L),
                MessageReaction("alice", "wow", 40L),
            ),
            ReactionMutationPolicy.applyLegacySnapshot(existing, "alice", snapshot, 99L),
        )
    }
}
