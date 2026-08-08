package com.maodouchat.chatlist

import com.maodouchat.data.model.MessageReaction
import com.maodouchat.ui.screen.chatlist.PendingReactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReactionPolicyTest {

    private fun reaction(userId: String = "u1", emoji: String = "👍") =
        MessageReaction(userId = userId, emoji = emoji, reactedAt = 1L)

    @Test
    fun put_then_take_returns_ready_and_clears() {
        val now = 1_000L
        val buffered = PendingReactionPolicy.put(
            pending = emptyMap(),
            chatId = "c1",
            messageId = "m1",
            reactions = listOf(reaction()),
            nowMs = now
        )
        assertEquals(1, buffered.size)
        val result = PendingReactionPolicy.takeForMessage(
            pending = buffered,
            chatId = "c1",
            messageId = "m1",
            nowMs = now + 10
        )
        assertEquals(1, result.ready.size)
        assertEquals("m1", result.ready.first().messageId)
        assertTrue(result.pending.isEmpty())
    }

    @Test
    fun take_wrong_message_keeps_pending() {
        val now = 1_000L
        val buffered = PendingReactionPolicy.put(
            pending = emptyMap(),
            chatId = "c1",
            messageId = "m1",
            reactions = listOf(reaction()),
            nowMs = now
        )
        val result = PendingReactionPolicy.takeForMessage(
            pending = buffered,
            chatId = "c1",
            messageId = "m2",
            nowMs = now
        )
        assertTrue(result.ready.isEmpty())
        assertEquals(1, result.pending.size)
    }

    @Test
    fun prune_expires_old_entries() {
        val now = 10_000L
        val buffered = PendingReactionPolicy.put(
            pending = emptyMap(),
            chatId = "c1",
            messageId = "m1",
            reactions = listOf(reaction()),
            nowMs = now - PendingReactionPolicy.DEFAULT_TTL_MS - 1
        )
        val pruned = PendingReactionPolicy.prune(buffered, nowMs = now)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun put_overwrites_same_key() {
        val now = 1_000L
        var map = PendingReactionPolicy.put(
            pending = emptyMap(),
            chatId = "c1",
            messageId = "m1",
            reactions = listOf(reaction(emoji = "👍")),
            nowMs = now
        )
        map = PendingReactionPolicy.put(
            pending = map,
            chatId = "c1",
            messageId = "m1",
            reactions = listOf(reaction(emoji = "❤️")),
            nowMs = now + 5
        )
        assertEquals(1, map.size)
        assertEquals("❤️", map.values.first().reactions.first().emoji)
    }

    @Test
    fun max_entries_keeps_newest() {
        var map = emptyMap<String, PendingReactionPolicy.Entry>()
        val base = 1_000L
        for (i in 0 until PendingReactionPolicy.DEFAULT_MAX_ENTRIES + 5) {
            map = PendingReactionPolicy.put(
                pending = map,
                chatId = "c1",
                messageId = "m$i",
                reactions = listOf(reaction()),
                nowMs = base + i,
                maxEntries = PendingReactionPolicy.DEFAULT_MAX_ENTRIES
            )
        }
        assertEquals(PendingReactionPolicy.DEFAULT_MAX_ENTRIES, map.size)
        assertTrue(map.containsKey(PendingReactionPolicy.bufferKey("c1", "m${PendingReactionPolicy.DEFAULT_MAX_ENTRIES + 4}")))
    }
}
