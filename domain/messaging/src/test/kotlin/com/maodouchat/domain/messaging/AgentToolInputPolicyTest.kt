package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentToolInputPolicyTest {

    @Test
    fun `cleanTaskTitle trims and truncates at 300`() {
        assertEquals("hello", AgentToolInputPolicy.cleanTaskTitle("  hello  "))
        assertEquals("x".repeat(300), AgentToolInputPolicy.cleanTaskTitle("x".repeat(400)))
        assertEquals("x".repeat(300), AgentToolInputPolicy.cleanTaskTitle("  " + "x".repeat(400) + "  "))
    }

    @Test
    fun `cleanTaskDueText trims truncates and maps blank to null`() {
        assertNull(AgentToolInputPolicy.cleanTaskDueText(null))
        assertNull(AgentToolInputPolicy.cleanTaskDueText("   "))
        assertEquals("tomorrow", AgentToolInputPolicy.cleanTaskDueText("  tomorrow "))
        assertEquals("x".repeat(120), AgentToolInputPolicy.cleanTaskDueText("x".repeat(200)))
    }

    @Test
    fun `cleanPostBody trims and truncates at 2000`() {
        assertEquals("hi", AgentToolInputPolicy.cleanPostBody("  hi\n"))
        assertEquals("x".repeat(2_000), AgentToolInputPolicy.cleanPostBody("x".repeat(2_500)))
    }

    @Test
    fun `cleanCommentBody trims and truncates at 800`() {
        assertEquals("nice", AgentToolInputPolicy.cleanCommentBody(" nice "))
        assertEquals("x".repeat(800), AgentToolInputPolicy.cleanCommentBody("x".repeat(900)))
    }

    @Test
    fun `cleanFriendRequestMessage truncates at 300 without trimming`() {
        assertEquals("  hi", AgentToolInputPolicy.cleanFriendRequestMessage("  hi"))
        assertEquals("  " + "x".repeat(298), AgentToolInputPolicy.cleanFriendRequestMessage("  " + "x".repeat(500)))
    }

    @Test
    fun `cleanAnnouncement truncates at 1200 without trimming`() {
        assertEquals("  notice", AgentToolInputPolicy.cleanAnnouncement("  notice"))
        assertEquals("x".repeat(1_200), AgentToolInputPolicy.cleanAnnouncement("x".repeat(1_500)))
    }

    @Test
    fun `normalizeReactionEmoji trims and truncates at 16`() {
        assertEquals("👍", AgentToolInputPolicy.normalizeReactionEmoji("  👍  "))
        assertEquals("x".repeat(16), AgentToolInputPolicy.normalizeReactionEmoji("x".repeat(20)))
    }
}
