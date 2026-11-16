package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactionSummaryPolicyTest {

    private fun reaction(user: String, emoji: String, at: Long) =
        MessageReaction(userId = user, emoji = emoji, reactedAt = at)

    @Test
    fun `empty reactions give empty summary`() {
        val summary = ReactionSummaryPolicy.summarize(emptyList(), "me")
        assertTrue(summary.visible.isEmpty())
        assertEquals(0, summary.overflow)
    }

    @Test
    fun `groups by emoji and sorts groups by earliest reaction`() {
        val summary = ReactionSummaryPolicy.summarize(
            listOf(
                reaction("a", "\uD83D\uDE02", 300L),
                reaction("b", "\u2764\uFE0F", 100L),
                reaction("c", "\uD83D\uDE02", 200L),
            ),
            null,
        )
        assertEquals(listOf("\u2764\uFE0F", "\uD83D\uDE02"), summary.visible.map { it.emoji })
        assertEquals(listOf(100L), summary.visible[0].reactions.map { it.reactedAt })
        assertEquals(listOf(200L, 300L), summary.visible[1].reactions.map { it.reactedAt })
        assertEquals(0, summary.overflow)
    }

    @Test
    fun `reactedByMe only for the group the user reacted in`() {
        val summary = ReactionSummaryPolicy.summarize(
            listOf(
                reaction("me", "\u2764\uFE0F", 100L),
                reaction("a", "\uD83D\uDE02", 200L),
            ),
            "me",
        )
        assertTrue(summary.visible[0].reactedByMe)
        assertFalse(summary.visible[1].reactedByMe)
    }

    @Test
    fun `null current user never reacts`() {
        val summary = ReactionSummaryPolicy.summarize(
            listOf(reaction("a", "\u2764\uFE0F", 100L)),
            null,
        )
        assertFalse(summary.visible[0].reactedByMe)
    }

    @Test
    fun `more than 24 groups keeps 24 and counts overflow`() {
        val reactions = (0 until 25).map { i -> reaction("u$i", "e$i", i.toLong()) }
        val summary = ReactionSummaryPolicy.summarize(reactions, null)
        assertEquals(24, summary.visible.size)
        assertEquals(1, summary.overflow)
    }

    @Test
    fun `exactly 24 groups has no overflow`() {
        val reactions = (0 until 24).map { i -> reaction("u$i", "e$i", i.toLong()) }
        val summary = ReactionSummaryPolicy.summarize(reactions, null)
        assertEquals(24, summary.visible.size)
        assertEquals(0, summary.overflow)
    }
}
