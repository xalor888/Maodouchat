package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentReactionTogglePolicyTest {

    private val mine = MessageReaction("me", "👍")
    private val theirs = MessageReaction("u2", "❤️")

    @Test
    fun `nextEmoji returns null when already reacted with same emoji`() {
        assertNull(AgentReactionTogglePolicy.nextEmoji(listOf(mine, theirs), "me", "👍"))
    }

    @Test
    fun `nextEmoji returns emoji when not reacted or reacted differently`() {
        assertEquals("👍", AgentReactionTogglePolicy.nextEmoji(listOf(theirs), "me", "👍"))
        assertEquals("🔥", AgentReactionTogglePolicy.nextEmoji(listOf(mine), "me", "🔥"))
    }

    @Test
    fun `applyReaction toggles off own reaction and keeps others`() {
        val result = AgentReactionTogglePolicy.applyReaction(listOf(mine, theirs), "me", null)
        assertEquals(listOf(theirs), result)
    }

    @Test
    fun `applyReaction replaces own reaction and keeps others`() {
        val result = AgentReactionTogglePolicy.applyReaction(listOf(mine, theirs), "me", "🔥")
        assertEquals(2, result.size)
        assertEquals(theirs, result[0])
        assertEquals("me", result[1].userId)
        assertEquals("🔥", result[1].emoji)
    }
}
